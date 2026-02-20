package io.github.cvieirasp.worker

import io.github.cvieirasp.worker.db.DatabaseFactory
import io.github.cvieirasp.worker.queue.DeliveryWorker
import io.github.cvieirasp.worker.queue.RabbitMQFactory
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch

private val logger = LoggerFactory.getLogger("io.github.cvieirasp.worker")

fun main() {
    val latch = CountDownLatch(1)

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutdown signal received")
        DeliveryWorker.close()
        RabbitMQFactory.close()
        DatabaseFactory.close()
        latch.countDown()
    })

    logger.info("Initializing database connection pool")
    DatabaseFactory.init()
    logger.info("Database ready (ping={})", DatabaseFactory.ping())

    logger.info("Initializing RabbitMQ connection")
    val connection = RabbitMQFactory.init()

    DeliveryWorker.start(connection)

    // Block the main thread until the shutdown hook fires.
    latch.await()
    logger.info("Worker stopped")
}
