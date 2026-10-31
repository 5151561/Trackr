package com.trackr.data.provider

import com.trackr.data.provider.chelaile.ChelaileTransitProvider
import com.trackr.data.provider.model.LineDetailRequest
import com.trackr.data.provider.model.StopDetailRequest
import com.trackr.data.provider.mygolbs.MygolbsTransitProvider
import com.trackr.data.remote.chelaile.ChelaileApiClient
import com.trackr.data.remote.mygolbs.MygolbsApiClient
import com.trackr.domain.model.TransitCity
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Provider / ApiClient 层请求节省（缓存、合并、降级共享）测试，全部使用拦截器模拟响应，不发真实 HTTP
 */
@RunWith(RobolectricTestRunner::class)
class TransitProviderCacheTest {

    private val testCity = TransitCity(cityId = "034", cityName = "上海", mygolbsCityCode = "310000")
    private var now = 100_000L
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    private fun count(key: String): Int = counts[key]?.get() ?: 0
    private fun hit(key: String) {
        counts.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun mockResponse(request: Request, bodyJson: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bodyJson.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private fun requestBody(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun client(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    // =========================================================================
    // 车来了
    // =========================================================================

    private val homePageJson = """
        {"jsonr": {"data": {"nearSts": [
          {"sn": "站点1", "sId": "s_1", "physicalStId": "p_1", "lat": 31.20, "lng": 121.40, "isSubway": 0,
           "lines": [{"line": {"lineId": "l_1", "name": "1路", "shortDesc": "还有1站"}, "stnStates": []}]},
          {"sn": "站点3", "sId": "s_3", "physicalStId": "p_3", "lat": 31.22, "lng": 121.42, "isSubway": 0,
           "lines": [{"line": {"lineId": "l_3", "name": "3路", "shortDesc": ""}, "stnStates": []}]}
        ]}}}
    """.trimIndent()

    private val phyStnP3Json = """
        {"jsonr": {"data": {"stationList": [
          {"sn": "站点3", "sId": "s_3", "physicalStId": "p_3", "lat": 31.22, "lng": 121.42,
           "lines": [{"line": {"lineId": "l_3", "name": "3路", "shortDesc": "还有2站"}, "stnStates": []}]}
        ]}}}
    """.trimIndent()

    @Test
    fun chelaileNearbyCompletion_isCachedForShortTtl() = runBlocking {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            when {
                url.contains("encryptedHomePage.action") -> { hit("home"); mockResponse(request, homePageJson) }
                url.contains("encryptedPhyStnDetail.action") -> { hit("phy"); mockResponse(request, phyStnP3Json) }
                else -> mockResponse(request, """{"jsonr": {"data": {}}}""")
            }
        }
        val provider = ChelaileTransitProvider(ChelaileApiClient(client(interceptor)), nowMillis = { now })

        val first = provider.getNearbyStops(testCity, 31.20, 121.40, 4)
        assertEquals("还有2站", first[1].lines[0].statusText)
        provider.getNearbyStops(testCity, 31.20, 121.40, 4)
        assertEquals(2, count("home"))
        assertEquals(1, count("phy"))

        now += ChelaileTransitProvider.NEARBY_COMPLETION_TTL_MS
        provider.getNearbyStops(testCity, 31.20, 121.40, 4)
        assertEquals(2, count("phy"))
    }

    @Test
    fun chelaileStopDetail_otherPlatformCompletionOnlyOnFirstLoad() = runBlocking {
        val mainJson = """
            {"jsonr": {"data": {"stationList": [
              {"sn": "人民广场(1)", "sId": "stn_1", "physicalStId": "phys_1", "lat": 31.231, "lng": 121.471,
               "lines": [{"line": {"lineId": "cl_101", "name": "1路"}, "stnStates": []}]},
              {"sn": "人民广场(2)", "sId": "stn_2", "physicalStId": "phys_2", "lat": 31.232, "lng": 121.472,
               "lines": [{"line": {"lineId": "", "name": "2路"}, "stnStates": []}]}
            ]}}}
        """.trimIndent()
        val phys2Json = """
            {"jsonr": {"data": {"stationList": [
              {"sn": "人民广场(2)", "sId": "stn_2", "physicalStId": "phys_2", "lat": 31.232, "lng": 121.472,
               "lines": [{"line": {"lineId": "cl_202", "name": "2路"}, "stnStates": []}]}
            ]}}}
        """.trimIndent()
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            if (request.url.toString().contains("encryptedPhyStnDetail.action")) {
                if (request.url.queryParameter("physicalStId") == "phys_2") {
                    hit("phys_2"); mockResponse(request, phys2Json)
                } else {
                    hit("main"); mockResponse(request, mainJson)
                }
            } else {
                mockResponse(request, """{"jsonr": {"data": {}}}""")
            }
        }
        val provider = ChelaileTransitProvider(ChelaileApiClient(client(interceptor)), nowMillis = { now })
        val request = StopDetailRequest(stopId = "stn_1", physicalStId = "phys_1")

        val first = provider.getStopDetail(testCity, request)
        val second = provider.getStopDetail(testCity, request)

        assertEquals("cl_202", first[1].lines[0].line.lineId)
        assertEquals("cl_202", second[1].lines[0].line.lineId)
        assertEquals(2, count("main"))
        assertEquals(1, count("phys_2"))
    }

    @Test
    fun chelaileStopDetail_emptyMainQuery_isSkippedOnNextPoll() = runBlocking {
        val nearbyJson = """
            {"jsonr": {"data": {"nearSts": [
              {"sn": "人民广场", "sId": "stn_rmgc", "lat": 31.2310, "lng": 121.4710, "isSubway": 0, "lines": []}
            ]}}}
        """.trimIndent()
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            when {
                url.contains("encryptedPhyStnDetail.action") -> {
                    hit("main"); mockResponse(request, """{"jsonr": {"data": {"stationList": []}}}""")
                }
                url.contains("encryptedHomePage.action") -> { hit("nearby"); mockResponse(request, nearbyJson) }
                else -> mockResponse(request, """{"jsonr": {"data": {}}}""")
            }
        }
        val provider = ChelaileTransitProvider(ChelaileApiClient(client(interceptor)), nowMillis = { now })
        val request = StopDetailRequest(stopId = "unknown", stopName = "人民广场", stationLat = 31.2310, stationLng = 121.4710)

        assertEquals(1, provider.getStopDetail(testCity, request).size)
        assertEquals(1, provider.getStopDetail(testCity, request).size)
        assertEquals(1, count("main"))
        assertEquals(2, count("nearby"))

        // 记录过期后重新尝试主查询
        now += ChelaileTransitProvider.EMPTY_MAIN_QUERY_TTL_MS
        provider.getStopDetail(testCity, request)
        assertEquals(2, count("main"))
    }

    // =========================================================================
    // 掌上公交
    // =========================================================================

    private fun mygolbsCmd(body: String): String =
        Regex("CMD=(\\d+)").find(body)?.groupValues?.getOrNull(1).orEmpty()

    @Test
    fun mygolbsLineDetail_cachesCmd103_andPollsOnlyCmd104() = runBlocking {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val cmd = mygolbsCmd(requestBody(request))
            hit(cmd)
            when (cmd) {
                "103" -> mockResponse(
                    request,
                    """{"status": "1", "commonts": "票价:2元", "data": [
                        {"stationName": "A", "stationOrder": 1}, {"stationName": "B", "stationOrder": 2}]}"""
                )
                "104" -> mockResponse(request, """{"status": "1", "list": [{"busNumber": "桂K1", "index": 0, "statusType": "0"}]}""")
                else -> mockResponse(request, """{"status": "1", "data": []}""")
            }
        }
        val provider = MygolbsTransitProvider(MygolbsApiClient(client(interceptor), nowMillis = { now }))
        val request = LineDetailRequest(lineId = "mg:450900:玉林市:1路:1", targetOrder = 2)

        val first = provider.getLineDetail(testCity, request)
        val second = provider.getLineDetail(testCity, request)

        assertEquals(2, first.stops.size)
        assertEquals(2, second.stops.size)
        assertEquals("2元", second.line.price)
        assertEquals(1, second.buses.size)
        assertEquals(1, count("103"))
        assertEquals(2, count("104"))

        now += MygolbsApiClient.ROUTE_STATIC_TTL_MS
        provider.getLineDetail(testCity, request)
        assertEquals(2, count("103"))
    }

