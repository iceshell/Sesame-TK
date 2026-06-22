package fansirsqi.xposed.sesame.entity

import fansirsqi.xposed.sesame.util.maps.CooperateMap
import fansirsqi.xposed.sesame.util.maps.IdMapManager

/**
 * 表示合作用户的实体类，包含 ID 和名称。
 */
class CooperateEntity(i: String, n: String) : MapperEntity() {
    init {
        id = i
        name = n
    }

    companion object {
        @JvmStatic
        fun getList(): List<CooperateEntity> = EntityHelper.getList(CooperateMap::class.java) { k, v -> CooperateEntity(k, v) }
    }

}
