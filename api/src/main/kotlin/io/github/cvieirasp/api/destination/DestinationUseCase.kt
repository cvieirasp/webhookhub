package io.github.cvieirasp.api.destination

import io.github.cvieirasp.api.NotFoundException
import kotlinx.datetime.Clock
import java.net.URI
import java.util.UUID

/**
 * Use case for managing destinations and their routing rules.
 *
 * @param repository The repository for accessing destination aggregate data.
 */
class DestinationUseCase(private val repository: DestinationRepository) {

    fun listDestinations(): List<Destination> = repository.findAll()

    fun getDestination(id: UUID): Destination =
        repository.findById(id) ?: throw NotFoundException("destination not found")

    /**
     * Creates a new destination with the specified name, target URL, and routing rules.
     *
     * @param name The name of the destination.
     * @param targetUrl The URL to which events should be routed.
     * @param rules The routing rules for the destination.
     * @return The created destination.
     * @throws IllegalArgumentException if validation fails.
     */
    fun createDestination(name: String, targetUrl: String, rules: List<DestinationRuleInput>): Destination {
        require(name.isNotBlank()) { "name must not be blank" }
        require(name.trim().length <= 100) { "name must not exceed 100 characters" }
        require(targetUrl.isNotBlank()) { "targetUrl must not be blank" }
        require(isValidHttpUrl(targetUrl.trim())) { "targetUrl must be a valid HTTP or HTTPS URL" }
        require(rules.isNotEmpty()) { "rules must not be empty" }
        rules.forEachIndexed { i, rule ->
            require(rule.sourceName.isNotBlank()) { "rules[$i].sourceName must not be blank" }
            require(rule.eventType.isNotBlank()) { "rules[$i].eventType must not be blank" }
        }

        val destinationId = UUID.randomUUID()
        val destination = Destination(
            id = destinationId,
            name = name.trim(),
            targetUrl = targetUrl.trim(),
            active = true,
            createdAt = Clock.System.now(),
            rules = rules.map { rule ->
                DestinationRule(
                    id = UUID.randomUUID(),
                    destinationId = destinationId,
                    sourceName = rule.sourceName.trim(),
                    eventType = rule.eventType.trim(),
                )
            },
        )
        return repository.create(destination)
    }

    /**
     * Returns the routing rules associated with a destination.
     *
     * @param destinationId The ID of the destination.
     * @return The list of rules for the destination.
     * @throws IllegalArgumentException if the destination does not exist.
     */
    fun listRules(destinationId: UUID): List<DestinationRule> {
        val destination = repository.findById(destinationId) ?: throw NotFoundException("destination not found")
        return destination.rules
    }

    /**
     * Adds a routing rule to an existing destination.
     *
     * @param destinationId The ID of the destination to add the rule to.
     * @param sourceName The name of the source whose events should be routed.
     * @param eventType The event type to match.
     * @return The created rule.
     * @throws IllegalArgumentException if validation fails or destination does not exist.
     */
    fun addRule(destinationId: UUID, sourceName: String, eventType: String): DestinationRule {
        repository.findById(destinationId) ?: throw NotFoundException("destination not found")
        require(sourceName.isNotBlank()) { "sourceName must not be blank" }
        require(eventType.isNotBlank()) { "eventType must not be blank" }

        val rule = DestinationRule(
            id = UUID.randomUUID(),
            destinationId = destinationId,
            sourceName = sourceName.trim(),
            eventType = eventType.trim(),
        )
        return repository.addRule(destinationId, rule)
    }

    /**
     * Validates that the provided URL is a well-formed HTTP or HTTPS URL.
     */
    private fun isValidHttpUrl(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme in listOf("http", "https") && uri.host != null
    } catch (_: Exception) {
        false
    }
}
