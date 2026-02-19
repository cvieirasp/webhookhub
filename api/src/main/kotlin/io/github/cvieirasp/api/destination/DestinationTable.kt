package io.github.cvieirasp.api.destination

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

/**
 * Defines the database table for destinations.
 */
object DestinationTable : Table("destinations") {
    val id = uuid("id")
    val name = text("name")
    val targetUrl = text("target_url")
    val active = bool("active").default(true)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
