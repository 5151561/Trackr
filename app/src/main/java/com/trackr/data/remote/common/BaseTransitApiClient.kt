package com.trackr.data.remote.common

import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.sanitizePlateOrFleetNo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * 实时公交 API 客户端抽象基类。
 * 封装底层的 OkHttp 请求调度、响应转 JSON、纯地铁站点过滤、测距与车牌清洗等通用算法。
 */
abstract class BaseTransitApiClient(
    protected val httpClient: OkHttpClient
) {

    /**
     * 可取消的 HTTP 发包：使用 enqueue 异步执行，协程取消时同步取消 OkHttp Call，
     * 避免页面暂停/离开后请求仍在后台跑完。
     */
    protected open suspend fun executeHttpCall(request: Request): Response {
        val call = try {
            httpClient.newCall(request)
        } catch (e: Exception) {
            throw IOException("HTTP request creation failed for ${safeUrl(request.url)}: ${e.message}", e)
        }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 已取消时 resume 会被忽略
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isActive) {
                        response.close()
                        return
                    }
                    cont.resume(response) { response.close() }
                }
            })
        }
    }

    /**
     * 统一读取 response body、校验状态码并安全解析为 [JSONObject]。
     * 异常信息只带 scheme + host + path，不带 query（避免把 key 等敏感参数写进日志）。
     */
    protected open suspend fun executeJson(request: Request): JSONObject {
        val response = executeHttpCall(request)
        return withContext(Dispatchers.IO) {
            response.use { resp ->
                val target = safeUrl(request.url)
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code} error for $target")
                }
                val bodyStr = resp.body.string().ifBlank { throw IOException("Empty response body from $target") }
                try {
                    JSONObject(bodyStr)
                } catch (e: Exception) {
                    throw IOException("Malformed JSON response from $target: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 去掉 query / fragment / 用户信息后的 URL，仅用于异常与日志
     */
    protected fun safeUrl(url: HttpUrl): String = "${url.scheme}://${url.host}${url.encodedPath}"

    /**
     * 统一识别站点名称是否代表纯地铁站（如包含“（地铁站）”、“(地铁站)”、“（M）”、“(M)”等特征）。
     */
    protected open fun isSubwayStationName(stationName: String): Boolean {
        val trimmed = stationName.trim()
        return trimmed.endsWith("（M）", ignoreCase = true) ||
                trimmed.endsWith("(M)", ignoreCase = true) ||
                trimmed.contains("（地铁站）") ||
                trimmed.contains("(地铁站)")
    }

    /**
     * 统一清洗地铁相关后缀。
     */
    protected open fun cleanSubwaySuffix(stationName: String): String {
        return stationName.trim()
            .removeSuffix("（地铁站）")
            .removeSuffix("(地铁站)")
            .removeSuffix("（M）")
            .removeSuffix("(M)")
            .trim()
    }

    /**
     * 统一服务端距离优先、无则自动通过 [RouteProjectionMath.calculateDistanceMeters] 补全的计算逻辑。
     */
    protected open fun resolveDistance(
        serverDistance: Int?,
        userLat: Double?,
        userLng: Double?,
        stationLat: Double,
        stationLng: Double
    ): Int? {
        if (serverDistance != null && serverDistance > 0) {
            return serverDistance
        }
        if (userLat != null && userLng != null && userLat > 0.0 && userLng > 0.0 && stationLat > 0.0 && stationLng > 0.0) {
            return RouteProjectionMath.calculateDistanceMeters(userLat, userLng, stationLat, stationLng)
        }
        return null
    }

    /**
     * 统一清洗车牌号与自编号，去除机器哈希与无效数据。
     */
    protected open fun sanitizeBusPlate(raw: String?): String {
        return sanitizePlateOrFleetNo(raw)
    }
}
