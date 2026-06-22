package fansirsqi.xposed.sesame.util

import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.data.StatusFlags
import org.json.JSONObject

/**
 * 全局风控检测器
 *
 * 检测支付宝风控验证(error:1009)并设置当日跳过标记，
 * 避免继续发送 RPC 请求导致账号被进一步限制。
 */
object RiskControlDetector {

    private const val TAG = "RiskControl"

    /**
     * 检测 RPC 响应是否触发风控验证
     * @param resp RPC 响应字符串
     * @return true 表示已触发风控，应立即停止所有请求
     */
    @JvmStatic
    fun checkRiskControl(resp: String?): Boolean {
        if (resp.isNullOrEmpty()) return false
        return try {
            val jo = JSONObject(resp)
            val errorCode = jo.optInt("error", -1)
            if (errorCode == 1009) {
                triggerRiskControl(jo.optString("errorMessage", "风控验证"))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检测 JSONObject 是否触发风控验证
     */
    @JvmStatic
    fun checkRiskControl(jo: JSONObject?): Boolean {
        if (jo == null) return false
        val errorCode = jo.optInt("error", -1)
        return if (errorCode == 1009) {
            triggerRiskControl(jo.optString("errorMessage", "风控验证"))
            true
        } else {
            false
        }
    }

    /**
     * 标记今日已触发风控
     */
    private fun triggerRiskControl(message: String) {
        if (!Status.hasFlagToday(StatusFlags.FLAG_GLOBAL_RISK_CONTROL_TRIGGERED)) {
            Log.error(TAG, "🚫 检测到支付宝风控验证(error:1009): $message，暂停今日所有 RPC 请求")
            Status.setFlagToday(StatusFlags.FLAG_GLOBAL_RISK_CONTROL_TRIGGERED)
        }
    }

    /**
     * 检查今日是否已触发风控
     * @return true 表示今日已触发风控，应跳过所有任务
     */
    fun isRiskControlTriggered(): Boolean {
        return Status.hasFlagToday(StatusFlags.FLAG_GLOBAL_RISK_CONTROL_TRIGGERED)
    }
}
