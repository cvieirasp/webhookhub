package io.github.cvieirasp.api.destination

import kotlinx.datetime.Instant
import java.util.UUID

data class Destination(
    val id: UUID,
    val name: String,
    val targetUrl: String,
    val active: Boolean,
    val createdAt: Instant,
    val rules: List<DestinationRule> = emptyList(),
)
