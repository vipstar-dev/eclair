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

import java.net.InetSocketAddress
import java.sql.DriverManager

import akka.actor.{ActorSystem, Props}
import fr.acinq.bitcoin.{Block, MnemonicCode, Satoshi}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.WalletParameters
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb
import fr.acinq.eclair.blockchain.electrum.{ElectrumClientPool, ElectrumWallet}

object ElectrumDebug extends App {

  import scala.concurrent.ExecutionContext.Implicits.global

  val mnemonics  = args
  val seed = MnemonicCode.toSeed(mnemonics, "")
  val system = ActorSystem()
  val electrumClient = system.actorOf(Props(new ElectrumClientPool(Set(ElectrumServerAddress(new InetSocketAddress("electrum.acinq.co", 50002), SSL.STRICT)))))
  val wallet = system.actorOf(Props(new ElectrumWallet(seed, electrumClient, WalletParameters(Block.LivenetGenesisBlock.hash, new SqliteWalletDb(DriverManager.getConnection("jdbc:sqlite::memory:")), minimumFee = Satoshi(5000)))), "wallet")
  Thread.sleep(Long.MaxValue)
}
