package com.trackr.domain.model

import kotlinx.serialization.Serializable

/**
 * 乘车码渠道与偏好定义
 */
@Serializable
enum class TransitCodeProvider(val id: String, val displayName: String) {
    ASK_ALWAYS("ask_always", "每次询问"),
    ALIPAY("alipay", "支付宝乘车码"),
    UNIONPAY("unionpay", "云闪付乘车码");

    companion object {
        fun fromId(id: String?): TransitCodeProvider {
            return entries.firstOrNull { it.id == id } ?: ASK_ALWAYS
        }
    }
}
