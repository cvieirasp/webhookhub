package io.github.cvieirasp.api.delivery

import java.util.UUID

data class DeliveryFilter(
    val status: DeliveryStatus? = null,
    val eventId: UUID? = null,
)
