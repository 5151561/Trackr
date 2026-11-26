package com.trackr.core.network.remote.common

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout

/**
 * 超时设置，沿用 OkHttp 时代的数值（重构计划 §2.8、§2.10）：
 * 整个请求（含重定向重试）20 秒，连接 12 秒，读 15 秒、写 12 秒。
 */
object NetworkTimeouts {
    const val REQUEST_MS = 20_000L
    const val CONNECT_MS = 12_000L
    const val READ_MS = 15_000L
    const val WRITE_MS = 12_000L
    /** 下载安装包时两次读取之间允许的最长间隔（不限总时长，网慢也能下完） */
    const val DOWNLOAD_READ_MS = 60_000L
}

/** 平台的 HTTP 客户端：Android 用 OkHttp 引擎（连接 / 读 / 写超时设在 OkHttp 上），iOS 用 Darwin 引擎 */
internal expect fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * 应用共用的 Ktor 客户端。
 *
 * - 总超时 [NetworkTimeouts.REQUEST_MS]，弱网下单次请求不会无限挂起；
 * - 不开 Ktor 的 `expectSuccess`：状态码由各客户端自己判断、组织异常信息；
 * - 不装 Logging 插件（URL 里有 key）。
 *
 * @param engine 测试传 `MockEngine`；为 null 时用平台引擎
 * @param requestTimeoutMillis 总超时，测试可调小
 */
fun createTrackrHttpClient(
    engine: HttpClientEngine? = null,
    requestTimeoutMillis: Long = NetworkTimeouts.REQUEST_MS
): HttpClient {
    val configure: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        install(HttpTimeout) {
            this.requestTimeoutMillis = requestTimeoutMillis
        }
    }
    return if (engine != null) HttpClient(engine, configure) else platformHttpClient(configure)
}
