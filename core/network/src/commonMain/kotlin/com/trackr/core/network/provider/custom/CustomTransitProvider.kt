package com.trackr.core.network.provider.custom

import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.CustomSourceConfig
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.network.provider.ITransitProvider
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.network.remote.common.BaseTransitApiClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.io.IOException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

/** 用户还没在「我的 → 数据源」里填服务地址 */
class DataSourceNotConfiguredException :
    IOException("尚未接入数据源：请到「我的 → 数据源」填写你自己的数据源服务地址")

/**
 * 用户自行接入的数据源。
 *
 * Trackr 本身不内置、不提供任何第三方实时公交数据源。用户在「我的 → 数据源」里填一个自己部署的
 * 服务地址，这个服务按 `docs/provider-api.md` 里的 Trackr 数据源协议（HTTP + JSON）返回数据，
 * App 只认这份协议，不关心服务背后接的是什么。
 *
 * @param configProvider 每次请求时读当前配置（用户改完立即生效，不用重启）
 */
class CustomTransitProvider(
    httpClient: HttpClient,
    private val configProvider: () -> CustomSourceConfig
) : BaseTransitApiClient(httpClient), ITransitProvider {

    override val sourceType: DataSourceType = DataSourceType.CUSTOM

    override val cacheKey: String
        get() = configProvider().normalizedBaseUrl

    /** 只有一个数据源：所有 id 都归它 */
    override fun supportsLine(lineId: String): Boolean = true
    override fun supportsStop(request: StopDetailRequest): Boolean = true

    /** 能力声明按服务的 manifest 缓存；还没拿到时按「全支持」处理，真不支持服务端返回空即可 */
    private var cachedManifest: Pair<String, ProtocolManifest>? = null

    private fun capabilities(): ProtocolCapabilities {
        val base = configProvider().normalizedBaseUrl
        return cachedManifest?.takeIf { it.first == base }?.second?.capabilities ?: ProtocolCapabilities()
    }

    override fun supportsLocationRadar(): Boolean = capabilities().nearby
    override fun stopDetailIncludesBuses(): Boolean = capabilities().stopDetailIncludesBuses

    /** 读取（并缓存）服务的 manifest；服务没实现 manifest 时用默认能力 */
    suspend fun fetchManifest(): ProtocolManifest {
        val config = requireConfig()
        val manifest = runCatching { request("manifest", null, ProtocolManifest.serializer(), get = true) }
            .getOrElse { if (it is DataSourceNotConfiguredException) throw it else ProtocolManifest() }
        cachedManifest = config.normalizedBaseUrl to manifest
        return manifest
    }

    private suspend fun ensureManifest() {
        val base = configProvider().normalizedBaseUrl
        if (base.isNotEmpty() && cachedManifest?.first != base) fetchManifest()
    }

    override suspend fun isCityReachable(city: TransitCity): Boolean {
        if (!configProvider().isConfigured) return true
        ensureManifest()
        if (!capabilities().cityReachability) return true
        return runCatching {
            request("reachable", CityBody(city.toProtocol()), ReachableResponse.serializer()).reachable
        }.getOrDefault(true)
    }

    override suspend fun listAllLines(city: TransitCity): List<BusLineSummary>? {
        if (!configProvider().isConfigured) return null
        ensureManifest()
        if (!capabilities().listAllLines) return null
        return request("lines", CityBody(city.toProtocol()), ListSerializer(BusLineSummary.serializer()))
    }

    override suspend fun getSupportedCities(): List<TransitCity> {
        if (!configProvider().isConfigured) return emptyList()
        ensureManifest()
        return request("cities", null, ListSerializer(ProtocolCity.serializer()), get = true).map { wire ->
            val city = wire.toDomain()
            if (city.province.isBlank() || city.province == "其他") {
                city.copy(province = ProvinceCityCatalog.inferProvince(city.cityName, city.mygolbsCityCode))
            } else {
                city
            }
        }
    }

    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? =
        geocode(wgsLat, wgsLng)?.city?.takeIf { it.isNotBlank() }

    override suspend fun resolveDistrictByLocation(wgsLat: Double, wgsLng: Double): String? =
        geocode(wgsLat, wgsLng)?.district?.takeIf { it.isNotBlank() }

    private suspend fun geocode(lat: Double, lng: Double): GeocodeResponse? {
        if (!configProvider().isConfigured) return null
        ensureManifest()
        val caps = capabilities()
        // 不支持附近站时位置雷达整体关闭，逆地理也不问（和 docs/provider-api.md 一致）
        if (!caps.nearby || !caps.geocode) return null
        return request("geocode", GeocodeBody(lat, lng), GeocodeResponse.serializer())
    }

    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): TransitSearchResult =
        request("search", SearchBody(city.toProtocol(), keyword, userLat, userLng), TransitSearchResult.serializer())

    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): List<NearbyStop> {
        ensureManifest()
        if (!capabilities().nearby) return emptyList()
        return request("nearby", NearbyBody(city.toProtocol(), wgsLat, wgsLng, limit), ListSerializer(NearbyStop.serializer()))
    }

    override suspend fun getStopDetail(city: TransitCity, request: StopDetailRequest): List<NearbyStop> =
        request(
            "stop",
            StopBody(
                city = city.toProtocol(),
                stopId = request.stopId,
                stopName = request.stopName,
                physicalStId = request.physicalStId,
                namesakeStId = request.namesakeStId,
                stationLat = request.stationLat,
                stationLng = request.stationLng,
                userLat = request.userLat,
                userLng = request.userLng
            ),
            ListSerializer(NearbyStop.serializer())
        )

    override suspend fun getLineDetail(city: TransitCity, request: LineDetailRequest): BusLineDetail =
        request(
            "line",
            LineBody(
                city = city.toProtocol(),
                lineId = request.lineId,
                targetOrder = request.targetOrder,
                stationId = request.stationId,
                userLat = request.wgsLat,
                userLng = request.wgsLng,
                lineNameHint = request.lineNameHint,
                directionHint = request.directionHint
            ),
            BusLineDetail.serializer()
        )

    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): LineTimetable {
        ensureManifest()
        if (!capabilities().timetable) {
            return LineTimetable(lineId = line.lineId, lineName = line.name, depDesc = "当前数据源未提供时刻表")
        }
        return request("timetable", TimetableBody(city.toProtocol(), line, atTimeHHMM), LineTimetable.serializer())
    }

    private fun requireConfig(): CustomSourceConfig {
        val config = configProvider()
        if (!config.isConfigured) throw DataSourceNotConfiguredException()
        return config
    }

    private suspend inline fun <reified B : Any, R> request(
        path: String,
        body: B?,
        responseSerializer: KSerializer<R>,
        get: Boolean = false
    ): R {
        val config = requireConfig()
        val payload = body?.let { protocolJson.encodeToString(kotlinx.serialization.serializer<B>(), it) }
        val response = executeHttpCall {
            url("${config.normalizedBaseUrl}/v1/$path")
            method = if (get) HttpMethod.Get else HttpMethod.Post
            header(HttpHeaders.Accept, "application/json")
            if (config.token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${config.token.trim()}")
            if (payload != null) {
                setBody(TextContent(payload, ContentType.Application.Json))
            }
        }
        if (!response.isSuccessful) throw IOException("数据源返回 HTTP ${response.code}（${response.safeUrl}）")
        val text = response.body.ifBlank { throw IOException("数据源返回了空响应（${response.safeUrl}）") }
        return try {
            protocolJson.decodeFromString(responseSerializer, text)
        } catch (e: Exception) {
            throw IOException("数据源响应格式不符合 Trackr 数据源协议（${response.safeUrl}）：${e.message}", e)
        }
    }

    companion object {
        /** 当前实现的协议版本 */
        const val PROTOCOL_VERSION = 1

        internal val protocolJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
            encodeDefaults = true
        }
    }
}

