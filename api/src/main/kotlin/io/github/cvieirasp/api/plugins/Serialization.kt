package io.github.cvieirasp.api.plugins

import io.github.cvieirasp.shared.config.AppJson
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*

/**
 * This file is for configuring serialization in the Ktor application.
 * It sets up JSON serialization using kotlinx.serialization with a custom configuration defined in AppJson.
 */
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(AppJson)
    }
}
