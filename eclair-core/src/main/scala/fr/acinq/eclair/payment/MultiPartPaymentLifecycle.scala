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

import akka.actor.{ActorRef, FSM, Props}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.{AvailableBalanceChanged, LocalChannelDown, LocalChannelUpdate}
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus}
import fr.acinq.eclair.payment.PaymentInitiator.LocalChannel
import fr.acinq.eclair.payment.PaymentLifecycle.{SendPayment, TlvPayload}
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Logs, NodeParams, ShortChannelId}

import scala.compat.Platform
import scala.math.{max, min}

/**
 * Created by t-bast on 18/07/2019.
 */

/**
 * Sender for a multi-part payment (see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#basic-multi-part-payments).
 * The payment will be split into multiple sub-payments that will be sent in parallel.
 * The sub-payments will either all succeed or all fail.
 */
class MultiPartPaymentLifecycle(nodeParams: NodeParams, id: UUID, currentLocalChannels: Map[ShortChannelId, LocalChannel], router: ActorRef, register: ActorRef) extends FSMDiagnosticActorLogging[MultiPartPaymentLifecycle.State, MultiPartPaymentLifecycle.Data] {

  import MultiPartPaymentLifecycle._

  val paymentsDb = nodeParams.db.payments
  var localChannels = currentLocalChannels

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])
  context.system.eventStream.subscribe(self, classOf[AvailableBalanceChanged])

  startWith(WAITING_FOR_REQUEST, WaitingForRequest)

  when(WAITING_FOR_REQUEST) {
    case Event(p: SendPayment, _) =>
      log.debug(s"sending ${p.finalAmountMsat} to ${p.targetNodeId}: local channels balance for send (msat): ${localChannels.map { case (id, c) => s"$id -> ${c.localBalanceMsat}" }.mkString(", ")}")
      paymentsDb.addOutgoingPayment(OutgoingPayment(id, p.paymentHash, None, p.finalAmountMsat, Platform.currentTime, None, OutgoingPaymentStatus.PENDING))
      // If the target is accessible by direct channels, we use as much of their available balance as possible.
      val localChannelsToTarget = localChannels.filter { case (_, c) => c.remoteNodeId == p.targetNodeId }
      val (remainingAmountMsat, localPayments) = splitPayment(nodeParams, p.finalAmountMsat, localChannelsToTarget, p)
      // And we complete by payments split among potentially all channels.
      val (finalRemainingAmountMsat, remotePayments) = splitPayment(nodeParams, remainingAmountMsat, localChannels -- localChannelsToTarget.keys, p)
      if (finalRemainingAmountMsat > 0) {
        log.warning(s"payment amount is bigger than our current balance (${p.finalAmountMsat} > ${localChannels.values.map(_.localBalanceMsat).sum})")
        goto(PAYMENT_FAILURE) using PaymentFailure(sender, p.finalAmountMsat, p.paymentHash, PaymentLifecycle.LocalFailure(new RuntimeException("payment amount is bigger than our current balance")), Set.empty)
      } else {
        val payments = (localPayments ++ remotePayments).map(p => (UUID.randomUUID(), p)).toMap
        log.debug(s"${p.finalAmountMsat}msat payment successfully split locally: ${payments.values.map(p => s"${p.finalAmountMsat}msat -> ${p.routePrefix.head.nextNodeId} (${p.routePrefix.head.lastUpdate.shortChannelId})").mkString(", ")}")
        payments.foreach { case (paymentId, payment) => spawnPaymentFsm(paymentId) ! payment }
        goto(WAITING_FOR_PAYMENT_RESULTS) using WaitingForPaymentResults(sender, p, payments, p.maxAttempts - 1)
      }
  }

  when(WAITING_FOR_PAYMENT_RESULTS) {
    case Event(PaymentLifecycle.PaymentFailed(paymentId, _, _), d: WaitingForPaymentResults) =>
      log.debug(s"partial payment with id $paymentId failed")
      if (d.remainingAttempts == 0) {
        val failure = PaymentLifecycle.LocalFailure(new RuntimeException("payment attempts exhausted without success"))
        goto(PAYMENT_FAILURE) using PaymentFailure(d.sender, d.parent.finalAmountMsat, d.parent.paymentHash, failure, d.payments.keys.toSet - paymentId)
      } else {
        // We want to avoid competing for channel balance that is needed by other partial payments we know about.
        val availableChannels = d.payments.values.foldLeft(localChannels) { case (channels, payment) =>
          val paymentChannelId = payment.routePrefix.head.lastUpdate.shortChannelId
          channels get paymentChannelId match {
            case Some(c) => channels + (paymentChannelId -> c.copy(localBalanceMsat = max(0, c.localBalanceMsat - payment.finalAmountMsat)))
            case None => channels
          }
        }
        splitPayment(nodeParams, d.payments(paymentId).finalAmountMsat, availableChannels, d.parent) match {
          case (0, split) =>
            val newPayments = split.map(p => (UUID.randomUUID(), p)).toMap
            log.debug(s"failed partial payment successfully split: ${newPayments.values.map(p => s"${p.finalAmountMsat}msat -> ${p.routePrefix.head.nextNodeId} (${p.routePrefix.head.lastUpdate.shortChannelId})").mkString(", ")}")
            newPayments.foreach { case (paymentId, payment) => spawnPaymentFsm(paymentId) ! payment }
            stay using d.copy(payments = d.payments ++ newPayments - paymentId, remainingAttempts = d.remainingAttempts - 1)
          case _ =>
            val failure = PaymentLifecycle.LocalFailure(new RuntimeException("cannot retry multi-part payment (not enough balance)"))
            goto(PAYMENT_FAILURE) using PaymentFailure(d.sender, d.parent.finalAmountMsat, d.parent.paymentHash, failure, d.payments.keys.toSet - paymentId)
        }
      }

    case Event(PaymentLifecycle.PaymentSucceeded(paymentId, _, _, preimage, _), d: WaitingForPaymentResults) =>
      goto(PAYMENT_SUCCESS) using PaymentSuccess(d.sender, d.parent.finalAmountMsat, d.parent.paymentHash, preimage, d.payments.keys.toSet - paymentId)
  }

  when(PAYMENT_FAILURE) {
    case Event(PaymentLifecycle.PaymentFailed(paymentId, _, _), d: PaymentFailure) =>
      val pending = d.pending - paymentId
      if (pending.isEmpty) {
        failPayment(d.sender, d)
      }
      stay using d.copy(pending = pending)

    // The recipient released the pre-image without receiving the full payment amount.
    // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
    case Event(PaymentLifecycle.PaymentSucceeded(paymentId, _, paymentHash, preimage, _), d: PaymentFailure) =>
      log.warning(s"payment recipient fulfilled incomplete multi-part payment (id=$paymentId)")
      goto(PAYMENT_SUCCESS) using PaymentSuccess(d.sender, d.totalAmountMsat, paymentHash, preimage, d.pending - paymentId)
  }

  when(PAYMENT_SUCCESS) {
    case Event(PaymentLifecycle.PaymentSucceeded(paymentId, _, _, _, _), d: PaymentSuccess) =>
      val pending = d.pending - paymentId
      if (pending.isEmpty) {
        stop(FSM.Normal)
      }
      stay using d.copy(pending = pending)

    // The recipient released the pre-image without receiving the full payment amount.
    // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
    case Event(PaymentLifecycle.PaymentFailed(paymentId, _, _), d: PaymentSuccess) =>
      log.warning(s"payment succeeded but partial payment failed (id=$paymentId)")
      stay using d.copy(pending = d.pending - paymentId)
  }

  whenUnhandled {
    case Event(LocalChannelUpdate(_, _, shortChannelId, remoteNodeId, _, channelUpdate, commitments), _) =>
      val localChannel = localChannels.get(shortChannelId) match {
        case Some(c) => c.copy(localBalanceMsat = commitments.availableBalanceForSendMsat, lastUpdate = channelUpdate)
        case None => LocalChannel(remoteNodeId, commitments.availableBalanceForSendMsat, channelUpdate)
      }
      localChannels = localChannels + (shortChannelId -> localChannel)
      stay

    case Event(LocalChannelDown(_, _, shortChannelId, _), _) =>
      localChannels = localChannels - shortChannelId
      stay

    case Event(AvailableBalanceChanged(_, _, shortChannelId, localBalanceMsat, _), _) =>
      localChannels.get(shortChannelId) match {
        case Some(c) => localChannels = localChannels + (shortChannelId -> c.copy(localBalanceMsat = localBalanceMsat))
        case None =>
      }
      stay
  }

  onTransition {
    case _ -> PAYMENT_FAILURE => nextStateData match {
      case f: PaymentFailure if f.pending.isEmpty => failPayment(f.sender, f)
      case _ =>
    }
    case _ -> PAYMENT_SUCCESS => nextStateData match {
      case s: PaymentSuccess =>
        // As soon as we get the pre-image we can consider that the whole payment succeeded.
        log.debug("multi-part payment succeeded")
        paymentsDb.updateOutgoingPayment(id, OutgoingPaymentStatus.SUCCEEDED, Some(s.preimage))
        reply(s.sender, PaymentLifecycle.PaymentSucceeded(id, s.totalAmountMsat, s.paymentHash, s.preimage, Nil))
        if (s.pending.isEmpty) {
          stop(FSM.Normal)
        }
      case _ =>
    }
  }

  def spawnPaymentFsm(paymentId: UUID): ActorRef = context.actorOf(PaymentLifecycle.props(nodeParams, paymentId, router, register))

  def reply(to: ActorRef, e: PaymentLifecycle.PaymentResult): Unit = {
    to ! e
    context.system.eventStream.publish(e)
  }

  def failPayment(sender: ActorRef, f: PaymentFailure): Unit = {
    log.warning("multi-part payment failed")
    paymentsDb.updateOutgoingPayment(id, OutgoingPaymentStatus.FAILED)
    reply(sender, PaymentLifecycle.PaymentFailed(id, f.paymentHash, f.failure :: Nil))
    stop(FSM.Normal)
  }

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(paymentId_opt = Some(id))
  }

  initialize()

}

