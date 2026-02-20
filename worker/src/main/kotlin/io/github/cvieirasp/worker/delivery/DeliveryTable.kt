package io.github.cvieirasp.worker.delivery

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.postgresql.util.PGobject

/**
 * Exposed table reference for the `deliveries` table.
 *
 * The worker only needs the columns it updates; the full schema is owned and
 * migrated by the API module (Flyway).
 */
object DeliveryTable : Table("deliveries") {
    val id            = uuid("id")
    val status        = customEnumeration(
        name  = "status",
        sql   = "delivery_status",
        fromDb = { value ->
            val str = if (value is PGobject) value.value!! else value as String
            DeliveryStatus.valueOf(str)
        },
        toDb = { s -> PGobject().apply { type = "delivery_status"; value = s.name } },
    )
    val attempts      = integer("attempts")
    val lastError     = text("last_error").nullable()
    val lastAttemptAt = timestamp("last_attempt_at").nullable()
    val deliveredAt   = timestamp("delivered_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
