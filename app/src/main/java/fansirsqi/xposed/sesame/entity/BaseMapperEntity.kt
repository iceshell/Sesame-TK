package fansirsqi.xposed.sesame.entity

import fansirsqi.xposed.sesame.util.maps.IdMapManager

/**
 * Entity工具：统一从 IdMap 构建列表的模板方法。
 * 用法：EntityHelper.getList(SesameGiftMap::class.java) { k, v -> SesameGift(k, v) }
 */
object EntityHelper {
    fun <T : MapperEntity, M : IdMapManager> getList(
        mapClass: Class<M>,
        factory: (String, String) -> T
    ): List<T> {
        return IdMapManager.getInstance(mapClass).map
            .map { (key, value) -> factory(key, value) }
    }
}
