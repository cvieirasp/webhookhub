package io.github.cvieirasp.shared.queue

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel

/**
 * Central definition of all RabbitMQ exchanges, queues, and bindings.
 *
 * Topology:
 *
 *   [Producer] ──► [webhookhub (direct)] ──► [webhookhub.deliveries (TTL=30min, DLX→deliveries.dlx)]
 *                         ▲                              │ nack (DEAD: exhausted retries or non-retryable)
 *                         │                             ▼
 *                         │                 [deliveries.dlx (fanout)] ──► [deliveries.dlq]
 *                         │
 *   [Worker retry] ──► [deliveries.retry.q (per-msg TTL, DLX→webhookhub, DLRK=delivery)]
 *                       Messages sit here for the backoff delay, then are forwarded
 *                       back to webhookhub.deliveries for the next attempt.
 */
object RabbitMQTopology {

    const val EXCHANGE              = "webhookhub"
    const val EXCHANGE_DLX          = "deliveries.dlx"
    const val QUEUE_DELIVERIES      = "webhookhub.deliveries"
    const val QUEUE_RETRY           = "deliveries.retry.q"
    const val QUEUE_DLQ             = "deliveries.dlq"
    const val ROUTING_KEY_DELIVERY  = "delivery"

    /** 30 minutes — messages not consumed within this window are dead-lettered. */
    private const val MESSAGE_TTL_MS = 1_800_000L

    /**
     * Idempotently declares all exchanges, queues, and bindings.
     *
     * RabbitMQ returns OK when a resource is re-declared with identical arguments,
     * so this is safe to call on every startup. A [com.rabbitmq.client.ShutdownSignalException]
     * will surface if an existing resource was declared with different arguments —
     * that signals a configuration mismatch and must be resolved manually.
     */
    fun declare(channel: Channel) {
        // Main exchange — producers publish DeliveryJob messages here
        channel.exchangeDeclare(EXCHANGE, BuiltinExchangeType.DIRECT, true)

        // Dedicated dead-letter exchange — fanout so the DLQ catches everything without a routing key.
        // Receives nacked messages from QUEUE_DELIVERIES: deliveries that exhausted all retries
        // or were classified as non-retryable (4xx ≠ 429).
        channel.exchangeDeclare(EXCHANGE_DLX, BuiltinExchangeType.FANOUT, true)

        // Dead-letter queue — terminal destination for permanently failed deliveries.
        // Bound to EXCHANGE_DLX with an empty routing key (fanout ignores it).
        channel.queueDeclare(QUEUE_DLQ, true, false, false, null)
        channel.queueBind(QUEUE_DLQ, EXCHANGE_DLX, "")

        // Main delivery queue with TTL and DLX forwarding
        channel.queueDeclare(
            QUEUE_DELIVERIES,
            true,
            false,
            false,
            mapOf(
                "x-message-ttl"          to MESSAGE_TTL_MS,
                "x-dead-letter-exchange" to EXCHANGE_DLX,
            ),
        )
        channel.queueBind(QUEUE_DELIVERIES, EXCHANGE, ROUTING_KEY_DELIVERY)

        // Retry holding queue — has no consumer. The worker publishes failed jobs here
        // with a per-message expiration (x-expiration) equal to the backoff delay.
        // When the TTL expires RabbitMQ dead-letters the message using:
        //   x-dead-letter-exchange    → EXCHANGE (webhookhub, direct)
        //   x-dead-letter-routing-key → ROUTING_KEY_DELIVERY (delivery)
        // The direct exchange routes "delivery" back to QUEUE_DELIVERIES, making the
        // message available for the next attempt without any additional binding.
        channel.queueDeclare(
            QUEUE_RETRY,
            true,
            false,
            false,
            mapOf(
                "x-dead-letter-exchange"    to EXCHANGE,
                "x-dead-letter-routing-key" to ROUTING_KEY_DELIVERY,
            ),
        )
        // Published via the default exchange (routing key = queue name); no explicit binding.
    }
}
