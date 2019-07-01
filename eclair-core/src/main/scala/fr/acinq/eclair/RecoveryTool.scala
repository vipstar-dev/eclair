package fr.acinq.eclair


import java.io.{File, FileWriter}

import akka.util.Timeout
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, OutPoint, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.{KeyManager, LocalKeyManager, ShaChain}
import fr.acinq.eclair.io.{NodeURI, Peer, ReconnectWithCommitments}
import fr.acinq.eclair.transactions.{CommitmentSpec, Scripts, Transactions}
import fr.acinq.eclair.transactions.Transactions.{CommitTx, InputInfo}
import scodec.bits.ByteVector
import akka.pattern._
import fr.acinq.eclair.api.JsonSupport
import grizzled.slf4j.Logging

import concurrent.duration._
import scala.compat.Platform
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Random, Success, Try}
import scodec.bits._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source
import JsonSupport.formats
import JsonSupport.serialization
import fr.acinq.eclair.wire.ChannelUpdate

object RecoveryTool extends Logging {

  case class StaticBackup(fundingTxid: ByteVector32, fundingOutputIndex: Long, isFunder: Boolean, remoteNodeId: PublicKey, remoteFundingPubkey_opt:Option[PublicKey])

  private lazy val scanner = new java.util.Scanner(System.in).useDelimiter("\\n")

  def interactiveRecovery(appKit: Kit): Unit = {

    print(s"\n ### Welcome to the eclair recovery tool ### \n")

    val nodeUri = getInput("Please insert the URI of the target node: ", NodeURI.parse(_))
    if(!getInput("Do you have the backup y/n? ", getBoolInput)){
      val shortId = getInput("Please insert the fund: ", ShortChannelId(_))
      println(s"### Attempting channel recovery now - good luck! ###")
      //doRecovery(appKit, shortId, nodeUri)
    } else {
      val backup = getInput("Please insert the absolute path of the backup file: ", path => {
        val source = Source.fromFile(path)
        val fileContent = source.mkString
        val s = serialization.read[StaticBackup](fileContent)
        source.close()
        s
      })
      println(s"### Attempting channel recovery now - good luck! ###")
      doRecovery(appKit, backup, nodeUri)
    }

  }

  def storeBackup(nodeParams: NodeParams, channelData: HasCommitments) = Future {

    val backup = StaticBackup(
      fundingTxid = channelData.commitments.commitInput.outPoint.txid,
      fundingOutputIndex = channelData.commitments.commitInput.outPoint.index,
      isFunder = channelData.commitments.localParams.isFunder,
      remoteNodeId = channelData.commitments.remoteParams.nodeId,
      remoteFundingPubkey_opt = Some(channelData.commitments.remoteParams.fundingPubKey)
    )

    if (nodeParams.db.dbDir.isEmpty) {
      logger.warn(s"No database folder defined, skipping static backup")
    }

    nodeParams.db.dbDir.foreach { dbDir =>
      val backupDir = new File(dbDir, "channel-backups")
      if (!backupDir.exists()) backupDir.mkdir()

      val channelBackup = new File(backupDir, "backup_" + backup.fundingTxid.toHex + ".json")
      val writer = new FileWriter(channelBackup)
      writer.write(serialization.writePretty(backup))
      writer.close()
      logger.info(s"Created static backup: ${channelBackup.getAbsolutePath}")
    }

  }

  private def getBoolInput = { in: String =>
    in match {
      case "y" | "yes" => true
      case "n" | "no"  => false
      case _ => throw new IllegalArgumentException("Please answer y/n")
    }
  }

  private def getInput[T](msg: String, parse: String => T): T = {
    do {
      print(msg)
      Try(parse(scanner.next())) match {
        case Success(someT) => return someT
        case Failure(thr) => println(s"Error: ${thr.getMessage}")
      }
    } while (true)

    throw new IllegalArgumentException("Unable to get input")
  }

