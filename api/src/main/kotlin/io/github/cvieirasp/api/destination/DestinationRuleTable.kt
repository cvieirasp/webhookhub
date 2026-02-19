package io.github.cvieirasp.api.destination

import org.jetbrains.exposed.sql.Table

/**
 * Defines the database table for destination rules.
 */
object DestinationRuleTable : Table("destination_rules") {
    val id = uuid("id")
    val destinationId = uuid("destination_id").references(DestinationTable.id)
    val sourceName = text("source_name")
    val eventType = text("event_type")

    override val primaryKey = PrimaryKey(id)
}
