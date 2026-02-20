package io.github.cvieirasp.worker.queue

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.MessageProperties
import io.github.cvieirasp.shared.config.AppJson
import io.github.cvieirasp.shared.queue.DeliveryJob
import io.github.cvieirasp.shared.queue.RabbitMQTopology
import io.github.cvieirasp.worker.delivery.DeliveryRepository
import io.github.cvieirasp.worker.delivery.DeliveryStatus
import io.github.cvieirasp.worker.http.HttpDeliveryClient
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * AMQP consumer for the `webhookhub.deliveries` queue.
 *
 * Guarantees:
 * - [basicAck] is only sent **after** the delivery status has been durably
 *   persisted to the database.  If the database write fails, the message is
 *   nacked (requeue=false) so the broker dead-letters it for manual inspection.
 * - At most [DeliveryWorker.PREFETCH_COUNT] messages are unacknowledged at any
 *   time (enforced via `basicQos` in [DeliveryWorker]).
 */
class DeliveryConsumer(
    channel: Channel,
    private val repository: DeliveryRepository,
    private val httpClient: HttpDeliveryClient,
) : DefaultConsumer(channel) {

    private companion object {
        private const val MAX_ATTEMPTS = 5
        private val logger = LoggerFactory.getLogger(DeliveryConsumer::class.java)
    }

    /**
     * Handles incoming delivery jobs from the queue.
     * The method is invoked by the RabbitMQ client library on a dedicated thread.
     */
    override fun handleDelivery(
        consumerTag: String,
        envelope: Envelope,
        properties: AMQP.BasicProperties,
        body: ByteArray,
    ) {
        val tag = envelope.deliveryTag
        try {
            val job = AppJson.decodeFromString<DeliveryJob>(body.toString(Charsets.UTF_8))
            logger.info("Processing delivery={} attempt={}/{}", job.deliveryId, job.attempt, MAX_ATTEMPTS)

            when (val result = httpClient.post(job.targetUrl, job.payloadJson)) {
                is HttpDeliveryClient.Result.Success -> {
                    // Timestamp captured after the HTTP call so delivered_at reflects
                    // when the 2xx response was actually received, not when processing started.
                    val deliveredAt = Clock.System.now()

                    // Persist DELIVERED status before acking.
                    repository.updateStatus(
                        deliveryId  = UUID.fromString(job.deliveryId),
                        status      = DeliveryStatus.DELIVERED,
                        attempts    = job.attempt,
                        deliveredAt = deliveredAt,
                    )
                    logger.info("Delivered delivery={}", job.deliveryId)
                }

                is HttpDeliveryClient.Result.Failure -> {
                    val exceeded      = job.attempt >= MAX_ATTEMPTS
                    val nextStatus    = if (exceeded) DeliveryStatus.DEAD else DeliveryStatus.RETRYING
                    val lastAttemptAt = Clock.System.now()

                    // Persist the failure before acking so the outcome is always durable.
                    // attempts = job.attempt records the incremented counter (e.g. first failure → 1).
                    repository.updateStatus(
                        deliveryId    = UUID.fromString(job.deliveryId),
                        status        = nextStatus,
                        attempts      = job.attempt,
                        lastError     = result.message,
                        lastAttemptAt = lastAttemptAt,
                    )

                    if (!exceeded) {
                        val delayMs = retryDelayMs(job.attempt)
                        // Publish the next attempt to the retry holding queue with a
                        // per-message TTL equal to the backoff delay.  When the TTL expires,
                        // RabbitMQ dead-letters the message back to the main exchange so the
                        // worker picks it up as attempt (job.attempt + 1).
                        republishWithDelay(job, delayMs)
                        logger.warn(
                            "Delivery={} failed (attempt {}/{}); retry in {}ms — {}",
                            job.deliveryId, job.attempt, MAX_ATTEMPTS, delayMs, result.message,
                        )
                    } else {
                        // Attempts exhausted — ack without requeuing so the message is not
                        // re-delivered.  Status is already persisted as DEAD above.
                        logger.error(
                            "Delivery={} exhausted {} attempts; marked DEAD — {}",
                            job.deliveryId, MAX_ATTEMPTS, result.message,
                        )
                    }
                }
            }

            // Ack only after the DB write above has returned successfully
            channel.basicAck(tag, false)

        } catch (e: Exception) {
            logger.error("Unhandled failure for delivery tag={}; dead-lettering message", tag, e)
            // nack with requeue=false → broker forwards to the dead-letter exchange
            runCatching { channel.basicNack(tag, false, false) }
        }
    }

    /**
     * Publishes the next attempt to [RabbitMQTopology.QUEUE_RETRY] with a per-message
     * TTL of [delayMs].  When the TTL expires, the broker dead-letters the message back
     * to the main exchange with the delivery routing key, making it visible to the worker
     * as attempt `job.attempt + 1`.
     *
     * The message is marked persistent (deliveryMode=2) so it survives a broker restart
     * during the backoff window.
     */
    private fun republishWithDelay(job: DeliveryJob, delayMs: Long) {
        val next  = job.copy(attempt = job.attempt + 1)
        val props = MessageProperties.PERSISTENT_BASIC.builder()
            .expiration(delayMs.toString())
            .build()
        channel.basicPublish(
            "",                            // default exchange — routes by queue name
            RabbitMQTopology.QUEUE_RETRY,  // routing key = queue name for default exchange
            props,
            AppJson.encodeToString(next).toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Returns the backoff delay in milliseconds before the next delivery attempt.
     *
     * The schedule is exponential, capped at 30 minutes for the final retry slot:
     *
     * | Failed attempt | Delay before next |
     * |---|---|
     * | 1 |  30 s  |
     * | 2 |   2 min |
     * | 3 |  10 min |
     * | 4+ | 30 min |
     */
    private fun retryDelayMs(failedAttempt: Int): Long = when (failedAttempt) {
        1    ->    30_000L  //  30 s
        2    ->   120_000L  //   2 min
        3    ->   600_000L  //  10 min
        else -> 1_800_000L  //  30 min
    }
}
