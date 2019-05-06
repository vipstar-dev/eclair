/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair

import java.util.UUID

import akka.actor.{ActorRef, SupervisorStrategy}
import akka.pattern._
import akka.util.Timeout
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.{Point, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.{Block, ByteVector32, MilliSatoshi, OutPoint, Satoshi, Script, Transaction, TxOut}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BatchingBitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.channel.Register.{Forward, ForwardShortId}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.db.{IncomingPayment, NetworkFee, OutgoingPayment, Stats}
import fr.acinq.eclair.io.Peer.{GetPeerInfo, Init, PeerInfo}
import fr.acinq.eclair.io.{Authenticator, NodeURI, Peer, Switchboard}
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.router.{ChannelDesc, RouteRequest, RouteResponse}
import scodec.bits.ByteVector
import scodec.bits._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.transactions.{CommitmentSpec, Transactions}
import fr.acinq.eclair.transactions.Transactions.{CommitTx, InputInfo}
import fr.acinq.eclair.wire._
import grizzled.slf4j.Logging

import scala.compat.Platform

case class GetInfoResponse(nodeId: PublicKey, alias: String, chainHash: ByteVector32, blockHeight: Int, publicAddresses: Seq[NodeAddress])

case class AuditResponse(sent: Seq[PaymentSent], received: Seq[PaymentReceived], relayed: Seq[PaymentRelayed])

trait Eclair {

  def connect(uri: String)(implicit timeout: Timeout): Future[String]

  def open(nodeId: PublicKey, fundingSatoshis: Long, pushMsat: Option[Long], fundingFeerateSatByte: Option[Long], flags: Option[Int], openTimeout_opt: Option[Timeout])(implicit timeout: Timeout): Future[String]

  def close(channelIdentifier: Either[ByteVector32, ShortChannelId], scriptPubKey: Option[ByteVector])(implicit timeout: Timeout): Future[String]

  def forceClose(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[String]

  def updateRelayFee(channelIdentifier: Either[ByteVector32, ShortChannelId], feeBaseMsat: Long, feeProportionalMillionths: Long)(implicit timeout: Timeout): Future[String]

  def channelsInfo(toRemoteNode: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[RES_GETINFO]]

  def channelInfo(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[RES_GETINFO]

  def peersInfo()(implicit timeout: Timeout): Future[Iterable[PeerInfo]]

  def receive(description: String, amountMsat: Option[Long], expire: Option[Long], fallbackAddress: Option[String])(implicit timeout: Timeout): Future[PaymentRequest]

  def receivedInfo(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[IncomingPayment]]

  def send(recipientNodeId: PublicKey, amountMsat: Long, paymentHash: ByteVector32, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty, minFinalCltvExpiry: Option[Long] = None, maxAttempts: Option[Int] = None)(implicit timeout: Timeout): Future[UUID]

  def sentInfo(id: Either[UUID, ByteVector32])(implicit timeout: Timeout): Future[Seq[OutgoingPayment]]

  def findRoute(targetNodeId: PublicKey, amountMsat: Long, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty)(implicit timeout: Timeout): Future[RouteResponse]

  def audit(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[AuditResponse]

  def networkFees(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[NetworkFee]]

  def channelStats()(implicit timeout: Timeout): Future[Seq[Stats]]

  def getInvoice(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[PaymentRequest]]

  def pendingInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]]

  def allInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]]

  def allNodes()(implicit timeout: Timeout): Future[Iterable[NodeAnnouncement]]

  def allChannels()(implicit timeout: Timeout): Future[Iterable[ChannelDesc]]

  def allUpdates(nodeId: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[ChannelUpdate]]

  def getInfoResponse()(implicit timeout: Timeout): Future[GetInfoResponse]

  def getChannelBackup(channelId: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[ByteVector]

  def attemptChannelRecovery(keyPathSerialized: ByteVector, fundingTxId: ByteVector32, channelId: ByteVector32, shortChannelIdSerialized: String, uri: String)(implicit timeout: Timeout): String

}

class EclairImpl(appKit: Kit) extends Eclair with Logging {

  implicit val ec = appKit.system.dispatcher

  override def connect(uri: String)(implicit timeout: Timeout): Future[String] = {
    (appKit.switchboard ? Peer.Connect(NodeURI.parse(uri))).mapTo[String]
  }

  override def open(nodeId: PublicKey, fundingSatoshis: Long, pushMsat: Option[Long], fundingFeerateSatByte: Option[Long], flags: Option[Int], openTimeout_opt: Option[Timeout])(implicit timeout: Timeout): Future[String] = {
    // we want the open timeout to expire *before* the default ask timeout, otherwise user won't get a generic response
    val openTimeout = openTimeout_opt.getOrElse(Timeout(10 seconds))
    (appKit.switchboard ? Peer.OpenChannel(
      remoteNodeId = nodeId,
      fundingSatoshis = Satoshi(fundingSatoshis),
      pushMsat = pushMsat.map(MilliSatoshi).getOrElse(MilliSatoshi(0)),
      fundingTxFeeratePerKw_opt = fundingFeerateSatByte.map(feerateByte2Kw),
      channelFlags = flags.map(_.toByte),
      timeout_opt = Some(openTimeout))).mapTo[String]
  }

  override def close(channelIdentifier: Either[ByteVector32, ShortChannelId], scriptPubKey: Option[ByteVector])(implicit timeout: Timeout): Future[String] = {
    sendToChannel(channelIdentifier, CMD_CLOSE(scriptPubKey)).mapTo[String]
  }

  override def forceClose(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[String] = {
    sendToChannel(channelIdentifier, CMD_FORCECLOSE).mapTo[String]
  }

  override def updateRelayFee(channelIdentifier: Either[ByteVector32, ShortChannelId], feeBaseMsat: Long, feeProportionalMillionths: Long)(implicit timeout: Timeout): Future[String] = {
    sendToChannel(channelIdentifier, CMD_UPDATE_RELAY_FEE(feeBaseMsat, feeProportionalMillionths)).mapTo[String]
  }

  override def peersInfo()(implicit timeout: Timeout): Future[Iterable[PeerInfo]] = for {
    peers <- (appKit.switchboard ? 'peers).mapTo[Iterable[ActorRef]]
    peerinfos <- Future.sequence(peers.map(peer => (peer ? GetPeerInfo).mapTo[PeerInfo]))
  } yield peerinfos

  override def channelsInfo(toRemoteNode: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[RES_GETINFO]] = toRemoteNode match {
    case Some(pk) => for {
      channelIds <- (appKit.register ? 'channelsTo).mapTo[Map[ByteVector32, PublicKey]].map(_.filter(_._2 == pk).keys)
      channels <- Future.sequence(channelIds.map(channelId => sendToChannel(Left(channelId), CMD_GETINFO).mapTo[RES_GETINFO]))
    } yield channels
    case None => for {
      channelIds <- (appKit.register ? 'channels).mapTo[Map[ByteVector32, ActorRef]].map(_.keys)
      channels <- Future.sequence(channelIds.map(channelId => sendToChannel(Left(channelId), CMD_GETINFO).mapTo[RES_GETINFO]))
    } yield channels
  }

  override def channelInfo(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[RES_GETINFO] = {
    sendToChannel(channelIdentifier, CMD_GETINFO).mapTo[RES_GETINFO]
  }

  override def allNodes()(implicit timeout: Timeout): Future[Iterable[NodeAnnouncement]] = (appKit.router ? 'nodes).mapTo[Iterable[NodeAnnouncement]]

  override def allChannels()(implicit timeout: Timeout): Future[Iterable[ChannelDesc]] = {
    (appKit.router ? 'channels).mapTo[Iterable[ChannelAnnouncement]].map(_.map(c => ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2)))
  }

  override def allUpdates(nodeId: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[ChannelUpdate]] = nodeId match {
    case None => (appKit.router ? 'updates).mapTo[Iterable[ChannelUpdate]]
    case Some(pk) => (appKit.router ? 'updatesMap).mapTo[Map[ChannelDesc, ChannelUpdate]].map(_.filter(e => e._1.a == pk || e._1.b == pk).values)
  }

  override def receive(description: String, amountMsat: Option[Long], expire: Option[Long], fallbackAddress: Option[String])(implicit timeout: Timeout): Future[PaymentRequest] = {
    fallbackAddress.map { fa => fr.acinq.eclair.addressToPublicKeyScript(fa, appKit.nodeParams.chainHash) } // if it's not a bitcoin address throws an exception
    (appKit.paymentHandler ? ReceivePayment(description = description, amountMsat_opt = amountMsat.map(MilliSatoshi), expirySeconds_opt = expire, fallbackAddress = fallbackAddress)).mapTo[PaymentRequest]
  }

  override def findRoute(targetNodeId: PublicKey, amountMsat: Long, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty)(implicit timeout: Timeout): Future[RouteResponse] = {
    (appKit.router ? RouteRequest(appKit.nodeParams.nodeId, targetNodeId, amountMsat, assistedRoutes)).mapTo[RouteResponse]
  }

  override def send(recipientNodeId: PublicKey, amountMsat: Long, paymentHash: ByteVector32, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty, minFinalCltvExpiry_opt: Option[Long] = None, maxAttempts_opt: Option[Int] = None)(implicit timeout: Timeout): Future[UUID] = {
    val maxAttempts = maxAttempts_opt.getOrElse(appKit.nodeParams.maxPaymentAttempts)
    val sendPayment = minFinalCltvExpiry_opt match {
      case Some(minCltv) => SendPayment(amountMsat, paymentHash, recipientNodeId, assistedRoutes, finalCltvExpiry = minCltv, maxAttempts = maxAttempts)
      case None => SendPayment(amountMsat, paymentHash, recipientNodeId, assistedRoutes, maxAttempts = maxAttempts)
    }
    (appKit.paymentInitiator ? sendPayment).mapTo[UUID]
  }

  override def sentInfo(id: Either[UUID, ByteVector32])(implicit timeout: Timeout): Future[Seq[OutgoingPayment]] = Future {
    id match {
      case Left(uuid) => appKit.nodeParams.db.payments.getOutgoingPayment(uuid).toSeq
      case Right(paymentHash) => appKit.nodeParams.db.payments.getOutgoingPayments(paymentHash)
    }
  }

  override def receivedInfo(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[IncomingPayment]] = Future {
    appKit.nodeParams.db.payments.getIncomingPayment(paymentHash)
  }

  override def audit(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[AuditResponse] = {
    val from = from_opt.getOrElse(0L)
    val to = to_opt.getOrElse(MaxEpochSeconds)

    Future(AuditResponse(
      sent = appKit.nodeParams.db.audit.listSent(from, to),
      received = appKit.nodeParams.db.audit.listReceived(from, to),
      relayed = appKit.nodeParams.db.audit.listRelayed(from, to)
    ))
  }

  override def networkFees(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[NetworkFee]] = {
    val from = from_opt.getOrElse(0L)
    val to = to_opt.getOrElse(MaxEpochSeconds)

    Future(appKit.nodeParams.db.audit.listNetworkFees(from, to))
  }

  override def channelStats()(implicit timeout: Timeout): Future[Seq[Stats]] = Future(appKit.nodeParams.db.audit.stats)

  override def allInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]] = Future {
    val from = from_opt.getOrElse(0L)
    val to = to_opt.getOrElse(MaxEpochSeconds)

    appKit.nodeParams.db.payments.listPaymentRequests(from, to)
  }

  override def pendingInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]] = Future {
    val from = from_opt.getOrElse(0L)
    val to = to_opt.getOrElse(MaxEpochSeconds)

    appKit.nodeParams.db.payments.listPendingPaymentRequests(from, to)
  }

  override def getInvoice(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[PaymentRequest]] = Future {
    appKit.nodeParams.db.payments.getPaymentRequest(paymentHash)
  }

  override def getChannelBackup(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[ByteVector] = {
    sendToChannel(channelIdentifier, CMD_GETINFO).mapTo[RES_GETINFO].map { info =>
      val keyPath = info.data.asInstanceOf[DATA_NORMAL].commitments.localParams.channelKeyPath
      ByteVector(ChannelCodecs.keyPathCodec.encode(keyPath).require.toByteArray)
    }
  }

  override def attemptChannelRecovery(keyPathSerialized: ByteVector, fundingTxId: ByteVector32, channelId: ByteVector32, shortChannelIdSerialized: String, uri: String)(implicit timeout: Timeout): String = {

    implicit val shttp = OkHttpFutureBackend()

    val nodeUri = NodeURI.parse(uri)
    val keyPath = ChannelCodecs.keyPathCodec.decodeValue(keyPathSerialized.toBitVector).require
    val shortChannelId = ShortChannelId(shortChannelIdSerialized)
    val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(shortChannelId)

    val bitcoinRpcClient = new BasicBitcoinJsonRPCClient(
      user = appKit.nodeParams.config.getString("bitcoind.rpcuser"),
      password = appKit.nodeParams.config.getString("bitcoind.rpcpassword"),
      host = appKit.nodeParams.config.getString("bitcoind.host"),
      port = appKit.nodeParams.config.getInt("bitcoind.rpcport")
    )

    val (fundingTx, finalAddress) = Await.result(for {
      funding <- new ExtendedBitcoinClient(bitcoinRpcClient).getTransaction(fundingTxId.toHex)
      address <- new BitcoinCoreWallet(bitcoinRpcClient).getFinalAddress
    } yield (funding, address), 30 seconds)

    val finalScriptPubkey = Script.write(addressToPublicKeyScript(finalAddress, appKit.nodeParams.chainHash))

    val inputInfo = Transactions.InputInfo(
      outPoint = OutPoint(fundingTx.hash, outputIndex),
      txOut = fundingTx.txOut(outputIndex),
      redeemScript = ByteVector.empty
    )

    val commitments = makeDummyCommitment(keyPath, nodeUri.nodeId, channelId, shortChannelId, inputInfo, finalScriptPubkey)
    appKit.switchboard ? Peer.Connect(nodeUri, Set(commitments))

    "in progress"
  }

  /**
    * Sends a request to a channel and expects a response
    *
    * @param channelIdentifier either a shortChannelId (BOLT encoded) or a channelId (32-byte hex encoded)
    * @param request
    * @return
    */
  def sendToChannel(channelIdentifier: Either[ByteVector32, ShortChannelId], request: Any)(implicit timeout: Timeout): Future[Any] = channelIdentifier match {
    case Left(channelId) => appKit.register ? Forward(channelId, request)
    case Right(shortChannelId) => appKit.register ? ForwardShortId(shortChannelId, request)
  }

  override def getInfoResponse()(implicit timeout: Timeout): Future[GetInfoResponse] = Future.successful(
    GetInfoResponse(nodeId = appKit.nodeParams.nodeId,
      alias = appKit.nodeParams.alias,
      chainHash = appKit.nodeParams.chainHash,
      blockHeight = Globals.blockCount.intValue(),
      publicAddresses = appKit.nodeParams.publicAddresses)
  )

  def makeDummyCommitment(channelKeyPath: KeyPath, remoteNodeId: PublicKey, channelId: ByteVector32, shortChannelId: ShortChannelId, commitInput: InputInfo, finalScriptPubkey: ByteVector) = DATA_NORMAL(
    commitments = Commitments(
      localParams = LocalParams(
        nodeId = appKit.nodeParams.nodeId,
        channelKeyPath = channelKeyPath,
        dustLimitSatoshis = 0,
        maxHtlcValueInFlightMsat = UInt64(0),
        channelReserveSatoshis = 0,
        toSelfDelay = 0,
        htlcMinimumMsat = 0,
        maxAcceptedHtlcs = 0,
        isFunder = true,
        defaultFinalScriptPubKey = finalScriptPubkey,
        globalFeatures = hex"00",
        localFeatures = hex"00"
      ),
      remoteParams = RemoteParams(
        remoteNodeId,
        dustLimitSatoshis = 0,
        maxHtlcValueInFlightMsat = UInt64(0),
        channelReserveSatoshis = 0,
        htlcMinimumMsat = 0,
        toSelfDelay = 0,
        maxAcceptedHtlcs = 0,
        fundingPubKey = PublicKey(hex"02184615bf2294acc075701892d7bd8aff28d78f84330e8931102e537c8dfe92a3"),
        revocationBasepoint = Point(hex"020beeba2c3015509a16558c35b930bed0763465cf7a9a9bc4555fd384d8d383f6"),
        paymentBasepoint = Point(hex"02e63d3b87e5269d96f1935563ca7c197609a35a928528484da1464eee117335c5"),
        delayedPaymentBasepoint = Point(hex"033dea641e24e7ae550f7c3a94bd9f23d55b26a649c79cd4a3febdf912c6c08281"),
        htlcBasepoint = Point(hex"0274a89988063045d3589b162ac6eea5fa0343bf34220648e92a636b1c2468a434"),
        globalFeatures = hex"00",
        localFeatures = hex"00"
      ),
      channelFlags = 1.toByte,
      localCommit = LocalCommit(
        1,
        spec = CommitmentSpec(
          htlcs = Set(),
          feeratePerKw = 234,
          toLocalMsat = 0,
          toRemoteMsat = 0
        ),
        publishableTxs = PublishableTxs(
          CommitTx(
            input = Transactions.InputInfo(
              outPoint = OutPoint(ByteVector32.Zeroes, 0),
              txOut = TxOut(Satoshi(0), ByteVector.empty),
              redeemScript = ByteVector.empty
            ),
            tx = Transaction.read("0200000000010163c75c555d712a81998ddbaf9ce1d55b153fc7cb71441ae1782143bb6b04b95d0000000000a325818002bc893c0000000000220020ae8d04088ff67f3a0a9106adb84beb7530097b262ff91f8a9a79b7851b50857f00127a0000000000160014be0f04e9ed31b6ece46ca8c17e1ed233c71da0e9040047304402203b280f9655f132f4baa441261b1b590bec3a6fcd6d7180c929fa287f95d200f80220100d826d56362c65d09b8687ca470a31c1e2bb3ad9a41321ceba355d60b77b79014730440220539e34ab02cced861f9c39f9d14ece41f1ed6aed12443a9a4a88eb2792356be6022023dc4f18730a6471bdf9b640dfb831744b81249ffc50bd5a756ae85d8c6749c20147522102184615bf2294acc075701892d7bd8aff28d78f84330e8931102e537c8dfe92a3210367d50e7eab4a0ab0c6b92aa2dcf6cc55a02c3db157866b27a723b8ec47e1338152ae74f15a20")
          ),
          htlcTxsAndSigs = List.empty
        )
      ),
      remoteCommit = RemoteCommit(
        666,
        spec = CommitmentSpec(
          htlcs = Set(),
          feeratePerKw = 432,
          toLocalMsat = 0,
          toRemoteMsat = 0
        ),
        txid = ByteVector32.fromValidHex("b70c3314af259029e7d11191ca0fe6ee407352dfaba59144df7f7ce5cc1c7b51"),
        remotePerCommitmentPoint = Point(hex"0286f6253405605640f6c19ea85a51267795163183a17df077050bf680ed62c224")
      ),
      localChanges = LocalChanges(
        proposed = List.empty,
        signed = List.empty,
        acked = List.empty
      ),
      remoteChanges = RemoteChanges(
        proposed = List.empty,
        signed = List.empty,
        acked = List.empty
      ),
      localNextHtlcId = 0,
      remoteNextHtlcId = 0,
      originChannels = Map(),
      remoteNextCommitInfo = Right(Point(hex"0386f6253405605640f6c19ea85a51267795163183a17df077050bf680ed62c224")),
      commitInput = commitInput,
      remotePerCommitmentSecrets = ShaChain.init,
      channelId = channelId
    ),
    shortChannelId = shortChannelId,
    buried = true,
    channelAnnouncement = None,
    channelUpdate = ChannelUpdate(
      signature = ByteVector.empty,
      chainHash = appKit.nodeParams.chainHash,
      shortChannelId = shortChannelId,
      timestamp = Platform.currentTime.milliseconds.toSeconds,
      messageFlags = 0.toByte,
      channelFlags = 0.toByte,
      cltvExpiryDelta = 144,
      htlcMinimumMsat = 0,
      feeBaseMsat = 0,
      feeProportionalMillionths = 0,
      htlcMaximumMsat = None
    ),
    localShutdown = None,
    remoteShutdown = None
  )

}
