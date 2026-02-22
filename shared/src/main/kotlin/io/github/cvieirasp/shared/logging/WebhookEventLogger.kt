package io.github.cvieirasp.shared.logging

import org.slf4j.LoggerFactory

/**
 * Emits structured log events at every significant state transition in the
 * webhook pipeline.
 *
 * Each method adds the fields relevant to its transition as SLF4J key-value
 * pairs.  The logstash-logback-encoder installed in :api and :worker serialises
 * these pairs — together with [timestamp], [level], and any MDC fields such as
 * [correlationId] — as a flat JSON object per log line.
 *
 * Fields that are not meaningful for a given transition (e.g. [deliveryId] for
 * ingest-side events) are simply absent from the emitted JSON.
 */
object WebhookEventLogger {

    private val logger = LoggerFactory.getLogger(WebhookEventLogger::class.java)

    /** Ingest request received; logged before any validation. */
    fun eventReceived(sourceName: String) {
        logger.atInfo()
            .addKeyValue("sourceName", sourceName)
            .addKeyValue("status", "RECEIVED")
            .log("event received")
    }

    /** HMAC-SHA256 signature verified successfully. */
    fun signatureValidated(sourceName: String) {
        logger.atInfo()
            .addKeyValue("sourceName", sourceName)
            .addKeyValue("status", "VALIDATED")
            .log("signature validated")
    }

    /** Idempotency check found an already-persisted event; no delivery scheduled. */
    fun duplicateDetected(sourceName: String, eventId: String) {
        logger.atInfo()
            .addKeyValue("sourceName", sourceName)
            .addKeyValue("eventId", eventId)
            .addKeyValue("status", "DUPLICATE")
            .log("duplicate event detected")
    }

    /** Worker picked up the job and is about to make the HTTP call. */
    fun deliveryAttempted(
        eventId: String,
        deliveryId: String,
        destination: String,
        attemptNumber: Int,
    ) {
        logger.atInfo()
            .addKeyValue("eventId", eventId)
            .addKeyValue("deliveryId", deliveryId)
            .addKeyValue("destination", destination)
            .addKeyValue("status", "ATTEMPTING")
            .addKeyValue("attemptNumber", attemptNumber)
            .log("delivery attempted")
    }

    /** HTTP destination responded with 2xx; delivery is complete. */
    fun deliverySucceeded(
        eventId: String,
        deliveryId: String,
        destination: String,
        attemptNumber: Int,
    ) {
        logger.atInfo()
            .addKeyValue("eventId", eventId)
            .addKeyValue("deliveryId", deliveryId)
            .addKeyValue("destination", destination)
            .addKeyValue("status", "DELIVERED")
            .addKeyValue("attemptNumber", attemptNumber)
            .log("delivery succeeded")
    }

    /** Retryable failure; job re-queued with exponential-backoff TTL. */
    fun retryScheduled(
        eventId: String,
        deliveryId: String,
        destination: String,
        attemptNumber: Int,
        errorMessage: String,
    ) {
        logger.atWarn()
            .addKeyValue("eventId", eventId)
            .addKeyValue("deliveryId", deliveryId)
            .addKeyValue("destination", destination)
            .addKeyValue("status", "RETRYING")
            .addKeyValue("attemptNumber", attemptNumber)
            .addKeyValue("errorMessage", errorMessage)
            .log("retry scheduled")
    }

    /** All attempts exhausted or non-retryable error; job moved to DLQ. */
    fun deliveryDead(
        eventId: String,
        deliveryId: String,
        destination: String,
        attemptNumber: Int,
        errorMessage: String,
    ) {
        logger.atError()
            .addKeyValue("eventId", eventId)
            .addKeyValue("deliveryId", deliveryId)
            .addKeyValue("destination", destination)
            .addKeyValue("status", "DEAD")
            .addKeyValue("attemptNumber", attemptNumber)
            .addKeyValue("errorMessage", errorMessage)
            .log("delivery dead")
    }
}
