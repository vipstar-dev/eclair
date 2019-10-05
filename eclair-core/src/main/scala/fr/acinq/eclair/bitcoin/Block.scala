package fr.acinq.bitcoin

import java.io.{InputStream, OutputStream}
import java.math.BigInteger
import java.nio.ByteOrder

import fr.acinq.bitcoin.Protocol._
import scodec.bits._


object BlockHeader extends BtcSerializer[BlockHeader] {
  override def read(input: InputStream, protocolVersion: Long): BlockHeader = {
    val version = uint32(input)
    val hashPreviousBlock = hash(input)
    val hashMerkleRoot = hash(input)
    val time = uint32(input)
    val bits = uint32(input)
    val nonce = uint32(input)

    // qtum
    val hashStateRoot = hash(input)
    val hashUTXORoot = hash(input)
    val prev_stake_hash = hash(input)
    val prev_stake_n = uint32(input)
    val sizeVchSig = uint8(input)
    val vchSig = bytes(input, sizeVchSig)

    BlockHeader(version, hashPreviousBlock, hashMerkleRoot, time, bits, nonce, hashStateRoot, hashUTXORoot, prev_stake_hash, prev_stake_n, sizeVchSig, vchSig.toArray)
  }

  override def write(input: BlockHeader, out: OutputStream, protocolVersion: Long) = {
    writeUInt32(input.version.toInt, out)

    writeBytes(input.hashPreviousBlock.toArray, out)
    writeBytes(input.hashMerkleRoot.toArray, out)

    writeUInt32(input.time.toInt, out)
    writeUInt32(input.bits.toInt, out)
    writeUInt32(input.nonce.toInt, out)

    writeBytes(input.hashStateRoot.toArray, out)
    writeBytes(input.hashUTXORoot.toArray, out)

    writeBytes(input.prev_stake_hash.toArray, out)
    writeUInt32(input.prev_stake_n.toInt, out)

    writeUInt8(input.sizeVchSig.toInt, out)
    writeBytes(input.vchSig, out)
  }

  def getDifficulty(header: BlockHeader): BigInteger = {
    val nsize = header.bits >> 24
    val isneg = header.bits & 0x00800000
    val nword = header.bits & 0x007fffff
    val result = if (nsize <= 3)
      BigInteger.valueOf(nword).shiftRight(8 * (3 - nsize.toInt))
    else
      BigInteger.valueOf(nword).shiftLeft(8 * (nsize.toInt - 3))
    if (isneg != 0) result.negate() else result
  }

  /**
    *
    * @param bits difficulty target
    * @return the amount of work represented by this difficulty target, as displayed
    *         by bitcoin core
    */
  def blockProof(bits: Long): Double = {
    val (target, negative, overflow) = decodeCompact(bits)
    if (target == BigInteger.ZERO || negative || overflow) 0.0 else {
      val work = BigInteger.valueOf(2).pow(256).divide(target.add(BigInteger.ONE))
      work.doubleValue()
    }
  }

  def blockProof(header: BlockHeader): Double = blockProof(header.bits)

  /**
    * Proof of work: hash(header) <= target difficulty
    *
    * @param header block header
    * @return true if the input block header validates its expected proof of work
    */
  def checkProofOfWork(header: BlockHeader): Boolean = {
    val (target, _, _) = decodeCompact(header.bits)
    val hash = new BigInteger(1, header.blockId.toArray)
    hash.compareTo(target) <= 0
  }

  def calculateNextWorkRequired(lastHeader: BlockHeader, lastRetargetTime: Long): Long = {
    var actualTimespan = lastHeader.time - lastRetargetTime
    val targetTimespan = 14 * 24 * 60 * 60 // two weeks
    if (actualTimespan < targetTimespan / 4) actualTimespan = targetTimespan / 4
    if (actualTimespan > targetTimespan * 4) actualTimespan = targetTimespan * 4

    var (target, false, false) = decodeCompact(lastHeader.bits)
    target = target.multiply(BigInteger.valueOf(actualTimespan))
    target = target.divide(BigInteger.valueOf(targetTimespan))

    val powLimit = new BigInteger(1, hex"00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffff".toArray)
    target = target.min(powLimit)
    encodeCompact(target)
  }

}



/**
  *
  * @param version           Block version information, based upon the software version creating this block
  * @param hashPreviousBlock The hash value of the previous block this particular block references. Please not that
  *                          this hash is not reversed (as opposed to Block.hash)
  * @param hashMerkleRoot    The reference to a Merkle tree collection which is a hash of all transactions related to this block
  * @param time              A timestamp recording when this block was created (Will overflow in 2106[2])
  * @param bits              The calculated difficulty target being used for this block
  * @param nonce             The nonce used to generate this block… to allow variations of the header and compute different hashes
  * @param hashStateRoot
  * @param hashUTXORoot
  * @param prev_stake_hash
  * @param prev_stake_n
  * @param sizeVchSig
  * @param vchSig
  */
case class BlockHeader(version: Long, hashPreviousBlock: ByteVector32, hashMerkleRoot: ByteVector32, time: Long, bits: Long, nonce: Long, hashStateRoot: ByteVector32, hashUTXORoot: ByteVector32, prev_stake_hash: ByteVector32, prev_stake_n: Long, sizeVchSig: Int, vchSig: Array[Byte]) extends BtcSerializable[BlockHeader] {
  require(hashPreviousBlock.length == 32, "hashPreviousBlock must be 32 bytes")
  require(hashMerkleRoot.length == 32, "hashMerkleRoot must be 32 bytes")
  lazy val hash: ByteVector32 = Crypto.hash256(BlockHeader.write(this))

  // hash is reversed here (same as tx id)
  lazy val blockId = hash.reverse

  override def serializer: BtcSerializer[BlockHeader] = BlockHeader
}

