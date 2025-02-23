// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.crypto.provider.jce

import cats.syntax.either.*
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.crypto.HkdfError.HkdfInternalError
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.deterministic.encryption.DeterministicRandom
import com.digitalasset.canton.crypto.provider.CryptoKeyConverter
import com.digitalasset.canton.crypto.provider.jce.JceSecurityProvider.bouncyCastleProvider
import com.digitalasset.canton.crypto.provider.tink.TinkJavaConverter
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.serialization.{
  DefaultDeserializationError,
  DeserializationError,
  DeterministicEncoding,
}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.{ErrorUtil, ShowUtil}
import com.digitalasset.canton.version.{HasVersionedToByteString, ProtocolVersion}
import com.google.crypto.tink.subtle.EllipticCurves.{CurveType, EcdsaEncoding}
import com.google.crypto.tink.subtle.Enums.HashType
import com.google.crypto.tink.subtle.*
import com.google.crypto.tink.{Aead, PublicKeySign, PublicKeyVerify}
import com.google.protobuf.ByteString
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.crypto.DataLengthException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.{Argon2BytesGenerator, HKDFBytesGenerator}
import org.bouncycastle.crypto.params.{Argon2Parameters, HKDFParameters}
import org.bouncycastle.jce.spec.IESParameterSpec
import sun.security.ec.ECPrivateKeyImpl

import java.security.interfaces.*
import java.security.spec.{InvalidKeySpecException, PKCS8EncodedKeySpec}
import java.security.{
  GeneralSecurityException,
  KeyFactory,
  NoSuchAlgorithmException,
  PrivateKey as JPrivateKey,
  PublicKey as JPublicKey,
  SecureRandom,
}
import javax.crypto.Cipher
import scala.collection.concurrent.TrieMap
import scala.util.Try

object JceSecureRandom {

  /** Uses [[ThreadLocal]] here to reduce contention and improve performance. */
  private[crypto] val random: ThreadLocal[SecureRandom] = new ThreadLocal[SecureRandom] {
    override def initialValue(): SecureRandom = newSecureRandom()
  }

  private def newSecureRandom() = {
    val rand = new SecureRandom()
    rand.nextLong()
    rand
  }

  private[jce] def generateRandomBytes(length: Int): Array[Byte] = {
    val randBytes = new Array[Byte](length)
    random.get().nextBytes(randBytes)
    randBytes
  }
}

