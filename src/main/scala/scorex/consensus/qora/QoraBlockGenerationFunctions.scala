package scorex.consensus.qora

import com.google.common.primitives.{Bytes, Longs}
import ntp.NTP
import scorex.account.PrivateKeyAccount
import scorex.block.{Block, BlockStub}
import scorex.consensus.BlockGenerationFunctions
import scorex.crypto.Crypto
import settings.Constants

//a lot of asInstanceOf[QoraBlockGenerationData] in the code, not type-safe
object QoraBlockGenerationFunctions extends BlockGenerationFunctions {
  private val RETARGET = 10
  private val MIN_BALANCE = 1L
  private val MAX_BALANCE = 10000000000L
  private val MIN_BLOCK_TIME = 1 * 60
  private val MAX_BLOCK_TIME = 5 * 60

  override protected def generateNextBlock(account: PrivateKeyAccount, lastBlock: Block): Option[BlockStub] = {
    require(account.generatingBalance > BigDecimal(0), "Zero generating balance in generateNextBlock")

    val signature = calculateSignature(lastBlock, account)
    val hash = Crypto.sha256(signature)
    val hashValue = BigInt(1, hash)

    //CALCULATE ACCOUNT TARGET
    val targetBytes = Array.fill(32)(Byte.MaxValue)
    val baseTarget = BigInt(getBaseTarget(getNextBlockGeneratingBalance(lastBlock)))
    //MULTIPLY TARGET BY USER BALANCE
    val target = BigInt(1, targetBytes) / baseTarget * account.generatingBalance.toBigInt()

    //CALCULATE GUESSES
    val guesses = hashValue / target + 1

    //CALCULATE TIMESTAMP
    val timestampRaw = guesses * 1000 + lastBlock.timestamp

    //CHECK IF NOT HIGHER THAN MAX LONG VALUE
    val timestamp = if (timestampRaw > Long.MaxValue) Long.MaxValue else timestampRaw.longValue()

    if (timestamp <= NTP.getTime) {
      Some(BlockStub(Block.Version, lastBlock.signature, timestamp, account,
        new QoraBlockGenerationData(getNextBlockGeneratingBalance(lastBlock), signature).asInstanceOf[Constants.ConsensusAlgo.kernelData]))
    } else None
  }

  private def blockGeneratingBalance(block: Block) =
    block.generationData.asInstanceOf[QoraBlockGenerationData].generatingBalance

  private def blockGeneratorSignature(block: Block) =
    block.generationData.asInstanceOf[QoraBlockGenerationData].generatorSignature

  def getNextBlockGeneratingBalance(block: Block): Long = {
    if (block.height().get % RETARGET == 0) {
      //GET FIRST BLOCK OF TARGET
      val firstBlock = (1 to RETARGET - 1).foldLeft(block) { case (bl, _) => bl.parent().get }

      //CALCULATE THE GENERATING TIME FOR LAST 10 BLOCKS
      val generatingTime = block.timestamp - firstBlock.timestamp

      //CALCULATE EXPECTED FORGING TIME
      val expectedGeneratingTime = getBlockTime(blockGeneratingBalance(block)) * RETARGET * 1000

      //CALCULATE MULTIPLIER
      val multiplier = expectedGeneratingTime / generatingTime.toDouble

      //CALCULATE NEW GENERATING BALANCE
      val generatingBalance = (blockGeneratingBalance(block) * multiplier).toLong
      minMaxBalance(generatingBalance)
    } else blockGeneratingBalance(block)
  }

  def getBaseTarget(generatingBalance: Long): Long = minMaxBalance(generatingBalance) * getBlockTime(generatingBalance)

  def getBlockTime(generatingBalance: Long): Long = {
    val percentageOfTotal = minMaxBalance(generatingBalance) / MAX_BALANCE.toDouble
    (MIN_BLOCK_TIME + ((MAX_BLOCK_TIME - MIN_BLOCK_TIME) * (1 - percentageOfTotal))).toLong
  }

  private def calculateSignature(solvingBlock: Block, account: PrivateKeyAccount) = {
    //PARENT GENERATOR SIGNATURE
    val generatorSignature = blockGeneratorSignature(solvingBlock)

    val genBalanceBytes = Longs.toByteArray(getNextBlockGeneratingBalance(solvingBlock))

    require(account.publicKey.length == Block.GENERATOR_LENGTH)
    Crypto.sign(account, Bytes.concat(generatorSignature, genBalanceBytes, account.publicKey))
  }

  private def minMaxBalance(generatingBalance: Long) =
    if (generatingBalance < MIN_BALANCE) MIN_BALANCE
    else if (generatingBalance > MAX_BALANCE) MAX_BALANCE
    else generatingBalance
}
