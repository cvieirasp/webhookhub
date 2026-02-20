package io.github.cvieirasp.shared.queue

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel

/**
 * Central definition of all RabbitMQ exchanges, queues, and bindings.
 *
 * Topology:
 *
 *   [Producer] ──► [webhookhub (direct)] ──► [webhookhub.deliveries (TTL=30min, DLX→webhookhub.dlx)]
 *                         ▲                              │ expired / nack
 *                         │                             ▼
 *                         │                 [webhookhub.dlx (fanout)] ──► [webhookhub.dlq]
 *                         │
 *   [Worker retry] ──► [webhookhub.retry (per-msg TTL, DLX→webhookhub, DLK=delivery)]
 *                       Messages sit here for the backoff delay, then are forwarded
 *                       back to webhookhub.deliveries for the next attempt.
 */
object RabbitMQTopology {

    const val EXCHANGE              = "webhookhub"
    const val EXCHANGE_DLX          = "webhookhub.dlx"
    const val QUEUE_DELIVERIES      = "webhookhub.deliveries"
    const val QUEUE_RETRY           = "webhookhub.retry"
    const val QUEUE_DLQ             = "webhookhub.dlq"
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

        // Dead-letter exchange — fanout so the DLQ catches everything without a routing key
        channel.exchangeDeclare(EXCHANGE_DLX, BuiltinExchangeType.FANOUT, true)

        // Dead-letter queue bound to DLX (no routing key needed for fanout)
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
        // with a per-message expiration equal to the backoff delay. When the TTL expires,
        // RabbitMQ dead-letters the message back to the main exchange with the delivery
        // routing key, making it available for the next attempt.
        channel.queueDeclare(
            QUEUE_RETRY,
            true,
            false,
            false,
            mapOf(
                "x-dead-letter-exchange"     to EXCHANGE,
                "x-dead-letter-routing-key"  to ROUTING_KEY_DELIVERY,
            ),
        )
        // Published via the default exchange (routing key = queue name); no explicit binding.
    }
}
