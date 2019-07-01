package fr.acinq.eclair

import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.io.{NodeURI, Peer, ReconnectWithCommitments}
import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.channel.DATA_NORMAL
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConversions._
import scala.concurrent.Await

class RecoveryToolSpec extends TestKit(ActorSystem("test")) with BitcoindService with FunSuiteLike with BeforeAndAfterAll {

  val commonConfig = ConfigFactory.parseMap(Map(
    "eclair.chain" -> "regtest",
    "eclair.bitcoind.rpcuser" -> "foo",
    "eclair.bitcoind.rpcpassword" -> "bar",
    "eclair.bitcoind.host" -> "localhost",
    "eclair.bitcoind.rpcport" -> 28332))
  val config = ConfigFactory.load(commonConfig).getConfig("eclair")

  override def beforeAll(): Unit = {
    startBitcoind()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  test("wait bitcoind ready") {
    waitForBitcoindReady()
  }

//  test("Recovery tool should create the correct mock state channel data") {
//    val switchboard = TestProbe()
//    val nodeParams = TestConstants.Alice.nodeParams.copy(config = config)
//    val kit = Kit(
//      nodeParams,
//      system,
//      TestProbe().ref,
//      TestProbe().ref,
//      TestProbe().ref,
//      TestProbe().ref,
//      TestProbe().ref,
//      switchboard.ref,
//      TestProbe().ref,
//      TestProbe().ref,
//      new TestWallet()
//    )
//
//    val bitcoinRpcClient = new BasicBitcoinJsonRPCClient(
//      user = config.getString("bitcoind.rpcuser"),
//      password = config.getString("bitcoind.rpcpassword"),
//      host = config.getString("bitcoind.host"),
//      port = config.getInt("bitcoind.rpcport")
//    )
//
//    val bitcoinClient = new ExtendedBitcoinClient(bitcoinRpcClient)
//    val block = Await.result(for {
//      genesisHash <- bitcoinClient.getBlockHash(1)
//      genesisBlock <- bitcoinClient.getBlock(genesisHash)
//    } yield genesisBlock, 60 seconds)
//
//    val tx = block.tx.head
//    val keyPath = KeyPath(Seq(1,2,3,4L))
//
//    val backup = StaticBackup(
//      channelId = ByteVector32.Zeroes,
//      fundingTxId = tx.txid,
//      fundingOutputIndex = 0,
//      channelKeyPath = keyPath,
//      remoteNodeId = Bob.nodeParams.nodeId
//    )
//
//    val remoteNodeUri = NodeURI.parse(s"${Bob.nodeParams.nodeId}@127.0.0.1:9735")
//
//    // set fees globally
//    Globals.feeratesPerKw.set(FeeratesPerKw.single(123))
//
//    RecoveryTool.doRecovery(kit, backup, remoteNodeUri)
//
//    val connect = switchboard.expectMsgType[ReconnectWithCommitments]
//    val stateData = connect.commitments.asInstanceOf[DATA_NORMAL]
//    assert(connect.uri == remoteNodeUri)
//    assert(stateData.commitments.localParams.channelKeyPath == keyPath)
//    assert(stateData.commitments.localParams.nodeId == nodeParams.nodeId)
//    assert(stateData.commitments.remoteParams.nodeId == Bob.nodeParams.nodeId)
//    assert(stateData.commitments.commitInput.outPoint.index == 0)
//    assert(stateData.commitments.commitInput.outPoint.txid == tx.txid)
//  }

}
