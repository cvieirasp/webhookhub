package io.github.cvieirasp.api.destination

import io.github.cvieirasp.api.plugins.configureSerialization
import io.github.cvieirasp.api.plugins.configureStatusPages
import io.github.cvieirasp.shared.config.AppJson
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.util.UUID
import kotlin.test.*

/**
 * Unit tests for [destinationRoutes].
 */
class DestinationUnitRoutesTest {

    private fun testApp(
        repo: FakeDestinationRepository = FakeDestinationRepository(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { destinationRoutes(DestinationUseCase(repo)) }
        }
        block()
    }

    // region GET /destinations

    @Test
    fun `GET destinations returns 200 with empty array`() = testApp {
        val response = client.get("/destinations")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.decodeFromString<List<DestinationResponse>>(response.bodyAsText())
        assertTrue(body.isEmpty())
    }

    @Test
    fun `GET destinations returns all seeded destinations`() {
        val rule = aDestinationRule()
        val repo = FakeDestinationRepository().apply {
            seed(
                aDestination(name = "service-a", rules = listOf(rule.copy(destinationId = java.util.UUID.randomUUID()))),
                aDestination(name = "service-b", rules = listOf(rule.copy(destinationId = java.util.UUID.randomUUID()))),
            )
        }
        testApp(repo) {
            val response = client.get("/destinations")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = AppJson.decodeFromString<List<DestinationResponse>>(response.bodyAsText())
            assertEquals(2, body.size)
            assertTrue(body.any { it.name == "service-a" })
            assertTrue(body.any { it.name == "service-b" })
        }
    }

    @Test
    fun `GET destinations includes rules in each destination`() {
        val destId = UUID.randomUUID()
        val rule = aDestinationRule(destinationId = destId, sourceName = "github", eventType = "push")
        val repo = FakeDestinationRepository().apply {
            seed(aDestination(id = destId, rules = listOf(rule)))
        }
        testApp(repo) {
            val response = client.get("/destinations")
            val body = AppJson.decodeFromString<List<DestinationResponse>>(response.bodyAsText())

            assertEquals(1, body.first().rules.size)
            with(body.first().rules.first()) {
                assertEquals("github", sourceName)
                assertEquals("push", eventType)
            }
        }
    }

    // endregion

    // region POST /destinations

