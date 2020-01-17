-- Copyright (c) 2020 The DAML Authors. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

module DA.Test.Packaging (main) where

import qualified "zip-archive" Codec.Archive.Zip as Zip
import Control.Monad.Extra
import Control.Exception.Safe
import DA.Bazel.Runfiles
import qualified DA.Daml.LF.Ast as LF
import DA.Daml.LF.Reader (readDalfManifest, readDalfs, packageName, Dalfs(..), DalfManifest(DalfManifest), mainDalfPath, dalfPaths)
import qualified DA.Daml.LF.Proto3.Archive as LFArchive
import Data.Conduit.Tar.Extra (dropDirectory1)
import qualified Data.ByteString.Lazy as BSL
import qualified Data.ByteString.Lazy.Char8 as BSL.Char8
import Data.List.Extra
import Data.Maybe
import System.Directory.Extra
import System.Environment.Blank
import System.Exit
import System.FilePath
import System.Info.Extra
import System.IO.Extra
import System.Process
import Test.Tasty
import Test.Tasty.HUnit

import SdkVersion

main :: IO ()
main = do
    setEnv "TASTY_NUM_THREADS" "1" True
    damlc <- locateRunfiles (mainWorkspace </> "compiler" </> "damlc" </> exe "damlc")
    repl <- locateRunfiles (mainWorkspace </> "daml-lf" </> "repl" </> exe "repl")
    defaultMain $ tests damlc repl

