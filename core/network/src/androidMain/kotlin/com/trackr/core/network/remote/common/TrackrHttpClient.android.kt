package com.trackr.core.network.remote.common

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

internal actual fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(NetworkTimeouts.CONNECT_MS, TimeUnit.MILLISECONDS)
                readTimeout(NetworkTimeouts.READ_MS, TimeUnit.MILLISECONDS)
                writeTimeout(NetworkTimeouts.WRITE_MS, TimeUnit.MILLISECONDS)
                // 整个调用（含重定向与重试）的总时限，和 HttpTimeout 插件的总超时一致
                callTimeout(NetworkTimeouts.REQUEST_MS, TimeUnit.MILLISECONDS)
            }
        }
        configure()
    }

/**
 * 下载 APK 用的客户端。
 *
 * 应用共用的 [createTrackrHttpClient] 把整个调用限制在 20 秒，几十兆的安装包会被它掐断。
 * 这里不设 `callTimeout`（不限总时长），只限制连接和两次读取之间的间隔。
 */
fun createTrackrDownloadHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                connectTimeout(NetworkTimeouts.CONNECT_MS, TimeUnit.MILLISECONDS)
                readTimeout(NetworkTimeouts.DOWNLOAD_READ_MS, TimeUnit.MILLISECONDS)
                writeTimeout(NetworkTimeouts.WRITE_MS, TimeUnit.MILLISECONDS)
            }
        }
    }
