package fansirsqi.xposed.sesame.entity

import fansirsqi.xposed.sesame.util.maps.IdMapManager
import fansirsqi.xposed.sesame.util.maps.SesameGiftMap

class SesameGift(i: String, n: String) : MapperEntity() {
    init {
        id = i; name = n
    }

    companion object {
        @JvmStatic
        fun getList(): List<SesameGift> = EntityHelper.getList(SesameGiftMap::class.java) { k, v -> SesameGift(k, v) }
    }
}