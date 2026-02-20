package io.github.cvieirasp.worker.delivery

import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class DeliveryRepositoryImpl : DeliveryRepository {

    /** Updates the delivery status and related metadata for the given [deliveryId].
     *
     * This method is called by [io.github.cvieirasp.worker.queue.DeliveryConsumer] after
     * each delivery attempt to persist the outcome.  The database write is part of
     * the critical path for message acknowledgment, so if this method throws an
     * exception, the consumer nacks the message (requeue=false) to trigger
     * dead-lettering for manual inspection.
     */
    override fun updateStatus(
        deliveryId: UUID,
        status: DeliveryStatus,
        attempts: Int,
        lastError: String?,
        lastAttemptAt: Instant?,
        deliveredAt: Instant?,
    ) {
        transaction {
            DeliveryTable.update({ DeliveryTable.id eq deliveryId }) {
                it[DeliveryTable.status]        = status
                it[DeliveryTable.attempts]      = attempts
                it[DeliveryTable.lastError]     = lastError
                it[DeliveryTable.lastAttemptAt] = lastAttemptAt
                it[DeliveryTable.deliveredAt]   = deliveredAt
            }
        }
    }
}
