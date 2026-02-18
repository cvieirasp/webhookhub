package io.github.cvieirasp.api.source

import kotlin.test.*

/**
 * Unit tests for [SourceUseCase].
 */
class SourceUnitUseCaseTest {

    private lateinit var repository: FakeSourceRepository
    private lateinit var useCase: SourceUseCase

    @BeforeTest
    fun setup() {
        repository = FakeSourceRepository()
        useCase = SourceUseCase(repository)
    }

    // region listSources

    @Test
    fun `listSources returns empty list when no sources exist`() {
        assertTrue(useCase.listSources().isEmpty())
    }

    @Test
    fun `listSources returns all sources from repository`() {
        val s1 = aSource(name = "github")
        val s2 = aSource(name = "stripe")
        repository.seed(s1, s2)

        assertEquals(listOf(s1, s2), useCase.listSources())
    }

    // endregion

    // region createSource — happy path

    @Test
    fun `createSource returns source with correct name`() {
        val source = useCase.createSource("github")
        assertEquals("github", source.name)
    }

    @Test
    fun `createSource sets active to true`() {
        val source = useCase.createSource("github")
        assertTrue(source.active)
    }

    @Test
    fun `createSource generates a 64-char lowercase hex hmacSecret`() {
        val source = useCase.createSource("github")
        assertEquals(64, source.hmacSecret.length)
        assertTrue(source.hmacSecret.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `createSource persists source in repository`() {
        useCase.createSource("github")
        assertEquals(1, repository.findAll().size)
    }

    @Test
    fun `createSource generates unique ids on each call`() {
        val id1 = useCase.createSource("source-1").id
        val id2 = useCase.createSource("source-2").id
        assertNotEquals(id1, id2)
    }

    @Test
    fun `createSource generates unique hmac secrets on each call`() {
        val s1 = useCase.createSource("source-1").hmacSecret
        val s2 = useCase.createSource("source-2").hmacSecret
        assertNotEquals(s1, s2)
    }

    @Test
    fun `createSource accepts a name of exactly 100 characters`() {
        val name = "a".repeat(100)
        val source = useCase.createSource(name)
        assertEquals(name, source.name)
    }

    // endregion

    // region createSource — validation

    @Test
    fun `createSource throws when name is blank`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            useCase.createSource("")
        }
        assertEquals("name must not be blank", ex.message)
    }

    @Test
    fun `createSource throws when name is whitespace only`() {
        assertFailsWith<IllegalArgumentException> {
            useCase.createSource("   ")
        }
    }

    @Test
    fun `createSource throws when name exceeds 100 characters`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            useCase.createSource("a".repeat(101))
        }
        assertEquals("name must not exceed 100 characters", ex.message)
    }

    // endregion
}
