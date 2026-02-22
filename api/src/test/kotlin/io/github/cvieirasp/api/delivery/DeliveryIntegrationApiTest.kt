package io.github.cvieirasp.api.delivery

import io.github.cvieirasp.api.TestPostgresContainer
import io.github.cvieirasp.api.destination.DestinationTable
import io.github.cvieirasp.api.ingest.EventTable
import io.github.cvieirasp.api.plugins.configureSerialization
import io.github.cvieirasp.api.plugins.configureStatusPages
import io.github.cvieirasp.api.setupTestDatabase
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
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Integration tests for [deliveryRoutes] using a real PostgreSQL database.
 */
@Testcontainers
class DeliveryIntegrationApiTest {

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer<*> = TestPostgresContainer("postgres:18.2-alpine")
            .withDatabaseName("webhookhub-test")
            .withUsername("webhookhub")
            .withPassword("webhookhub")

        @JvmStatic
        @BeforeAll
        fun setup() { setupTestDatabase(postgres) }
    }

    @AfterEach
    fun cleanup() {
        transaction {
            exec("DELETE FROM deliveries")
            exec("DELETE FROM events")
            exec("DELETE FROM destinations")
        }
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { deliveryRoutes(DeliveryUseCase(DeliveryRepositoryImpl())) }
        }
        block()
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun insertEvent(id: UUID = UUID.randomUUID()): UUID {
        transaction {
            EventTable.insert {
                it[EventTable.id]             = id
                it[EventTable.sourceName]     = "github"
                it[EventTable.eventType]      = "push"
                it[EventTable.idempotencyKey] = UUID.randomUUID().toString()
                it[EventTable.payloadJson]    = """{"ref":"main"}"""
                it[EventTable.correlationId]  = UUID.randomUUID().toString()
                it[EventTable.receivedAt]     = Clock.System.now()
            }
        }
        return id
    }

    private fun insertDestination(id: UUID = UUID.randomUUID()): UUID {
        transaction {
            DestinationTable.insert {
                it[DestinationTable.id]        = id
                it[DestinationTable.name]      = "dest-$id"
                it[DestinationTable.targetUrl] = "https://example.com/hook"
                it[DestinationTable.active]    = true
                it[DestinationTable.createdAt] = Clock.System.now()
            }
        }
        return id
    }

    private fun insertDelivery(
        id: UUID = UUID.randomUUID(),
        eventId: UUID,
        destinationId: UUID,
        status: DeliveryStatus = DeliveryStatus.PENDING,
        attempts: Int = 0,
        lastError: String? = null,
    ): UUID {
        transaction {
            DeliveryTable.insert {
                it[DeliveryTable.id]            = id
                it[DeliveryTable.eventId]       = eventId
                it[DeliveryTable.destinationId] = destinationId
                it[DeliveryTable.status]        = status
                it[DeliveryTable.attempts]      = attempts
                it[DeliveryTable.maxAttempts]   = 5
                it[DeliveryTable.lastError]     = lastError
                it[DeliveryTable.createdAt]     = Clock.System.now()
            }
        }
        return id
    }

    // ── GET /deliveries/{id} ───────────────────────────────────────────────

    @Test
    fun `GET deliveries by id returns 200 with all fields for persisted delivery`() {
        val eventId       = insertEvent()
        val destinationId = insertDestination()
        val id = insertDelivery(
            eventId       = eventId,
            destinationId = destinationId,
            status        = DeliveryStatus.DELIVERED,
            attempts      = 1,
        )

        testApp {
            val response = client.get("/deliveries/$id")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(id.toString(),            body["id"]!!.jsonPrimitive.content)
            assertEquals(eventId.toString(),       body["eventId"]!!.jsonPrimitive.content)
            assertEquals(destinationId.toString(), body["destinationId"]!!.jsonPrimitive.content)
            assertEquals("DELIVERED",              body["status"]!!.jsonPrimitive.content)
            assertEquals(1,                        body["attempts"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `GET deliveries by id returns 404 when delivery does not exist`() = testApp {
        val response = client.get("/deliveries/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ── GET /deliveries ────────────────────────────────────────────────────

    @Test
    fun `GET deliveries returns 200 with all deliveries when no filter`() {
        val eventId       = insertEvent()
        val destinationId = insertDestination()
        insertDelivery(eventId = eventId, destinationId = destinationId, status = DeliveryStatus.PENDING)
        insertDelivery(eventId = eventId, destinationId = insertDestination(), status = DeliveryStatus.DELIVERED)

        testApp {
            val response = client.get("/deliveries")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            assertEquals(1,  body["page"]!!.jsonPrimitive.content.toInt())
            assertEquals(20, body["pageSize"]!!.jsonPrimitive.content.toInt())
            assertEquals(2,  body["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun `GET deliveries filters by status`() {
        val eventId   = insertEvent()
        val destId1   = insertDestination()
        val destId2   = insertDestination()
        insertDelivery(eventId = eventId, destinationId = destId1, status = DeliveryStatus.PENDING)
        insertDelivery(eventId = eventId, destinationId = destId2, status = DeliveryStatus.DEAD)

        testApp {
            val response = client.get("/deliveries?status=DEAD")
            assertEquals(HttpStatusCode.OK, response.status)
            val body  = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            assertEquals("DEAD", body["items"]!!.jsonArray[0].jsonObject["status"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `GET deliveries filters by eventId`() {
        val eventId1  = insertEvent()
        val eventId2  = insertEvent()
        val destId    = insertDestination()
        val destId2   = insertDestination()
        insertDelivery(eventId = eventId1, destinationId = destId,  status = DeliveryStatus.PENDING)
        insertDelivery(eventId = eventId2, destinationId = destId2, status = DeliveryStatus.PENDING)

        testApp {
            val response = client.get("/deliveries?eventId=$eventId1")
            assertEquals(HttpStatusCode.OK, response.status)
            val body  = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            assertEquals(
                eventId1.toString(),
                body["items"]!!.jsonArray[0].jsonObject["eventId"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `GET deliveries returns paginated results with correct totalCount`() {
        val eventId = insertEvent()
        repeat(5) { insertDelivery(eventId = eventId, destinationId = insertDestination()) }

        testApp {
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
    fun `GET deliveries returns 400 for invalid status value`() = testApp {
        val response = client.get("/deliveries?status=BOGUS")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET deliveries returns 400 for malformed eventId`() = testApp {
        val response = client.get("/deliveries?eventId=not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
