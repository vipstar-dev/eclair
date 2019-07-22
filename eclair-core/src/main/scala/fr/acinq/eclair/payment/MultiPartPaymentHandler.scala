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

import akka.actor.{ActorRef, Props}
import fr.acinq.bitcoin.{ByteVector32, MilliSatoshi}
import fr.acinq.eclair.FSMDiagnosticActorLogging
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.wire.{IncorrectOrUnknownPaymentDetails, UpdateAddHtlc}

import scala.concurrent.duration.FiniteDuration

/**
  * Created by t-bast on 18/07/2019.
  */

/**
  * Handler for a multi-part payment (see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#basic-multi-part-payments).
  * Once all the partial payments are received, all the partial HTLCs are fulfilled.
  * After a reasonable delay, if not enough partial payments have been received, all the partial HTLCs are failed.
  * This handler assumes that the parent only sends payments for the same payment hash.
  */
class MultiPartPaymentHandler(preimage: ByteVector32, paymentTimeout: FiniteDuration, parent: ActorRef) extends FSMDiagnosticActorLogging[MultiPartPaymentHandler.State, MultiPartPaymentHandler.Data] {

  import MultiPartPaymentHandler._

  setTimer(PaymentTimeout.toString, PaymentTimeout, paymentTimeout, repeat = false)

  startWith(WAITING_FOR_FIRST_HTLC, WaitingForFirstHtlc)

  when(WAITING_FOR_FIRST_HTLC) {
    case Event(PaymentTimeout, _) =>
      goto(PAYMENT_FAILED) using PaymentFailed(0, List.empty[(Long, ActorRef)])

    case Event(MultiPartHtlc(totalAmountMsat, htlc), _) =>
      cancelTimer(PaymentTimeout.toString)
      setTimer(PaymentTimeout.toString, PaymentTimeout, paymentTimeout, repeat = false)
      if (htlc.amountMsat >= totalAmountMsat) {
        goto(PAYMENT_SUCCEEDED) using PaymentSucceeded(htlc.amountMsat, (htlc.id, sender) :: Nil)
      } else {
        goto(WAITING_FOR_MORE_HTLC) using WaitingForMoreHtlc(totalAmountMsat, htlc.amountMsat, (htlc.id, sender) :: Nil)
      }
  }

  when(WAITING_FOR_MORE_HTLC) {
    case Event(PaymentTimeout, d: WaitingForMoreHtlc) =>
      goto(PAYMENT_FAILED) using PaymentFailed(d.paidAmountMsat, d.htlcIds)

    case Event(MultiPartHtlc(totalAmountMsat, htlc), d: WaitingForMoreHtlc) =>
      cancelTimer(PaymentTimeout.toString)
      setTimer(PaymentTimeout.toString, PaymentTimeout, paymentTimeout, repeat = false)
      if (totalAmountMsat != d.totalAmountMsat) {
        log.warning(s"multi-part payment totalAmountMsat mismatch: previously ${d.totalAmountMsat}, now $totalAmountMsat")
        goto(PAYMENT_FAILED) using PaymentFailed(d.paidAmountMsat, (htlc.id, sender) :: d.htlcIds)
      } else if (htlc.amountMsat + d.paidAmountMsat >= d.totalAmountMsat) {
        goto(PAYMENT_SUCCEEDED) using PaymentSucceeded(htlc.amountMsat + d.paidAmountMsat, (htlc.id, sender) :: d.htlcIds)
      } else {
        stay using d.copy(paidAmountMsat = d.paidAmountMsat + htlc.amountMsat, htlcIds = (htlc.id, sender) :: d.htlcIds)
      }
  }

  when(PAYMENT_SUCCEEDED) {
    // A sender should not send us additional htlcs for that payment once we've already reached the total amount.
    // However if that happens the only rational choice is to fulfill it, because the pre-image has been released so
    // intermediate nodes will be able to fulfill that htlc anyway. This is a harmless spec violation.
    case Event(MultiPartHtlc(_, htlc), _) =>
      log.info(s"received extraneous htlc for payment hash ${htlc.paymentHash}")
      sender ! CMD_FULFILL_HTLC(htlc.id, preimage, commit = true)
      stay
  }

  when(PAYMENT_FAILED) {
    // If we receive htlcs after the multi-part payment has expired, we must fail them.
    case Event(MultiPartHtlc(_, htlc), PaymentFailed(paidAmountMsat, _)) =>
      sender ! CMD_FAIL_HTLC(htlc.id, Right(IncorrectOrUnknownPaymentDetails(paidAmountMsat)), commit = true)
      stay
  }

  onTransition {
    case _ -> PAYMENT_SUCCEEDED =>
      cancelTimer(PaymentTimeout.toString)
      nextStateData match {
        case PaymentSucceeded(paidAmountMsat, htlcs) =>
          htlcs.reverse.foreach { case (id, sender) => sender ! CMD_FULFILL_HTLC(id, preimage, commit = true) }
          // We expect the parent actor to send us a PoisonPill after receiving this message.
          parent ! MultiPartHtlcSucceeded(preimage, MilliSatoshi(paidAmountMsat))
        case d =>
          log.error(s"unexpected payment success data ${d.getClass.getSimpleName}")
      }
    case _ -> PAYMENT_FAILED =>
      cancelTimer(PaymentTimeout.toString)
      nextStateData match {
        case PaymentFailed(paidAmountMsat, htlcs) =>
          htlcs.reverse.foreach { case (id, sender) => sender ! CMD_FAIL_HTLC(id, Right(IncorrectOrUnknownPaymentDetails(paidAmountMsat)), commit = true) }
          // We expect the parent actor to send us a PoisonPill after receiving this message.
          parent ! MultiPartHtlcFailed(preimage)
        case d =>
          log.error(s"unexpected payment failure data ${d.getClass.getSimpleName}")
      }
  }

  initialize()

}

object MultiPartPaymentHandler {

  def props(preimage: ByteVector32, paymentTimeout: FiniteDuration, parent: ActorRef) = Props(new MultiPartPaymentHandler(preimage, paymentTimeout, parent))

  case object PaymentTimeout

  // @formatter:off
  case class MultiPartHtlc(totalAmountMsat: Long, htlc: UpdateAddHtlc)
  case class MultiPartHtlcSucceeded(preimage: ByteVector32, paidAmount: MilliSatoshi)
  case class MultiPartHtlcFailed(preimage: ByteVector32)
  // @formatter:on

  // @formatter:off
  sealed trait State
  case object WAITING_FOR_FIRST_HTLC extends State
  case object WAITING_FOR_MORE_HTLC extends State
  case object PAYMENT_SUCCEEDED extends State
  case object PAYMENT_FAILED extends State
  // @formatter:on

  // @formatter:off
  sealed trait Data
  case object WaitingForFirstHtlc extends Data
  case class WaitingForMoreHtlc(totalAmountMsat: Long, paidAmountMsat: Long, htlcIds: List[(Long, ActorRef)]) extends Data
  case class PaymentSucceeded(paidAmountMsat: Long, htlcIds: List[(Long, ActorRef)]) extends Data
  case class PaymentFailed(paidAmountMsat: Long, htlcIds: List[(Long, ActorRef)]) extends Data
  // @formatter:on

}