    @Test
    fun mygolbsSearch_fallbackCmd102_isSharedBetweenLinesAndStops() = runBlocking {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val cmd = mygolbsCmd(requestBody(request))
            hit(cmd)
            when (cmd) {
                "114", "110" -> mockResponse(request, """{"status": "0", "msg": "error"}""")
                "102" -> mockResponse(
                    request,
                    """{"status": "1",
                        "buslines": [{"lineName": "1路", "upperOrDown": "1", "from": "起点", "to": "终点"}],
                        "busstations": [{"stationName": "一中"}]}"""
                )
                else -> mockResponse(request, """{"status": "1", "data": []}""")
            }
        }
        val provider = MygolbsTransitProvider(MygolbsApiClient(client(interceptor), nowMillis = { now }))

        val result = provider.search(testCity, "1")

        assertEquals(1, result.lines.size)
        assertEquals(1, result.stops.size)
        assertEquals(1, count("114"))
        assertEquals(1, count("110"))
        assertEquals(1, count("102"))
    }

    @Test
    fun mygolbsNearby_cachesCmd105ForShortTtl() = runBlocking {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val cmd = mygolbsCmd(requestBody(request))
            hit(cmd)
            when (cmd) {
                "106" -> mockResponse(
                    request,
                    """{"status": 1, "data": [{"name": "人民广场", "lat": "31.231", "lon": "121.476", "dis": 200}]}"""
                )
                "105" -> mockResponse(
                    request,
                    """{"status": "1", "data": [{"lineName": "108路", "upperOrDown": "1", "neartext": "2站到达"}]}"""
                )
                else -> mockResponse(request, """{"status": "1", "data": []}""")
            }
        }
        val provider = MygolbsTransitProvider(MygolbsApiClient(client(interceptor), nowMillis = { now }))

        provider.getNearbyStops(testCity, 31.23, 121.47, 4)
        val second = provider.getNearbyStops(testCity, 31.23, 121.47, 4)

        assertEquals("2站到达", second[0].lines[0].statusText)
        assertEquals(2, count("106"))
        assertEquals(1, count("105"))

        // 站点详情页仍每次实时查询 CMD=105
        provider.getStopDetail(testCity, StopDetailRequest(stopName = "人民广场"))
        assertEquals(2, count("105"))

        now += MygolbsApiClient.NEARBY_STOP_LINES_TTL_MS
        provider.getNearbyStops(testCity, 31.23, 121.47, 4)
        assertEquals(3, count("105"))
    }

    @Test
    fun mygolbsReverseGeocode_nullResultIsCachedBriefly() = runBlocking {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            hit(mygolbsCmd(requestBody(request)))
            mockResponse(request, """{"status": "1", "msg": "error"}""")
        }
        val provider = MygolbsTransitProvider(MygolbsApiClient(client(interceptor), nowMillis = { now }))

        provider.resolveCityByLocation(31.23, 121.47)
        provider.resolveCityByLocation(31.23, 121.47)
        assertEquals(1, count("108"))

        now += MygolbsApiClient.REVERSE_GEOCODE_TTL_MS
        provider.resolveCityByLocation(31.23, 121.47)
        assertEquals(2, count("108"))
    }
}
