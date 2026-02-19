package io.github.cvieirasp.api.destination

import io.github.cvieirasp.api.NotFoundException
import java.util.UUID
import kotlin.test.*

/**
 * Unit tests for [DestinationUseCase].
 */
class DestinationUnitUseCaseTest {

    private lateinit var repository: FakeDestinationRepository
    private lateinit var useCase: DestinationUseCase

    @BeforeTest
    fun setup() {
        repository = FakeDestinationRepository()
        useCase = DestinationUseCase(repository)
    }

    private val defaultRules = listOf(DestinationRuleInput("github", "push"))

    // region listDestinations

    @Test
    fun `listDestinations returns empty list when no destinations exist`() {
        assertTrue(useCase.listDestinations().isEmpty())
    }

    @Test
    fun `listDestinations returns all destinations from repository`() {
        val d1 = aDestination(name = "service-a")
        val d2 = aDestination(name = "service-b")
        repository.seed(d1, d2)

        assertEquals(listOf(d1, d2), useCase.listDestinations())
    }

    // endregion

    // region createDestination — happy path

    @Test
    fun `createDestination returns destination with correct name`() {
        val destination = useCase.createDestination("my-service", "https://example.com/hook", defaultRules)
        assertEquals("my-service", destination.name)
    }

    @Test
    fun `createDestination returns destination with correct targetUrl`() {
        val destination = useCase.createDestination("my-service", "https://example.com/hook", defaultRules)
        assertEquals("https://example.com/hook", destination.targetUrl)
    }

    @Test
    fun `createDestination sets active to true`() {
        val destination = useCase.createDestination("my-service", "https://example.com/hook", defaultRules)
        assertTrue(destination.active)
    }

    @Test
    fun `createDestination persists destination with its rules`() {
        val rules = listOf(
            DestinationRuleInput("github", "push"),
            DestinationRuleInput("stripe", "charge.created"),
        )
        val destination = useCase.createDestination("my-service", "https://example.com/hook", rules)

        assertEquals(2, destination.rules.size)
        assertTrue(destination.rules.all { it.destinationId == destination.id })
        assertTrue(destination.rules.any { it.sourceName == "github" && it.eventType == "push" })
        assertTrue(destination.rules.any { it.sourceName == "stripe" && it.eventType == "charge.created" })
    }

    @Test
    fun `createDestination persists destination in repository`() {
        useCase.createDestination("my-service", "https://example.com/hook", defaultRules)
        assertEquals(1, repository.findAll().size)
    }

    @Test
    fun `createDestination generates unique ids on each call`() {
        val id1 = useCase.createDestination("service-a", "https://a.example.com/hook", defaultRules).id
        val id2 = useCase.createDestination("service-b", "https://b.example.com/hook", defaultRules).id
        assertNotEquals(id1, id2)
    }

    @Test
    fun `createDestination trims whitespace from name and targetUrl`() {
        val destination = useCase.createDestination("  my-service  ", "  https://example.com/hook  ", defaultRules)
        assertEquals("my-service", destination.name)
        assertEquals("https://example.com/hook", destination.targetUrl)
    }

    @Test
    fun `createDestination trims whitespace from rule fields`() {
        val destination = useCase.createDestination(
            "my-service", "https://example.com/hook",
            listOf(DestinationRuleInput("  github  ", "  push  ")),
        )
        assertEquals("github", destination.rules.first().sourceName)
        assertEquals("push", destination.rules.first().eventType)
    }

    @Test
    fun `createDestination accepts a name of exactly 100 characters`() {
        val name = "a".repeat(100)
        val destination = useCase.createDestination(name, "https://example.com/hook", defaultRules)
        assertEquals(name, destination.name)
    }

    @Test
    fun `createDestination accepts http URL`() {
        val destination = useCase.createDestination("my-service", "http://example.com/hook", defaultRules)
        assertEquals("http://example.com/hook", destination.targetUrl)
    }

    // endregion

    // region createDestination — validation

