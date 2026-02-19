package io.github.cvieirasp.api.destination

import io.github.cvieirasp.api.TestPostgresContainer
import io.github.cvieirasp.api.plugins.configureSerialization
import io.github.cvieirasp.api.plugins.configureStatusPages
import io.github.cvieirasp.api.setupTestDatabase
import io.github.cvieirasp.shared.config.AppJson
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.*

/**
 * Integration tests for [destinationRoutes] using a real PostgreSQL database.
 */
@Testcontainers
class DestinationIntegrationApiTest {

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
            exec("DELETE FROM destination_rules")
            exec("DELETE FROM destinations")
        }
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { destinationRoutes(DestinationUseCase(DestinationRepositoryImpl())) }
        }
        block()
    }

    // region POST /destinations

    @Test
    fun `POST destinations persists destination and rules to database`() = testApp {
        val response = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"https://example.com/hook","rules":[{"sourceName":"github","eventType":"push"}]}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(1L, transaction { DestinationTable.selectAll().count() })
        assertEquals(1L, transaction { DestinationRuleTable.selectAll().count() })
    }

    @Test
    fun `POST destinations persists all rules atomically`() = testApp {
        client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"https://example.com/hook","rules":[{"sourceName":"github","eventType":"push"},{"sourceName":"stripe","eventType":"charge.created"}]}""")
        }

        assertEquals(2L, transaction { DestinationRuleTable.selectAll().count() })
    }

    @Test
    fun `POST destinations returns 400 when rules list is empty`() = testApp {
        val response = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"https://example.com/hook","rules":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0L, transaction { DestinationTable.selectAll().count() })
    }

    // endregion

    // region GET /destinations

    @Test
    fun `GET destinations returns destinations with rules from database`() {
        val destination = aDestination(name = "my-service")
        val rule = aDestinationRule(destinationId = destination.id, sourceName = "github", eventType = "push")
        transaction {
            DestinationTable.insert {
                it[id] = destination.id
                it[name] = destination.name
                it[targetUrl] = destination.targetUrl
                it[active] = destination.active
                it[createdAt] = destination.createdAt
            }
            DestinationRuleTable.insert {
                it[id] = rule.id
                it[destinationId] = rule.destinationId
                it[sourceName] = rule.sourceName
                it[eventType] = rule.eventType
            }
        }

        testApp {
            val response = client.get("/destinations")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = AppJson.decodeFromString<List<DestinationResponse>>(response.bodyAsText())
            assertEquals(1, body.size)
            with(body.first()) {
                assertEquals("my-service", name)
                assertEquals(1, rules.size)
                assertEquals("github", rules.first().sourceName)
                assertEquals("push", rules.first().eventType)
            }
        }
    }

    // endregion

    // region GET /destinations/{id}

    @Test
    fun `GET destinations by id returns destination with rules from database`() {
        val destination = aDestination(name = "my-service")
        val rule = aDestinationRule(destinationId = destination.id, sourceName = "github", eventType = "push")
        transaction {
            DestinationTable.insert {
                it[id] = destination.id
                it[name] = destination.name
                it[targetUrl] = destination.targetUrl
                it[active] = destination.active
                it[createdAt] = destination.createdAt
            }
            DestinationRuleTable.insert {
                it[id] = rule.id
                it[destinationId] = rule.destinationId
                it[sourceName] = rule.sourceName
                it[eventType] = rule.eventType
            }
        }

        testApp {
            val response = client.get("/destinations/${destination.id}")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = AppJson.decodeFromString<DestinationResponse>(response.bodyAsText())
            assertEquals("my-service", body.name)
            assertEquals(1, body.rules.size)
            assertEquals("github", body.rules.first().sourceName)
        }
    }

    @Test
    fun `GET destinations by id returns 404 when destination does not exist`() = testApp {
        val response = client.get("/destinations/${java.util.UUID.randomUUID()}")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // endregion

    // region POST /destinations/{id}/rules

    @Test
    fun `POST destinations rules persists rule to database`() = testApp {
        val createResponse = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"https://example.com/hook","rules":[{"sourceName":"github","eventType":"push"}]}""")
        }
        val destination = AppJson.decodeFromString<DestinationResponse>(createResponse.bodyAsText())

        val response = client.post("/destinations/${destination.id}/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"sourceName":"stripe","eventType":"charge.created"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(2L, transaction { DestinationRuleTable.selectAll().count() })
    }

    @Test
    fun `POST destinations rules returns created rule`() = testApp {
        val createResponse = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"https://example.com/hook","rules":[{"sourceName":"github","eventType":"push"}]}""")
        }
        val destination = AppJson.decodeFromString<DestinationResponse>(createResponse.bodyAsText())

        val response = client.post("/destinations/${destination.id}/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"sourceName":"stripe","eventType":"charge.created"}""")
        }

        val body = AppJson.decodeFromString<DestinationRuleResponse>(response.bodyAsText())
        assertEquals("stripe", body.sourceName)
        assertEquals("charge.created", body.eventType)
        assertTrue(body.id.isNotBlank())
    }

    @Test
    fun `POST destinations rules returns 404 when destination does not exist`() = testApp {
        val response = client.post("/destinations/${java.util.UUID.randomUUID()}/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"sourceName":"github","eventType":"push"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // endregion

    // region POST → GET round-trips

    @Test
    fun `POST then GET returns the created destination with rules`() = testApp {
        client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"https://example.com/hook","rules":[{"sourceName":"github","eventType":"push"}]}""")
        }

        val response = client.get("/destinations")
        val body = AppJson.decodeFromString<List<DestinationResponse>>(response.bodyAsText())

        assertEquals(1, body.size)
        with(body.first()) {
            assertEquals("my-service", name)
            assertEquals("https://example.com/hook", targetUrl)
            assertTrue(active)
            assertTrue(id.isNotBlank())
            assertEquals(1, rules.size)
            assertEquals("github", rules.first().sourceName)
        }
    }

    @Test
    fun `multiple POST destinations all appear in GET destinations`() = testApp {
        listOf("service-a" to "https://a.example.com", "service-b" to "https://b.example.com").forEach { (name, url) ->
            client.post("/destinations") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"$name","targetUrl":"$url","rules":[{"sourceName":"github","eventType":"push"}]}""")
            }
        }

        val response = client.get("/destinations")
        val body = AppJson.decodeFromString<List<DestinationResponse>>(response.bodyAsText())

        assertEquals(2, body.size)
        assertTrue(body.any { it.name == "service-a" })
        assertTrue(body.any { it.name == "service-b" })
    }

    // endregion
}