object MultiPartPaymentLifecycle {

  def props(nodeParams: NodeParams, id: UUID, currentLocalChannels: Map[ShortChannelId, LocalChannel], router: ActorRef, register: ActorRef) = Props(new MultiPartPaymentLifecycle(nodeParams, id, currentLocalChannels, router, register))

  // @formatter:off
  sealed trait State
  case object WAITING_FOR_REQUEST extends State
  case object WAITING_FOR_PAYMENT_RESULTS extends State
  case object PAYMENT_SUCCESS extends State
  case object PAYMENT_FAILURE extends State

  sealed trait Data
  case object WaitingForRequest extends Data
  case class WaitingForPaymentResults(sender: ActorRef, parent: SendPayment, payments: Map[UUID, SendPayment], remainingAttempts: Int) extends Data
  case class PaymentFailure(sender: ActorRef, totalAmountMsat: Long, paymentHash: ByteVector32, failure: PaymentLifecycle.PaymentFailure, pending: Set[UUID]) extends Data
  case class PaymentSuccess(sender: ActorRef, totalAmountMsat: Long, paymentHash: ByteVector32, preimage: ByteVector32, pending: Set[UUID]) extends Data
  // @formatter:on

  /**
   * Split a payment into many sub-payments.
   * Channels with lowest balance are used first (it's a good opportunity to finish emptying them).
   *
   * @return the sub-payments that should be then sent to PaymentLifecycle actors.
   */
  def splitPayment(nodeParams: NodeParams, amountMsat: Long, localChannels: Map[ShortChannelId, LocalChannel], fullPayment: SendPayment): (Long, Seq[SendPayment]) = {
    require(fullPayment.paymentOptions.isInstanceOf[TlvPayload], "multi-part payments must use a tlv payload")
    val fullPaymentOptions = fullPayment.paymentOptions.asInstanceOf[TlvPayload]
    localChannels.values.toList.sortBy(_.localBalanceMsat).foldLeft((amountMsat, List.empty[SendPayment])) {
      case ((0, payments), _) => (0, payments)
      case (current, c) if c.localBalanceMsat < nodeParams.routerConf.multiPartMinShareMsat =>
        // We skip channels that have a balance smaller than the minimum payment share we'd like to create.
        current
      case ((remainingAmountMsat, payments), c) =>
        val payAmountMsat = c.remoteNodeId match {
          case fullPayment.targetNodeId => min(remainingAmountMsat, c.localBalanceMsat)
          case _ =>
            // If the next hop isn't the final destination, we can't use all the available balance because we need to take fees into account.
            // Note that there are still edge cases where fees could bite us, but this is mitigated by retries.
            // This will be fixed more elegantly once MPP are integrated directly in the router's algorithm.
            val maxFeePct = fullPayment.routeParams.map(_.maxFeePct).getOrElse(nodeParams.routerConf.searchMaxFeePct)
            val maxFeeBaseMsat = fullPayment.routeParams.map(_.maxFeeBaseMsat).getOrElse(nodeParams.routerConf.searchMaxFeeBaseSat)
            min(remainingAmountMsat, min((c.localBalanceMsat * (1 - maxFeePct)).toLong, c.localBalanceMsat - maxFeeBaseMsat))
        }
        val currentChannelPayments = payAmountMsat match {
          case payAmountMsat if nodeParams.routerConf.multiPartThresholdMsat < payAmountMsat =>
            // Splitting into multiple HTLCs will increase the size of the CommitTx for that channel (and thus its fee),
            // so we cannot use all available funds.
            val paymentsCount = if (payAmountMsat % nodeParams.routerConf.multiPartThresholdMsat == 0) {
              (payAmountMsat / nodeParams.routerConf.multiPartThresholdMsat).toInt - 1
            } else {
              (payAmountMsat / nodeParams.routerConf.multiPartThresholdMsat).toInt
            }
            List.fill(paymentsCount)(fullPayment.copy(paymentOptions = fullPaymentOptions.copy(finalAmountMsat = nodeParams.routerConf.multiPartThresholdMsat), routePrefix = Seq(Hop(nodeParams.nodeId, c.remoteNodeId, c.lastUpdate))))
          case payAmountMsat =>
            fullPayment.copy(paymentOptions = fullPaymentOptions.copy(finalAmountMsat = payAmountMsat), routePrefix = Seq(Hop(nodeParams.nodeId, c.remoteNodeId, c.lastUpdate))) :: Nil
        }
        (remainingAmountMsat - currentChannelPayments.map(_.finalAmountMsat).sum, payments ++ currentChannelPayments)
    }
  }

}
