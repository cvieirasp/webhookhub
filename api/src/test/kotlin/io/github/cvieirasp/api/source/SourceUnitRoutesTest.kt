package io.github.cvieirasp.api.source

import io.github.cvieirasp.api.plugins.configureSerialization
import io.github.cvieirasp.api.plugins.configureStatusPages
import io.github.cvieirasp.shared.config.AppJson
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.*

/**
 * Unit tests for [sourceRoutes].
 */
class SourceUnitRoutesTest {

    private fun testApp(
        repo: FakeSourceRepository = FakeSourceRepository(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { sourceRoutes(SourceUseCase(repo)) }
        }
        block()
    }

    // region GET /sources

    @Test
    fun `GET sources returns 200 with empty array`() = testApp {
        val response = client.get("/sources")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.decodeFromString<List<SourceSummaryResponse>>(response.bodyAsText())
        assertTrue(body.isEmpty())
    }

    @Test
    fun `GET sources returns all seeded sources`() {
        val repo = FakeSourceRepository().apply {
            seed(aSource(name = "github"), aSource(name = "stripe"))
        }
        testApp(repo) {
            val response = client.get("/sources")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = AppJson.decodeFromString<List<SourceSummaryResponse>>(response.bodyAsText())
            assertEquals(2, body.size)
            assertTrue(body.any { it.name == "github" })
            assertTrue(body.any { it.name == "stripe" })
        }
    }

    @Test
    fun `GET sources does not expose hmacSecret`() {
        val repo = FakeSourceRepository().apply { seed(aSource()) }
        testApp(repo) {
            val response = client.get("/sources")
            assertFalse(response.bodyAsText().contains("hmacSecret"))
        }
    }

    // endregion

    // region POST /sources

    @Test
    fun `POST sources returns 201 with created source`() = testApp {
        val response = client.post("/sources") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"github"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = AppJson.decodeFromString<SourceCreatedResponse>(response.bodyAsText())
        assertEquals("github", body.name)
        assertTrue(body.active)
        assertTrue(body.id.isNotBlank())
        assertTrue(body.createdAt.isNotBlank())
    }

    @Test
    fun `POST sources includes 64-char hmacSecret in creation response`() = testApp {
        val response = client.post("/sources") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"github"}""")
        }

        val body = AppJson.decodeFromString<SourceCreatedResponse>(response.bodyAsText())
        assertEquals(64, body.hmacSecret.length)
    }

    @Test
    fun `POST sources trims whitespace from name`() = testApp {
        val response = client.post("/sources") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"  github  "}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = AppJson.decodeFromString<SourceCreatedResponse>(response.bodyAsText())
        assertEquals("github", body.name)
    }

    @Test
    fun `POST sources returns 400 when name is blank`() = testApp {
        val response = client.post("/sources") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST sources returns 400 when name exceeds 100 characters`() = testApp {
        val response = client.post("/sources") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"${"a".repeat(101)}"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST sources returns 400 for malformed JSON body`() = testApp {
        val response = client.post("/sources") {
            contentType(ContentType.Application.Json)
            setBody("not-json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST sources returns 400 when name field is missing`() = testApp {
        val response = client.post("/sources") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // endregion
}
