package io.github.cvieirasp.api.destination

import java.util.UUID

data class DestinationRule(
    val id: UUID,
    val destinationId: UUID,
    val sourceName: String,
    val eventType: String,
)
