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
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, Crypto, MilliSatoshi}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.wire.{IncorrectOrUnknownPaymentDetails, UpdateAddHtlc}
import fr.acinq.eclair.{TestConstants, randomBytes32}
import org.scalatest.FunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
  * Created by t-bast on 18/07/2019.
  */

class MultiPartPaymentHandlerSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  import MultiPartPaymentHandler._
  import MultiPartPaymentHandlerSpec._

  test("timeout waiting for first htlc") {
    val parent = TestProbe()
    val handler = TestActorRef[MultiPartPaymentHandler](props(paymentPreimage, 25 millis, parent.ref))
    val monitor = TestProbe()

    handler ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_FIRST_HTLC) = monitor.expectMsgClass(classOf[CurrentState[_]])
    val Transition(_, WAITING_FOR_FIRST_HTLC, PAYMENT_FAILED) = monitor.expectMsgClass(classOf[Transition[_]])

    parent.expectMsg(MultiPartHtlcFailed(paymentPreimage))
  }

  test("timeout waiting for more htlcs") {
    val parent = TestProbe()
    val handler = TestActorRef[MultiPartPaymentHandler](props(paymentPreimage, 50 millis, parent.ref))
    val sender1 = TestProbe()
    val sender2 = TestProbe()

    sender1.send(handler, createMultiPartHtlc(1000, 150, 1))
    sender2.send(handler, createMultiPartHtlc(1000, 100, 2))

    sender1.expectMsg(CMD_FAIL_HTLC(1, Right(IncorrectOrUnknownPaymentDetails(250)), commit = true))
    sender2.expectMsg(CMD_FAIL_HTLC(2, Right(IncorrectOrUnknownPaymentDetails(250)), commit = true))
    parent.expectMsg(MultiPartHtlcFailed(paymentPreimage))
  }

  test("fail additional htlcs after timeout") {
    val parent = TestProbe()
    val handler = TestActorRef[MultiPartPaymentHandler](props(paymentPreimage, 50 millis, parent.ref))
    val sender = TestProbe()

    sender.send(handler, createMultiPartHtlc(1000, 150, 1))
    sender.send(handler, createMultiPartHtlc(1000, 100, 2))
    sender.expectMsg(CMD_FAIL_HTLC(1, Right(IncorrectOrUnknownPaymentDetails(250)), commit = true))
    sender.expectMsg(CMD_FAIL_HTLC(2, Right(IncorrectOrUnknownPaymentDetails(250)), commit = true))
    parent.expectMsg(MultiPartHtlcFailed(paymentPreimage))

    sender.send(handler, createMultiPartHtlc(1000, 300, 3))
    sender.expectMsg(CMD_FAIL_HTLC(3, Right(IncorrectOrUnknownPaymentDetails(250)), commit = true))
    parent.expectNoMsg(25 millis)
  }

  test("fail all if total amount is not consistent") {
    val parent = TestProbe()
    val handler = TestActorRef[MultiPartPaymentHandler](props(paymentPreimage, 50 millis, parent.ref))
    val sender = TestProbe()

    sender.send(handler, createMultiPartHtlc(1000, 600, 1))
    sender.send(handler, createMultiPartHtlc(1100, 650, 2))

    sender.expectMsg(CMD_FAIL_HTLC(1, Right(IncorrectOrUnknownPaymentDetails(600)), commit = true))
    sender.expectMsg(CMD_FAIL_HTLC(2, Right(IncorrectOrUnknownPaymentDetails(600)), commit = true))
    parent.expectMsg(MultiPartHtlcFailed(paymentPreimage))
  }

  test("fulfill all when total amount reached") {
    val parent = TestProbe()
    val handler = TestActorRef[MultiPartPaymentHandler](props(paymentPreimage, 10 seconds, parent.ref))
    val sender1 = TestProbe()
    val sender2 = TestProbe()

    sender1.send(handler, createMultiPartHtlc(1000, 300, 1))
    sender2.send(handler, createMultiPartHtlc(1000, 340, 2))
    sender1.send(handler, createMultiPartHtlc(1000, 360, 3))

    sender1.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    sender2.expectMsg(CMD_FULFILL_HTLC(2, paymentPreimage, commit = true))
    sender1.expectMsg(CMD_FULFILL_HTLC(3, paymentPreimage, commit = true))
    parent.expectMsg(MultiPartHtlcSucceeded(paymentPreimage, MilliSatoshi(1000)))
  }

  test("fulfill all with amount higher than total amount") {
    val parent = TestProbe()
    val handler = TestActorRef[MultiPartPaymentHandler](props(paymentPreimage, 10 seconds, parent.ref))
    val sender = TestProbe()

    sender.send(handler, createMultiPartHtlc(1000, 300, 1))
    sender.send(handler, createMultiPartHtlc(1000, 340, 2))
    sender.send(handler, createMultiPartHtlc(1000, 400, 3))

    sender.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    sender.expectMsg(CMD_FULFILL_HTLC(2, paymentPreimage, commit = true))
    sender.expectMsg(CMD_FULFILL_HTLC(3, paymentPreimage, commit = true))
    parent.expectMsg(MultiPartHtlcSucceeded(paymentPreimage, MilliSatoshi(1040)))
  }

  test("fulfill all with single htlc") {
    val parent = TestProbe()
    val handler = TestActorRef[MultiPartPaymentHandler](props(paymentPreimage, 10 seconds, parent.ref))
    val sender = TestProbe()

    sender.send(handler, createMultiPartHtlc(1000, 1000, 1))
    sender.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    parent.expectMsg(MultiPartHtlcSucceeded(paymentPreimage, MilliSatoshi(1000)))
  }

  test("fulfill all with single htlc and amount higher than total amount") {
    val parent = TestProbe()
    val handler = TestActorRef[MultiPartPaymentHandler](props(paymentPreimage, 10 seconds, parent.ref))
    val sender = TestProbe()

    sender.send(handler, createMultiPartHtlc(1000, 1100, 1))
    sender.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    parent.expectMsg(MultiPartHtlcSucceeded(paymentPreimage, MilliSatoshi(1100)))
  }

  test("fulfill additional htlcs after total amount reached") {
    val parent = TestProbe()
    val handler = TestActorRef[MultiPartPaymentHandler](props(paymentPreimage, 10 seconds, parent.ref))
    val sender = TestProbe()

    sender.send(handler, createMultiPartHtlc(1000, 600, 1))
    sender.send(handler, createMultiPartHtlc(1000, 400, 2))
    sender.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    sender.expectMsg(CMD_FULFILL_HTLC(2, paymentPreimage, commit = true))
    parent.expectMsg(MultiPartHtlcSucceeded(paymentPreimage, MilliSatoshi(1000)))

    sender.send(handler, createMultiPartHtlc(1000, 300, 3))
    sender.expectMsg(CMD_FULFILL_HTLC(3, paymentPreimage, commit = true))
    parent.expectNoMsg(25 millis)
  }

  test("actor restart") {
    val parent = TestProbe()
    val handler = TestActorRef[MultiPartPaymentHandler](props(paymentPreimage, 10 seconds, parent.ref))
    val sender = TestProbe()

    sender.send(handler, createMultiPartHtlc(1000, 600, 1))

    handler.suspend()
    handler.resume(new IllegalArgumentException("something went wrong"))

    sender.send(handler, createMultiPartHtlc(1000, 400, 2))
    sender.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    sender.expectMsg(CMD_FULFILL_HTLC(2, paymentPreimage, commit = true))
    parent.expectMsg(MultiPartHtlcSucceeded(paymentPreimage, MilliSatoshi(1000)))
  }

  test("unknown message") {
    val parent = TestProbe()
    val handler = TestActorRef[MultiPartPaymentHandler](props(paymentPreimage, 10 seconds, parent.ref))
    val sender = TestProbe()

    sender.send(handler, createMultiPartHtlc(1000, 600, 1))
    sender.send(handler, "hello")
    sender.send(handler, createMultiPartHtlc(1000, 400, 2))

    sender.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    sender.expectMsg(CMD_FULFILL_HTLC(2, paymentPreimage, commit = true))
    parent.expectMsg(MultiPartHtlcSucceeded(paymentPreimage, MilliSatoshi(1000)))
  }

}

object MultiPartPaymentHandlerSpec {

  import MultiPartPaymentHandler._

  val paymentPreimage = randomBytes32
  val paymentHash = Crypto.sha256(paymentPreimage)

  def htlcIdToChannelId(htlcId: Long) = ByteVector32(ByteVector.fromLong(htlcId).padLeft(32))

  def createMultiPartHtlc(totalAmountMsat: Long, htlcAmountMsat: Long, htlcId: Long) =
    MultiPartHtlc(totalAmountMsat, UpdateAddHtlc(htlcIdToChannelId(htlcId), htlcId, htlcAmountMsat, paymentHash, 144, TestConstants.emptyOnionPacket))
}