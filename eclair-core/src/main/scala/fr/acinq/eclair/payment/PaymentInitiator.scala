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

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.{AvailableBalanceChanged, Channel, LocalChannelDown, LocalChannelUpdate}
import fr.acinq.eclair.payment.PaymentLifecycle.{LegacyPayload, SendPayment, SendPaymentToRoute}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.RouteParams
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.eclair.{NodeParams, ShortChannelId}

/**
 * Created by PM on 29/08/2016.
 */
class PaymentInitiator(nodeParams: NodeParams, router: ActorRef, register: ActorRef) extends Actor with ActorLogging {

  import PaymentInitiator._

  var localChannels = Map.empty[ShortChannelId, LocalChannel]

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])
  context.system.eventStream.subscribe(self, classOf[AvailableBalanceChanged])

  override def receive: Receive = {

    // TODO: create an actor that sits in front of PaymentLifecycle and uses it for each sub-payment
    case p: SendPaymentRequest if p.allowMultiPart => ???

    case p: SendPaymentRequest =>
      val paymentId = UUID.randomUUID()
      val payFsm = context.actorOf(PaymentLifecycle.props(nodeParams, paymentId, router, register))
      p.predefinedRoute match {
        case Nil => payFsm forward SendPayment(p.paymentHash, p.targetNodeId, p.maxAttempts, LegacyPayload(p.amountMsat, p.finalCltvExpiry), p.assistedRoutes, p.routeParams)
        case hops => payFsm forward SendPaymentToRoute(p.paymentHash, hops, LegacyPayload(p.amountMsat, p.finalCltvExpiry))
      }
      sender ! paymentId

    case LocalChannelUpdate(_, _, shortChannelId, remoteNodeId, _, channelUpdate, commitments) =>
      val localChannel = localChannels.get(shortChannelId) match {
        case Some(c) => c.copy(localBalanceMsat = commitments.availableBalanceForSendMsat, lastUpdate = channelUpdate)
        case None => LocalChannel(remoteNodeId, commitments.availableBalanceForSendMsat, channelUpdate)
      }
      localChannels = localChannels + (shortChannelId -> localChannel)

    case LocalChannelDown(_, _, shortChannelId, _) => localChannels = localChannels - shortChannelId

    case AvailableBalanceChanged(_, _, shortChannelId, localBalanceMsat, _) => localChannels.get(shortChannelId) match {
      case Some(c) => localChannels = localChannels + (shortChannelId -> c.copy(localBalanceMsat = localBalanceMsat))
      case None =>
    }

  }

}

object PaymentInitiator {

  def props(nodeParams: NodeParams, router: ActorRef, register: ActorRef) = Props(classOf[PaymentInitiator], nodeParams, router, register)

  case class SendPaymentRequest(amountMsat: Long,
                                paymentHash: ByteVector32,
                                targetNodeId: PublicKey,
                                maxAttempts: Int,
                                predefinedRoute: Seq[PublicKey] = Nil,
                                assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                                finalCltvExpiry: Long = Channel.MIN_CLTV_EXPIRY,
                                routeParams: Option[RouteParams] = None,
                                allowMultiPart: Boolean = false,
                                multiPartTotalAmountMsat: Option[Long] = None) // TODO: set properly

  case class LocalChannel(remoteNodeId: PublicKey, localBalanceMsat: Long, lastUpdate: ChannelUpdate)

}
