package fr.acinq.eclair

import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.wire.ChannelCodecs._
import scodec.bits.ByteVector
import scala.util.{Failure, Success, Try}

object RecoveryTool {

  private lazy val scanner = new java.util.Scanner(System.in).useDelimiter("\\n")

  def interactiveRecovery(appKit: Kit): Unit = {

    print(s"\n ### Welcome to the eclair recovery tool ### \n")

    val nodeUri = getInput[NodeURI]("Please insert the URI of the target node:", s => NodeURI.parse(s))
    val keyPath = getInput[KeyPath]("Pleas insert the KeyPathSerizlized", raw => {
      keyPathCodec.decodeValue(ByteVector.fromValidHex(raw).toBitVector).require
    })
    val shortChannelId = getInput[ShortChannelId]("Please insert the short channel Id:", s => ShortChannelId(s))

    println(s"### Attempting channel recovery now ###")
    val eclair = new EclairImpl(appKit)
    eclair.doRecovery(keyPath, nodeUri, shortChannelId)
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

}
