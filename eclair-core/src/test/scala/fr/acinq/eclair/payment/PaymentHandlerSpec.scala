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

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, Crypto, MilliSatoshi}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.payment.PaymentLifecycle.{DecryptedHtlc, ReceivePayment}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, ShortChannelId, TestConstants, randomKey}
import org.scalatest.FunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
  * Created by PM on 24/03/2017.
  */

class PaymentHandlerSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  test("LocalPaymentHandler should reply with a fulfill/fail, emit a PaymentReceived and adds payment in DB") {
    val nodeParams = Alice.nodeParams
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    val amountMsat = MilliSatoshi(42000)
    val expiry = Globals.blockCount.get() + 12

    {
      sender.send(handler, ReceivePayment(Some(amountMsat), "1 coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).isEmpty)
      assert(nodeParams.db.payments.getPendingPaymentRequestAndPreimage(pr.paymentHash).isDefined)
      assert(!nodeParams.db.payments.getPendingPaymentRequestAndPreimage(pr.paymentHash).get._2.isExpired)

      val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, amountMsat.amount, pr.paymentHash, expiry, TestConstants.emptyOnionPacket)
      sender.send(handler, DecryptedHtlc(add, OnionForwardInfo(ShortChannelId(1), add.amountMsat, add.cltvExpiry)))
      sender.expectMsgType[CMD_FULFILL_HTLC]

      val paymentRelayed = eventListener.expectMsgType[PaymentReceived]
      assert(paymentRelayed.copy(timestamp = 0) === PaymentReceived(amountMsat, add.paymentHash, timestamp = 0))
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.paymentHash == pr.paymentHash))
    }

    {
      sender.send(handler, ReceivePayment(Some(amountMsat), "another coffee with multi-part", allowMultiPart = true))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(pr.features.allowMultiPart)
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).isEmpty)

      val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, amountMsat.amount, pr.paymentHash, expiry, TestConstants.emptyOnionPacket)
      sender.send(handler, DecryptedHtlc(add, OnionForwardInfo(ShortChannelId(1), add.amountMsat, add.cltvExpiry)))
      sender.expectMsgType[CMD_FULFILL_HTLC]
      val paymentRelayed = eventListener.expectMsgType[PaymentReceived]
      assert(paymentRelayed.copy(timestamp = 0) === PaymentReceived(amountMsat, add.paymentHash, timestamp = 0))
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.paymentHash == pr.paymentHash))
    }

    {
      sender.send(handler, ReceivePayment(Some(amountMsat), "bad expiry"))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).isEmpty)

      val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, amountMsat.amount, pr.paymentHash, cltvExpiry = Globals.blockCount.get() + 3, TestConstants.emptyOnionPacket)
      sender.send(handler, DecryptedHtlc(add, OnionForwardInfo(ShortChannelId(1), add.amountMsat, add.cltvExpiry)))
      assert(sender.expectMsgType[CMD_FAIL_HTLC].reason == Right(FinalExpiryTooSoon))
      eventListener.expectNoMsg(300 milliseconds)
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).isEmpty)
    }
  }

  test("Payment request generation should fail when the amount is not valid") {
    val nodeParams = Alice.nodeParams
    val handler = system.actorOf(LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    // negative amount should fail
    sender.send(handler, ReceivePayment(Some(MilliSatoshi(-50)), "1 coffee"))
    val negativeError = sender.expectMsgType[Failure]
    assert(negativeError.cause.getMessage.contains("amount is not valid"))

    // amount = 0 should fail
    sender.send(handler, ReceivePayment(Some(MilliSatoshi(0)), "1 coffee"))
    val zeroError = sender.expectMsgType[Failure]
    assert(zeroError.cause.getMessage.contains("amount is not valid"))

    // success with 1 mBTC
    sender.send(handler, ReceivePayment(Some(MilliSatoshi(100000000L)), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.amount.contains(MilliSatoshi(100000000L)) && pr.nodeId.toString == nodeParams.nodeId.toString)
  }

  test("Payment request generation should succeed when the amount is not set") {
    val handler = system.actorOf(LocalPaymentHandler.props(Alice.nodeParams))
    val sender = TestProbe()

    sender.send(handler, ReceivePayment(None, "This is a donation PR"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.amount.isEmpty && pr.nodeId.toString == Alice.nodeParams.nodeId.toString)
  }

  test("Payment request generation should handle custom expiries or use the default otherwise") {
    val handler = system.actorOf(LocalPaymentHandler.props(Alice.nodeParams))
    val sender = TestProbe()

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(42000)), "1 coffee"))
    assert(sender.expectMsgType[PaymentRequest].expiry === Some(Alice.nodeParams.paymentRequestExpiry.toSeconds))

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(42000)), "1 coffee with custom expiry", expirySeconds_opt = Some(60)))
    assert(sender.expectMsgType[PaymentRequest].expiry === Some(60))
  }

  test("Generated payment request contains the provided extra hops") {
    val handler = system.actorOf(LocalPaymentHandler.props(Alice.nodeParams))
    val sender = TestProbe()

    val x = randomKey.publicKey
    val y = randomKey.publicKey
    val extraHop_x_y = ExtraHop(x, ShortChannelId(1), 10, 11, 12)
    val extraHop_y_z = ExtraHop(y, ShortChannelId(2), 20, 21, 22)
    val extraHop_x_t = ExtraHop(x, ShortChannelId(3), 30, 31, 32)
    val route_x_z = extraHop_x_y :: extraHop_y_z :: Nil
    val route_x_t = extraHop_x_t :: Nil

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(42000)), "1 coffee with additional routing info", extraHops = List(route_x_z, route_x_t)))
    assert(sender.expectMsgType[PaymentRequest].routingInfo === Seq(route_x_z, route_x_t))

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(42000)), "1 coffee without routing info"))
    assert(sender.expectMsgType[PaymentRequest].routingInfo === Nil)
  }

  test("LocalPaymentHandler should reject incoming payments if the payment request is expired") {
    val nodeParams = Alice.nodeParams
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    val amountMsat = MilliSatoshi(42000)
    val expiry = Globals.blockCount.get() + 12

    sender.send(handler, ReceivePayment(Some(amountMsat), "some desc", expirySeconds_opt = Some(0)))
    val pr = sender.expectMsgType[PaymentRequest]

    val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, amountMsat.amount, pr.paymentHash, expiry, TestConstants.emptyOnionPacket)
    sender.send(handler, DecryptedHtlc(add, OnionForwardInfo(ShortChannelId(1), add.amountMsat, add.cltvExpiry)))

    sender.expectMsgType[CMD_FAIL_HTLC]
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).isEmpty)
  }

  test("LocalPaymentHandler should reject incoming multi-part payment if the payment request does not allow it") {
    val nodeParams = Alice.nodeParams
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(1000)), "no multi-part support"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(!pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, 800, pr.paymentHash, Globals.blockCount.get() + 12, TestConstants.emptyOnionPacket)
    sender.send(handler, DecryptedHtlc(add, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(1000))))
    assert(sender.expectMsgType[CMD_FAIL_HTLC].reason === Right(IncorrectOrUnknownPaymentDetails(1000)))
  }

  test("LocalPaymentHandler should reject incoming multi-part payment if the payment request is expired") {
    val nodeParams = Alice.nodeParams
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(1000)), "multi-part expired", expirySeconds_opt = Some(0), allowMultiPart = true))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, 800, pr.paymentHash, Globals.blockCount.get() + 12, TestConstants.emptyOnionPacket)
    sender.send(handler, DecryptedHtlc(add, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(1000))))
    assert(sender.expectMsgType[CMD_FAIL_HTLC].reason === Right(IncorrectOrUnknownPaymentDetails(1000)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).isEmpty)
  }

  test("LocalPaymentHandler should reject incoming multi-part payment with an invalid expiry") {
    val nodeParams = Alice.nodeParams
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(1000)), "multi-part invalid expiry", allowMultiPart = true))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, 800, pr.paymentHash, Globals.blockCount.get() + 1, TestConstants.emptyOnionPacket)
    sender.send(handler, DecryptedHtlc(add, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(1000))))
    assert(sender.expectMsgType[CMD_FAIL_HTLC].reason === Right(FinalExpiryTooSoon))
  }

  test("LocalPaymentHandler should reject incoming multi-part payment with an unknown payment hash") {
    val nodeParams = Alice.nodeParams
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(1000)), "multi-part unknown payment hash", allowMultiPart = true))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, 800, pr.paymentHash.reverse, Globals.blockCount.get() + 12, TestConstants.emptyOnionPacket)
    sender.send(handler, DecryptedHtlc(add, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(1000))))
    assert(sender.expectMsgType[CMD_FAIL_HTLC].reason === Right(IncorrectOrUnknownPaymentDetails(1000)))
  }

  test("LocalPaymentHandler should reject incoming multi-part payment with a total amount too low") {
    val nodeParams = Alice.nodeParams
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(1000)), "multi-part total amount too low", allowMultiPart = true))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, 800, pr.paymentHash, Globals.blockCount.get() + 12, TestConstants.emptyOnionPacket)
    sender.send(handler, DecryptedHtlc(add, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(999))))
    assert(sender.expectMsgType[CMD_FAIL_HTLC].reason === Right(IncorrectOrUnknownPaymentDetails(999)))
  }

  test("LocalPaymentHandler should reject incoming multi-part payment with a total amount too high") {
    val nodeParams = Alice.nodeParams
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(1000)), "multi-part total amount too low", allowMultiPart = true))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, 800, pr.paymentHash, Globals.blockCount.get() + 12, TestConstants.emptyOnionPacket)
    sender.send(handler, DecryptedHtlc(add, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(2001))))
    assert(sender.expectMsgType[CMD_FAIL_HTLC].reason === Right(IncorrectOrUnknownPaymentDetails(2001)))
  }

  test("LocalPaymentHandler should handle multi-part payment timeout") {
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 50 millis)
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender1 = TestProbe()
    val sender2 = TestProbe()

    sender1.send(handler, ReceivePayment(Some(MilliSatoshi(1000)), "1 slow coffee", allowMultiPart = true))
    val pr1 = sender1.expectMsgType[PaymentRequest]
    val add1 = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, 800, pr1.paymentHash, Globals.blockCount.get() + 12, TestConstants.emptyOnionPacket)
    sender1.send(handler, DecryptedHtlc(add1, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(1000))))

    sender2.send(handler, ReceivePayment(Some(MilliSatoshi(1500)), "1 slow latte", allowMultiPart = true))
    val pr2 = sender2.expectMsgType[PaymentRequest]
    val add2 = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 1, 1000, pr2.paymentHash, Globals.blockCount.get() + 12, TestConstants.emptyOnionPacket)
    sender2.send(handler, DecryptedHtlc(add2, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(1500))))

    sender1.expectMsg(CMD_FAIL_HTLC(0, Right(IncorrectOrUnknownPaymentDetails(800)), commit = true))
    sender2.expectMsg(CMD_FAIL_HTLC(1, Right(IncorrectOrUnknownPaymentDetails(1000)), commit = true))
  }

  test("LocalPaymentHandler should handle multi-part payment success") {
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 500 millis)
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender1 = TestProbe()
    val sender2 = TestProbe()

    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    sender1.send(handler, ReceivePayment(Some(MilliSatoshi(1000)), "1 fast coffee", allowMultiPart = true))
    val pr = sender1.expectMsgType[PaymentRequest]

    val add1 = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, 800, pr.paymentHash, Globals.blockCount.get() + 12, TestConstants.emptyOnionPacket)
    sender1.send(handler, DecryptedHtlc(add1, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(1000))))
    val add2 = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(2)), 42, 200, pr.paymentHash, Globals.blockCount.get() + 16, TestConstants.emptyOnionPacket)
    sender2.send(handler, DecryptedHtlc(add2, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(1000))))

    val fulfill1 = sender1.expectMsgType[CMD_FULFILL_HTLC]
    assert(fulfill1.id === 0)
    assert(Crypto.sha256(fulfill1.r) === pr.paymentHash)
    val fulfill2 = sender2.expectMsgType[CMD_FULFILL_HTLC]
    assert(fulfill2.id === 42)
    assert(Crypto.sha256(fulfill2.r) === pr.paymentHash)

    val paymentRelayed = eventListener.expectMsgType[PaymentReceived]
    assert(paymentRelayed.copy(timestamp = 0) === PaymentReceived(MilliSatoshi(1000), pr.paymentHash, timestamp = 0))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.paymentHash == pr.paymentHash))
  }

  test("LocalPaymentHandler should handle multi-part payment timeout then success") {
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 100 millis)
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()

    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(1000)), "1 coffee, no sugar", allowMultiPart = true))
    val pr = sender.expectMsgType[PaymentRequest]

    val add1 = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, 800, pr.paymentHash, Globals.blockCount.get() + 12, TestConstants.emptyOnionPacket)
    sender.send(handler, DecryptedHtlc(add1, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(1000))))
    sender.expectMsg(CMD_FAIL_HTLC(0, Right(IncorrectOrUnknownPaymentDetails(800)), commit = true))

    val add2 = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(2)), 2, 300, pr.paymentHash, Globals.blockCount.get() + 12, TestConstants.emptyOnionPacket)
    sender.send(handler, DecryptedHtlc(add2, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(1000))))
    val add3 = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 5, 700, pr.paymentHash, Globals.blockCount.get() + 12, TestConstants.emptyOnionPacket)
    sender.send(handler, DecryptedHtlc(add3, TlvStream[OnionTlv](OnionTlv.MultiPartPayment(1000))))

    val fulfill1 = sender.expectMsgType[CMD_FULFILL_HTLC]
    assert(fulfill1.id === 2)
    assert(Crypto.sha256(fulfill1.r) === pr.paymentHash)
    val fulfill2 = sender.expectMsgType[CMD_FULFILL_HTLC]
    assert(fulfill2.id === 5)
    assert(Crypto.sha256(fulfill2.r) === pr.paymentHash)

    val paymentRelayed = eventListener.expectMsgType[PaymentReceived]
    assert(paymentRelayed.copy(timestamp = 0) === PaymentReceived(MilliSatoshi(1000), pr.paymentHash, timestamp = 0))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.paymentHash == pr.paymentHash))
  }

}