    @Test
    fun `createDestination throws when name is blank`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            useCase.createDestination("", "https://example.com/hook", defaultRules)
        }
        assertEquals("name must not be blank", ex.message)
    }

    @Test
    fun `createDestination throws when name exceeds 100 characters`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            useCase.createDestination("a".repeat(101), "https://example.com/hook", defaultRules)
        }
        assertEquals("name must not exceed 100 characters", ex.message)
    }

    @Test
    fun `createDestination throws when targetUrl is blank`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            useCase.createDestination("my-service", "", defaultRules)
        }
        assertEquals("targetUrl must not be blank", ex.message)
    }

    @Test
    fun `createDestination throws when targetUrl is not a valid URL`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            useCase.createDestination("my-service", "not-a-url", defaultRules)
        }
        assertEquals("targetUrl must be a valid HTTP or HTTPS URL", ex.message)
    }

    @Test
    fun `createDestination throws when targetUrl uses unsupported scheme`() {
        assertFailsWith<IllegalArgumentException> {
            useCase.createDestination("my-service", "ftp://example.com/hook", defaultRules)
        }
    }

    @Test
    fun `createDestination throws when rules list is empty`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            useCase.createDestination("my-service", "https://example.com/hook", emptyList())
        }
        assertEquals("rules must not be empty", ex.message)
    }

    @Test
    fun `createDestination throws when a rule sourceName is blank`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            useCase.createDestination(
                "my-service", "https://example.com/hook",
                listOf(DestinationRuleInput("", "push")),
            )
        }
        assertEquals("rules[0].sourceName must not be blank", ex.message)
    }

    @Test
    fun `createDestination throws when a rule eventType is blank`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            useCase.createDestination(
                "my-service", "https://example.com/hook",
                listOf(DestinationRuleInput("github", "")),
            )
        }
        assertEquals("rules[0].eventType must not be blank", ex.message)
    }

    // endregion

    // region getDestination

    @Test
    fun `getDestination returns destination when it exists`() {
        val destination = aDestination(name = "service-a")
        repository.seed(destination)

        val result = useCase.getDestination(destination.id)
        assertEquals(destination, result)
    }

    @Test
    fun `getDestination throws NotFoundException when destination does not exist`() {
        val ex = assertFailsWith<NotFoundException> {
            useCase.getDestination(UUID.randomUUID())
        }
        assertEquals("destination not found", ex.message)
    }

    // endregion

    // region listRules

    @Test
    fun `listRules returns rules from the created destination`() {
        val destination = useCase.createDestination("my-service", "https://example.com/hook", defaultRules)
        val result = useCase.listRules(destination.id)
        assertEquals(1, result.size)
        assertEquals("github", result.first().sourceName)
    }

    @Test
    fun `listRules returns only rules for the given destination`() {
        val rule1 = aDestinationRule(sourceName = "github", eventType = "push")
        val d1 = aDestination(name = "service-a")
        val d2 = aDestination(name = "service-b")

        val r1 = rule1.copy(destinationId = d1.id)
        val r2 = aDestinationRule(destinationId = d2.id)
        repository.seed(d1.copy(rules = listOf(r1)), d2.copy(rules = listOf(r2)))

        val result = useCase.listRules(d1.id)
        assertEquals(1, result.size)
        assertEquals(r1, result.first())
    }

    @Test
    fun `listRules throws NotFoundException when destination does not exist`() {
        val ex = assertFailsWith<NotFoundException> {
            useCase.listRules(UUID.randomUUID())
        }
        assertEquals("destination not found", ex.message)
    }

    // endregion

    // region addRule — happy path

    @Test
    fun `addRule returns rule with correct fields`() {
        val destination = useCase.createDestination("my-service", "https://example.com/hook", defaultRules)
        val rule = useCase.addRule(destination.id, "stripe", "charge.created")

        assertEquals(destination.id, rule.destinationId)
        assertEquals("stripe", rule.sourceName)
        assertEquals("charge.created", rule.eventType)
    }

    @Test
    fun `addRule persists rule inside the destination aggregate`() {
        val destination = useCase.createDestination("my-service", "https://example.com/hook", defaultRules)
        useCase.addRule(destination.id, "stripe", "charge.created")

        assertEquals(2, repository.findById(destination.id)!!.rules.size)
    }

    @Test
    fun `addRule generates unique ids on each call`() {
        val destination = useCase.createDestination("my-service", "https://example.com/hook", defaultRules)
        val id1 = useCase.addRule(destination.id, "stripe", "charge.created").id
        val id2 = useCase.addRule(destination.id, "stripe", "payment.created").id
        assertNotEquals(id1, id2)
    }

    @Test
    fun `addRule trims whitespace from sourceName and eventType`() {
        val destination = useCase.createDestination("my-service", "https://example.com/hook", defaultRules)
        val rule = useCase.addRule(destination.id, "  stripe  ", "  charge.created  ")

        assertEquals("stripe", rule.sourceName)
        assertEquals("charge.created", rule.eventType)
    }

    // endregion

    // region addRule — validation

    @Test
    fun `addRule throws NotFoundException when destination does not exist`() {
        val ex = assertFailsWith<NotFoundException> {
            useCase.addRule(UUID.randomUUID(), "github", "push")
        }
        assertEquals("destination not found", ex.message)
    }

    @Test
    fun `addRule throws when sourceName is blank`() {
        val destination = useCase.createDestination("my-service", "https://example.com/hook", defaultRules)
        val ex = assertFailsWith<IllegalArgumentException> {
            useCase.addRule(destination.id, "", "push")
        }
        assertEquals("sourceName must not be blank", ex.message)
    }

    @Test
    fun `addRule throws when eventType is blank`() {
        val destination = useCase.createDestination("my-service", "https://example.com/hook", defaultRules)
        val ex = assertFailsWith<IllegalArgumentException> {
            useCase.addRule(destination.id, "github", "")
        }
        assertEquals("eventType must not be blank", ex.message)
    }

    // endregion
}
