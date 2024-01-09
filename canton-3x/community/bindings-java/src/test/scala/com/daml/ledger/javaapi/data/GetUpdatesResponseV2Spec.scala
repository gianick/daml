// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates.
// Proprietary code. All rights reserved.

package com.daml.ledger.javaapi.data

import com.daml.ledger.javaapi.data.GeneratorsV2.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class GetUpdatesResponseV2Spec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckDrivenPropertyChecks {

  "GetUpdatesResponse.fromProto" should "convert Protoc-generated instances to data instances" in forAll(
    getUpdatesResponseGen
  ) { updatesResponse =>
    val converted =
      GetUpdatesResponseV2.fromProto(updatesResponse)
    GetUpdatesResponseV2.fromProto(converted.toProto) shouldEqual converted
  }
}
