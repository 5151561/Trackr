package com.trackr.core.network.remote.common

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.Url
import kotlinx.io.IOException

/**
 * 网络异常信息去敏：异常和日志里的地址只留 scheme + host + path（重构计划 §2.10「异常信息不带 query」）。
 */
object NetworkErrors {

    /** 去掉 query / fragment / 用户信息后的 URL */
    fun safeUrl(url: Url): String = "${url.protocol.name}://${url.host}${url.encodedPath}"

    /** URL 后面的 query / fragment：到空白、方括号或「逗号 + 空白」为止（query 里的逗号如 `origin=1,2` 一并去掉） */
    private val urlWithQuery = Regex("""(https?://[^\s?#\]\[,]+)[?#](?:[^\s\]\[,]|,(?!\s))*""")

    /** 把文本里出现的 URL 去掉 query / fragment */
    fun redactUrls(text: String): String = urlWithQuery.replace(text) { it.groupValues[1] }

    /**
     * 把请求时抛出的异常换成不带 query 的 [IOException]。
     * 超时类异常的消息里带完整 URL，原样丢掉不再挂成 cause；其余异常的消息去掉 URL 的 query 后保留，
     * 只有消息里本来就没有 query 时才把原异常挂成 cause（方便排查又不漏 key）。
     */
    fun sanitize(error: Throwable, target: String): IOException {
        if (error is HttpRequestTimeoutException || error is ConnectTimeoutException ||
            error::class.simpleName == "SocketTimeoutException"
        ) {
            return IOException("Request timeout for $target")
        }
        val rawMessage = error.message.orEmpty()
        val redacted = redactUrls(rawMessage)
        val name = error::class.simpleName ?: "Exception"
        val message = if (redacted.isBlank()) "$name for $target" else "$name for $target: $redacted"
        return if (redacted == rawMessage) IOException(message, error) else IOException(message)
    }
}
