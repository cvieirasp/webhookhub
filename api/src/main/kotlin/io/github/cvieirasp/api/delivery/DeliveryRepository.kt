package io.github.cvieirasp.api.delivery

import java.util.UUID

interface DeliveryRepository {
    fun createPending(delivery: Delivery): Delivery
    fun findById(id: UUID): Delivery?
    fun findFiltered(filter: DeliveryFilter, page: Int, pageSize: Int): Pair<Long, List<Delivery>>
}
