package scorex.transaction

import java.util.Arrays

import com.google.common.primitives.{Bytes, Ints, Longs}
import play.api.libs.json.Json
import scorex.account.{Account, PrivateKeyAccount, PublicKeyAccount}
import scorex.crypto.{Base58, Crypto}
import scorex.database.blockchain.PrunableBlockchainStorage
import scorex.transaction.Transaction.TransactionType

case class PaymentTransaction(sender: PublicKeyAccount,
                              override val recipient: Account,
                              override val amount: BigDecimal,
                              override val fee: BigDecimal,
                              override val timestamp: Long,
                              override val signature: Array[Byte])
  extends Transaction(TransactionType.PAYMENT_TRANSACTION, recipient, amount, fee, timestamp, signature) {

  import scorex.transaction.PaymentTransaction._
  import scorex.transaction.Transaction._

  override lazy val dataLength = TYPE_LENGTH + BASE_LENGTH

  override def toJson() = getJsonBase() ++ Json.obj(
    "sender" -> sender.address,
    "recipient" -> recipient.address,
    "amount" -> amount
  )

  override def toBytes() = {
    //WRITE TYPE
    val typeBytes = Array(TypeId.toByte)

    //WRITE TIMESTAMP
    val timestampBytes = Bytes.ensureCapacity(Longs.toByteArray(timestamp), TIMESTAMP_LENGTH, 0)

    //WRITE AMOUNT
    val amountBytes = amount.toBigInt().toByteArray
    val amountFill = new Array[Byte](AMOUNT_LENGTH - amountBytes.length)

    //WRITE FEE
    val feeBytes = fee.toBigInt().toByteArray
    val feeFill = new Array[Byte](FEE_LENGTH - feeBytes.length)

    Bytes.concat(typeBytes, timestampBytes, sender.publicKey,
      Base58.decode(recipient.address), Bytes.concat(amountFill, amountBytes),
      Bytes.concat(feeFill, feeBytes, signature)
    )
  }

  override def isSignatureValid() = {
    val data = signatureData(sender, recipient, amount, fee, timestamp)
    Crypto.verify(signature, data, sender.publicKey)
  }

  override def isValid() =
    if (!Crypto.isValidAddress(recipient.address)) {
      ValidationResult.INVALID_ADDRESS //CHECK IF RECIPIENT IS VALID ADDRESS
    } else if (PrunableBlockchainStorage.balance(sender.address) < amount + fee) {
      ValidationResult.NO_BALANCE //CHECK IF SENDER HAS ENOUGH MONEY
    } else if (amount <= BigDecimal(0)) {
      ValidationResult.NEGATIVE_AMOUNT //CHECK IF AMOUNT IS POSITIVE
    } else if (fee <= BigDecimal(0)) {
      ValidationResult.NEGATIVE_FEE //CHECK IF FEE IS POSITIVE
    } else ValidationResult.VALIDATE_OKE

  override def getCreator() = Some(sender)

  override def involvedAmount(account: Account) = {
    val address = account.address

    if (address.equals(sender.address) && address.equals(recipient.address)) {
      BigDecimal(0).setScale(8) - fee
    } else if (address.equals(sender.address)) {
      BigDecimal(0).setScale(8) - amount - fee
    } else if (address.equals(recipient.address)) {
      amount
    } else BigDecimal(0)
  }

  override def balanceChanges(): Map[Option[Account], BigDecimal] =
    Map(Some(sender) -> -amount, Some(recipient) -> amount, None -> fee)
}


object PaymentTransaction {

  import scorex.transaction.Transaction._

  private val SENDER_LENGTH = 32
  private val FEE_LENGTH = 8
  private val SIGNATURE_LENGTH = 64
  private val BASE_LENGTH = TIMESTAMP_LENGTH + SENDER_LENGTH + RECIPIENT_LENGTH + AMOUNT_LENGTH + FEE_LENGTH + SIGNATURE_LENGTH


  def Parse(data: Array[Byte]) = {
    require(data.length >= BASE_LENGTH, "Data does not match base length")

    var position = 0

    //READ TIMESTAMP
    val timestampBytes = Arrays.copyOfRange(data, position, position + TIMESTAMP_LENGTH)
    val timestamp = Longs.fromByteArray(timestampBytes)
    position += TIMESTAMP_LENGTH

    //READ SENDER
    val senderBytes = Arrays.copyOfRange(data, position, position + SENDER_LENGTH)
    val sender = new PublicKeyAccount(senderBytes)
    position += SENDER_LENGTH

    //READ RECIPIENT
    val recipientBytes = Arrays.copyOfRange(data, position, position + RECIPIENT_LENGTH)
    val recipient = new Account(Base58.encode(recipientBytes))
    position += RECIPIENT_LENGTH

    //READ AMOUNT
    val amountBytes = Arrays.copyOfRange(data, position, position + AMOUNT_LENGTH)
    val amount = BigDecimal(BigInt(amountBytes), 8)
    position += AMOUNT_LENGTH

    //READ FEE
    val feeBytes = Arrays.copyOfRange(data, position, position + FEE_LENGTH)
    val fee = BigDecimal(BigInt(feeBytes), 8)
    position += FEE_LENGTH

    //READ SIGNATURE
    val signatureBytes = Arrays.copyOfRange(data, position, position + SIGNATURE_LENGTH)

    new PaymentTransaction(sender, recipient, amount, fee, timestamp, signatureBytes)
  }

  def generateSignature(sender: PrivateKeyAccount, recipient: Account,
                        amount: BigDecimal, fee: BigDecimal, timestamp: Long): Array[Byte] = {
    Crypto.sign(sender, signatureData(sender, recipient, amount, fee, timestamp))
  }

  private def signatureData(sender: PublicKeyAccount, recipient: Account,
                            amount: BigDecimal, fee: BigDecimal, timestamp: Long): Array[Byte] = {
    //WRITE TYPE
    val typeBytes = Bytes.ensureCapacity(Ints.toByteArray(TransactionType.PAYMENT_TRANSACTION.id), TYPE_LENGTH, 0)

    //WRITE TIMESTAMP
    val timestampBytes = Bytes.ensureCapacity(Longs.toByteArray(timestamp), TIMESTAMP_LENGTH, 0)

    //WRITE AMOUNT
    val amountBytes = amount.toBigInt().toByteArray
    val amountFill = new Array[Byte](AMOUNT_LENGTH - amountBytes.length)

    //WRITE FEE
    val feeBytes = fee.toBigInt().toByteArray
    val feeFill = new Array[Byte](FEE_LENGTH - feeBytes.length)

    Bytes.concat(typeBytes, timestampBytes, sender.publicKey,
      Base58.decode(recipient.address), Bytes.concat(amountFill, amountBytes), Bytes.concat(feeFill, feeBytes))
  }
}