  // FUNDER = m/47'/2'/SHA256(funding_tx.input[0].outpoint)/0
  // FUNDEE_0 = m/47'/2'/SHA256(blockchain_height || counter)/2
  // FUNDEE_1 = m/47'/2'/SHA256(funding_tx.output[channel_output_index].scriptPubkey)/1
  def doRecovery(appKit: Kit, backup: StaticBackup, uri: NodeURI): Future[Unit] = {

    implicit val timeout = Timeout(10 minutes)
    implicit val shttp = OkHttpFutureBackend()

    val bitcoinRpcClient = new BasicBitcoinJsonRPCClient(
      user = appKit.nodeParams.config.getString("bitcoind.rpcuser"),
      password = appKit.nodeParams.config.getString("bitcoind.rpcpassword"),
      host = appKit.nodeParams.config.getString("bitcoind.host"),
      port = appKit.nodeParams.config.getInt("bitcoind.rpcport")
    )

    val bitcoinClient = new ExtendedBitcoinClient(bitcoinRpcClient)

    val (fundingTx, blockHeight, finalAddress, isFundingSpendable) = Await.result(for {
      Some(blockHash) <- bitcoinClient.getTxBlockHash(backup.fundingTxid.toHex)
      block <- bitcoinClient.getBlock(ByteVector32.fromValidHex(blockHash))
      height <- bitcoinClient.getBlockHeight(ByteVector32.fromValidHex(blockHash))
      Some(funding) = block.tx.find(_.txid === backup.fundingTxid)
      isSpendable <- bitcoinClient.isTransactionOutputSpendable(funding.txid.toHex, backup.fundingOutputIndex.toInt, includeMempool = true)
      address <- new BitcoinCoreWallet(bitcoinRpcClient).getFinalAddress
    } yield (funding, height, address, isSpendable), 60 seconds)

    if (!isFundingSpendable) {
      logger.info(s"Sorry but the funding tx has been spent, the channel has been closed")
      return Future.successful(())
    }

    val finalScriptPubkey = Script.write(addressToPublicKeyScript(finalAddress, appKit.nodeParams.chainHash))
    val channelId = fr.acinq.eclair.toLongId(fundingTx.hash, backup.fundingOutputIndex.toInt)

    val inputInfo = Transactions.InputInfo(
      outPoint = OutPoint(fundingTx.hash, backup.fundingOutputIndex),
      txOut = fundingTx.txOut(backup.fundingOutputIndex.toInt),
      redeemScript = ByteVector.empty
    )

    val channelKeyPath: Either[KeyPath, KeyPathFundee] = backup.isFunder match {
      case true   => Left(LocalKeyManager.makeChannelKeyPathFunder(fundingTx.txIn.head.outPoint.hash))
      case false  =>
        val counter = findCounterValue(appKit.nodeParams, blockHeight, fundingTx.txOut(backup.fundingOutputIndex.toInt).publicKeyScript, backup.remoteFundingPubkey_opt.get) getOrElse {
          throw new IllegalArgumentException(s"Could not compute counter for backup=$backup")
        }
        Right(KeyPathFundee(
          fundingKeyPath = LocalKeyManager.makeChannelKeyPathFundeePubkey(blockHeight, counter),
          pointsKeyPath = LocalKeyManager.makeChannelKeyPathFundee(fundingTx.txOut(backup.fundingOutputIndex.toInt).publicKeyScript)
        ))
    }

    logger.info(s"Recovery using: channelId=$channelId finalScriptPubKey=$finalAddress remotePeer=${uri.nodeId} funder=${backup.isFunder}")
    val commitments = makeDummyCommitment(appKit.nodeParams.keyManager, channelKeyPath, uri.nodeId, appKit.nodeParams.nodeId, channelId, inputInfo, finalScriptPubkey, appKit.nodeParams.chainHash)
    (appKit.switchboard ? ReconnectWithCommitments(uri, commitments)).mapTo[Unit]
  }

