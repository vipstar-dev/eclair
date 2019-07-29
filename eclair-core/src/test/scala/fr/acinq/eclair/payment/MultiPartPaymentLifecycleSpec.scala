/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.payment

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{AvailableBalanceChanged, LocalChannelDown, LocalChannelUpdate}
import fr.acinq.eclair.db.OutgoingPaymentStatus
import fr.acinq.eclair.payment.PaymentInitiator.LocalChannel
import fr.acinq.eclair.payment.PaymentLifecycle.{SendPayment, TlvPayload}
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.eclair.wire.OnionTlv.MultiPartPayment
import org.scalatest.{Outcome, fixture}

import scala.concurrent.duration._

/**
 * Created by t-bast on 18/07/2019.
 */

class MultiPartPaymentLifecycleSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike {

  import MultiPartPaymentLifecycleSpec._

  case class FixtureParam(paymentId: UUID,
                          nodeParams: NodeParams,
                          paymentHandler: TestFSMRef[MultiPartPaymentLifecycle.State, MultiPartPaymentLifecycle.Data, MultiPartPaymentLifecycle],
                          sender: TestProbe,
                          payFsm: TestProbe,
                          paymentIds: TestProbe,
                          eventListener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val id = UUID.randomUUID()
    val nodeParams = TestConstants.Alice.nodeParams
    val payFsm = TestProbe()
    val paymentIds = TestProbe()
    class TestMultiPartPaymentLifecycle extends MultiPartPaymentLifecycle(nodeParams, id, localChannels, TestProbe().ref, TestProbe().ref) {
      override def spawnPaymentFsm(paymentId: UUID): ActorRef = {
        payFsm.send(paymentIds.ref, paymentId)
        payFsm.ref
      }
    }
    val paymentHandler = TestFSMRef(new TestMultiPartPaymentLifecycle().asInstanceOf[MultiPartPaymentLifecycle])
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentLifecycle.PaymentResult])
    withFixture(test.toNoArgTest(FixtureParam(id, nodeParams, paymentHandler, sender, payFsm, paymentIds, eventListener)))
  }

  test("can send directly to peer") { f =>
    import f._
    val payment = SendPayment(paymentHash, b, 1, TlvPayload(15000000, 12, MultiPartPayment(15000000) :: Nil))
    sender.send(paymentHandler, payment)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.paymentHash == paymentHash && p.amountMsat == 15000000 && p.status == OutgoingPaymentStatus.PENDING))

    // The payment should be split in two, using direct channels with b.
    payFsm.expectMsg(SendPayment(paymentHash, b, 1, TlvPayload(10000000, 12, MultiPartPayment(15000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_1) :: Nil))
    payFsm.expectMsg(SendPayment(paymentHash, b, 1, TlvPayload(5000000, 12, MultiPartPayment(15000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_2) :: Nil))
    val payId1 :: payId2 :: Nil = Seq.fill(2)(paymentIds.expectMsgType[UUID])
    payFsm.expectNoMsg(100 millis)

    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId2, 5000000, paymentHash, paymentPreimage, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId1, 10000000, paymentHash, paymentPreimage, Nil))
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.status == OutgoingPaymentStatus.SUCCEEDED && p.preimage === Some(paymentPreimage)))
    sender.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 15000000, paymentHash, paymentPreimage, Nil))
    eventListener.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 15000000, paymentHash, paymentPreimage, Nil))
  }

  test("cannot send directly to peer") { f =>
    import f._
    val payment = SendPayment(paymentHash, d, 3, TlvPayload(12000000, 12, MultiPartPayment(12000000) :: Nil))
    sender.send(paymentHandler, payment)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.paymentHash == paymentHash && p.amountMsat == 12000000 && p.status == OutgoingPaymentStatus.PENDING))

    // The payment should be split in two, using a direct channel with d and an indirect channel.
    payFsm.expectMsg(SendPayment(paymentHash, d, 3, TlvPayload(10000000, 12, MultiPartPayment(12000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, d, channelUpdate_ad_1) :: Nil))
    payFsm.expectMsg(SendPayment(paymentHash, d, 3, TlvPayload(2000000, 12, MultiPartPayment(12000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_1) :: Nil))
    val payId1 :: payId2 :: Nil = Seq.fill(2)(paymentIds.expectMsgType[UUID])
    payFsm.expectNoMsg(100 millis)

    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId1, 10000000, paymentHash, paymentPreimage, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId2, 2000000, paymentHash, paymentPreimage, Nil))
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.status == OutgoingPaymentStatus.SUCCEEDED && p.preimage === Some(paymentPreimage)))
    sender.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 12000000, paymentHash, paymentPreimage, Nil))
    eventListener.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 12000000, paymentHash, paymentPreimage, Nil))
  }

  test("send to remote peer without splitting") { f =>
    import f._
    val payment = SendPayment(paymentHash, e, 3, TlvPayload(4000000, 12, MultiPartPayment(4000000) :: Nil))
    sender.send(paymentHandler, payment)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.paymentHash == paymentHash && p.amountMsat == 4000000 && p.status == OutgoingPaymentStatus.PENDING))

    payFsm.expectMsg(SendPayment(paymentHash, e, 3, TlvPayload(4000000, 12, MultiPartPayment(4000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_1) :: Nil))
    val payId = paymentIds.expectMsgType[UUID]
    payFsm.expectNoMsg(100 millis)

    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId, 4000000, paymentHash, paymentPreimage, Nil))
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.status == OutgoingPaymentStatus.SUCCEEDED && p.preimage === Some(paymentPreimage)))
    sender.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 4000000, paymentHash, paymentPreimage, Nil))
    eventListener.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 4000000, paymentHash, paymentPreimage, Nil))
  }

  test("send to remote peer split through multiple channels") { f =>
    import f._
    val payment = SendPayment(paymentHash, e, 5, TlvPayload(13000000, 12, MultiPartPayment(13000000) :: Nil))
    sender.send(paymentHandler, payment)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.paymentHash == paymentHash && p.amountMsat == 13000000 && p.status == OutgoingPaymentStatus.PENDING))

    payFsm.expectMsg(SendPayment(paymentHash, e, 5, TlvPayload(4850000, 12, MultiPartPayment(13000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_1) :: Nil))
    payFsm.expectMsg(SendPayment(paymentHash, e, 5, TlvPayload(8150000, 12, MultiPartPayment(13000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_1) :: Nil))
    val payId1 :: payId2 :: Nil = Seq.fill(2)(paymentIds.expectMsgType[UUID])
    payFsm.expectNoMsg(100 millis)

    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId1, 4850000, paymentHash, paymentPreimage, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId2, 8150000, paymentHash, paymentPreimage, Nil))
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.status == OutgoingPaymentStatus.SUCCEEDED && p.preimage === Some(paymentPreimage)))
    sender.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 13000000, paymentHash, paymentPreimage, Nil))
    eventListener.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 13000000, paymentHash, paymentPreimage, Nil))
  }

  test("send to remote peer split inside channel if threshold reached") { f =>
    import f._
    val payment = SendPayment(paymentHash, e, 5, TlvPayload(47000000, 144, MultiPartPayment(47000000) :: Nil))
    sender.send(paymentHandler, payment)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.paymentHash == paymentHash && p.amountMsat == 47000000 && p.status == OutgoingPaymentStatus.PENDING))

    payFsm.expectMsgAllOf(
      SendPayment(paymentHash, e, 5, TlvPayload(4850000, 144, MultiPartPayment(47000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_1) :: Nil),
      SendPayment(paymentHash, e, 5, TlvPayload(9700000, 144, MultiPartPayment(47000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_1) :: Nil),
      SendPayment(paymentHash, e, 5, TlvPayload(9700000, 144, MultiPartPayment(47000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_2) :: Nil),
      SendPayment(paymentHash, e, 5, TlvPayload(9700000, 144, MultiPartPayment(47000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, d, channelUpdate_ad_1) :: Nil),
      SendPayment(paymentHash, e, 5, TlvPayload(10000000, 144, MultiPartPayment(47000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_3) :: Nil),
      SendPayment(paymentHash, e, 5, TlvPayload(3050000, 144, MultiPartPayment(47000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_2) :: Nil)
    )
    val payId1 :: payId2 :: payId3 :: payId4 :: payId5 :: payId6 :: Nil = Seq.fill(6)(paymentIds.expectMsgType[UUID])
    payFsm.expectNoMsg(100 millis)

    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId1, 4850000, paymentHash, paymentPreimage, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId2, 9700000, paymentHash, paymentPreimage, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId3, 9700000, paymentHash, paymentPreimage, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId4, 9700000, paymentHash, paymentPreimage, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId5, 10000000, paymentHash, paymentPreimage, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId6, 3050000, paymentHash, paymentPreimage, Nil))
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.status == OutgoingPaymentStatus.SUCCEEDED && p.preimage === Some(paymentPreimage)))
    sender.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 47000000, paymentHash, paymentPreimage, Nil))
    eventListener.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 47000000, paymentHash, paymentPreimage, Nil))
  }

  test("retry after error") { f =>
    import f._
    val payment = SendPayment(paymentHash, e, 5, TlvPayload(13000000, 12, MultiPartPayment(13000000) :: Nil))
    sender.send(paymentHandler, payment)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.paymentHash == paymentHash && p.amountMsat == 13000000 && p.status == OutgoingPaymentStatus.PENDING))

    payFsm.expectMsg(SendPayment(paymentHash, e, 5, TlvPayload(4850000, 12, MultiPartPayment(13000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_1) :: Nil))
    payFsm.expectMsg(SendPayment(paymentHash, e, 5, TlvPayload(8150000, 12, MultiPartPayment(13000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_1) :: Nil))
    val payId1 :: payId2 :: Nil = Seq.fill(2)(paymentIds.expectMsgType[UUID])

    payFsm.send(paymentHandler, PaymentLifecycle.PaymentFailed(payId2, paymentHash, Nil))
    payFsm.expectMsg(SendPayment(paymentHash, e, 5, TlvPayload(1794500, 12, MultiPartPayment(13000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_1) :: Nil))
    payFsm.expectMsg(SendPayment(paymentHash, e, 5, TlvPayload(6355500, 12, MultiPartPayment(13000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_2) :: Nil))
    val payId3 :: payId4 :: Nil = Seq.fill(2)(paymentIds.expectMsgType[UUID])

    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId1, 4850000, paymentHash, paymentPreimage, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId3, 1794500, paymentHash, paymentPreimage, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId4, 6355500, paymentHash, paymentPreimage, Nil))
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.status == OutgoingPaymentStatus.SUCCEEDED && p.preimage === Some(paymentPreimage)))
    sender.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 13000000, paymentHash, paymentPreimage, Nil))
    eventListener.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 13000000, paymentHash, paymentPreimage, Nil))
  }

  test("fail after too many attempts") { f =>
    import f._
    val payment = SendPayment(paymentHash, e, 2, TlvPayload(13000000, 12, MultiPartPayment(13000000) :: Nil))
    sender.send(paymentHandler, payment)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.paymentHash == paymentHash && p.amountMsat == 13000000 && p.status == OutgoingPaymentStatus.PENDING))

    payFsm.expectMsg(SendPayment(paymentHash, e, 2, TlvPayload(4850000, 12, MultiPartPayment(13000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_1) :: Nil))
    payFsm.expectMsg(SendPayment(paymentHash, e, 2, TlvPayload(8150000, 12, MultiPartPayment(13000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_1) :: Nil))
    val payId1 :: payId2 :: Nil = Seq.fill(2)(paymentIds.expectMsgType[UUID])
    payFsm.expectNoMsg(100 millis)

    payFsm.send(paymentHandler, PaymentLifecycle.PaymentFailed(payId1, paymentHash, Nil))
    payFsm.expectMsgType[SendPayment]
    payFsm.expectMsgType[SendPayment]
    val payId3 :: payId4 :: Nil = Seq.fill(2)(paymentIds.expectMsgType[UUID])
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentFailed(payId2, paymentHash, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentFailed(payId3, paymentHash, Nil))
    // The payment should only be failed once all payments have been failed downstream.
    assert(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.status == OutgoingPaymentStatus.PENDING))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentFailed(payId4, paymentHash, Nil))

    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.status == OutgoingPaymentStatus.FAILED))
    assert(sender.expectMsgType[PaymentLifecycle.PaymentFailed].id == paymentId)
    assert(eventListener.expectMsgType[PaymentLifecycle.PaymentFailed].id == paymentId)
  }

  test("cannot send (not enough capacity on local channels)") { f =>
    import f._
    val payment = SendPayment(paymentHash, e, 5, TlvPayload(100000000, 144, MultiPartPayment(100000000) :: Nil))
    sender.send(paymentHandler, payment)

    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.status == OutgoingPaymentStatus.FAILED))
    assert(sender.expectMsgType[PaymentLifecycle.PaymentFailed].id == paymentId)
    assert(eventListener.expectMsgType[PaymentLifecycle.PaymentFailed].id == paymentId)
    payFsm.expectNoMsg(50 millis)
  }

  test("receive partial failure after success (recipient spec violation)") { f =>
    import f._
    val payment = SendPayment(paymentHash, e, 2, TlvPayload(13000000, 12, MultiPartPayment(13000000) :: Nil))
    sender.send(paymentHandler, payment)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.paymentHash == paymentHash && p.amountMsat == 13000000 && p.status == OutgoingPaymentStatus.PENDING))

    payFsm.expectMsg(SendPayment(paymentHash, e, 2, TlvPayload(4850000, 12, MultiPartPayment(13000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_1) :: Nil))
    payFsm.expectMsg(SendPayment(paymentHash, e, 2, TlvPayload(8150000, 12, MultiPartPayment(13000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_1) :: Nil))
    val payId1 :: payId2 :: Nil = Seq.fill(2)(paymentIds.expectMsgType[UUID])
    payFsm.expectNoMsg(100 millis)

    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId1, 4850000, paymentHash, paymentPreimage, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentFailed(payId2, paymentHash, Nil))
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.status == OutgoingPaymentStatus.SUCCEEDED && p.preimage === Some(paymentPreimage)))
    sender.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 13000000, paymentHash, paymentPreimage, Nil))
    eventListener.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 13000000, paymentHash, paymentPreimage, Nil))
  }

  test("receive partial success after complete failure (recipient spec violation)") { f =>
    import f._
    val payment = SendPayment(paymentHash, e, 2, TlvPayload(13000000, 12, MultiPartPayment(13000000) :: Nil))
    sender.send(paymentHandler, payment)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.paymentHash == paymentHash && p.amountMsat == 13000000 && p.status == OutgoingPaymentStatus.PENDING))

    payFsm.expectMsg(SendPayment(paymentHash, e, 2, TlvPayload(4850000, 12, MultiPartPayment(13000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_1) :: Nil))
    payFsm.expectMsg(SendPayment(paymentHash, e, 2, TlvPayload(8150000, 12, MultiPartPayment(13000000) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_1) :: Nil))
    val payId1 :: payId2 :: Nil = Seq.fill(2)(paymentIds.expectMsgType[UUID])
    payFsm.expectNoMsg(100 millis)

    payFsm.send(paymentHandler, PaymentLifecycle.PaymentFailed(payId1, paymentHash, Nil))
    payFsm.send(paymentHandler, PaymentLifecycle.PaymentSucceeded(payId2, 8150000, paymentHash, paymentPreimage, Nil))
    awaitCond(nodeParams.db.payments.getOutgoingPayment(paymentId).exists(p => p.status == OutgoingPaymentStatus.SUCCEEDED && p.preimage === Some(paymentPreimage)))
    sender.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 13000000, paymentHash, paymentPreimage, Nil))
    eventListener.expectMsg(PaymentLifecycle.PaymentSucceeded(paymentId, 13000000, paymentHash, paymentPreimage, Nil))
  }

  test("split payment correctly") { _ =>
    val routerConf = TestConstants.Alice.nodeParams.routerConf.copy(multiPartMinShareMsat = 100, multiPartThresholdMsat = 500, searchMaxFeePct = 0.1, searchMaxFeeBaseSat = 20)
    val nodeParams = TestConstants.Alice.nodeParams.copy(routerConf = routerConf)

    val channels1 = Map(
      channelId_ab_1 -> LocalChannel(b, 50, channelUpdate_ab_1), // skipped (balance too low)
      channelId_ab_2 -> LocalChannel(b, 200, channelUpdate_ab_2), // takes percentage fee into account
      channelId_ac_1 -> LocalChannel(c, 150, channelUpdate_ac_1), // takes flat fee into account
      channelId_ac_2 -> LocalChannel(c, 2000, channelUpdate_ac_2) // split payment multiple times in this channel
    )
    val (remainder1, payments1) = MultiPartPaymentLifecycle.splitPayment(nodeParams, 2500, channels1, SendPayment(paymentHash, e, 3, TlvPayload(2500, 42, MultiPartPayment(3000) :: Nil)))
    assert(remainder1 === 690)
    assert(payments1 === Seq(
      SendPayment(paymentHash, e, 3, TlvPayload(130, 42, MultiPartPayment(3000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_1) :: Nil),
      SendPayment(paymentHash, e, 3, TlvPayload(180, 42, MultiPartPayment(3000) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_2) :: Nil),
      SendPayment(paymentHash, e, 3, TlvPayload(500, 42, MultiPartPayment(3000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_2) :: Nil),
      SendPayment(paymentHash, e, 3, TlvPayload(500, 42, MultiPartPayment(3000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_2) :: Nil),
      SendPayment(paymentHash, e, 3, TlvPayload(500, 42, MultiPartPayment(3000) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_2) :: Nil)
    ))

    val channels2 = Map(
      channelId_ab_1 -> LocalChannel(b, 300, channelUpdate_ab_1), // ignore fees because b is the final recipient
      channelId_ab_2 -> LocalChannel(b, 800, channelUpdate_ab_2), // ignore fees because b is the final recipient (and splits in two payments)
      channelId_ac_1 -> LocalChannel(c, 150, channelUpdate_ac_1), // takes percentage fee into account
      channelId_ac_2 -> LocalChannel(c, 2000, channelUpdate_ac_2),
      channelId_ac_3 -> LocalChannel(c, 2100, channelUpdate_ac_3),
      channelId_ad_1 -> LocalChannel(c, 2500, channelUpdate_ad_1) // ignores because full amount is already paid
    )
    val (remainder2, payments2) = MultiPartPaymentLifecycle.splitPayment(nodeParams, 1500, channels2, SendPayment(paymentHash, b, 1, TlvPayload(1500, 12, MultiPartPayment(1500) :: Nil)))
    assert(remainder2 === 0)
    assert(payments2 === Seq(
      SendPayment(paymentHash, b, 1, TlvPayload(130, 12, MultiPartPayment(1500) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_1) :: Nil),
      SendPayment(paymentHash, b, 1, TlvPayload(300, 12, MultiPartPayment(1500) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_1) :: Nil),
      SendPayment(paymentHash, b, 1, TlvPayload(500, 12, MultiPartPayment(1500) :: Nil), routePrefix = Hop(nodeParams.nodeId, b, channelUpdate_ab_2) :: Nil),
      SendPayment(paymentHash, b, 1, TlvPayload(500, 12, MultiPartPayment(1500) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_2) :: Nil),
      SendPayment(paymentHash, b, 1, TlvPayload(70, 12, MultiPartPayment(1500) :: Nil), routePrefix = Hop(nodeParams.nodeId, c, channelUpdate_ac_3) :: Nil)
    ))
  }

  test("handle channel events") { f =>
    import HtlcGenerationSpec.makeCommitments
    import f._

    sender.send(paymentHandler, AvailableBalanceChanged(TestProbe().ref, randomBytes32, channelId_ab_1, 40000, makeCommitments(randomBytes32, availableBalanceForSend = 40000)))
    assert(paymentHandler.underlyingActor.localChannels(channelId_ab_1) === LocalChannel(b, 40000, channelUpdate_ab_1))

    assert(paymentHandler.underlyingActor.localChannels.get(channelId_ab_2).isDefined)
    sender.send(paymentHandler, LocalChannelDown(TestProbe().ref, randomBytes32, channelId_ab_2, b))
    assert(paymentHandler.underlyingActor.localChannels.get(channelId_ab_2) === None)

    val channelUpdate_ac_1_2 = channelUpdate_ac_1.copy(timestamp = 42)
    sender.send(paymentHandler, LocalChannelUpdate(TestProbe().ref, randomBytes32, channelId_ac_1, c, None, channelUpdate_ac_1_2, makeCommitments(randomBytes32, availableBalanceForSend = 40000)))
    assert(paymentHandler.underlyingActor.localChannels(channelId_ac_1) === LocalChannel(c, 40000, channelUpdate_ac_1_2))
  }

}

object MultiPartPaymentLifecycleSpec {

  val paymentPreimage = randomBytes32
  val paymentHash = Crypto.sha256(paymentPreimage)

  /**
   * We simulate a multi-part-friendly network:
   * .-----> b -------.
   * |                |
   * a ----> c -----> e
   * |                |
   * '-----> d -------'
   * where a has multiple channels with each of his peers.
   */

  val a :: b :: c :: d :: e :: Nil = Seq.fill(5)(PrivateKey(randomBytes32).publicKey)
  val channelId_ab_1 = ShortChannelId(1)
  val channelId_ab_2 = ShortChannelId(2)
  val channelId_ac_1 = ShortChannelId(11)
  val channelId_ac_2 = ShortChannelId(12)
  val channelId_ac_3 = ShortChannelId(13)
  val channelId_ad_1 = ShortChannelId(21)
  val defaultChannelUpdate = ChannelUpdate(randomBytes64, Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0, 1, 0, 0, 1000, 0, 0, Some(100000000L))
  val channelUpdate_ab_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ab_1, cltvExpiryDelta = 4, feeBaseMsat = 1000, feeProportionalMillionths = 7)
  val channelUpdate_ab_2 = defaultChannelUpdate.copy(shortChannelId = channelId_ab_2, cltvExpiryDelta = 4, feeBaseMsat = 1000, feeProportionalMillionths = 7)
  val channelUpdate_ac_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_1, cltvExpiryDelta = 5, feeBaseMsat = 1500, feeProportionalMillionths = 4)
  val channelUpdate_ac_2 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_2, cltvExpiryDelta = 5, feeBaseMsat = 1500, feeProportionalMillionths = 4)
  val channelUpdate_ac_3 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_3, cltvExpiryDelta = 5, feeBaseMsat = 1500, feeProportionalMillionths = 4)
  val channelUpdate_ad_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ad_1, cltvExpiryDelta = 6, feeBaseMsat = 2000, feeProportionalMillionths = 7)
  val localChannels = Map(
    channelId_ab_1 -> LocalChannel(b, 10000 * 1000, channelUpdate_ab_1),
    channelId_ab_2 -> LocalChannel(b, 15000 * 1000, channelUpdate_ab_2),
    channelId_ac_1 -> LocalChannel(c, 5000 * 1000, channelUpdate_ac_1),
    channelId_ac_2 -> LocalChannel(c, 10000 * 1000, channelUpdate_ac_2),
    channelId_ac_3 -> LocalChannel(c, 15000 * 1000, channelUpdate_ac_3),
    channelId_ad_1 -> LocalChannel(d, 10000 * 1000, channelUpdate_ad_1)
  )

}