package io.github.cvieirasp.api.delivery

import io.github.cvieirasp.api.NotFoundException
import java.util.UUID

class DeliveryUseCase(private val repository: DeliveryRepository) {

    fun getDelivery(id: UUID): Delivery =
        repository.findById(id) ?: throw NotFoundException("delivery not found")

    fun listDeliveries(filter: DeliveryFilter, page: Int, pageSize: Int): Pair<Long, List<Delivery>> =
        repository.findFiltered(filter, page, pageSize)
}
