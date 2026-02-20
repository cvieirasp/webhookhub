package io.github.cvieirasp.worker.delivery

import kotlinx.datetime.Instant
import java.util.UUID

interface DeliveryRepository {
    fun updateStatus(
        deliveryId: UUID,
        status: DeliveryStatus,
        attempts: Int,
        lastError: String? = null,
        lastAttemptAt: Instant? = null,
        deliveredAt: Instant? = null,
    )
}