tests :: FilePath -> FilePath -> TestTree
tests damlc repl = testGroup "Packaging"
    [ testCaseSteps "Build package with dependency" $ \step -> withTempDir $ \tmpDir -> do
        let projectA = tmpDir </> "a"
        let projectB = tmpDir </> "b"
        let aDar = projectA </> ".daml" </> "dist" </> "a-1.0.dar"
        let bDar = projectB </> ".daml" </> "dist" </> "b-1.0.dar"
        step "Creating project a..."
        createDirectoryIfMissing True (projectA </> "daml" </> "Foo" </> "Bar")
        writeFileUTF8 (projectA </> "daml" </> "A.daml") $ unlines
            [ "daml 1.2"
            , "module A (a) where"
            , "a : ()"
            , "a = ()"
            ]
        writeFileUTF8 (projectA </> "daml" </> "Foo" </> "Bar" </> "Baz.daml") $ unlines
            [ "daml 1.2"
            , "module Foo.Bar.Baz (c) where"
            , "import A (a)"
            , "c : ()"
            , "c = a"
            ]
        writeFileUTF8 (projectA </> "daml.yaml") $ unlines
            [ "sdk-version: " <> sdkVersion
            , "name: a"
            , "version: \"1.0\""
            , "source: daml"
            , "exposed-modules: [A, Foo.Bar.Baz]"
            , "dependencies:"
            , "  - daml-prim"
            , "  - daml-stdlib"
            ]
        buildProject projectA
        assertBool "a-1.0.dar was not created." =<< doesFileExist aDar
        step "Creating project b..."
        createDirectoryIfMissing True (projectB </> "daml")
        writeFileUTF8 (projectB </> "daml" </> "B.daml") $ unlines
            [ "daml 1.2"
            , "module B where"
            , "import C"
            , "import Foo.Bar.Baz"
            , "b : ()"
            , "b = a"
            , "d : ()"
            , "d = c"
            ]
        writeFileUTF8 (projectB </> "daml.yaml") $ unlines
            [ "sdk-version: " <> sdkVersion
            , "version: \"1.0\""
            , "name: b"
            , "source: daml"
            , "exposed-modules: [B]"
            , "dependencies:"
            , "  - daml-prim"
            , "  - daml-stdlib"
            , "  - " <> aDar
            , "build-options:"
            , "- '--package=(\"a-1.0\", True, [(\"A\", \"C\")])'"
            ]
            -- the last option checks that module aliases work and modules imported without aliases
            -- are still exposed.
        buildProject projectB
        assertBool "b.dar was not created." =<< doesFileExist bDar
    , testCaseSteps "Dependency on a package with source: A.daml" $ \step -> withTempDir $ \tmpDir -> do
        let projectA = tmpDir </> "a"
        let projectB = tmpDir </> "b"
        let aDar = projectA </> ".daml" </> "dist" </> "a-1.0.dar"
        let bDar = projectB </> ".daml" </> "dist" </> "b-1.0.dar"
        step "Creating project a..."
        createDirectoryIfMissing True projectA
        writeFileUTF8 (projectA </> "A.daml") $ unlines
            [ "daml 1.2"
            , "module A () where"
            ]
        writeFileUTF8 (projectA </> "daml.yaml") $ unlines
            [ "sdk-version: " <> sdkVersion
            , "name: a"
            , "version: \"1.0\""
            , "source: A.daml"
            , "dependencies:"
            , "  - daml-prim"
            , "  - daml-stdlib"
            ]
        buildProject projectA
        assertBool "a-1.0.dar was not created." =<< doesFileExist aDar
        step "Creating project b..."
        createDirectoryIfMissing True projectB
        writeFileUTF8 (projectB </> "B.daml") $ unlines
            [ "daml 1.2"
            , "module B where"
            , "import A ()"
            ]
        writeFileUTF8 (projectB </> "daml.yaml") $ unlines
            [ "sdk-version: " <> sdkVersion
            , "version: \"1.0\""
            , "name: b"
            , "source: ."
            , "dependencies:"
            , "  - daml-prim"
            , "  - daml-stdlib"
            , "  - " <> aDar
            ]
        buildProject projectB
        assertBool "b.dar was not created." =<< doesFileExist bDar
        darFiles <- Zip.filesInArchive . Zip.toArchive <$> BSL.readFile bDar
        assertBool "b.dar contains source file from package database" $
            not $ any ("A.daml" `isSuffixOf`) darFiles
    , testCase "Top-level source files" $ withTempDir $ \tmpDir -> do
        -- Test that a source file in the project root will be included in the
        -- DAR file. Regression test for #1048.
        let projDir = tmpDir </> "proj"
        createDirectoryIfMissing True projDir
        writeFileUTF8 (projDir </> "A.daml") $ unlines
          [ "daml 1.2"
          , "module A (a) where"
          , "a : ()"
          , "a = ()"
          ]
        writeFileUTF8 (projDir </> "daml.yaml") $ unlines
          [ "sdk-version: " <> sdkVersion
          , "name: proj"
          , "version: \"1.0\""
          , "source: ."
          , "exposed-modules: [A]"
          , "dependencies:"
          , "  - daml-prim"
          , "  - daml-stdlib"
          ]
        buildProject projDir
        let dar = projDir </> ".daml" </> "dist" </> "proj-1.0.dar"
        assertBool "proj.dar was not created." =<< doesFileExist dar
        darFiles <- Zip.filesInArchive . Zip.toArchive <$> BSL.readFile dar
        assertBool "A.daml is missing" (any (\f -> takeFileName f == "A.daml") darFiles)

    , testCase "Check that DAR manifest prefers DAML_SDK_VERSION over daml.yaml sdk-version." $ withTempDir $ \tmpDir -> do
        -- Regression test for bug fixed in #3587.
        let projDir = tmpDir </> "proj"
        createDirectoryIfMissing True projDir
        writeFileUTF8 (projDir </> "A.daml") $ unlines
          [ "daml 1.2"
          , "module A (a) where"
          , "a : ()"
          , "a = ()"
          ]
        writeFileUTF8 (projDir </> "daml.yaml") $ unlines
          [ "sdk-version: a-bad-sdk-version"
          , "name: proj"
          , "version: \"1.0\""
          , "source: ."
          , "exposed-modules: [A]"
          , "dependencies:"
          , "  - daml-prim"
          , "  - daml-stdlib"
          ]

        bracket
            (setEnv "DAML_SDK_VERSION" sdkVersion True)
            (\ _ -> unsetEnv "DAML_SDK_VERSION")
            (\ _ -> buildProject projDir)

        let dar = projDir </> ".daml" </> "dist" </> "proj-1.0.dar"
        assertBool "proj.dar was not created." =<< doesFileExist dar
        archive <- Zip.toArchive <$> BSL.readFile dar
        Just entry <- pure $ Zip.findEntryByPath "META-INF/MANIFEST.MF" archive
        let lines = BSL.Char8.lines (Zip.fromEntry entry)
            expectedLine = "Sdk-Version: " <> BSL.Char8.pack sdkVersion
        assertBool "META-INF/MANIFEST.MF picked up the wrong sdk version" (expectedLine `elem` lines)

    , testCase "Non-root sources files" $ withTempDir $ \projDir -> do
        -- Test that all daml source files get included in the dar if "source" points to a file
        -- rather than a directory
        writeFileUTF8 (projDir </> "A.daml") $ unlines
          [ "daml 1.2"
          , "module A where"
          , "import B ()"
          ]
        writeFileUTF8 (projDir </> "B.daml") $ unlines
          [ "daml 1.2"
          , "module B where"
          ]
        writeFileUTF8 (projDir </> "daml.yaml") $ unlines
          [ "sdk-version: " <> sdkVersion
          , "name: proj"
          , "version: 0.1.0"
          , "source: A.daml"
          , "dependencies: [daml-prim, daml-stdlib]"
          ]
        buildProject projDir
        let dar = projDir </> ".daml/dist/proj-0.1.0.dar"
        assertBool "proj-0.1.0.dar was not created." =<< doesFileExist dar
        darFiles <- Zip.filesInArchive . Zip.toArchive <$> BSL.readFile dar
        forM_ ["A.daml", "A.hi", "A.hie", "B.daml", "B.hi", "B.hie"] $ checkDarFile darFiles "."
    , testCase "Root source file in subdir" $ withTempDir $ \projDir -> do
        -- Test that the daml source files get included properly if "source" points to a file
        -- in a subdirectory.
        createDirectoryIfMissing True (projDir </> "A")
        createDirectoryIfMissing True (projDir </> "B")
        writeFileUTF8 (projDir </> "A/B.daml") $ unlines
          [ "daml 1.2"
          , "module A.B where"
          , "import B.C ()"
          ]
        writeFileUTF8 (projDir </> "B/C.daml") $ unlines
          [ "daml 1.2"
          , "module B.C where"
          ]
        writeFileUTF8 (projDir </> "daml.yaml") $ unlines
          [ "sdk-version: " <> sdkVersion
          , "name: proj"
          , "version: 0.1.0"
          , "source: A/B.daml"
          , "dependencies: [daml-prim, daml-stdlib]"
          ]
        buildProject projDir
        let dar = projDir </> ".daml/dist/proj-0.1.0.dar"
        assertBool "proj-0.1.0.dar was not created." =<< doesFileExist dar
        darFiles <- Zip.filesInArchive . Zip.toArchive <$> BSL.readFile dar
        checkDarFile darFiles "A" "B.daml"
        checkDarFile darFiles "A" "B.hi"
        checkDarFile darFiles "A" "B.hie"
        checkDarFile darFiles "B" "C.daml"
        checkDarFile darFiles "B" "C.hi"
        checkDarFile darFiles "B" "C.hie"

    , testCase "Dalf dependencies get package id suffices" $ withTempDir $ \projDir -> do
        createDirectoryIfMissing True (projDir </> "daml")
        writeFileUTF8 (projDir </> "daml/A.daml") $ unlines
          [ "daml 1.2"
          , "module A where"
          , "data A = A ()"
          ]
        writeFileUTF8 (projDir </> "daml.yaml") $ unlines
          [ "sdk-version: " <> sdkVersion
          , "name: proj"
          , "version: 0.1.0"
          , "source: daml"
          , "dependencies: [daml-prim, daml-stdlib]"
          ]
        buildProject projDir
        let dar = projDir </> ".daml/dist/proj-0.1.0.dar"
        assertBool "proj-0.1.0.dar was not created." =<< doesFileExist dar
        darFiles <- Zip.filesInArchive . Zip.toArchive <$> BSL.readFile dar
        let allDalfFilesHavePkgId = and $ do
              fp <- darFiles
              guard $ "dalf" `isExtensionOf` fp
              let (_s, pId) = fromMaybe ("", "not a package id") $ stripInfixEnd "-" $ takeBaseName fp
              pure $ all (`elem` ['a' .. 'f'] ++ ['0' .. '9']) pId
        assertBool "Dalf files without package ids" allDalfFilesHavePkgId

    , testCase "Imports from different directories" $ withTempDir $ \projDir -> do
        -- Regression test for #2929
        createDirectory (projDir </> "A")
        writeFileUTF8 (projDir </> "A.daml") $ unlines
          [ "daml 1.2"
          , "module A where"
          , "import A.B ()"
          , "import A.C ()"
          ]
        writeFileUTF8 (projDir </> "A/B.daml") $ unlines
          [ "daml 1.2"
          , "module A.B where"
          , "import A.C ()"
          ]
        writeFileUTF8 (projDir </> "A/C.daml") $ unlines
          [ "daml 1.2"
          , "module A.C where"
          ]
        writeFileUTF8 (projDir </> "daml.yaml") $ unlines
          [ "sdk-version: " <> sdkVersion
          , "name: proj"
          , "version: 0.1.0"
          , "source: ."
          , "dependencies: [daml-prim, daml-stdlib]"
          ]
        buildProject projDir

    , testCase "Project without exposed modules" $ withTempDir $ \projDir -> do
        writeFileUTF8 (projDir </> "A.daml") $ unlines
            [ "daml 1.2"
            , "module A (a) where"
            , "a : ()"
            , "a = ()"
            ]
        writeFileUTF8 (projDir </> "daml.yaml") $ unlines
            [ "sdk-version: " <> sdkVersion
            , "name: proj"
            , "version: \"1.0\""
            , "source: A.daml"
            , "dependencies: [daml-prim, daml-stdlib]"
            ]
        buildProject projDir

    , testCase "Empty package" $ withTempDir $ \projDir -> do
        writeFileUTF8 (projDir </> "daml.yaml") $ unlines
            [ "sdk-version: " <> sdkVersion
            , "name: proj"
            , "version: 0.0.1"
            , "source: src"
            , "dependencies: [daml-prim, daml-stdlib]"
            ]
        createDirectoryIfMissing True (projDir </> "src")
        buildProject projDir

    , testCase "Package-wide name collision" $ withTempDir $ \projDir -> do
        createDirectoryIfMissing True (projDir </> "src")
        createDirectoryIfMissing True (projDir </> "src" </> "A")
        writeFileUTF8 (projDir </> "daml.yaml") $ unlines
            [ "sdk-version: " <> sdkVersion
            , "name: proj"
            , "version: 0.0.1"
            , "source: src"
            , "dependencies: [daml-prim, daml-stdlib]"
            ]
        writeFileUTF8 (projDir </> "src" </> "A.daml") $ unlines
            [ "daml 1.2"
            , "module A where"
            , "data B = B Int"
            ]
        writeFileUTF8 (projDir </> "src" </> "A" </> "B.daml") $ unlines
            [ "daml 1.2"
            , "module A.B where"
            , "data C = C Int"
            ]
        buildProjectError projDir "" "name collision"

    , testCase "Manifest name" $ withTempDir $ \projDir -> do
          createDirectoryIfMissing True (projDir </> "src")
          writeFileUTF8 (projDir </> "daml.yaml") $ unlines
            [ "sdk-version: " <> sdkVersion
            , "name: foobar"
            , "version: 0.0.1"
            , "source: src"
            , "dependencies: [daml-prim, daml-stdlib]"
            ]
          withCurrentDirectory projDir $ callProcessSilent damlc ["build", "-o", "baz.dar"]
          Right manifest <- readDalfManifest . Zip.toArchive  <$> BSL.readFile (projDir </> "baz.dar")
          -- Verify that the name in the manifest is independent of the DAR name.
          packageName manifest @?= Just "foobar-0.0.1"

    , testCase "Transitive package deps" $ withTempDir $ \projDir -> do
          -- Check that the depends field in the package config files does not depend on the name of the DAR.
          let projA = projDir </> "a"
          let projB = projDir </> "b"
          let projC = projDir </> "c"

          createDirectoryIfMissing True (projA </> "src")
          writeFileUTF8 (projA </> "daml.yaml") $ unlines
            [ "sdk-version: " <> sdkVersion
            , "name: a"
            , "version: 0.0.1"
            , "source: src"
            , "dependencies: [daml-prim, daml-stdlib]"
            ]
          writeFileUTF8 (projA </> "src" </> "A.daml") $ unlines
            [ "daml 1.2"
            , "module A where"
            ]
          withCurrentDirectory projA $ callProcessSilent damlc ["build", "-o", "foo.dar"]

          createDirectoryIfMissing True (projB </> "src")
          writeFileUTF8 (projB </> "daml.yaml") $ unlines
            [ "sdk-version: " <> sdkVersion
            , "name: b"
            , "version: 0.0.1"
            , "source: src"
            , "dependencies:"
            , " - daml-prim"
            , " - daml-stdlib"
            , " - " <> projA </> "foo.dar"
            ]
          writeFileUTF8 (projB </> "src" </> "B.daml") $ unlines
            [ "daml 1.2"
            , "module B where"
            , "import A"
            ]
          withCurrentDirectory projB $ callProcessSilent damlc ["build", "-o", "bar.dar"]

          createDirectoryIfMissing True (projC </> "src")
          writeFileUTF8 (projC </> "daml.yaml") $ unlines
            [ "sdk-version: " <> sdkVersion
            , "name: c"
            , "version: 0.0.1"
            , "source: src"
            , "dependencies:"
            , " - daml-prim"
            , " - daml-stdlib"
            , " - " <> projA </> "foo.dar"
            , " - " <> projB </> "bar.dar"
            ]
          writeFileUTF8 (projC </> "src" </> "C.daml") $ unlines
            [ "daml 1.2"
            , "module C where"
            , "import A"
            , "import B"
            ]
          withCurrentDirectory projC $ callProcessSilent damlc ["build", "-o", "baz.dar"]

    , testCase "build-options + project-root" $ withTempDir $ \projDir -> do
          createDirectoryIfMissing True (projDir </> "src")
          writeFileUTF8 (projDir </> "daml.yaml") $ unlines
            [ "sdk-version: " <> sdkVersion
            , "name: a"
            , "version: 0.0.1"
            , "source: src"
            , "dependencies: [daml-prim, daml-stdlib]"
            , "build-options: [\"--ghc-option=-Werror\"]"
            ]
          writeFileUTF8 (projDir </> "src" </> "A.daml") $ unlines
            [ "daml 1.2"
            , "module A where"
            , "f : Optional a -> a"
            , "f (Some a) = a"
            ]
          (exitCode, _, stderr) <- readProcessWithExitCode damlc ["build", "--project-root", projDir] ""
          exitCode @?= ExitFailure 1
          assertBool ("non-exhaustive error in " <> stderr) ("non-exhaustive" `isInfixOf` stderr)

    , lfVersionTests damlc

    , dataDependencyTests damlc repl
    ]
  where
      buildProject' :: FilePath -> FilePath -> IO ()
      buildProject' damlc dir = withCurrentDirectory dir $ callProcessSilent damlc ["build"]
      buildProject = buildProject' damlc

      buildProjectError :: FilePath -> String -> String -> IO ()
      buildProjectError dir expectedOut expectedErr = withCurrentDirectory dir $ do
          (exitCode, out, err) <- readProcessWithExitCode damlc ["build"] ""
          if exitCode /= ExitSuccess then do
              unless (expectedOut `isInfixOf` out && expectedErr `isInfixOf` err) $ do
                  hPutStrLn stderr $ unlines
                      [ "TEST FAILED:"
                      , "    Command \"damlc build\" failed as expected, but did not produce expected output."
                      , "    stdout = " <> show out
                      , "    stderr = " <> show err
                      ]
                  exitFailure
          else do
              hPutStrLn stderr $ unlines
                  [ "TEST FAILED:"
                  , "    Command \"damlc build\" was expected to fail, but it succeeded."
                  , "    stdout = " <> show out
                  , "    stderr = " <> show err
                  ]
              exitFailure

