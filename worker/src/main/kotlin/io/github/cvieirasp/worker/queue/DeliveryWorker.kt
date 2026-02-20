package io.github.cvieirasp.worker.queue

import com.rabbitmq.client.Connection
import io.github.cvieirasp.shared.queue.RabbitMQTopology
import io.github.cvieirasp.worker.delivery.DeliveryRepositoryImpl
import io.github.cvieirasp.worker.http.HttpDeliveryClient
import org.slf4j.LoggerFactory

/**
 * Bootstraps the RabbitMQ delivery consumer.
 *
 * Call [start] once on application startup.  The method creates a dedicated
 * channel, configures the prefetch window, and registers [DeliveryConsumer]
 * on [RabbitMQTopology.QUEUE_DELIVERIES] with `autoAck=false`.
 */
object DeliveryWorker {

    /**
     * Maximum number of unacknowledged messages the broker may push to this
     * consumer at one time.  Matches the HikariCP pool size so every
     * in-flight message can always acquire a database connection.
     */
    const val PREFETCH_COUNT = 5

    private val logger = LoggerFactory.getLogger(DeliveryWorker::class.java)

    private lateinit var httpClient: HttpDeliveryClient

    /**
     * Initializes the delivery consumer on the given RabbitMQ connection.
     * The connection is shared across all workers, but each worker creates its own channel and HTTP client.
     */
    fun start(connection: Connection) {
        httpClient = HttpDeliveryClient()

        val channel = connection.createChannel()

        // Limit the number of unacknowledged messages dispatched to this consumer.
        // RabbitMQ will not push more than PREFETCH_COUNT messages until older ones
        // are acked, bounding memory usage and concurrency for this process.
        channel.basicQos(PREFETCH_COUNT)

        val consumer = DeliveryConsumer(
            channel    = channel,
            repository = DeliveryRepositoryImpl(),
            httpClient = httpClient,
        )

        channel.basicConsume(
            RabbitMQTopology.QUEUE_DELIVERIES,
            false,    // autoAck=false — consumer acks manually after DB persist
            consumer,
        )

        logger.info(
            "Listening on queue='{}' prefetch={}",
            RabbitMQTopology.QUEUE_DELIVERIES,
            PREFETCH_COUNT,
        )
    }

    /** Releases the underlying Ktor HTTP engine. Call on application shutdown. */
    fun close() {
        if (::httpClient.isInitialized) httpClient.close()
    }
}
