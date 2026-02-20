package io.github.cvieirasp.worker.queue

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.github.cvieirasp.shared.config.EnvConfig
import io.github.cvieirasp.shared.queue.RabbitMQTopology

object RabbitMQFactory {

    private lateinit var connection: Connection

    fun init(): Connection {
        connection = ConnectionFactory().apply {
            host        = EnvConfig.RabbitMQ.host
            port        = EnvConfig.RabbitMQ.port
            username    = EnvConfig.RabbitMQ.username
            password    = EnvConfig.RabbitMQ.password
            virtualHost = EnvConfig.RabbitMQ.vhost
        }.newConnection()

        // Idempotently declare exchanges, queues, and bindings so the worker
        // can start independently of whether the API has run first.
        connection.createChannel().use { ch -> RabbitMQTopology.declare(ch) }

        return connection
    }

    fun close() {
        if (::connection.isInitialized && connection.isOpen) {
            connection.close()
        }
    }
}
