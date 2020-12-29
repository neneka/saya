package blue.starry.saya.endpoints

import blue.starry.saya.common.respondOrNotFound
import blue.starry.saya.services.chinachu.ChinachuApi
import blue.starry.saya.services.chinachu.ChinachuDataManager
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*

fun Route.getRules() {
    get {
        call.respond(
            ChinachuDataManager.Rules.toList()
        )
    }
}

fun Route.getRule() {
    get {
        val id: Long by call.parameters

        call.respondOrNotFound(
            ChinachuDataManager.Rules.find { it.id == id}
        )
    }
}

fun Route.deleteRule() {
    get {
        val id: Long by call.parameters

        ChinachuApi.deleteRule(id.toInt())
        call.respond(HttpStatusCode.OK)
    }
}