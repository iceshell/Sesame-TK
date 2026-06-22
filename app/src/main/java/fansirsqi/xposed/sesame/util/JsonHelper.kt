package fansirsqi.xposed.sesame.util

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * JSON 工具类（Kotlin 友好封装）
 *
 * 使用 jacksonObjectMapper() 确保 Kotlin data class 的序列化/反序列化支持
 * （无参构造函数、默认参数等），同时应用与 JsonUtil 一致的配置。
 */
object JsonHelper {

    val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
    }

    inline fun <reified T> fromJson(json: String): T {
        return mapper.readValue(json)
    }

    fun toJson(obj: Any): String {
        return mapper.writeValueAsString(obj)
    }
}
