package com.trackr.data.remote.common

import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.sanitizePlateOrFleetNo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * 实时公交 API 客户端抽象基类。
 * 封装底层的 OkHttp 请求调度、响应转 JSON、纯地铁站点过滤、测距与车牌清洗等通用算法。
 */
abstract class BaseTransitApiClient(
    protected val httpClient: OkHttpClient
) {

    /**
     * 协程内统一调度 [Dispatchers.IO] 执行 HTTP 发包，并做基础网络异常处理。
     */
    protected open suspend fun executeHttpCall(request: Request): Response = withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("HTTP request execution failed: ${e.message}", e)
        }
    }

    /**
     * 统一读取 response body、校验状态码并安全解析为 [JSONObject]。
     */
    protected open suspend fun executeJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        val response = executeHttpCall(request)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("HTTP $code error for ${request.url}")
        }
        val bodyStr = response.body.string().ifBlank { throw IOException("Empty response body from ${request.url}") }
        try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            throw IOException("Malformed JSON response from ${request.url}: ${e.message}", e)
        }
    }

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