-- | Test that a package build with --target=targetVersion never has a dependency on a package with version > targetVersion
lfVersionTests :: FilePath -> TestTree
lfVersionTests damlc = testGroup "LF version dependencies"
    [ testCase ("Package in " <> LF.renderVersion version) $ withTempDir $ \projDir -> do
          writeFileUTF8 (projDir </> "daml.yaml") $ unlines
              [ "sdk-version: " <> sdkVersion
              , "name: proj"
              , "version: 0.1.0"
              , "source: ."
              , "dependencies: [daml-prim, daml-stdlib]"
              ]
          writeFileUTF8 (projDir </> "A.daml") $ unlines
              [ "daml 1.2 module A where"]
          withCurrentDirectory projDir $ callProcessSilent damlc ["build", "-o", projDir </> "proj.dar", "--target", LF.renderVersion version]
          archive <- Zip.toArchive <$> BSL.readFile (projDir </> "proj.dar")
          DalfManifest {mainDalfPath, dalfPaths} <- either fail pure $ readDalfManifest archive
          Dalfs main other <- either fail pure $ readDalfs archive
          forM_ (zip (mainDalfPath : dalfPaths) (main : other)) $ \(path, bytes) -> do
              Right (_, pkg) <- pure $ LFArchive.decodeArchive LFArchive.DecodeAsMain $ BSL.toStrict bytes
              assertBool ("Expected LF version <=" <> show version <> " but got " <> show (LF.packageLfVersion pkg) <> " in " <> path) $
                  LF.packageLfVersion pkg <= version
    | version <- LF.supportedOutputVersions
    ]

