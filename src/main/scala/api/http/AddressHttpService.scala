package api.http

import java.nio.charset.StandardCharsets

import play.api.libs.json.Json
import scorex.account.PublicKeyAccount
import scorex.crypto.{Base58, Crypto}
import scorex.database.blockchain.PrunableBlockchainStorage
import scorex.wallet.Wallet
import spray.routing.HttpService

import scala.util.{Failure, Success, Try}


trait AddressHttpService extends HttpService with CommonApifunctions {

  lazy val adressesRouting =
    pathPrefix("addresses") {
      path("") {
        get {
          complete {
            val jsRes = if (!Wallet.isUnlocked) {
              ApiError.toJson(ApiError.ERROR_WALLET_NO_EXISTS)
            } else {
              //GET ACCOUNTS
              val addresses = Wallet.privateKeyAccounts().map(_.address)
              Json.arr(addresses)
            }
            Json.stringify(jsRes)
          }
        }
      } ~ path("validate" / Segment) { case address =>
        get {
          complete {
            val jsRes = Json.obj("address" -> address, "valid" -> Crypto.isValidAddress(address))
            Json.stringify(jsRes)
          }
        }
      } ~ path("seed" / Segment) { case address =>
        get {
          complete {
            //CHECK IF WALLET EXISTS
            val jsRes = withAccount(address) { account =>
              Wallet.exportAccountSeed(account.address) match {
                case None => ApiError.toJson(ApiError.ERROR_WALLET_SEED_EXPORT_FAILED)
                case Some(seed) => Json.obj("address" -> address, "seed" -> Base58.encode(seed))
              }
            }
            Json.stringify(jsRes)
          }
        }
      } ~ path("new") {
        get {
          complete {
            walletNotExistsOrLocked().getOrElse {
              Wallet.generateNewAccount() match {
                case Some(pka) => Json.obj("address" -> pka.address)
                case None => ApiError.toJson(ApiError.ERROR_UNKNOWN)
              }

            }.toString()
          }
        }
      } ~ path("balance" / Segment / IntNumber) { case (address, confirmations) =>
        get {
          complete {
            val jsRes = balanceJson(address, confirmations)
            Json.stringify(jsRes)
          }
        }
      } ~ path("balance" / Segment) { case address =>
        get {
          complete {
            val jsRes = balanceJson(address, 1)
            Json.stringify(jsRes)
          }
        }
      } ~ path("generatingbalance" / Segment) { case address =>
        get {
          complete {
            val jsRes = if (!Crypto.isValidAddress(address)) {
              ApiError.toJson(ApiError.ERROR_INVALID_ADDRESS)
            } else {
              Json.obj(
                "address" -> address,
                "balance" -> PrunableBlockchainStorage.generationBalance(address)
              )
            }
            Json.stringify(jsRes)
          }
        }
      } ~ path("") {
        post {
          entity(as[String]) { seed =>
            complete {
              val jsRes = if (seed.isEmpty) {
                walletNotExistsOrLocked().getOrElse {
                  Wallet.generateNewAccount() match {
                    case Some(pka) => Json.obj("address" -> pka.address)
                    case None => ApiError.toJson(ApiError.ERROR_UNKNOWN)
                  }
                }
              } else {
                walletNotExistsOrLocked().getOrElse {
                  //DECODE SEED
                  Try(Base58.decode(seed)).toOption.flatMap { seedBytes =>
                    if (seedBytes != null && seedBytes.size == 32) {
                      Some(Json.obj("address" -> Wallet.importAccountSeed(seedBytes)))
                    } else None
                  }.getOrElse(ApiError.toJson(ApiError.ERROR_INVALID_SEED))
                }
              }
              Json.stringify(jsRes)
            }
          }
        }
      } ~ path("verify" / Segment) { case address =>
        post {
          entity(as[String]) { jsText =>
            complete {
              val jsRes = Try {
                val js = Json.parse(jsText)
                val msg = (js \ "message").as[String]
                val signature = (js \ "signature").as[String]
                val pubKey = (js \ "publickey").as[String]

                if (!Crypto.isValidAddress(address)) {
                  ApiError.toJson(ApiError.ERROR_INVALID_ADDRESS)
                } else {
                  //DECODE SIGNATURE
                  (Try(Base58.decode(signature)), Try(Base58.decode(pubKey))) match {
                    case (Failure(_), _) => ApiError.toJson(ApiError.ERROR_INVALID_SIGNATURE)
                    case (_, Failure(_)) => ApiError.toJson(ApiError.ERROR_INVALID_PUBLIC_KEY)
                    case (Success(signatureBytes), Success(pubKeyBytes)) =>
                      val account = new PublicKeyAccount(pubKeyBytes)
                      val isValid = account.address == address &&
                        Crypto.verify(signatureBytes, msg.getBytes(StandardCharsets.UTF_8), pubKeyBytes)
                      Json.obj("valid" -> isValid)
                  }
                }
              }.getOrElse(ApiError.toJson(ApiError.ERROR_JSON))
              Json.stringify(jsRes)
            }
          }
        }
      } ~ path("sign" / Segment) { case address =>
        post {
          entity(as[String]) { message =>
            complete {
              val jsRes = walletNotExistsOrLocked().getOrElse {
                if (!Crypto.isValidAddress(address)) {
                  ApiError.toJson(ApiError.ERROR_INVALID_ADDRESS)
                } else {
                  Wallet.privateKeyAccount(address) match {
                    case None => ApiError.toJson(ApiError.ERROR_WALLET_ADDRESS_NO_EXISTS)
                    case Some(account) =>
                      Try(Crypto.sign(account, message.getBytes(StandardCharsets.UTF_8))) match {
                        case Success(signature) =>
                          Json.obj("message" -> message,
                            "publickey" -> Base58.encode(account.publicKey),
                            "signature" -> Base58.encode(signature))
                        case Failure(t) => ApiError.toJson(t)
                      }
                  }
                }
              }
              jsRes.toString()
            }
          }
        }
      } ~ path("address" / Segment) { case address => //todo: fix routing to that?
        delete {
          complete {
            val jsRes = walletNotExistsOrLocked().getOrElse {
              if (!Crypto.isValidAddress(address)) {
                ApiError.toJson(ApiError.ERROR_INVALID_ADDRESS)
              } else {
                val deleted = Wallet.privateKeyAccount(address).exists(account =>
                  Wallet.deleteAccount(account))
                Json.obj("deleted" -> deleted)
              }
            }
            jsRes.toString()
          }
        }
      }
    }

  private def balanceJson(address: String, confirmations: Int) =
    if (!Crypto.isValidAddress(address)) {
      ApiError.toJson(ApiError.ERROR_INVALID_ADDRESS)
    } else {
      Json.obj(
        "address" -> address,
        "confirmations" -> confirmations,
        "balance" -> PrunableBlockchainStorage.balance(address, confirmations)
      )
    }
}