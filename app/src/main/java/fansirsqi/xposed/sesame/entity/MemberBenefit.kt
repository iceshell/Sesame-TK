package fansirsqi.xposed.sesame.entity

import fansirsqi.xposed.sesame.util.maps.IdMapManager
import fansirsqi.xposed.sesame.util.maps.MemberBenefitsMap

class MemberBenefit(i: String, n: String) : MapperEntity() {

    init {
        id = i
        name = n
    }

    companion object {
        @JvmStatic
        fun getList(): List<MemberBenefit> = EntityHelper.getList(MemberBenefitsMap::class.java) { k, v -> MemberBenefit(k, v) }
    }
}