  /**
    * This method tries to find the correct value that was used for the counter when opening the channel, it explores the range (0,10)
    * and up to 10 blocks before the given blockHeight (the height that was used to make the fundingKeyPath can a bit behind the actual height
    * where the funding was mined)
    * @param nodeParams
    * @param blockHeight
    * @param scriptPubkey
    * @param remoteFundingPubkey
    * @return
    */
  def findCounterValue(nodeParams: NodeParams, blockHeight: Long, scriptPubkey: ByteVector, remoteFundingPubkey: PublicKey): Option[Int] = {
    (0 until 10).find { i =>
      (0 until 10).exists { j =>
        val fundingKeyPath = LocalKeyManager.makeChannelKeyPathFundeePubkey(blockHeight - i, j)
        val localFundingPubkey = nodeParams.keyManager.fundingPublicKey(fundingKeyPath).publicKey
        val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey, remoteFundingPubkey)))
        fundingPubkeyScript == scriptPubkey
      }
    }
  }

  /**
    * This creates the necessary data to simulate a channel in state NORMAL, it contains dummy "points" and "indexes", as well as a dummy channel_update.
    */
  def makeDummyCommitment(
                           keyManager: KeyManager,
                           channelKeyPath: Either[KeyPath, KeyPathFundee],
                           remoteNodeId: PublicKey,
                           localNodeId: PublicKey,
                           channelId: ByteVector32,
                           commitInput: InputInfo,
                           finalScriptPubkey: ByteVector,
                           chainHash: ByteVector32
                         ) = DATA_NORMAL(
    commitments = Commitments(
      localParams = LocalParams(
        nodeId = localNodeId,
        channelKeyPath = channelKeyPath,
        dustLimitSatoshis = 0,
        maxHtlcValueInFlightMsat = UInt64(0),
        channelReserveSatoshis = 0,
        toSelfDelay = 0,
        htlcMinimumMsat = 0,
        maxAcceptedHtlcs = 0,
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
        fundingPubKey = keyManager.fundingPublicKey(randomKeyPath).publicKey,
        revocationBasepoint = randomPoint(chainHash),
        paymentBasepoint = randomPoint(chainHash),
        delayedPaymentBasepoint = randomPoint(chainHash),
        htlcBasepoint = randomPoint(chainHash),
        globalFeatures = hex"00",
        localFeatures = hex"00"
      ),
      channelFlags = 1.toByte,
      localCommit = LocalCommit(
        0,
        spec = CommitmentSpec(
          htlcs = Set(),
          feeratePerKw = Globals.feeratesPerKw.get().blocks_6,
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
        0,
        spec = CommitmentSpec(
          htlcs = Set(),
          feeratePerKw = Globals.feeratesPerKw.get().blocks_6,
          toLocalMsat = 0,
          toRemoteMsat = 0
        ),
        txid = ByteVector32.Zeroes,
        remotePerCommitmentPoint = randomPoint(chainHash)
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
      remoteNextCommitInfo = Right(randomPoint(chainHash)),
      commitInput = commitInput,
      remotePerCommitmentSecrets = ShaChain.init,
      channelId = channelId
    ),
    shortChannelId = ShortChannelId("123x1x0"),
    buried = true,
    channelAnnouncement = None,
    channelUpdate = ChannelUpdate(
      signature = ByteVector64.Zeroes,
      chainHash = chainHash,
      shortChannelId = ShortChannelId("123x1x0"),
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

  private def randomPoint(chainHash: ByteVector32) = {
    val keyManager = new LocalKeyManager(seed = randomBytes(32), chainHash)
    val keyPath = randomKeyPath()
    keyManager.commitmentPoint(keyPath, Random.nextLong().abs)
  }

  private def randomKeyPath() = KeyPath(Seq(
    Random.nextLong().abs,
    Random.nextLong().abs,
    Random.nextLong().abs,
    Random.nextLong().abs
  ))

}
