package api.http

import play.api.libs.json.Json
import scorex.database.blockchain.PrunableBlockchainStorage
import settings.Constants
import spray.routing.HttpService


trait BlocksHttpService extends HttpService with CommonApifunctions {

  lazy val blocksRouting =
    pathPrefix("blocks") {
      path("signature" / Segment) { case encodedSignature =>
        get {
          complete(withBlock(encodedSignature)(_.toJson).toString())
        }
      } ~ path("first") {
        get {
          complete(Constants.ConsensusAlgo.genesisBlock.toJson.toString())
        }
      } ~ path("last") {
        get {
          complete(PrunableBlockchainStorage.lastBlock.toJson.toString())
        }
      } ~ path("height") {
        get {
          complete(Json.obj("height" -> PrunableBlockchainStorage.height()).toString())
        }
      } ~ path("height" / Segment) { case encodedSignature =>
        get {
          complete {
            withBlock(encodedSignature) { block =>
              Json.obj("height" -> block.height())
            }.toString()
          }
        }
      } ~ path("child" / Segment) { case encodedSignature =>
        get {
          complete(withBlock(encodedSignature)(_.child().get.toJson).toString())
        }
      } ~ path("address" / Segment) { case address =>
        get {
          complete(withAccount(address) { account =>
            Json.arr(PrunableBlockchainStorage.generatedBy(account).map(_.toJson))
          }.toString())
        }
      }

      /* todo: consider how to obtain consensus-specific data via API, commented out for now
      ~ path("time") {
        get {
          complete {
            val block = PrunableBlockchainStorage.lastBlock
            val timePerBlock = QoraBlockGenerationFunctions.getBlockTime(block.generationData.generatingBalance)
            Json.obj("time" -> timePerBlock).toString()
          }
        }
      } ~ path("time" / Segment) { case generatingBalance =>
        get {
          complete {
            val jsRes = Try {
              val timePerBlock = QoraBlockGenerationFunctions.getBlockTime(generatingBalance.toLong)
              Json.obj("time" -> timePerBlock)
            }.getOrElse(ApiError.toJson(ApiError.ERROR_INVALID_NOT_NUMBER))
            jsRes.toString()
          }
        }
      } ~ path("generatingbalance") {
        get {
          complete {
            val generatingBalance = Controller.nextBlockGeneratingBalance()
            Json.obj("generatingbalance" -> generatingBalance).toString()
          }
        }
      } ~ path("generatingbalance" / Segment) { case encodedSignature =>
        get {
          complete(withBlock(encodedSignature) { block =>
            Json.obj("generatingbalance" -> block.generationData.generatingBalance)
          }.toString())
        }
      } */
    }
}
