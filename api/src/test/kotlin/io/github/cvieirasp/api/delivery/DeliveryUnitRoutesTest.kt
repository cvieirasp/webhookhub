package io.github.cvieirasp.api.delivery

import io.github.cvieirasp.api.plugins.configureSerialization
import io.github.cvieirasp.api.plugins.configureStatusPages
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [deliveryRoutes].
 */
class DeliveryUnitRoutesTest {

    private fun testApp(
        repo: FakeDeliveryRepository = FakeDeliveryRepository(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { deliveryRoutes(DeliveryUseCase(repo)) }
        }
        block()
    }

    // ── GET /deliveries/{id} ───────────────────────────────────────────────

    @Test
    fun `GET deliveries by id returns 200 with all fields`() {
        val id            = UUID.randomUUID()
        val eventId       = UUID.randomUUID()
        val destinationId = UUID.randomUUID()
        val repo = FakeDeliveryRepository().also {
            it.saved.add(aDelivery(
                id            = id,
                eventId       = eventId,
                destinationId = destinationId,
                status        = DeliveryStatus.DELIVERED,
                attempts      = 2,
                lastError     = null,
                createdAt     = Clock.System.now(),
            ))
        }

        testApp(repo) {
            val response = client.get("/deliveries/$id")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(id.toString(),            body["id"]!!.jsonPrimitive.content)
            assertEquals(eventId.toString(),       body["eventId"]!!.jsonPrimitive.content)
            assertEquals(destinationId.toString(), body["destinationId"]!!.jsonPrimitive.content)
            assertEquals("DELIVERED",              body["status"]!!.jsonPrimitive.content)
            assertEquals(2,      body["attempts"]!!.jsonPrimitive.content.toInt())
            assertEquals("null", body["lastError"].toString())
        }
    }

    @Test
    fun `GET deliveries by id returns 404 when not found`() = testApp {
        val response = client.get("/deliveries/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ── GET /deliveries ────────────────────────────────────────────────────

    @Test
    fun `GET deliveries returns 200 with empty items when no deliveries exist`() = testApp {
        val response = client.get("/deliveries")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(0L,  body["totalCount"]!!.jsonPrimitive.content.toLong())
        assertEquals(1,   body["page"]!!.jsonPrimitive.content.toInt())
        assertEquals(20,  body["pageSize"]!!.jsonPrimitive.content.toInt())
        assertEquals(0,   body["items"]!!.jsonArray.size)
    }

    @Test
    fun `GET deliveries returns 200 with all deliveries`() {
        val repo = FakeDeliveryRepository().also {
            it.saved.add(aDelivery(status = DeliveryStatus.PENDING))
            it.saved.add(aDelivery(status = DeliveryStatus.DELIVERED))
        }
        testApp(repo) {
            val response = client.get("/deliveries")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            assertEquals(2,  body["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun `GET deliveries filters by status`() {
        val repo = FakeDeliveryRepository().also {
            it.saved.add(aDelivery(status = DeliveryStatus.PENDING))
            it.saved.add(aDelivery(status = DeliveryStatus.DELIVERED))
            it.saved.add(aDelivery(status = DeliveryStatus.DEAD))
        }
        testApp(repo) {
            val response = client.get("/deliveries?status=PENDING")
            assertEquals(HttpStatusCode.OK, response.status)
            val body  = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            val items = body["items"]!!.jsonArray
            assertEquals(1,         items.size)
            assertEquals("PENDING", items[0].jsonObject["status"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `GET deliveries filters by eventId`() {
        val targetEventId = UUID.randomUUID()
        val repo = FakeDeliveryRepository().also {
            it.saved.add(aDelivery(eventId = targetEventId,       status = DeliveryStatus.PENDING))
            it.saved.add(aDelivery(eventId = UUID.randomUUID(),   status = DeliveryStatus.PENDING))
        }
        testApp(repo) {
            val response = client.get("/deliveries?eventId=$targetEventId")
            assertEquals(HttpStatusCode.OK, response.status)
            val body  = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            assertEquals(
                targetEventId.toString(),
                body["items"]!!.jsonArray[0].jsonObject["eventId"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `GET deliveries respects page and pageSize`() {
        val repo = FakeDeliveryRepository().also { r ->
            repeat(5) { r.saved.add(aDelivery()) }
        }
        testApp(repo) {
            val response = client.get("/deliveries?page=2&pageSize=2")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(5L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            assertEquals(2,  body["page"]!!.jsonPrimitive.content.toInt())
            assertEquals(2,  body["pageSize"]!!.jsonPrimitive.content.toInt())
            assertEquals(2,  body["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun `GET deliveries returns 400 for invalid status`() = testApp {
        val response = client.get("/deliveries?status=INVALID")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET deliveries returns 400 for malformed eventId`() = testApp {
        val response = client.get("/deliveries?eventId=not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
