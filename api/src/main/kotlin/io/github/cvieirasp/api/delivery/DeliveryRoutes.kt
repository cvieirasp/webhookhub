package io.github.cvieirasp.api.delivery

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class DeliveryResponse(
    val id: String,
    val eventId: String,
    val destinationId: String,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val createdAt: String,
    val deliveredAt: String?,
)

@Serializable
data class DeliveryListResponse(
    val totalCount: Long,
    val page: Int,
    val pageSize: Int,
    val items: List<DeliveryResponse>,
)

fun Route.deliveryRoutes(useCase: DeliveryUseCase) {
    route("/deliveries") {
        get {
            val statusRaw  = call.request.queryParameters["status"]
            val eventIdRaw = call.request.queryParameters["eventId"]
            val page     = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val pageSize = (call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

            val status  = statusRaw?.let { DeliveryStatus.valueOf(it.uppercase()) }
            val eventId = eventIdRaw?.let { UUID.fromString(it) }

            val filter = DeliveryFilter(status = status, eventId = eventId)
            val (total, deliveries) = withContext(Dispatchers.IO) { useCase.listDeliveries(filter, page, pageSize) }
            call.respond(HttpStatusCode.OK, DeliveryListResponse(
                totalCount = total,
                page       = page,
                pageSize   = pageSize,
                items      = deliveries.map { it.toResponse() },
            ))
        }

        get("/{id}") {
            val id = UUID.fromString(call.parameters["id"]!!)
            val delivery = withContext(Dispatchers.IO) { useCase.getDelivery(id) }
            call.respond(HttpStatusCode.OK, delivery.toResponse())
        }
    }
}

private fun Delivery.toResponse() = DeliveryResponse(
    id            = id.toString(),
    eventId       = eventId.toString(),
    destinationId = destinationId.toString(),
    status        = status.name,
    attempts      = attempts,
    lastError     = lastError,
    createdAt     = createdAt.toString(),
    deliveredAt   = deliveredAt?.toString(),
)
