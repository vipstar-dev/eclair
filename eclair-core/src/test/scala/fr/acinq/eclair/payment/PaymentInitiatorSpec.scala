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

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import fr.acinq.eclair.channel.{AvailableBalanceChanged, LocalChannelDown, LocalChannelUpdate}
import fr.acinq.eclair.payment.HtlcGenerationSpec._
import fr.acinq.eclair.payment.PaymentInitiator.{LocalChannel, SendPaymentRequest}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.{FinalizeRoute, RouteParams, RouteRequest}
import fr.acinq.eclair.{NodeParams, TestConstants, randomBytes32}
import org.scalatest.{Outcome, fixture}

/**
 * Created by t-bast on 24/07/2019.
 */

class PaymentInitiatorSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, initiator: TestActorRef[PaymentInitiator], router: TestProbe, register: TestProbe, sender: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams = TestConstants.Alice.nodeParams
    val router = TestProbe()
    val register = TestProbe()
    val sender = TestProbe()
    val initiator = TestActorRef(new PaymentInitiator(nodeParams, router.ref, register.ref))
    withFixture(test.toNoArgTest(FixtureParam(nodeParams, initiator, router, register, sender)))
  }

  test("forward payment with pre-defined route") { f =>
    import f._
    sender.send(initiator, SendPaymentRequest(finalAmountMsat, paymentHash, c, 1, Seq(a, b, c)))
    sender.expectMsgType[UUID]
    router.expectMsg(FinalizeRoute(Seq(a, b, c)))
  }

  test("forward legacy payment") { f =>
    import f._
    val hints = Seq(Seq(ExtraHop(b, channelUpdate_bc.shortChannelId, feeBaseMsat = 10, feeProportionalMillionths = 1, cltvExpiryDelta = 12)))
    val routeParams = RouteParams(randomize = true, 15, 1.5, 5, 561, None)
    sender.send(initiator, PaymentInitiator.SendPaymentRequest(finalAmountMsat, paymentHash, c, 1, assistedRoutes = hints, finalCltvExpiry = 42, routeParams = Some(routeParams)))
    sender.expectMsgType[UUID]
    router.expectMsg(RouteRequest(TestConstants.Alice.nodeParams.nodeId, c, finalAmountMsat, assistedRoutes = hints, routeParams = Some(routeParams)))

    sender.send(initiator, PaymentInitiator.SendPaymentRequest(finalAmountMsat, paymentHash, e, 3))
    sender.expectMsgType[UUID]
    router.expectMsg(RouteRequest(TestConstants.Alice.nodeParams.nodeId, e, finalAmountMsat))
  }

  test("handle channel events") { f =>
    import f._
    val shortChannelId_ab = channelUpdate_ab.shortChannelId
    val channelUpdate_ab_1 = channelUpdate_ab.copy(timestamp = 41)
    val update_ab_1 = LocalChannelUpdate(TestProbe().ref, randomBytes32, shortChannelId_ab, b, None, channelUpdate_ab_1, makeCommitments(randomBytes32, availableBalanceForSend = 41000L))
    val channelUpdate_ab_2 = channelUpdate_ab.copy(timestamp = 42)
    val update_ab_2 = LocalChannelUpdate(TestProbe().ref, randomBytes32, shortChannelId_ab, b, None, channelUpdate_ab_2, makeCommitments(randomBytes32, availableBalanceForSend = 42000L))

    assert(initiator.underlyingActor.localChannels.isEmpty)
    sender.send(initiator, update_ab_1)
    assert(initiator.underlyingActor.localChannels === Map(shortChannelId_ab -> LocalChannel(b, 41000L, channelUpdate_ab_1)))
    sender.send(initiator, update_ab_2)
    assert(initiator.underlyingActor.localChannels === Map(shortChannelId_ab -> LocalChannel(b, 42000L, channelUpdate_ab_2)))
    sender.send(initiator, AvailableBalanceChanged(TestProbe().ref, randomBytes32, shortChannelId_ab, 40000L, makeCommitments(randomBytes32, availableBalanceForSend = 40000L)))
    assert(initiator.underlyingActor.localChannels === Map(shortChannelId_ab -> LocalChannel(b, 40000L, channelUpdate_ab_2)))
    sender.send(initiator, LocalChannelDown(TestProbe().ref, randomBytes32, shortChannelId_ab, b))
    assert(initiator.underlyingActor.localChannels.isEmpty)
  }

}