darPackageIds :: FilePath -> IO [LF.PackageId]
darPackageIds fp = do
    archive <- Zip.toArchive <$> BSL.readFile fp
    Dalfs mainDalf dalfDeps <- either fail pure $ readDalfs archive
    Right parsedDalfs <- pure $ mapM (LFArchive.decodeArchive LFArchive.DecodeAsMain . BSL.toStrict) $ mainDalf : dalfDeps
    pure $ map fst parsedDalfs

numStable16Packages :: Int
numStable16Packages = 13

numStable17Packages :: Int
numStable17Packages = 14

dataDependencyTests :: FilePath -> FilePath -> TestTree
dataDependencyTests damlc repl = testGroup "Data Dependencies" $
    [ testCaseSteps "Cross DAML-LF version" $ \step -> withTempDir $ \tmpDir -> do
          let proja = tmpDir </> "proja"
          let projb = tmpDir </> "projb"

          step "Build proja"
          createDirectoryIfMissing True (proja </> "src")
          writeFileUTF8 (proja </> "src" </> "A.daml") $ unlines
              [" daml 1.2"
              , "module A where"
              , "import DA.Text"
              , "data A = A Int deriving Show"
              -- This ensures that we have a reference to daml-stdlib and therefore daml-prim.
              , "x : [Text]"
              , "x = lines \"abc\\ndef\""
              ]
          writeFileUTF8 (proja </> "daml.yaml") $ unlines
              [ "sdk-version: " <> sdkVersion
              , "name: proja"
              , "version: 0.0.1"
              , "source: src"
              , "dependencies: [daml-prim, daml-stdlib]"
              ]
          withCurrentDirectory proja $ callProcessSilent damlc ["build", "--target=1.6", "-o", proja </> "proja.dar"]
          projaPkgIds <- darPackageIds (proja </> "proja.dar")
          -- daml-stdlib, daml-prim and proja
          length projaPkgIds @?= numStable16Packages + 2 + 1

          step "Build projb"
          createDirectoryIfMissing True (projb </> "src")
          writeFileUTF8 (projb </> "src" </> "B.daml") $ unlines
              [ "daml 1.2"
              , "module B where"
              , "import A"
              , "data B = B A"
              ]
          writeFileUTF8 (projb </> "daml.yaml") $ unlines
              [ "sdk-version: " <> sdkVersion
              , "name: projb"
              , "version: 0.0.1"
              , "source: src"
              , "dependencies: [daml-prim, daml-stdlib]"
              , "data-dependencies: [" <> show (proja </> "proja.dar") <> "]"
              ]
          -- TODO Users should not have to pass --hide-all-packages, see https://github.com/digital-asset/daml/issues/4094
          withCurrentDirectory projb $ callProcessSilent damlc
            [ "build", "--target=1.7", "-o", projb </> "projb.dar"
            , "--hide-all-packages"
            , "--package", "(\"daml-prim\", True, [])"
            , "--package", "(\"" <> damlStdlib <> "\", True, [])"
            , "--package", "(\"proja-0.0.1\", True, [])"
            ]
          callProcessSilent repl ["validate", projb </> "projb.dar"]
          projbPkgIds <- darPackageIds (projb </> "projb.dar")
          -- daml-prim, daml-stdlib for 1.6, daml-prim, daml-stdlib for 1.7, proja and projb
          length projbPkgIds @?= numStable17Packages + 2 + 2 + 1 + 1
          -- daml-prim, daml-stdlib for 1.7, 2 stable 1.7 packages and projb
          length (filter (`notElem` projaPkgIds) projbPkgIds) @?= 4
    ] <>
    [ testCaseSteps "Source generation edge cases" $ \step -> withTempDir $ \tmpDir -> do
      writeFileUTF8 (tmpDir </> "Foo.daml") $ unlines
        [ "daml 1.2"
        , "module Foo where"
        , "template Bar"
        , "   with"
        , "     p : Party"
        , "     t : (Text, Int)" -- check for correct tuple type generation
        , "   where"
        , "     signatory p"
        ]
      withCurrentDirectory tmpDir $ do
        step "Compile source to dalf ..."
        callProcessSilent damlc ["compile", "Foo.daml", "-o", "Foo.dalf"]
        step "Regenerate source ..."
        callProcessSilent damlc ["generate-src", "Foo.dalf", "--srcdir=gen"]
        step "Compile generated source ..."
        callProcessSilent
            damlc
            [ "compile"
            , "--generated-src"
            , "gen/Foo.daml"
            , "-o"
            , "FooGen.dalf"
            , "--package=(" <> show damlStdlib <>
              ", False, [(\"DA.Internal.LF\", \"CurrentSdk.DA.Internal.LF\"), (\"DA.Internal.Prelude\", \"Sdk.DA.Internal.Prelude\"), (\"DA.Internal.Template\", \"CurrentSdk.DA.Internal.Template\")])"
            , "--package=(\"daml-prim\", False, [(\"DA.Types\", \"CurrentSdk.DA.Types\"), (\"GHC.Types\", \"CurrentSdk.GHC.Types\")])"
            ]
        assertBool "FooGen.dalf was not created" =<< doesFileExist "FooGen.dalf"
    ] <>
    [ testCase ("Dalf imports (withArchiveChoice=" <> show withArchiveChoice <> ")") $ withTempDir $ \projDir -> do
        let genSimpleDalfExe
              | isWindows = "generate-simple-dalf.exe"
              | otherwise = "generate-simple-dalf"
        genSimpleDalf <-
            locateRunfiles
            (mainWorkspace </> "compiler" </> "damlc" </> "tests" </> genSimpleDalfExe)
        writeFileUTF8 (projDir </> "daml.yaml") $ unlines
          [ "sdk-version: " <> sdkVersion
          , "name: proj"
          , "version: 0.1.0"
          , "source: ."
          , "dependencies: [daml-prim, daml-stdlib]"
          , "data-dependencies: [simple-dalf-0.0.0.dalf]"
          , "build-options:"
          , "- '--package=(\"daml-stdlib-" <> sdkVersion <> "\", True, [])'"
          ]
        writeFileUTF8 (projDir </> "A.daml") $ unlines
            [ "daml 1.2"
            , "module A where"
            , "import DA.Assert"
            , "import qualified \"simple-dalf\" Module"
            , "swapParties : Module.Template -> Module.Template"
            , "swapParties (Module.Template a b) = Module.Template b a"
            , "getThis : Module.Template -> Party"
            , "getThis (Module.Template this _) = this"
            , "getArg : Module.Template -> Party"
            , "getArg (Module.Template _ arg) = arg"
            , "test_methods = scenario do"
            , "  alice <- getParty \"Alice\""
            , "  bob <- getParty \"Bob\""
            , "  let t = Module.Template alice bob"
            , "  getThis (Module.Template alice bob) === alice"
            , "  getArg (Module.Template alice bob) === bob"
            , "  getThis (swapParties (Module.Template alice bob)) === bob"
            , "  getArg (swapParties (Module.Template alice bob)) === alice"
            -- Disabled until we support reusing old type classes
            -- , "  let t = newTemplate alice bob"
            -- , "  assert $ signatory t == [alice, bob]"
            -- , "  assert $ observer t == []"
            -- , "  assert $ ensure t"
            -- , "  assert $ agreement t == \"\""
            -- , "  coid <- submit alice $ createTemplate alice alice"
            -- , "  " <> (if withArchiveChoice then "submit" else "submitMustFail") <> " alice $ archive coid"
            -- , "  coid1 <- submit bob $ createTemplate bob bob"
            -- , "  t1 <- submit bob $ fetch coid1"
            -- , "  assert $ signatory t1 == [bob, bob]"
            -- , "  let anyTemplate = toAnyTemplate t1"
            -- , "  let (Some t2 : Optional Module.Template) = fromAnyTemplate anyTemplate"
            -- , "  submit bob $ exercise coid1 Module.Choice2 with choiceArg = ()"
            -- , "  pure ()"
            ]
        withCurrentDirectory projDir $
            callProcessSilent genSimpleDalf $
            ["--with-archive-choice" | withArchiveChoice ] <> ["simple-dalf-0.0.0.dalf"]
        withCurrentDirectory projDir $ callProcess damlc ["build", "--target=1.dev", "--generated-src"]
        let dar = projDir </> ".daml/dist/proj-0.1.0.dar"
        assertBool "proj-0.1.0.dar was not created." =<< doesFileExist dar
        callProcessSilent damlc ["test", "--target=1.dev", "--project-root", projDir, "--generated-src"]
    | withArchiveChoice <- [False, True]
    ]


