package com.trackr.core.network.remote.common

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

internal actual fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) {
        configure()
        // Darwin 没有分开的读 / 写超时，用 socketTimeout 取读超时
        install(HttpTimeout) {
            connectTimeoutMillis = NetworkTimeouts.CONNECT_MS
            socketTimeoutMillis = NetworkTimeouts.READ_MS
        }
    }
