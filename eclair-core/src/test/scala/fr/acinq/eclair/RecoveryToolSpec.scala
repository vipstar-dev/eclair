package fr.acinq.eclair

import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.io.{NodeURI, Peer, ReconnectWithCommitments}
import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.channel.DATA_NORMAL
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.collection.JavaConversions._

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

  test("Recovery tool should create the correct mock state channel data") {
    val switchboard = TestProbe()
    val nodeParams = TestConstants.Alice.nodeParams.copy(config = config)
    val kit = Kit(
      nodeParams,
      system,
      TestProbe().ref,
      TestProbe().ref,
      TestProbe().ref,
      TestProbe().ref,
      TestProbe().ref,
      switchboard.ref,
      TestProbe().ref,
      TestProbe().ref,
      new TestWallet()
    )

    val keyPath = KeyPath(Seq(1,2,3,4L))
    val remoteNodeUri = NodeURI.parse(s"${Bob.nodeParams.nodeId}@127.0.0.1:9735")
    val shortId = ShortChannelId("1x0x0")

    // set fees globally
    Globals.feeratesPerKw.set(FeeratesPerKw.single(123))

    RecoveryTool.doRecovery(kit, keyPath, remoteNodeUri, shortId)

    val connect = switchboard.expectMsgType[ReconnectWithCommitments]
    val stateData = connect.commitments.asInstanceOf[DATA_NORMAL]
    assert(connect.uri == remoteNodeUri)
    assert(stateData.shortChannelId == shortId)
    assert(stateData.commitments.localParams.channelKeyPath == keyPath)
    assert(stateData.commitments.localParams.nodeId == nodeParams.nodeId)
    assert(stateData.commitments.commitInput.outPoint.index == 0)
  }

}
