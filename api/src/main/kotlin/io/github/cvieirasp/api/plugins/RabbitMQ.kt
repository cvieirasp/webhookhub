package io.github.cvieirasp.api.plugins

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.github.cvieirasp.shared.config.EnvConfig
import io.github.cvieirasp.shared.queue.RabbitMQTopology
import io.ktor.server.application.*

/**
 * Factory object for managing RabbitMQ connections and channels.
 */
object RabbitMQFactory {

    lateinit var connection: Connection
        private set

    /**
     * Initializes the RabbitMQ connection and declares the necessary topology.
     * This should be called during application startup.
     */
    fun init() {
        connection = ConnectionFactory().apply {
            host = EnvConfig.RabbitMQ.host
            port = EnvConfig.RabbitMQ.port
            username = EnvConfig.RabbitMQ.username
            password = EnvConfig.RabbitMQ.password
            virtualHost = EnvConfig.RabbitMQ.vhost
        }.newConnection()

        connection.createChannel().use { channel ->
            RabbitMQTopology.declare(channel)
        }
    }
}

fun Application.configureRabbitMQ() {
    RabbitMQFactory.init()
}
