// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates.
// Proprietary code. All rights reserved.

package com.digitalasset.canton.domain.sequencing.sequencer.reference.store

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.domain.block.BlockOrderer
import com.digitalasset.canton.domain.sequencing.sequencer.reference.store.ReferenceSequencerDriverStore.{
  sequencedAcknowledgement,
  sequencedRegisterMember,
  sequencedSend,
}
import com.digitalasset.canton.tracing.{TraceContext, Traced, W3CTraceContext}
import com.google.protobuf.ByteString
import org.scalatest.wordspec.AsyncWordSpec

trait ReferenceBlockOrderingStoreTest extends AsyncWordSpec with BaseTest {

  private val event1 =
    sequencedSend(payload = ByteString.copyFromUtf8("payload"), microsecondsSinceEpoch = 0)
  private val event2 =
    sequencedRegisterMember(payload = ByteString.copyFromUtf8("member"), microsecondsSinceEpoch = 1)
  private val event3 =
    sequencedAcknowledgement(
      payload = ByteString.copyFromUtf8("acknowledge"),
      microsecondsSinceEpoch = 1,
    )

  val traceContext1: TraceContext =
    W3CTraceContext("00-5c14649f3f82e93b658a1f34e5f6aece-93bb0fa23a8fb53a-01").toTraceContext
  val traceContext2: TraceContext =
    W3CTraceContext("00-16e9c9f2d11d1a45d13083d423bc7e45-6a5ad6c4f4f2041b-01").toTraceContext
  val traceContext3: TraceContext =
    W3CTraceContext("00-7a5e6a5c6f8d8646adce33d4f0b1c3b1-8546d5a6a5f6c5b6-01").toTraceContext

  def b(
      height: Long,
      tracedEvent: Traced[BlockOrderer.OrderedRequest],
  ): BlockOrderer.Block =
    BlockOrderer.Block(
      height,
      Seq(tracedEvent),
    )

  def referenceBlockOrderingStore(mk: () => ReferenceBlockOrderingStore): Unit = {

    "count blocks" should {
      "increment counter when inserting block" in {
        val sut = mk()
        for {
          count <- sut.countBlocks()
          _ = count shouldBe 0
          _ <- sut.insertRequest(event1)
          count2 <- sut.countBlocks()
        } yield count2 shouldBe 1
      }
    }
    "queryBlocks" should {
      "get all when initial height is 0 or below" in {
        val sut = mk()
        for {
          _ <- sut.insertRequest(event1)(traceContext1)
          _ <- sut.insertRequest(event2)(traceContext2)
          result0 <- sut.queryBlocks(0)
          result1 <- sut.queryBlocks(-1)
        } yield {
          result0 should contain theSameElementsInOrderAs Seq(
            b(0L, Traced(event1)(traceContext1)),
            b(1L, Traced(event2)(traceContext2)),
          )
          result1 shouldBe empty
        }
      }

      "get from height to the end" in {
        val sut = mk()
        for {
          _ <- sut.insertRequest(event1)(traceContext1)
          _ <- sut.insertRequest(event2)(traceContext2)
          _ <- sut.insertRequest(event3)(traceContext3)
          result0 <- sut.queryBlocks(1)
          result1 <- sut.queryBlocks(2)
        } yield {
          result0 should contain theSameElementsInOrderAs Seq(
            b(1L, Traced(event2)(traceContext2)),
            b(2L, Traced(event3)(traceContext3)),
          )
          result1 should contain only b(2L, Traced(event3)(traceContext3))
        }
      }
    }

    // TODO(#14736): remove this code once we dont need insertRequestWithHeight
    "set height" should {
      "get from height to the end" in {
        val sut = mk()
        for {
          _ <- sut.insertRequestWithHeight(0L, event1)(traceContext1)
          _ <- sut.insertRequestWithHeight(1L, event2)(traceContext2)
          _ <- sut.insertRequestWithHeight(2L, event3)(traceContext3)
          result0 <- sut.queryBlocks(1)
          result1 <- sut.queryBlocks(2)
        } yield {
          result0 should contain theSameElementsInOrderAs Seq(
            b(1L, Traced(event2)(traceContext2)),
            b(2L, Traced(event3)(traceContext3)),
          )
          result1 should contain only b(2L, Traced(event3)(traceContext3))
        }
      }
      "support gaps" in {
        val sut = mk()
        for {
          _ <- sut.insertRequestWithHeight(0L, event1)(traceContext1)
          _ <- sut.insertRequestWithHeight(2L, event3)(traceContext3)
          result0 <- sut.queryBlocks(0)
          _ = result0 should contain theSameElementsInOrderAs Seq(
            b(0L, Traced(event1)(traceContext1))
          )
          _ <- sut.insertRequestWithHeight(1L, event2)(traceContext2)

          result1 <- sut.queryBlocks(0)
        } yield {
          result1 should contain theSameElementsInOrderAs Seq(
            b(0L, Traced(event1)(traceContext1)),
            b(1L, Traced(event2)(traceContext2)),
            b(2L, Traced(event3)(traceContext3)),
          )
        }
      }
      "be idempotent" in {
        val sut = mk()
        for {
          _ <- sut.insertRequestWithHeight(0L, event1)(traceContext1)
          _ <- sut.insertRequestWithHeight(1L, event2)(traceContext2)
          _ <- sut.insertRequestWithHeight(1L, event2)(traceContext2)
          result0 <- sut.queryBlocks(0)
        } yield {
          result0 should contain theSameElementsInOrderAs Seq(
            b(0L, Traced(event1)(traceContext1)),
            b(1L, Traced(event2)(traceContext2)),
          )
        }
      }
    }
  }
}

class InMemoryReferenceBlockOrderingStoreTest extends ReferenceBlockOrderingStoreTest {
  "InMemoryReferenceBlockOrderingStore" should {
    behave like referenceBlockOrderingStore(() => new InMemoryReferenceSequencerDriverStore())
  }
}