    @Test
    fun `POST destinations returns 201 with created destination`() = testApp {
        val response = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"https://example.com/hook","rules":[{"sourceName":"github","eventType":"push"}]}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = AppJson.decodeFromString<DestinationResponse>(response.bodyAsText())
        assertEquals("my-service", body.name)
        assertEquals("https://example.com/hook", body.targetUrl)
        assertTrue(body.active)
        assertTrue(body.id.isNotBlank())
        assertTrue(body.createdAt.isNotBlank())
    }

    @Test
    fun `POST destinations includes rules in creation response`() = testApp {
        val response = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"https://example.com/hook","rules":[{"sourceName":"github","eventType":"push"},{"sourceName":"stripe","eventType":"charge.created"}]}""")
        }

        val body = AppJson.decodeFromString<DestinationResponse>(response.bodyAsText())
        assertEquals(2, body.rules.size)
        assertTrue(body.rules.any { it.sourceName == "github" && it.eventType == "push" })
        assertTrue(body.rules.any { it.sourceName == "stripe" && it.eventType == "charge.created" })
    }

    @Test
    fun `POST destinations returns 400 when name is blank`() = testApp {
        val response = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"","targetUrl":"https://example.com/hook","rules":[{"sourceName":"github","eventType":"push"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST destinations returns 400 when name exceeds 100 characters`() = testApp {
        val response = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"${"a".repeat(101)}","targetUrl":"https://example.com/hook","rules":[{"sourceName":"github","eventType":"push"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST destinations returns 400 when targetUrl is blank`() = testApp {
        val response = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"","rules":[{"sourceName":"github","eventType":"push"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST destinations returns 400 when targetUrl is not a valid URL`() = testApp {
        val response = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"not-a-url","rules":[{"sourceName":"github","eventType":"push"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST destinations returns 400 when rules list is empty`() = testApp {
        val response = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"https://example.com/hook","rules":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST destinations returns 400 when a rule sourceName is blank`() = testApp {
        val response = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"https://example.com/hook","rules":[{"sourceName":"","eventType":"push"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST destinations returns 400 when a rule eventType is blank`() = testApp {
        val response = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-service","targetUrl":"https://example.com/hook","rules":[{"sourceName":"github","eventType":""}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST destinations returns 400 for malformed JSON body`() = testApp {
        val response = client.post("/destinations") {
            contentType(ContentType.Application.Json)
            setBody("not-json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // endregion

    // region GET /destinations/{id}

    @Test
    fun `GET destinations by id returns 200 with destination`() {
        val destId = UUID.randomUUID()
        val rule = aDestinationRule(destinationId = destId, sourceName = "github", eventType = "push")
        val repo = FakeDestinationRepository().apply {
            seed(aDestination(id = destId, name = "my-service", rules = listOf(rule)))
        }
        testApp(repo) {
            val response = client.get("/destinations/$destId")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = AppJson.decodeFromString<DestinationResponse>(response.bodyAsText())
            assertEquals("my-service", body.name)
            assertEquals(destId.toString(), body.id)
        }
    }

    @Test
    fun `GET destinations by id includes rules in response`() {
        val destId = UUID.randomUUID()
        val rule = aDestinationRule(destinationId = destId, sourceName = "github", eventType = "push")
        val repo = FakeDestinationRepository().apply {
            seed(aDestination(id = destId, rules = listOf(rule)))
        }
        testApp(repo) {
            val response = client.get("/destinations/$destId")
            val body = AppJson.decodeFromString<DestinationResponse>(response.bodyAsText())

            assertEquals(1, body.rules.size)
            with(body.rules.first()) {
                assertEquals("github", sourceName)
                assertEquals("push", eventType)
            }
        }
    }

    @Test
    fun `GET destinations by id returns 404 when destination does not exist`() = testApp {
        val response = client.get("/destinations/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET destinations by id returns 400 when id is not a valid UUID`() = testApp {
        val response = client.get("/destinations/not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // endregion

    // region POST /destinations/{id}/rules

    @Test
    fun `POST destinations rules returns 201 with created rule`() {
        val destId = UUID.randomUUID()
        val repo = FakeDestinationRepository().apply {
            seed(aDestination(id = destId, rules = listOf(aDestinationRule(destinationId = destId))))
        }
        testApp(repo) {
            val response = client.post("/destinations/$destId/rules") {
                contentType(ContentType.Application.Json)
                setBody("""{"sourceName":"stripe","eventType":"charge.created"}""")
            }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = AppJson.decodeFromString<DestinationRuleResponse>(response.bodyAsText())
            assertEquals("stripe", body.sourceName)
            assertEquals("charge.created", body.eventType)
            assertTrue(body.id.isNotBlank())
        }
    }

    @Test
    fun `POST destinations rules returns 404 when destination does not exist`() = testApp {
        val response = client.post("/destinations/${UUID.randomUUID()}/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"sourceName":"github","eventType":"push"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST destinations rules returns 400 when sourceName is blank`() {
        val destId = UUID.randomUUID()
        val repo = FakeDestinationRepository().apply {
            seed(aDestination(id = destId, rules = listOf(aDestinationRule(destinationId = destId))))
        }
        testApp(repo) {
            val response = client.post("/destinations/$destId/rules") {
                contentType(ContentType.Application.Json)
                setBody("""{"sourceName":"","eventType":"push"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST destinations rules returns 400 when eventType is blank`() {
        val destId = UUID.randomUUID()
        val repo = FakeDestinationRepository().apply {
            seed(aDestination(id = destId, rules = listOf(aDestinationRule(destinationId = destId))))
        }
        testApp(repo) {
            val response = client.post("/destinations/$destId/rules") {
                contentType(ContentType.Application.Json)
                setBody("""{"sourceName":"github","eventType":""}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST destinations rules returns 400 for malformed JSON`() {
        val destId = UUID.randomUUID()
        val repo = FakeDestinationRepository().apply {
            seed(aDestination(id = destId, rules = listOf(aDestinationRule(destinationId = destId))))
        }
        testApp(repo) {
            val response = client.post("/destinations/$destId/rules") {
                contentType(ContentType.Application.Json)
                setBody("not-json")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST destinations rules returns 400 when id is not a valid UUID`() = testApp {
        val response = client.post("/destinations/not-a-uuid/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"sourceName":"github","eventType":"push"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // endregion
}