object Block extends BtcSerializer[Block] {
  override def read(input: InputStream, protocolVersion: Long): Block = {
    val header = BlockHeader.read(input)
    Block(header, readCollection[Transaction](input, protocolVersion))
  }

  override def write(input: Block, out: OutputStream, protocolVersion: Long) = {
    BlockHeader.write(input.header, out)
    writeCollection(input.tx, out, protocolVersion)
  }

  override def validate(input: Block): Unit = {
    BlockHeader.validate(input.header)
    require(input.header.hashMerkleRoot === MerkleTree.computeRoot(input.tx.map(_.hash)), "invalid block:  merkle root mismatch")
    require(input.tx.map(_.txid).toSet.size == input.tx.size, "invalid block: duplicate transactions")
    input.tx.foreach(Transaction.validate)
  }

  def blockProof(block: Block): Double = BlockHeader.blockProof(block.header)

  // genesis blocks
  val LivenetGenesisBlock = {
    val script = OP_PUSHDATA(writeUInt32(488804799L)) :: OP_PUSHDATA(hex"04") :: OP_PUSHDATA(ByteVector("Sep 02, 2017 Bitcoin breaks $5,000 in latest price frenzy".getBytes("UTF-8"))) :: Nil
    val scriptPubKey = OP_PUSHDATA(hex"040d61d8653448c98731ee5fffd303c15e71ec2057b77f11ab3601979728cdaff2d68afbba14e4fa0bc44f2072b0b23ef63717f8cdfbe58dcd33f32b6afe98741a") :: OP_CHECKSIG :: Nil
    Block(
      BlockHeader(version = 1, hashPreviousBlock = ByteVector32.Zeroes, hashMerkleRoot = ByteVector32(hex"6db905142382324db417761891f2d2f355ea92f27ab0fc35e59e90b50e0534ed"), time = 1504695029, bits = 0x1f00ffff, nonce = 8026361,
        hashStateRoot = ByteVector32(hex"e965ffd002cd6ad0e2dc402b8044de833e06b23127ea8c3d80aec91410771495"),
        hashUTXORoot = ByteVector32(hex"56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"),
          prev_stake_hash = ByteVector32.Zeroes, prev_stake_n = 0xffffffff, sizeVchSig = 0, vchSig = Array()),
      List(
        Transaction(version = 1,
          txIn = List(TxIn.coinbase(script)),
          txOut = List(TxOut(amount = 50 btc, publicKeyScript = scriptPubKey)),
          lockTime = 0))
    )
  }

  val TestnetGenesisBlock = LivenetGenesisBlock.copy(header = LivenetGenesisBlock.header.copy(time = 1504695029, nonce = 7349697))

  val RegtestGenesisBlock = LivenetGenesisBlock.copy(header = LivenetGenesisBlock.header.copy(bits = 0x207fffffL, nonce = 17, time = 1504695029))

  /**
    * Proof of work: hash(block) <= target difficulty
    *
    * @param block
    * @return true if the input block validates its expected proof of work
    */
  def checkProofOfWork(block: Block): Boolean = {
    val (target, _, _) = decodeCompact(block.header.bits)
    val hash = new BigInteger(1, block.blockId.toArray)
    hash.compareTo(target) <= 0
  }

  /**
    *
    * @param tx coinbase transaction
    * @return the witness reserved value included in the input of this tx if any
    */
  def witnessReservedValue(tx: Transaction): Option[ByteVector] = tx.txIn(0).witness match {
    case ScriptWitness(Seq(nonce)) if nonce.length == 32 => Some(nonce)
    case _ => None
  }

  /**
    *
    * @param tx coinbase transaction
    * @return the witness commitment included in this transaction, if any
    */
  def witnessCommitment(tx: Transaction): Option[ByteVector32] = tx.txOut.map(o => Script.parse(o.publicKeyScript)).reverse.collectFirst {
    // we've reversed the outputs because if there are more than one scriptPubKey matching the pattern, the one with
    // the highest output index is assumed to be the commitment.
    case OP_RETURN :: OP_PUSHDATA(commitmentHeader, _) :: Nil if commitmentHeader.length == 36 && Protocol.uint32(commitmentHeader.take(4).toArray, ByteOrder.BIG_ENDIAN) == 0xaa21a9edL => ByteVector32(commitmentHeader.takeRight(32))
  }

  /**
    * Checks the witness commitment of a block
    *
    * @param block block
    * @return true if the witness commitment for this block is valid, or if this block does not contain a witness commitment
    *         nor any segwit transactions.
    */
  def checkWitnessCommitment(block: Block): Boolean = {
    val coinbase = block.tx.head
    (witnessReservedValue(coinbase), witnessCommitment(coinbase)) match {
      case (Some(nonce), Some(commitment)) =>
        val rootHash = MerkleTree.computeRoot(ByteVector32.Zeroes +: block.tx.tail.map(tx => tx.whash))
        val commitmentHash = Crypto.hash256(rootHash ++ nonce)
        commitment == commitmentHash
      case _ if block.tx.exists(_.hasWitness) => false // block has segwit transactions but no witness commitment
      case _ => true
    }
  }
}

/**
  * Bitcoin block
  *
  * @param header block header
  * @param tx     transactions
  */
case class Block(header: BlockHeader, tx: Seq[Transaction]) extends BtcSerializable[Block] {
  lazy val hash = header.hash

  lazy val blockId = header.blockId

  override def serializer: BtcSerializer[Block] = Block
}
