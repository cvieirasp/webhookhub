package io.github.cvieirasp.api.source

import io.github.cvieirasp.api.TestPostgresContainer
import io.github.cvieirasp.api.setupTestDatabase
import org.testcontainers.containers.PostgreSQLContainer
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/**
 * Integration tests for [SourceRepositoryImpl] using a real PostgreSQL database.
 * Tests cover the main repository methods and ensure correct persistence behavior.
 */
@Testcontainers
class SourceIntegrationRepositoryTest {

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

    private val repository = SourceRepositoryImpl()

    @AfterEach
    fun cleanup() {
        transaction { exec("DELETE FROM sources") }
    }

    // region findAll

    @Test
    fun `findAll returns empty list when table is empty`() {
        assertTrue(repository.findAll().isEmpty())
    }

    @Test
    fun `findAll returns all persisted sources`() {
        repository.create(aSource(name = "github"))
        repository.create(aSource(name = "stripe"))

        assertEquals(2, repository.findAll().size)
    }

    @Test
    fun `findAll returns sources ordered by createdAt descending`() {
        val now = Clock.System.now()
        repository.create(aSource(name = "older", createdAt = now.minus(1.minutes)))
        repository.create(aSource(name = "newer", createdAt = now))

        val result = repository.findAll()
        assertEquals("newer", result.first().name)
        assertEquals("older", result.last().name)
    }

    // endregion

    // region create

    @Test
    fun `create persists source and findAll returns it`() {
        val source = aSource(name = "github")
        repository.create(source)

        val result = repository.findAll()
        assertEquals(1, result.size)
        with(result.first()) {
            assertEquals(source.id, id)
            assertEquals("github", name)
            assertEquals(source.hmacSecret, hmacSecret)
            assertTrue(active)
        }
    }

    @Test
    fun `create returns the inserted source unchanged`() {
        val source = aSource(name = "github")
        val result = repository.create(source)
        assertEquals(source, result)
    }

    @Test
    fun `create throws on duplicate name`() {
        repository.create(aSource(name = "github"))

        assertFailsWith<Exception> {
            repository.create(aSource(name = "github"))
        }
    }

    // endregion
}
