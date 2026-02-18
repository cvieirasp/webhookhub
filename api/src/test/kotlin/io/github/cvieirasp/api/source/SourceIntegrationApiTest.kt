package io.github.cvieirasp.api.source

import io.github.cvieirasp.api.plugins.configureSerialization
import io.github.cvieirasp.api.plugins.configureStatusPages
import io.github.cvieirasp.api.TestPostgresContainer
import io.github.cvieirasp.api.setupTestDatabase
import org.testcontainers.containers.PostgreSQLContainer
import io.github.cvieirasp.shared.config.AppJson
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.*

/**
 * Integration tests for [SourceRepositoryImpl] using a real PostgreSQL database.
 * Tests cover the main repository methods and ensure correct persistence behavior.
 */
@Testcontainers
class SourceIntegrationApiTest {

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
        transaction { exec("DELETE FROM sources") }
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { sourceRoutes(SourceUseCase(SourceRepositoryImpl())) }
        }
        block()
    }

    // region POST /sources

    @Test
    fun `POST sources persists source to database`() = testApp {
        val response = client.post("/sources") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"github"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(1L, transaction { SourceTable.selectAll().count() })
    }

    @Test
    fun `POST sources returns 500 on duplicate name`() = testApp {
        client.post("/sources") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"github"}""")
        }

        val response = client.post("/sources") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"github"}""")
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    // endregion

    // region GET /sources

    @Test
    fun `GET sources returns sources persisted directly in DB`() {
        transaction {
            SourceTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "github"
                it[hmacSecret] = "a".repeat(64)
                it[active] = true
                it[createdAt] = Clock.System.now()
            }
        }

        testApp {
            val response = client.get("/sources")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = AppJson.decodeFromString<List<SourceSummaryResponse>>(response.bodyAsText())
            assertEquals(1, body.size)
            assertEquals("github", body.first().name)
        }
    }

    // endregion

    // region POST → GET round-trips

    @Test
    fun `POST then GET returns the created source`() = testApp {
        client.post("/sources") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"github"}""")
        }

        val response = client.get("/sources")
        val body = AppJson.decodeFromString<List<SourceSummaryResponse>>(response.bodyAsText())

        assertEquals(1, body.size)
        with(body.first()) {
            assertEquals("github", name)
            assertTrue(active)
            assertTrue(id.isNotBlank())
        }
    }

    @Test
    fun `multiple POST sources all appear in GET sources`() = testApp {
        listOf("github", "stripe", "twilio").forEach { name ->
            client.post("/sources") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"$name"}""")
            }
        }

        val response = client.get("/sources")
        val body = AppJson.decodeFromString<List<SourceSummaryResponse>>(response.bodyAsText())

        assertEquals(3, body.size)
        assertTrue(body.any { it.name == "github" })
        assertTrue(body.any { it.name == "stripe" })
        assertTrue(body.any { it.name == "twilio" })
    }

    @Test
    fun `GET sources does not expose hmacSecret`() = testApp {
        client.post("/sources") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"github"}""")
        }

        val response = client.get("/sources")
        assertFalse(response.bodyAsText().contains("hmacSecret"))
    }

    // endregion
}
