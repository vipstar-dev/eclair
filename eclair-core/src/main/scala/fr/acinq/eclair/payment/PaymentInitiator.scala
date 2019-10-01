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
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.payment.PaymentLifecycle.{DefaultPaymentProgressHandler, SendPayment, SendPaymentToRoute}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.RouteParams
import fr.acinq.eclair.wire.Onion
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshi, NodeParams}

/**
 * Created by PM on 29/08/2016.
 */
class PaymentInitiator(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef, register: ActorRef) extends Actor with ActorLogging {

  import PaymentInitiator._

  override def receive: Receive = {
    case r: SendPaymentRequest =>
      val paymentId = UUID.randomUUID()
      val finalExpiry = r.finalExpiry(nodeParams.currentBlockHeight)
      r.paymentRequest match {
        case Some(invoice) if invoice.features.allowMultiPart =>
          r.predefinedRoute match {
            case Nil => spawnMultiPartPaymentFsm(paymentId) forward r
            case hops => spawnPaymentFsm(paymentId, r) forward SendPaymentToRoute(r.paymentHash, hops, Onion.createMultiPartPayload(r.amount, invoice.amount.getOrElse(r.amount), finalExpiry, invoice.paymentSecret.get))
          }
        case _ =>
          val payFsm = spawnPaymentFsm(paymentId, r)
          // NB: we only generate legacy payment onions for now for maximum compatibility.
          r.predefinedRoute match {
            case Nil => payFsm forward SendPayment(r.paymentHash, r.targetNodeId, FinalLegacyPayload(r.amount, finalExpiry), r.maxAttempts, r.assistedRoutes, r.routeParams)
            case hops => payFsm forward SendPaymentToRoute(r.paymentHash, hops, FinalLegacyPayload(r.amount, finalExpiry))
          }
      }
      sender ! paymentId
  }

  def spawnPaymentFsm(paymentId: UUID, r: SendPaymentRequest): ActorRef =
    context.actorOf(PaymentLifecycle.props(nodeParams, DefaultPaymentProgressHandler(paymentId, r, nodeParams.db.payments), router, register))

  def spawnMultiPartPaymentFsm(paymentId: UUID): ActorRef =
    context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, paymentId, relayer, router, register))

}

object PaymentInitiator {

  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef, register: ActorRef) = Props(classOf[PaymentInitiator], nodeParams, router, relayer, register)

  case class SendPaymentRequest(amount: MilliSatoshi,
                                paymentHash: ByteVector32,
                                targetNodeId: PublicKey,
                                maxAttempts: Int,
                                finalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                paymentRequest: Option[PaymentRequest] = None,
                                externalId: Option[String] = None,
                                predefinedRoute: Seq[PublicKey] = Nil,
                                assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                                routeParams: Option[RouteParams] = None) {
    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: Long) = finalExpiryDelta.toCltvExpiry(currentBlockHeight + 1)
  }

}
