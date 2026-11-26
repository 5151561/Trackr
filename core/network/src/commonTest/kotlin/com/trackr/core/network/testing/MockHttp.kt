package com.trackr.core.network.testing

import com.trackr.core.network.remote.common.createTrackrHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/*
 * 测试用的 HTTP 替身：Ktor MockEngine，不发真实请求（重构计划第 8 步「Provider 测试用 MockEngine」）。
 * 写法贴近原来的 OkHttp 拦截器，方便对照：处理函数拿到 [MockRequest]，返回 [MockReply]。
 */

/** 一次被拦下的请求 */
class MockRequest(val data: HttpRequestData) {
    /** 完整 URL（含 query） */
    val url: String get() = data.url.toString()

    val encodedPath: String get() = data.url.encodedPath

    fun queryParameter(name: String): String? = data.url.parameters[name]

    fun header(name: String): String? = data.headers[name]

    /** 请求体文本（表单、JSON） */
    val body: String
        get() = when (val content = data.body) {
            is TextContent -> content.text
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            else -> ""
        }

    /** 请求体的 Content-Type（TextContent 上的） */
    val contentType: String? get() = data.body.contentType?.toString()
}

/** 替身返回的响应 */
class MockReply(val body: String, val code: Int = 200)

fun interface MockHandler {
    suspend fun handle(request: MockRequest): MockReply
}

/** 和原来测试里的 `mockResponse(request, json)` 同名同参，返回 [MockReply] */
fun mockResponse(@Suppress("UNUSED_PARAMETER") request: MockRequest, bodyJson: String, code: Int = 200): MockReply =
    MockReply(bodyJson, code)

/** 用 [handler] 应答所有请求的客户端（和生产一样经 [createTrackrHttpClient] 装超时插件） */
fun mockHttpClient(
    requestTimeoutMillis: Long = 20_000L,
    handler: MockHandler
): HttpClient = createTrackrHttpClient(
    engine = MockEngine { data ->
        val reply = handler.handle(MockRequest(data))
        respond(
            content = reply.body,
            status = HttpStatusCode.fromValue(reply.code),
            headers = headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8")
        )
    },
    requestTimeoutMillis = requestTimeoutMillis
)

/** 不该被调用的客户端：只测不发请求的逻辑时用 */
fun unusedHttpClient(): HttpClient = mockHttpClient { error("unexpected HTTP request: ${it.url}") }

/** 线程安全的计数器（MockEngine 的处理函数在别的线程跑） */
class HitCounter {
    private val lock = SynchronizedObject()
    private val counts = HashMap<String, Int>()

    fun hit(key: String) {
        synchronized(lock) { counts[key] = (counts[key] ?: 0) + 1 }
    }

    fun count(key: String): Int = synchronized(lock) { counts[key] ?: 0 }
}