/** `GET /v1/manifest`：服务自我介绍与能力声明。整个接口可选，不实现时按默认值 */
@Serializable
data class ProtocolManifest(
    val protocolVersion: Int = CustomTransitProvider.PROTOCOL_VERSION,
    val name: String = "",
    val capabilities: ProtocolCapabilities = ProtocolCapabilities()
)

@Serializable
data class ProtocolCapabilities(
    /** 支持附近站（`/v1/nearby`） */
    val nearby: Boolean = true,
    /** 支持逆地理（`/v1/geocode`） */
    val geocode: Boolean = true,
    /** 支持时刻表（`/v1/timetable`） */
    val timetable: Boolean = true,
    /** `/v1/stop` 返回的 approachingBuses 带全线站序 */
    val stopDetailIncludesBuses: Boolean = true,
    /** 支持列出城市全部线路（`/v1/lines`） */
    val listAllLines: Boolean = false,
    /** 支持城市在线探测（`/v1/reachable`） */
    val cityReachability: Boolean = false
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class ProtocolCity(
    val cityId: String,
    val cityName: String,
    val pinyin: String = "",
    val supportSubway: Boolean = false,
    val isHot: Boolean = false,
    @JsonNames("mygolbsCityCode")
    val adcode: String? = null,
    val province: String = "",
    val parentCity: String? = null
) {
    fun toDomain(): TransitCity = TransitCity(
        cityId = cityId,
        cityName = cityName,
        pinyin = pinyin,
        supportSubway = supportSubway,
        isHot = isHot,
        mygolbsCityCode = adcode,
        province = province,
        parentCity = parentCity
    )
}

private fun TransitCity.toProtocol(): ProtocolCity = ProtocolCity(
    cityId = cityId,
    cityName = cityName,
    pinyin = pinyin,
    supportSubway = supportSubway,
    isHot = isHot,
    adcode = mygolbsCityCode,
    province = province,
    parentCity = parentCity
)

@Serializable
internal data class CityBody(val city: ProtocolCity)

@Serializable
internal data class GeocodeBody(val lat: Double, val lng: Double)

@Serializable
internal data class GeocodeResponse(val city: String? = null, val district: String? = null)

@Serializable
internal data class ReachableResponse(val reachable: Boolean = true)

@Serializable
internal data class SearchBody(
    val city: ProtocolCity,
    val keyword: String,
    val userLat: Double? = null,
    val userLng: Double? = null
)

@Serializable
internal data class NearbyBody(
    val city: ProtocolCity,
    val lat: Double,
    val lng: Double,
    val limit: Int
)

@Serializable
internal data class StopBody(
    val city: ProtocolCity,
    val stopId: String? = null,
    val stopName: String? = null,
    val physicalStId: String? = null,
    val namesakeStId: String? = null,
    val stationLat: Double? = null,
    val stationLng: Double? = null,
    val userLat: Double? = null,
    val userLng: Double? = null
)

@Serializable
internal data class LineBody(
    val city: ProtocolCity,
    val lineId: String,
    val targetOrder: Int? = null,
    val stationId: String? = null,
    val userLat: Double? = null,
    val userLng: Double? = null,
    val lineNameHint: String = "",
    val directionHint: Int = 0
)

@Serializable
internal data class TimetableBody(
    val city: ProtocolCity,
    val line: BusLineDirection,
    val at: String? = null
)