-- | Only displays stdout and stderr on errors
callProcessSilent :: FilePath -> [String] -> IO ()
callProcessSilent cmd args = do
    (exitCode, out, err) <- readProcessWithExitCode cmd args ""
    unless (exitCode == ExitSuccess) $ do
      hPutStrLn stderr $ "Failure: Command \"" <> cmd <> " " <> unwords args <> "\" exited with " <> show exitCode
      hPutStrLn stderr $ unlines ["stdout:", out]
      hPutStrLn stderr $ unlines ["stderr: ", err]
      exitFailure

-- | Check that the given file exists in the dar in the given directory.
--
-- This function automatically strips away the root directory e.g.
-- foobar-0.0.1-b2d63d90f3cb73434ae005ee1c9762166bb84563ac9d108a606c8384803f09f2
-- so to check that foobar-0.0.1-b2d63d90f3cb73434ae005ee1c9762166bb84563ac9d108a606c8384803f09f2/A/B.daml
-- exists use checkDarFile darFiles "A" "B.daml"
checkDarFile :: [FilePath] -> FilePath -> FilePath -> IO ()
checkDarFile darFiles dir file =
    assertBool (dir </> file <> " not in " <> show darFiles) $
    any (\f -> normalise (dropDirectory1 f) == normalise (dir </> file)) darFiles
