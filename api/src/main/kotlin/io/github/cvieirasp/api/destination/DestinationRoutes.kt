package io.github.cvieirasp.api.destination

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class RuleRequest(
    val sourceName: String,
    val eventType: String,
)

@Serializable
data class CreateDestinationRequest(
    val name: String,
    val targetUrl: String,
    val rules: List<RuleRequest>,
)

@Serializable
data class DestinationRuleResponse(
    val id: String,
    val sourceName: String,
    val eventType: String,
)

@Serializable
data class DestinationResponse(
    val id: String,
    val name: String,
    val targetUrl: String,
    val active: Boolean,
    val createdAt: String,
    val rules: List<DestinationRuleResponse>,
)

/**
 * Defines the API routes for managing destinations.
 *
 * @param useCase The use case for handling destination-related operations.
 */
fun Route.destinationRoutes(useCase: DestinationUseCase) {
    route("/destinations") {
        get {
            val destinations = withContext(Dispatchers.IO) { useCase.listDestinations() }
            call.respond(HttpStatusCode.OK, destinations.map { it.toResponse() })
        }

        post {
            val request = call.receive<CreateDestinationRequest>()
            val rules = request.rules.map { DestinationRuleInput(it.sourceName, it.eventType) }
            val destination = withContext(Dispatchers.IO) {
                useCase.createDestination(request.name, request.targetUrl, rules)
            }
            call.respond(HttpStatusCode.Created, destination.toResponse())
        }

        route("/{id}") {
            get {
                val id = UUID.fromString(call.parameters["id"]!!)
                val destination = withContext(Dispatchers.IO) { useCase.getDestination(id) }
                call.respond(HttpStatusCode.OK, destination.toResponse())
            }

            post("/rules") {
                val id = UUID.fromString(call.parameters["id"]!!)
                val request = call.receive<RuleRequest>()
                val rule = withContext(Dispatchers.IO) {
                    useCase.addRule(id, request.sourceName, request.eventType)
                }
                call.respond(HttpStatusCode.Created, rule.toResponse())
            }
        }
    }
}

private fun Destination.toResponse() = DestinationResponse(
    id = id.toString(),
    name = name,
    targetUrl = targetUrl,
    active = active,
    createdAt = createdAt.toString(),
    rules = rules.map { it.toResponse() },
)

private fun DestinationRule.toResponse() = DestinationRuleResponse(
    id = id.toString(),
    sourceName = sourceName,
    eventType = eventType,
)