class JcePureCrypto(
    javaKeyConverter: JceJavaConverter,
    override val defaultSymmetricKeyScheme: SymmetricKeyScheme,
    override val defaultHashAlgorithm: HashAlgorithm,
    override val defaultPbkdfScheme: PbkdfScheme,
    override val loggerFactory: NamedLoggerFactory,
) extends CryptoPureApi
    with ShowUtil
    with NamedLogging {

  // Cache for the java key conversion results
  private val javaPublicKeyCache: TrieMap[Fingerprint, Either[JavaKeyConversionError, JPublicKey]] =
    TrieMap.empty
  private val javaPrivateKeyCache
      : TrieMap[Fingerprint, Either[JavaKeyConversionError, JPrivateKey]] = TrieMap.empty

  private lazy val tinkJavaConverter = new TinkJavaConverter
  private lazy val keyConverter = new CryptoKeyConverter(tinkJavaConverter, javaKeyConverter)

  /* security parameters for EciesP256HmacSha256Aes128Cbc encryption scheme,
    in particular for the HMAC and symmetric crypto algorithms.
   */
  private object EciesP256HmacSha256Aes128CbcParams {
    // the internal jce designation for this scheme
    val jceInternalName: String = "ECIESwithSHA256andAES-CBC"
    // the key size in bits for HMACSHA256 is 64bytes (recommended)
    val macKeySizeInBits: Int = 512
    // the key size in bits for AES-128-CBC is 16 bytes
    val cipherKeySizeInBits: Int = 128
    // the IV for AES-128-CBC is 16 bytes
    val ivSizeForAesCbcInBytes: Int = 16
    // the parameter specification for this scheme.
    def parameterSpec(iv: Array[Byte]): IESParameterSpec = new IESParameterSpec(
      // we do no use any encoding or derivation vector for the KDF.
      Array[Byte](),
      Array[Byte](),
      macKeySizeInBits,
      cipherKeySizeInBits,
      iv,
    )
  }

  private object RSA2048OaepSha256Params {
    // the internal jce designation for this scheme
    val jceInternalName: String = "RSA/NONE/OAEPWithSHA256AndMGF1Padding"
    // the key size in bit for RSA2048
    val keySizeInBits = 2048
  }

  private def checkKeyFormat[E](
      expected: CryptoKeyFormat,
      actual: CryptoKeyFormat,
      errFn: String => E,
  ): Either[E, Unit] =
    Either.cond(expected == actual, (), errFn(s"Expected key format $expected, but got $actual"))

  private def convertKey[E](
      publicKey: PublicKey,
      expected: CryptoKeyFormat,
      errFn: String => E,
  ): Either[E, PublicKey] =
    keyConverter.convert(publicKey, expected).leftMap(errFn)

  private def encryptAes128Gcm(
      plaintext: ByteString,
      symmetricKey: ByteString,
  ): Either[EncryptionError, ByteString] =
    for {
      encrypter <- Either
        .catchOnly[GeneralSecurityException](new AesGcmJce(symmetricKey.toByteArray))
        .leftMap(err => EncryptionError.InvalidSymmetricKey(err.toString))
      ciphertext <- Either
        .catchOnly[GeneralSecurityException](
          encrypter.encrypt(plaintext.toByteArray, Array[Byte]())
        )
        .leftMap(err => EncryptionError.FailedToEncrypt(err.toString))
    } yield ByteString.copyFrom(ciphertext)

  private def decryptAes128Gcm(
      ciphertext: ByteString,
      symmetricKey: ByteString,
  ): Either[DecryptionError, ByteString] =
    for {
      decrypter <- Either
        .catchOnly[GeneralSecurityException](new AesGcmJce(symmetricKey.toByteArray))
        .leftMap(err => DecryptionError.InvalidSymmetricKey(err.toString))
      plaintext <- Either
        .catchOnly[GeneralSecurityException](
          decrypter.decrypt(ciphertext.toByteArray, Array[Byte]())
        )
        .leftMap(err => DecryptionError.FailedToDecrypt(err.toString))
    } yield ByteString.copyFrom(plaintext)

  // Internal helper class for the symmetric encryption as part of the hybrid encryption scheme.
  private object Aes128GcmDemHelper extends EciesAeadHkdfDemHelper {

    override def getSymmetricKeySizeInBytes: Int = SymmetricKeyScheme.Aes128Gcm.keySizeInBytes

    override def getAead(symmetricKeyValue: Array[Byte]): Aead = new Aead {
      override def encrypt(plaintext: Array[Byte], associatedData: Array[Byte]): Array[Byte] = {
        val encrypter = new AesGcmJce(symmetricKeyValue)
        encrypter.encrypt(plaintext, associatedData)
      }

      override def decrypt(ciphertext: Array[Byte], associatedData: Array[Byte]): Array[Byte] = {
        val decrypter = new AesGcmJce(symmetricKeyValue)
        decrypter.decrypt(ciphertext, associatedData)
      }
    }
  }

  private def ensureFormat(
      key: CryptoKey,
      format: CryptoKeyFormat,
  ): Either[JavaKeyConversionError, Unit] =
    Either.cond(
      key.format == format,
      (),
      JavaKeyConversionError.UnsupportedKeyFormat(key.format, format),
    )

  private def toJavaEcDsa(
      privateKey: PrivateKey,
      curveType: CurveType,
  ): Either[JavaKeyConversionError, JPrivateKey] =
    for {
      _ <- ensureFormat(privateKey, CryptoKeyFormat.Der)
      ecPrivateKey <- Either
        .catchOnly[GeneralSecurityException](
          EllipticCurves.getEcPrivateKey(curveType, privateKey.key.toByteArray)
        )
        .leftMap(JavaKeyConversionError.GeneralError)
      _ =
        // There exists a race condition in ECPrivateKey before java 15
        if (Runtime.version.feature < 15)
          ecPrivateKey match {
            case pk: ECPrivateKeyImpl =>
              /* Force the initialization of the private key's internal array data structure.
               * This prevents concurrency problems later on during decryption, while generating the shared secret,
               * due to a race condition in getArrayS.
               */
              pk.getArrayS.discard
            case _ => ()
          }
    } yield ecPrivateKey

  private def toJava(privateKey: PrivateKey): Either[JavaKeyConversionError, JPrivateKey] = {

    def convert(
        format: CryptoKeyFormat,
        pkcs8PrivateKey: Array[Byte],
        keyInstance: String,
    ): Either[JavaKeyConversionError, JPrivateKey] =
      for {
        _ <- Either.cond(
          privateKey.format == format,
          (),
          JavaKeyConversionError.UnsupportedKeyFormat(privateKey.format, format),
        )
        pkcs8KeySpec = new PKCS8EncodedKeySpec(pkcs8PrivateKey)
        keyFactory <- Either
          .catchOnly[NoSuchAlgorithmException](KeyFactory.getInstance(keyInstance, "BC"))
          .leftMap(JavaKeyConversionError.GeneralError)
        javaPrivateKey <- Either
          .catchOnly[InvalidKeySpecException](keyFactory.generatePrivate(pkcs8KeySpec))
          .leftMap(err => JavaKeyConversionError.InvalidKey(show"$err"))
      } yield javaPrivateKey

    (privateKey: @unchecked) match {
      case sigKey: SigningPrivateKey =>
        sigKey.scheme match {
          case SigningKeyScheme.Ed25519 =>
            val privateKeyInfo = new PrivateKeyInfo(
              new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519),
              new DEROctetString(privateKey.key.toByteArray),
            )
            convert(CryptoKeyFormat.Raw, privateKeyInfo.getEncoded, "Ed25519")

          case SigningKeyScheme.EcDsaP256 => toJavaEcDsa(privateKey, CurveType.NIST_P256)
          case SigningKeyScheme.EcDsaP384 => toJavaEcDsa(privateKey, CurveType.NIST_P384)
        }
      case encKey: EncryptionPrivateKey =>
        encKey.scheme match {
          case EncryptionKeyScheme.EciesP256HkdfHmacSha256Aes128Gcm =>
            toJavaEcDsa(privateKey, CurveType.NIST_P256)
          case EncryptionKeyScheme.EciesP256HmacSha256Aes128Cbc =>
            convert(CryptoKeyFormat.Der, privateKey.key.toByteArray, "EC")
          case EncryptionKeyScheme.Rsa2048OaepSha256 =>
            convert(CryptoKeyFormat.Der, privateKey.key.toByteArray, "RSA")
        }
    }
  }

  private def ecDsaSigner(
      signingKey: SigningPrivateKey,
      hashType: HashType,
  ): Either[SigningError, PublicKeySign] =
    for {
      _ <- checkKeyFormat(CryptoKeyFormat.Der, signingKey.format, SigningError.InvalidSigningKey)
      javaPrivateKey <- javaPrivateKeyCache
        .getOrElseUpdate(
          signingKey.id,
          toJava(signingKey),
        )
        .leftMap(err =>
          SigningError.InvalidSigningKey(s"Failed to convert signing private key: $err")
        )

      ecPrivateKey <- javaPrivateKey match {
        case k: ECPrivateKey => Right(k)
        case _ =>
          Left(SigningError.InvalidSigningKey(s"Signing private key is not an EC private key"))
      }

      signer <- Either
        .catchOnly[GeneralSecurityException](
          new EcdsaSignJce(ecPrivateKey, hashType, EcdsaEncoding.DER)
        )
        .leftMap(err => SigningError.InvalidSigningKey(show"Failed to get signer for EC-DSA: $err"))
    } yield signer

  private def ecDsaVerifier(
      publicKey: SigningPublicKey,
      hashType: HashType,
  ): Either[SignatureCheckError, PublicKeyVerify] =
    for {
      pubKey <- convertKey(
        publicKey,
        CryptoKeyFormat.Der,
        SignatureCheckError.InvalidKeyError,
      )
      javaPublicKey <- javaPublicKeyCache
        .getOrElseUpdate(
          pubKey.id,
          javaKeyConverter
            .toJava(pubKey)
            .map(_._2),
        )
        .leftMap(err =>
          SignatureCheckError.InvalidKeyError(s"Failed to convert signing public key: $err")
        )

      ecPublicKey <- javaPublicKey match {
        case k: ECPublicKey => Right(k)
        case _ =>
          Left(SignatureCheckError.InvalidKeyError(s"Signing public key is not an EC public key"))
      }

      verifier <- Either
        .catchOnly[GeneralSecurityException](
          new EcdsaVerifyJce(ecPublicKey, hashType, EcdsaEncoding.DER)
        )
        .leftMap(err =>
          SignatureCheckError.InvalidKeyError(s"Failed to get signer for Ed25519: $err")
        )
    } yield verifier

  override def generateSymmetricKey(
      scheme: SymmetricKeyScheme
  ): Either[EncryptionKeyGenerationError, SymmetricKey] =
    scheme match {
      case SymmetricKeyScheme.Aes128Gcm =>
        val key128 = generateRandomByteString(scheme.keySizeInBytes)
        Right(SymmetricKey(CryptoKeyFormat.Raw, key128, scheme))
    }

  override def createSymmetricKey(
      bytes: SecureRandomness,
      scheme: SymmetricKeyScheme,
  ): Either[EncryptionKeyCreationError, SymmetricKey] = {
    val randomnessLength = bytes.unwrap.size()
    val keyLength = scheme.keySizeInBytes

    for {
      _ <- Either.cond(
        randomnessLength == keyLength,
        (),
        EncryptionKeyCreationError.InvalidRandomnessLength(randomnessLength, keyLength),
      )
      key = scheme match {
        case SymmetricKeyScheme.Aes128Gcm =>
          SymmetricKey(CryptoKeyFormat.Raw, bytes.unwrap, scheme)
      }
    } yield key
  }

  override protected[crypto] def sign(
      bytes: ByteString,
      signingKey: SigningPrivateKey,
  ): Either[SigningError, Signature] = {

    def signWithSigner(signer: PublicKeySign): Either[SigningError, Signature] =
      Either
        .catchOnly[GeneralSecurityException](signer.sign(bytes.toByteArray))
        .bimap(
          err => SigningError.FailedToSign(show"$err"),
          signatureBytes =>
            new Signature(SignatureFormat.Raw, ByteString.copyFrom(signatureBytes), signingKey.id),
        )

    signingKey.scheme match {
      case SigningKeyScheme.Ed25519 =>
        for {
          _ <- checkKeyFormat(
            CryptoKeyFormat.Raw,
            signingKey.format,
            SigningError.InvalidSigningKey,
          )
          signer <- Either
            .catchOnly[GeneralSecurityException](new Ed25519Sign(signingKey.key.toByteArray))
            .leftMap(err =>
              SigningError.InvalidSigningKey(show"Failed to get signer for Ed25519: $err")
            )
          signature <- signWithSigner(signer)
        } yield signature

      case SigningKeyScheme.EcDsaP256 =>
        ecDsaSigner(signingKey, HashType.SHA256).flatMap(signWithSigner)
      case SigningKeyScheme.EcDsaP384 =>
        ecDsaSigner(signingKey, HashType.SHA384).flatMap(signWithSigner)
    }
  }

  override protected[crypto] def verifySignature(
      bytes: ByteString,
      publicKey: SigningPublicKey,
      signature: Signature,
  ): Either[SignatureCheckError, Unit] = {

    def verify(verifier: PublicKeyVerify): Either[SignatureCheckError, Unit] =
      Either
        .catchOnly[GeneralSecurityException](
          verifier.verify(signature.unwrap.toByteArray, bytes.toByteArray)
        )
        .leftMap(err =>
          SignatureCheckError
            .InvalidSignature(signature, bytes, s"Failed to verify signature: $err")
        )

    for {
      _ <- Either.cond(
        signature.signedBy == publicKey.id,
        (),
        SignatureCheckError.SignatureWithWrongKey(
          s"Signature signed by ${signature.signedBy} instead of ${publicKey.id}"
        ),
      )

      _ <- publicKey.scheme match {
        case SigningKeyScheme.Ed25519 =>
          for {
            pubKey <- convertKey(
              publicKey,
              CryptoKeyFormat.Raw,
              SignatureCheckError.InvalidKeyError,
            )
            verifier <- Either
              .catchOnly[GeneralSecurityException](new Ed25519Verify(pubKey.key.toByteArray))
              .leftMap(err =>
                SignatureCheckError.InvalidKeyError(show"Failed to get signer for Ed25519: $err")
              )
            _ <- verify(verifier)
          } yield ()

        case SigningKeyScheme.EcDsaP256 => ecDsaVerifier(publicKey, HashType.SHA256).flatMap(verify)
        case SigningKeyScheme.EcDsaP384 => ecDsaVerifier(publicKey, HashType.SHA384).flatMap(verify)
      }
    } yield ()
  }

  private def checkEcKeyInCurve[K <: ECKey](key: K, keyId: Fingerprint): Either[String, K] = {
    val curve = EllipticCurves.getNistP256Params.getCurve
    Either.cond(
      key.getParams.getCurve.equals(curve),
      key,
      s"EC key $keyId is not a key in curve $curve",
    )
  }

  private def checkRsaKeySize[K <: RSAKey](key: K, keyId: Fingerprint): Either[String, K] = {
    val keySizeInBits = key.getModulus.bitLength()
    Either.cond(
      keySizeInBits == RSA2048OaepSha256Params.keySizeInBits,
      key,
      s"RSA key $keyId does not have the correct size. " +
        s"Expected: ${RSA2048OaepSha256Params.keySizeInBits} but got: $keySizeInBits.",
    )
  }

  private def checkDerFormatAndGetPublicKey[T <: JPublicKey](
      encPubKey: EncryptionPublicKey,
      checker: PartialFunction[JPublicKey, Either[EncryptionError, T]],
  ): Either[EncryptionError, T] = {
    for {
      pubKey <- convertKey(
        encPubKey,
        CryptoKeyFormat.Der,
        EncryptionError.InvalidEncryptionKey,
      )
      javaPublicKey <- javaPublicKeyCache
        .getOrElseUpdate(
          pubKey.id,
          javaKeyConverter
            .toJava(pubKey)
            .map(_._2),
        )
        .leftMap(err => EncryptionError.InvalidEncryptionKey(err.toString))
      publicKey <- checker(javaPublicKey)
    } yield publicKey
  }

  private def encryptWithEciesP256HmacSha256Aes128Cbc[M <: HasVersionedToByteString](
      message: ByteString,
      publicKey: EncryptionPublicKey,
      random: SecureRandom,
  ): Either[EncryptionError, AsymmetricEncrypted[M]] =
    for {
      ecPublicKey <- checkDerFormatAndGetPublicKey[ECPublicKey](
        publicKey,
        { case k: ECPublicKey =>
          checkEcKeyInCurve(k, publicKey.id).leftMap(err =>
            EncryptionError.InvalidEncryptionKey(err)
          )
        },
      )
      /* this encryption scheme makes use of AES-128-CBC as a DEM (Data Encapsulation Method)
       * and therefore we need to generate a IV/nonce of 16bytes as the IV for CBC mode.
       */
      iv = new Array[Byte](EciesP256HmacSha256Aes128CbcParams.ivSizeForAesCbcInBytes)
      _ = random.nextBytes(iv)
      encrypter <- Either
        .catchOnly[GeneralSecurityException] {
          val cipher = Cipher
            .getInstance(EciesP256HmacSha256Aes128CbcParams.jceInternalName, bouncyCastleProvider)
          cipher.init(
            Cipher.ENCRYPT_MODE,
            ecPublicKey,
            EciesP256HmacSha256Aes128CbcParams.parameterSpec(iv),
            random,
          )
          cipher
        }
        .leftMap(err => EncryptionError.InvalidEncryptionKey(err.toString))
      ciphertext <- Either
        .catchOnly[GeneralSecurityException](
          encrypter.doFinal(message.toByteArray)
        )
        .leftMap(err => EncryptionError.FailedToEncrypt(err.toString))
    } yield new AsymmetricEncrypted[M](
      /* Prepend our IV to the ciphertext. On contrary to the Tink library, BouncyCastle's
       * ECIES encryption does not deal with the AES IV by itself and we have to randomly generate it and
       * manually prepend it to the ciphertext.
       */
      ByteString.copyFrom(iv ++ ciphertext),
      publicKey.fingerprint,
    )

  private def encryptWithRSA2048OaepSha256[M <: HasVersionedToByteString](
      message: ByteString,
      publicKey: EncryptionPublicKey,
      random: SecureRandom,
  ): Either[EncryptionError, AsymmetricEncrypted[M]] =
    for {
      rsaPublicKey <- checkDerFormatAndGetPublicKey[RSAPublicKey](
        publicKey,
        { case k: RSAPublicKey =>
          checkRsaKeySize(k, publicKey.id).leftMap(err => EncryptionError.InvalidEncryptionKey(err))
        },
      )
      encrypter <- Either
        .catchOnly[GeneralSecurityException] {
          val cipher = Cipher
            .getInstance(RSA2048OaepSha256Params.jceInternalName, "BC")
          cipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey, random)
          cipher
        }
        .leftMap(err => EncryptionError.InvalidEncryptionKey(err.toString))
      ciphertext <- Either
        .catchOnly[GeneralSecurityException](
          encrypter.doFinal(message.toByteArray)
        )
        .leftMap(err => EncryptionError.FailedToEncrypt(err.toString))
    } yield new AsymmetricEncrypted[M](
      ByteString.copyFrom(ciphertext),
      publicKey.fingerprint,
    )

  override def encryptWith[M <: HasVersionedToByteString](
      message: M,
      publicKey: EncryptionPublicKey,
      version: ProtocolVersion,
  ): Either[EncryptionError, AsymmetricEncrypted[M]] =
    publicKey.scheme match {
      case EncryptionKeyScheme.EciesP256HkdfHmacSha256Aes128Gcm =>
        for {
          ecPublicKey <- checkDerFormatAndGetPublicKey[ECPublicKey](
            publicKey,
            { case k: ECPublicKey =>
              checkEcKeyInCurve(k, publicKey.id).leftMap(err =>
                EncryptionError.InvalidEncryptionKey(err)
              )
            },
          )
          encrypter <- Either
            .catchOnly[GeneralSecurityException](
              new EciesAeadHkdfHybridEncrypt(
                ecPublicKey,
                Array[Byte](),
                "HmacSha256",
                EllipticCurves.PointFormatType.UNCOMPRESSED,
                Aes128GcmDemHelper,
              )
            )
            .leftMap(err => EncryptionError.InvalidEncryptionKey(err.toString))
          ciphertext <- Either
            .catchOnly[GeneralSecurityException](
              encrypter
                .encrypt(
                  message.toByteString(version).toByteArray,
                  Array[Byte](),
                )
            )
            .leftMap(err => EncryptionError.FailedToEncrypt(err.toString))
          encrypted = new AsymmetricEncrypted[M](
            ByteString.copyFrom(ciphertext),
            publicKey.fingerprint,
          )
        } yield encrypted
      case EncryptionKeyScheme.EciesP256HmacSha256Aes128Cbc =>
        encryptWithEciesP256HmacSha256Aes128Cbc(
          message.toByteString(version),
          publicKey,
          JceSecureRandom.random.get(),
        )
      case EncryptionKeyScheme.Rsa2048OaepSha256 =>
        encryptWithRSA2048OaepSha256(
          message.toByteString(version),
          publicKey,
          JceSecureRandom.random.get(),
        )
    }

  override def encryptDeterministicWith[M <: HasVersionedToByteString](
      message: M,
      publicKey: EncryptionPublicKey,
      version: ProtocolVersion,
  )(implicit traceContext: TraceContext): Either[EncryptionError, AsymmetricEncrypted[M]] =
    if (!publicKey.scheme.supportDeterministicEncryption) {
      Left(
        EncryptionError.UnsupportedSchemeForDeterministicEncryption(
          s"${publicKey.scheme.name} does not support deterministic asymmetric/hybrid encryption"
        )
      )
    } else {
      lazy val messageSerialized = message.toByteString(version)
      lazy val deterministicRandomGenerator = DeterministicRandom.getDeterministicRandomGenerator(
        messageSerialized,
        publicKey.fingerprint,
        loggerFactory,
      )

      publicKey.scheme match {
        case EncryptionKeyScheme.EciesP256HkdfHmacSha256Aes128Gcm =>
          Left(
            EncryptionError.UnsupportedSchemeForDeterministicEncryption(
              s"${EncryptionKeyScheme.EciesP256HkdfHmacSha256Aes128Gcm.name} does not support deterministic asymmetric/hybrid encryption"
            )
          )
        case EncryptionKeyScheme.EciesP256HmacSha256Aes128Cbc =>
          encryptWithEciesP256HmacSha256Aes128Cbc(
            messageSerialized,
            publicKey,
            deterministicRandomGenerator,
          )
        case EncryptionKeyScheme.Rsa2048OaepSha256 =>
          encryptWithRSA2048OaepSha256(
            message.toByteString(version),
            publicKey,
            deterministicRandomGenerator,
          )
      }
    }

  override protected def decryptWithInternal[M](
      encrypted: AsymmetricEncrypted[M],
      privateKey: EncryptionPrivateKey,
  )(
      deserialize: ByteString => Either[DeserializationError, M]
  ): Either[DecryptionError, M] = {

    def checkDerFormatAndGetPrivateKey[T <: JPrivateKey](
        checker: PartialFunction[JPrivateKey, Either[DecryptionError, T]]
    ): Either[DecryptionError, T] =
      for {
        _ <- checkKeyFormat(
          CryptoKeyFormat.Der,
          privateKey.format,
          DecryptionError.InvalidEncryptionKey,
        )
        javaPrivateKey <- javaPrivateKeyCache
          .getOrElseUpdate(
            privateKey.id,
            toJava(privateKey),
          )
          .leftMap(err => DecryptionError.InvalidEncryptionKey(err.toString))
        privateKey <- checker(javaPrivateKey)
      } yield privateKey

    privateKey.scheme match {
      case EncryptionKeyScheme.EciesP256HkdfHmacSha256Aes128Gcm =>
        for {
          ecPrivateKey <- checkDerFormatAndGetPrivateKey(
            { case k: ECPrivateKey =>
              checkEcKeyInCurve(k, privateKey.id).leftMap(err =>
                DecryptionError.InvalidEncryptionKey(err)
              )
            }
          )
          decrypter <- Either
            .catchOnly[GeneralSecurityException](
              new EciesAeadHkdfHybridDecrypt(
                ecPrivateKey,
                Array[Byte](),
                "HmacSha256",
                EllipticCurves.PointFormatType.UNCOMPRESSED,
                Aes128GcmDemHelper,
              )
            )
            .leftMap(err => DecryptionError.InvalidEncryptionKey(err.toString))
          plaintext <- Either
            .catchOnly[GeneralSecurityException](
              decrypter.decrypt(encrypted.ciphertext.toByteArray, Array[Byte]())
            )
            .leftMap(err => DecryptionError.FailedToDecrypt(err.toString))
          message <- deserialize(ByteString.copyFrom(plaintext))
            .leftMap(DecryptionError.FailedToDeserialize)
        } yield message
      case EncryptionKeyScheme.EciesP256HmacSha256Aes128Cbc =>
        for {
          ecPrivateKey <- checkDerFormatAndGetPrivateKey[ECPrivateKey](
            { case k: ECPrivateKey =>
              checkEcKeyInCurve(k, privateKey.id).leftMap(err =>
                DecryptionError.InvalidEncryptionKey(err)
              )
            }
          )
          /* we split at 'ivSizeForAesCbc' (=16) because that is the size of our iv (for AES-128-CBC)
           * that gets  pre-appended to the ciphertext.
           */
          ciphertextSplit <- DeterministicEncoding
            .splitAt(
              EciesP256HmacSha256Aes128CbcParams.ivSizeForAesCbcInBytes,
              encrypted.ciphertext,
            )
            .leftMap(err =>
              DecryptionError.FailedToDeserialize(DefaultDeserializationError(err.show))
            )
          (iv, ciphertext) = ciphertextSplit
          decrypter <- Either
            .catchOnly[GeneralSecurityException] {
              val cipher = Cipher
                .getInstance(
                  EciesP256HmacSha256Aes128CbcParams.jceInternalName,
                  bouncyCastleProvider,
                )
              cipher.init(
                Cipher.DECRYPT_MODE,
                ecPrivateKey,
                EciesP256HmacSha256Aes128CbcParams.parameterSpec(iv.toByteArray),
              )
              cipher
            }
            .leftMap(err => DecryptionError.InvalidEncryptionKey(err.toString))
          plaintext <- Either
            .catchOnly[GeneralSecurityException](
              decrypter.doFinal(ciphertext.toByteArray)
            )
            .leftMap(err => DecryptionError.FailedToDecrypt(err.toString))
          message <- deserialize(ByteString.copyFrom(plaintext))
            .leftMap(DecryptionError.FailedToDeserialize)
        } yield message
      case EncryptionKeyScheme.Rsa2048OaepSha256 =>
        for {
          rsaPrivateKey <- checkDerFormatAndGetPrivateKey[RSAPrivateKey](
            { case k: RSAPrivateKey =>
              checkRsaKeySize(k, privateKey.id).leftMap(err =>
                DecryptionError.InvalidEncryptionKey(err)
              )
            }
          )
          decrypter <- Either
            .catchOnly[GeneralSecurityException] {
              val cipher = Cipher
                .getInstance(
                  RSA2048OaepSha256Params.jceInternalName,
                  "BC",
                )
              cipher.init(
                Cipher.DECRYPT_MODE,
                rsaPrivateKey,
              )
              cipher
            }
            .leftMap(err => DecryptionError.InvalidEncryptionKey(err.toString))
          plaintext <- Try[Array[Byte]](
            decrypter.doFinal(encrypted.ciphertext.toByteArray)
          ).toEither.leftMap {
            case err: DataLengthException =>
              DecryptionError
                .FailedToDecrypt(
                  s"Most probably using a wrong secret key to decrypt the ciphertext: ${err.toString}"
                )
            case err =>
              DecryptionError.FailedToDecrypt(ErrorUtil.messageWithStacktrace(err))
          }
          message <- deserialize(ByteString.copyFrom(plaintext))
            .leftMap(DecryptionError.FailedToDeserialize)
        } yield message
    }
  }

  override def encryptWith[M <: HasVersionedToByteString](
      message: M,
      symmetricKey: SymmetricKey,
      version: ProtocolVersion,
  ): Either[EncryptionError, Encrypted[M]] =
    symmetricKey.scheme match {
      case SymmetricKeyScheme.Aes128Gcm =>
        for {
          _ <- checkKeyFormat(
            CryptoKeyFormat.Raw,
            symmetricKey.format,
            EncryptionError.InvalidSymmetricKey,
          )
          encryptedBytes <- encryptAes128Gcm(
            message.toByteString(version),
            symmetricKey.key,
          )
          encrypted = new Encrypted[M](encryptedBytes)
        } yield encrypted
    }

  override def decryptWith[M](encrypted: Encrypted[M], symmetricKey: SymmetricKey)(
      deserialize: ByteString => Either[DeserializationError, M]
  ): Either[DecryptionError, M] =
    symmetricKey.scheme match {
      case SymmetricKeyScheme.Aes128Gcm =>
        for {
          _ <- checkKeyFormat(
            CryptoKeyFormat.Raw,
            symmetricKey.format,
            DecryptionError.InvalidSymmetricKey,
          )
          plaintext <- decryptAes128Gcm(encrypted.ciphertext, symmetricKey.key)
          message <- deserialize(plaintext).leftMap(DecryptionError.FailedToDeserialize)
        } yield message
    }

  private def hkdf(
      params: HKDFParameters,
      outputBytes: Int,
      algorithm: HmacAlgorithm,
  ): Either[HkdfError, SecureRandomness] = {
    val output = Array.fill[Byte](outputBytes)(0)
    val digest = algorithm match {
      case HmacAlgorithm.HmacSha256 => new SHA256Digest()
    }
    val generator = new HKDFBytesGenerator(digest)

    for {
      generated <-
        Either
          .catchNonFatal {
            generator.init(params)
            generator.generateBytes(output, 0, outputBytes)
          }
          .leftMap(err => HkdfInternalError(show"Failed to compute HKDF with JCE: $err"))
      _ <- Either.cond(
        generated == outputBytes,
        (),
        HkdfInternalError(s"Generated only $generated bytes instead of $outputBytes"),
      )
      expansion <- SecureRandomness
        .fromByteString(outputBytes)(ByteString.copyFrom(output))
        .leftMap(err => HkdfInternalError(s"Invalid output from HKDF: $err"))
    } yield expansion
  }

  override protected def computeHkdfInternal(
      keyMaterial: ByteString,
      outputBytes: Int,
      info: HkdfInfo,
      salt: ByteString,
      algorithm: HmacAlgorithm,
  ): Either[HkdfError, SecureRandomness] = {
    val params =
      new HKDFParameters(keyMaterial.toByteArray, salt.toByteArray, info.bytes.toByteArray)
    hkdf(params, outputBytes, algorithm)
  }

  override protected def hkdfExpandInternal(
      keyMaterial: SecureRandomness,
      outputBytes: Int,
      info: HkdfInfo,
      algorithm: HmacAlgorithm,
  ): Either[HkdfError, SecureRandomness] = {
    val params =
      HKDFParameters.skipExtractParameters(keyMaterial.unwrap.toByteArray, info.bytes.toByteArray)
    hkdf(params, outputBytes, algorithm)
  }

  override protected def generateRandomBytes(length: Int): Array[Byte] =
    JceSecureRandom.generateRandomBytes(length)

  override def deriveSymmetricKey(
      password: String,
      symmetricKeyScheme: SymmetricKeyScheme,
      pbkdfScheme: PbkdfScheme,
      saltO: Option[SecureRandomness],
  ): Either[PasswordBasedEncryptionError, PasswordBasedEncryptionKey] = {
    pbkdfScheme match {
      case mode: PbkdfScheme.Argon2idMode1.type =>
        val salt = saltO.getOrElse(generateSecureRandomness(pbkdfScheme.defaultSaltLengthInBytes))

        val params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
          .withIterations(mode.iterations)
          .withMemoryAsKB(mode.memoryInKb)
          .withParallelism(mode.parallelism)
          .withSalt(salt.unwrap.toByteArray)
          .build()

        val argon2 = new Argon2BytesGenerator()
        argon2.init(params)

        val keyLength = symmetricKeyScheme.keySizeInBytes
        val keyBytes = new Array[Byte](keyLength)
        val keyLen = argon2.generateBytes(password.toCharArray, keyBytes)

        val key =
          SymmetricKey(CryptoKeyFormat.Raw, ByteString.copyFrom(keyBytes), symmetricKeyScheme)

        Either.cond(
          keyLen == keyLength,
          PasswordBasedEncryptionKey(key = key, salt = salt),
          PasswordBasedEncryptionError.PbkdfOutputLengthInvalid(
            expectedLength = keyLength,
            actualLength = keyLen,
          ),
        )
    }
  }
}
