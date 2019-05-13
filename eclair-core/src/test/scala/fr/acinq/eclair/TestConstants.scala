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

package fr.acinq.eclair

import java.sql.{Connection, DriverManager}

import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.{Block, ByteVector32, OutPoint, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.NodeParams.BITCOIND
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.{LocalKeyManager, ShaChain}
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.sqlite._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.Relayed
import fr.acinq.eclair.router.RouterConf
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.{ChannelUpdate, Color, NodeAddress, UpdateAddHtlc}
import scodec.bits.ByteVector
import scodec.bits._
import scala.concurrent.duration._

/**
  * Created by PM on 26/04/2016.
  */
object TestConstants {
  val fundingSatoshis = 1000000L
  val pushMsat = 200000000L
  val feeratePerKw = 10000L

  def sqliteInMemory() = DriverManager.getConnection("jdbc:sqlite::memory:")

  def inMemoryDb(connection: Connection = sqliteInMemory()): Databases = Databases.databaseByConnections(connection, connection, connection)


  object Alice {
    val seed = ByteVector32(ByteVector.fill(32)(1))
    val keyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)

    // This is a function, and not a val! When called will return a new NodeParams
    def nodeParams = NodeParams(
      keyManager = keyManager,
      alias = "alice",
      color = Color(1, 2, 3),
      publicAddresses = NodeAddress.fromParts("localhost", 9731).get :: Nil,
      globalFeatures = ByteVector.empty,
      localFeatures = ByteVector(0),
      overrideFeatures = Map.empty,
      dustLimitSatoshis = 1100,
      maxHtlcValueInFlightMsat = UInt64(150000000),
      maxAcceptedHtlcs = 100,
      expiryDeltaBlocks = 144,
      htlcMinimumMsat = 0,
      minDepthBlocks = 3,
      toRemoteDelayBlocks = 144,
      maxToLocalDelayBlocks = 1000,
      smartfeeNBlocks = 3,
      feeBaseMsat = 546000,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      db = inMemoryDb(sqliteInMemory),
      revocationTimeout = 20 seconds,
      pingInterval = 30 seconds,
      pingTimeout = 10 seconds,
      pingDisconnect = true,
      maxFeerateMismatch = 1.5,
      updateFeeMinDiffRatio = 0.1,
      autoReconnect = false,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      watcherType = BITCOIND,
      paymentRequestExpiry = 1 hour,
      minFundingSatoshis = 1000L,
      routerConf = RouterConf(
        randomizeRouteSelection = false,
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 5 seconds,
        searchMaxFeeBaseSat = 21,
        searchMaxFeePct = 0.03,
        searchMaxCltv = 2016,
        searchMaxRouteLength = 20,
        searchHeuristicsEnabled = false,
        searchRatioCltv = 0.0,
        searchRatioChannelAge = 0.0,
        searchRatioChannelCapacity = 0.0
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5
    )

    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(randomBytes32, compressed = true).publicKey)),
      isFunder = true,
      fundingSatoshis).copy(
      channelReserveSatoshis = 10000 // Bob will need to keep that much satoshis as direct payment
    )
  }

  object Bob {
    val seed = ByteVector32(ByteVector.fill(32)(2))
    val keyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)

    def nodeParams = NodeParams(
      keyManager = keyManager,
      alias = "bob",
      color = Color(4, 5, 6),
      publicAddresses = NodeAddress.fromParts("localhost", 9732).get :: Nil,
      globalFeatures = ByteVector.empty,
      localFeatures = ByteVector.empty, // no announcement
      overrideFeatures = Map.empty,
      dustLimitSatoshis = 1000,
      maxHtlcValueInFlightMsat = UInt64.MaxValue, // Bob has no limit on the combined max value of in-flight htlcs
      maxAcceptedHtlcs = 30,
      expiryDeltaBlocks = 144,
      htlcMinimumMsat = 1000,
      minDepthBlocks = 3,
      toRemoteDelayBlocks = 144,
      maxToLocalDelayBlocks = 1000,
      smartfeeNBlocks = 3,
      feeBaseMsat = 546000,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      db = inMemoryDb(sqliteInMemory),
      revocationTimeout = 20 seconds,
      pingInterval = 30 seconds,
      pingTimeout = 10 seconds,
      pingDisconnect = true,
      maxFeerateMismatch = 1.0,
      updateFeeMinDiffRatio = 0.1,
      autoReconnect = false,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      watcherType = BITCOIND,
      paymentRequestExpiry = 1 hour,
      minFundingSatoshis = 1000L,
      routerConf = RouterConf(
        randomizeRouteSelection = false,
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 5 seconds,
        searchMaxFeeBaseSat = 21,
        searchMaxFeePct = 0.03,
        searchMaxCltv = 2016,
        searchMaxRouteLength = 20,
        searchHeuristicsEnabled = false,
        searchRatioCltv = 0.0,
        searchRatioChannelAge = 0.0,
        searchRatioChannelCapacity = 0.0
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5
    )

    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(randomBytes32, compressed = true).publicKey)),
      isFunder = false,
      fundingSatoshis).copy(
      channelReserveSatoshis = 20000 // Alice will need to keep that much satoshis as direct payment
    )
  }

  val mockResGetInfo = RES_GETINFO(
    nodeId = Alice.nodeParams.nodeId,
    channelId = ByteVector32.fromValidHex("63c75c555d712a81998ddbaf9ce1d55b153fc7cb71441ae1782143bb6b04b95d"),
    state = NORMAL,
    data = DATA_NORMAL(
      commitments = Commitments(
        localParams = Alice.channelParams.copy(
          channelKeyPath = KeyPath(Seq(1L, 2L, 3L, 4L, 5L)),
          defaultFinalScriptPubKey = hex"001459c9d053beb25049fd2d35d621f5c56fd4f2415d"
        ),
        remoteParams = RemoteParams(
          Bob.nodeParams.nodeId,
          dustLimitSatoshis = 546,
          maxHtlcValueInFlightMsat = UInt64(5000000000L),
          channelReserveSatoshis = 120000,
          htlcMinimumMsat = 1,
          toSelfDelay = 720,
          maxAcceptedHtlcs = 30,
          fundingPubKey = PublicKey(hex"02184615bf2294acc075701892d7bd8aff28d78f84330e8931102e537c8dfe92a3"),
          revocationBasepoint = Point(hex"020beeba2c3015509a16558c35b930bed0763465cf7a9a9bc4555fd384d8d383f6"),
          paymentBasepoint = Point(hex"02e63d3b87e5269d96f1935563ca7c197609a35a928528484da1464eee117335c5"),
          delayedPaymentBasepoint = Point(hex"033dea641e24e7ae550f7c3a94bd9f23d55b26a649c79cd4a3febdf912c6c08281"),
          htlcBasepoint = Point(hex"0274a89988063045d3589b162ac6eea5fa0343bf34220648e92a636b1c2468a434"),
          globalFeatures = hex"00",
          localFeatures = hex"82"
        ),
        channelFlags = 1.toByte,
        localCommit = LocalCommit(
          2,
          spec = CommitmentSpec(
            htlcs = Set(DirectedHtlc(
              direction = IN,
              add = UpdateAddHtlc(
                channelId = ByteVector32.fromValidHex("63c75c555d712a81998ddbaf9ce1d55b153fc7cb71441ae1782143bb6b04b95d"),
                id = 123,
                amountMsat = 24000,
                paymentHash = ByteVector32.fromValidHex("a3c1ec535f712a81998ddbaf9ce1d55b153fc7cb71441ae1782143bb6b04b95a"),
                cltvExpiry = 12,
                onionRoutingPacket = ByteVector32.Zeroes
              )
            )),
            feeratePerKw = 45000,
            toLocalMsat = 4000000000L,
            toRemoteMsat = 8000000000L
          ),
          publishableTxs = PublishableTxs(
            CommitTx(
              input = Transactions.InputInfo(
                outPoint = OutPoint(ByteVector32.fromValidHex("5db9046bbb432178e11a4471cbc73f155bd5e19cafdb8d99812a715d555cc763"), 1),
                txOut = TxOut(Satoshi(12000000), ByteVector.empty),
                redeemScript = ByteVector.empty
              ),
              tx = Transaction.read("0200000000010163c75c555d712a81998ddbaf9ce1d55b153fc7cb71441ae1782143bb6b04b95d0000000000a325818002bc893c0000000000220020ae8d04088ff67f3a0a9106adb84beb7530097b262ff91f8a9a79b7851b50857f00127a0000000000160014be0f04e9ed31b6ece46ca8c17e1ed233c71da0e9040047304402203b280f9655f132f4baa441261b1b590bec3a6fcd6d7180c929fa287f95d200f80220100d826d56362c65d09b8687ca470a31c1e2bb3ad9a41321ceba355d60b77b79014730440220539e34ab02cced861f9c39f9d14ece41f1ed6aed12443a9a4a88eb2792356be6022023dc4f18730a6471bdf9b640dfb831744b81249ffc50bd5a756ae85d8c6749c20147522102184615bf2294acc075701892d7bd8aff28d78f84330e8931102e537c8dfe92a3210367d50e7eab4a0ab0c6b92aa2dcf6cc55a02c3db157866b27a723b8ec47e1338152ae74f15a20")
            ),
            htlcTxsAndSigs = List.empty
          )
        ),
        remoteCommit = RemoteCommit(
          2,
          spec = CommitmentSpec(
            htlcs = Set(DirectedHtlc(
              direction = OUT,
              add = UpdateAddHtlc(
                channelId = ByteVector32.fromValidHex("63c75c555d712a81998ddbaf9ce1d55b153fc7cb71441ae1782143bb6b04b95d"),
                id = 123,
                amountMsat = 24000,
                paymentHash = ByteVector32.fromValidHex("a3c1ec535f712a81998ddbaf9ce1d55b153fc7cb71441ae1782143bb6b04b95a"),
                cltvExpiry = 12,
                onionRoutingPacket = ByteVector32.Zeroes
              )
            )),
            feeratePerKw = 45000,
            toLocalMsat = 4000000000L,
            toRemoteMsat = 8000000000L
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
        localNextHtlcId = 5,
        remoteNextHtlcId = 5,
        originChannels = Map(
          4200L -> Relayed(
            originChannelId = ByteVector32.fromValidHex("63c75c555d712a81998ddbaf9ce1d55b153fc7cb71441ae1782143bb6b04b95d"),
            originHtlcId = 4201,
            amountMsatIn = 5001,
            amountMsatOut = 5000
          )),
        remoteNextCommitInfo = Right(Point(hex"033dea641e24e7ae550f7c3a94bd9f23d55b26a649c79cd4a3febdf912c6c08281")),
        commitInput = Transactions.InputInfo(
          outPoint = OutPoint(ByteVector32.fromValidHex("5db9046bbb432178e11a4471cbc73f155bd5e19cafdb8d99812a715d555cc763"), 1),
          txOut = TxOut(Satoshi(12000000), ByteVector.empty),
          redeemScript = ByteVector.empty
        ),
        remotePerCommitmentSecrets = ShaChain.init,
        channelId = ByteVector32.fromValidHex("5db9046bbb432178e11a4471cbc73f155bd5e19cafdb8d99812a715d555cc763")
      ),
      shortChannelId = ShortChannelId("501x1x0"),
      buried = true,
      channelAnnouncement = None,
      channelUpdate = ChannelUpdate(
        signature = ByteVector.empty,
        chainHash = Alice.nodeParams.chainHash,
        shortChannelId = ShortChannelId("501x1x0"),
        timestamp = 1556526043L,
        messageFlags = 1.toByte,
        channelFlags = 0.toByte,
        cltvExpiryDelta = 144,
        htlcMinimumMsat = 1,
        feeBaseMsat = 1000,
        feeProportionalMillionths = 100,
        htlcMaximumMsat = Some(1200000)
      ),
      localShutdown = None,
      remoteShutdown = None
    )
  )

}
