package io.github.cvieirasp.api.delivery

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class DeliveryRepositoryImpl : DeliveryRepository {

    /**
     * Creates a new pending delivery in the database.
     *
     * @param delivery The delivery to be created.
     * @return The created delivery.
     */
    override fun createPending(delivery: Delivery): Delivery = transaction {
        DeliveryTable.insert {
            it[id] = delivery.id
            it[eventId] = delivery.eventId
            it[destinationId] = delivery.destinationId
            it[status] = delivery.status
            it[attempts] = delivery.attempts
            it[maxAttempts] = delivery.maxAttempts
            it[lastError] = delivery.lastError
            it[lastAttemptAt] = delivery.lastAttemptAt
            it[deliveredAt] = delivery.deliveredAt
            it[createdAt] = delivery.createdAt
        }
        delivery
    }

    override fun findById(id: UUID): Delivery? = transaction {
        DeliveryTable
            .selectAll()
            .where { DeliveryTable.id eq id }
            .map { it.toDelivery() }
            .singleOrNull()
    }

    override fun findFiltered(filter: DeliveryFilter, page: Int, pageSize: Int): Pair<Long, List<Delivery>> = transaction {
        fun buildQuery() = DeliveryTable.selectAll().apply {
            filter.status?.let { s -> andWhere { DeliveryTable.status eq s } }
            filter.eventId?.let { e -> andWhere { DeliveryTable.eventId eq e } }
        }
        val total = buildQuery().count()
        val items = buildQuery()
            .orderBy(DeliveryTable.createdAt to SortOrder.DESC)
            .limit(pageSize, ((page - 1) * pageSize).toLong())
            .map { it.toDelivery() }
        total to items
    }

    private fun ResultRow.toDelivery() = Delivery(
        id            = this[DeliveryTable.id],
        eventId       = this[DeliveryTable.eventId],
        destinationId = this[DeliveryTable.destinationId],
        status        = this[DeliveryTable.status],
        attempts      = this[DeliveryTable.attempts],
        maxAttempts   = this[DeliveryTable.maxAttempts],
        lastError     = this[DeliveryTable.lastError],
        lastAttemptAt = this[DeliveryTable.lastAttemptAt],
        deliveredAt   = this[DeliveryTable.deliveredAt],
        createdAt     = this[DeliveryTable.createdAt],
    )
}
