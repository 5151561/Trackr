package com.trackr

import com.trackr.data.remote.chelaile.ChelaileCrypto
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusLineSummary
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.LineOperatingState
import com.trackr.domain.model.LiveBus
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.isPureSubwayStation
import com.trackr.domain.model.sanitizePlateOrFleetNo
import com.trackr.ui.components.formatBusChipShortText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitUnitTest {

    @Test
    fun testPureSubwayStationFilter_preservesGroundBusStopsAndFiltersMetro() {
        // 1. 同名地面公交站：必须保留（返回 false）
        val groundStopChelaile = NearbyStop(stopId = "1", name = "湖滨中路", isSubway = false)
        val groundStopMygolbs = NearbyStop(stopId = "2", name = "湖滨中路站", isSubway = false)
        val groundStopWithMetroSuffix = NearbyStop(stopId = "3", name = "高新园地铁站", isSubway = false)
        val groundStopNamedMetroEntrance = NearbyStop(stopId = "4", name = "人民广场地铁站公交站", isSubway = false)

        org.junit.Assert.assertFalse(groundStopChelaile.isPureSubwayStation)
        org.junit.Assert.assertFalse(groundStopMygolbs.isPureSubwayStation)
        org.junit.Assert.assertFalse(groundStopWithMetroSuffix.isPureSubwayStation)
        org.junit.Assert.assertFalse(groundStopNamedMetroEntrance.isPureSubwayStation)

        // 2. 纯地铁站点：必须被判定为 true 予以过滤
        val metroChelaileM = NearbyStop(stopId = "10", name = "湖滨中路站（M）", isSubway = false)
        val metroChelaileHalfM = NearbyStop(stopId = "11", name = "湖滨中路站(M)", isSubway = false)
        val metroMygolbs = NearbyStop(stopId = "12", name = "湖滨中路（地铁站）", isSubway = false)
        val metroMygolbsHalf = NearbyStop(stopId = "13", name = "湖滨中路(地铁站)", isSubway = false)
        val metroFlagged = NearbyStop(stopId = "14", name = "任意地铁站", isSubway = true)

        org.junit.Assert.assertTrue(metroChelaileM.isPureSubwayStation)
        org.junit.Assert.assertTrue(metroChelaileHalfM.isPureSubwayStation)
        org.junit.Assert.assertTrue(metroMygolbs.isPureSubwayStation)
        org.junit.Assert.assertTrue(metroMygolbsHalf.isPureSubwayStation)
        org.junit.Assert.assertTrue(metroFlagged.isPureSubwayStation)
    }

    @Test
    fun testComputeSegmentProgress_whenArrived_returnsOne() {
        val prevStop = BusStop(stopId = "1", name = "A", order = 1, wgsLat = 31.23, wgsLng = 121.47)
        val targetStop = BusStop(stopId = "2", name = "B", order = 2, wgsLat = 31.23, wgsLng = 121.49)

        val progress = RouteProjectionMath.computeSegmentProgress(
            busLat = 31.23,
            busLng = 121.48,
            isArrived = true,
            prevStop = prevStop,
            targetStop = targetStop
        )
        assertEquals(1.0f, progress, 0.0001f)
    }

    @Test
    fun testComputeSegmentProgress_whenStopsNullOrZeroCoordinates_returnsOne() {
        val validStop = BusStop(stopId = "1", name = "A", order = 1, wgsLat = 31.23, wgsLng = 121.47)
        val zeroStop = BusStop(stopId = "2", name = "B", order = 2, wgsLat = 0.0, wgsLng = 0.0)

        // Null prevStop
        assertEquals(1.0f, RouteProjectionMath.computeSegmentProgress(31.23, 121.48, false, null, validStop), 0.0001f)

        // Zero coordinates
        assertEquals(1.0f, RouteProjectionMath.computeSegmentProgress(31.23, 121.48, false, validStop, zeroStop), 0.0001f)
        assertEquals(1.0f, RouteProjectionMath.computeSegmentProgress(0.0, 0.0, false, validStop, validStop), 0.0001f)
    }

    @Test
    fun testComputeSegmentProgress_interpolationAtMidpointAndEndpoints() {
        val prevStop = BusStop(stopId = "1", name = "A", order = 1, wgsLat = 31.2300, wgsLng = 121.4700)
        val targetStop = BusStop(stopId = "2", name = "B", order = 2, wgsLat = 31.2300, wgsLng = 121.4900)

        // At start stop (prevStop)
        val progressAtStart = RouteProjectionMath.computeSegmentProgress(
            busLat = 31.2300,
            busLng = 121.4700,
            isArrived = false,
            prevStop = prevStop,
            targetStop = targetStop
        )
        assertEquals(0.0f, progressAtStart, 0.01f)

        // At midpoint
        val progressAtMid = RouteProjectionMath.computeSegmentProgress(
            busLat = 31.2300,
            busLng = 121.4800,
            isArrived = false,
            prevStop = prevStop,
            targetStop = targetStop
        )
        assertEquals(0.5f, progressAtMid, 0.02f)

        // At target stop
        val progressAtTarget = RouteProjectionMath.computeSegmentProgress(
            busLat = 31.2300,
            busLng = 121.4900,
            isArrived = false,
            prevStop = prevStop,
            targetStop = targetStop
        )
        assertEquals(1.0f, progressAtTarget, 0.01f)
    }

    @Test
    fun testCoreStopName_stripsParenthesesAccurately() {
        assertEquals("金城商厦", RouteProjectionMath.coreStopName("金城商厦（市中医院）"))
        assertEquals("人民广场", RouteProjectionMath.coreStopName("人民广场(福州路)"))
        assertEquals("南京东路", RouteProjectionMath.coreStopName("南京东路"))
        assertEquals("北京西路", RouteProjectionMath.coreStopName("北京西路（西向东）"))
    }

    @Test
    fun testFindOppositeStop_exactAndCoreMatches() {
        val reverseStops = listOf(
            BusStop(stopId = "r1", name = "火车站", order = 1),
            BusStop(stopId = "r2", name = "金城商厦", order = 2),
            BusStop(stopId = "r3", name = "市政府", order = 3)
        )

        // 精确匹配
        val exactCurrent = BusStop(stopId = "s1", name = "金城商厦", order = 2)
        val matchExact = RouteProjectionMath.findOppositeStop(exactCurrent, reverseStops)
        assertNotNull(matchExact)
        assertEquals("r2", matchExact?.stopId)

        // 核心站名带括号匹配
        val withParenCurrent = BusStop(stopId = "s2", name = "金城商厦（市中医院）", order = 2)
        val matchCore = RouteProjectionMath.findOppositeStop(withParenCurrent, reverseStops)
        assertNotNull(matchCore)
        assertEquals("r2", matchCore?.stopId)

        // 空反向站列表安全兜底
        assertNull(RouteProjectionMath.findOppositeStop(exactCurrent, emptyList()))
    }

    @Test
    fun testCompareLineNames_numericSortingBeforeAlphanumeric() {
        val lines = listOf("11路", "1路", "G01路", "2路")
        val sorted = lines.sortedWith { a, b -> RouteProjectionMath.compareLineNames(a, b) }
        assertEquals(listOf("1路", "2路", "11路", "G01路"), sorted)
    }

    @Test
    fun testFormatEtaText_variousConditions() {
        // 已到站
        assertEquals("已到站", RouteProjectionMath.formatEtaText(0, isArrivedAtTarget = true))
        assertEquals("已到站", RouteProjectionMath.formatEtaText(50, isArrivedAtTarget = true))

        // 暂无预测
        assertEquals("暂无预测", RouteProjectionMath.formatEtaText(-1, isArrivedAtTarget = false))
        assertEquals("暂无预测", RouteProjectionMath.formatEtaText(0, isArrivedAtTarget = false))

        // 即将到站 (<= 60 秒)
        assertEquals("即将到站", RouteProjectionMath.formatEtaText(30, isArrivedAtTarget = false))
        assertEquals("即将到站", RouteProjectionMath.formatEtaText(60, isArrivedAtTarget = false))

        // 约 N 分钟
        assertEquals("约 1 分钟", RouteProjectionMath.formatEtaText(65, isArrivedAtTarget = false))
        assertEquals("约 2 分钟", RouteProjectionMath.formatEtaText(120, isArrivedAtTarget = false))
        assertEquals("约 5 分钟", RouteProjectionMath.formatEtaText(300, isArrivedAtTarget = false))
    }

    @Test
    fun testBusLineSummary_primaryDirectionPreference() {
        val dir0 = BusLineDirection(
            lineId = "l1",
            name = "1路",
            direction = 0,
            startStop = "A",
            endStop = "B",
            source = DataSourceType.CHELAILE
        )
        val dir1 = BusLineDirection(
            lineId = "l2",
            name = "1路",
            direction = 1,
            startStop = "B",
            endStop = "A",
            source = DataSourceType.CHELAILE
        )

        val summary = BusLineSummary(
            name = "1路",
            lineNo = "1",
            directions = listOf(dir1, dir0),
            source = DataSourceType.CHELAILE
        )

        // 优先返回 direction == 0 的方向
        assertEquals("l1", summary.primaryDirection?.lineId)
        assertEquals("B", summary.primaryDirection?.endStop)
    }

    @Test
    fun testFavoriteStationLine_creationAndIdIntegrity() {
        val fav = FavoriteStationLine(
            id = "021_line001_5",
            cityId = "021",
            cityName = "上海",
            lineId = "line001",
            lineName = "49路",
            direction = 0,
            startStop = "汉口路四川中路",
            endStop = "上海体育馆",
            targetStopId = "s5",
            targetStopName = "人民广场",
            targetOrder = 5,
            source = DataSourceType.CHELAILE
        )
        assertEquals("021_line001_5", fav.id)
        assertEquals("49路", fav.lineName)
        assertEquals("人民广场", fav.targetStopName)
        assertTrue(fav.addedAtMillis > 0)
    }

    @Test
    fun testLineOperatingState_labels() {
        assertEquals("运营中", LineOperatingState.RUNNING.defaultLabel)
        assertEquals("等待发车", LineOperatingState.WAITING.defaultLabel)
        assertEquals("临时停运", LineOperatingState.SUSPENDED.defaultLabel)
        assertEquals("末班已过", LineOperatingState.ENDED.defaultLabel)
    }

    @Test
    fun testChelaileCryptoSign_matchesOfficialTestVector() {
        val sign = ChelaileCrypto.cryptoSign(mapOf("lineId" to "775200849296"))
        assertEquals("3f2031d13050245b1d39d5e90a824eca", sign)
    }

    @Test
    fun testChelaileEnvelopeStripping_plainData() {
        val raw = """**YGKJ{"jsonr":{"status":"00","errmsg":"","data":{"maxInterval":30}}}YGKJ##"""
        val json = ChelaileCrypto.extractEnvelopeDataJson(raw)
        assertEquals(30, json.getInt("maxInterval"))
    }

    @Test
    fun testChelaileAesEcbDecryption_roundTrip() {
        val plainJson = """{"targetOrder":12,"buses":[{"licence":"417","order":7}]}"""
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        val keySpec = javax.crypto.spec.SecretKeySpec(ChelaileCrypto.AES_KEY_WX.toByteArray(Charsets.UTF_8), "AES")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)
        val encryptedBase64 = java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8)))

        val rawEnvelope = """**YGKJ{"jsonr":{"status":"00","errmsg":"","data":{"encryptResult":"$encryptedBase64"}}}YGKJ##"""
        val extracted = ChelaileCrypto.extractEnvelopeDataJson(rawEnvelope)
        assertEquals(12, extracted.getInt("targetOrder"))
        assertEquals("417", extracted.getJSONArray("buses").getJSONObject(0).getString("licence"))
    }

    @Test
    fun testCalculateDistanceMeters_knownLocations() {
        // 上海人民广场 (31.2317, 121.4756) 到 陆家嘴东方明珠 (31.2397, 121.4998) 距离约 2.4 公里 (约 2400-2500 米)
        val dist = RouteProjectionMath.calculateDistanceMeters(31.2317, 121.4756, 31.2397, 121.4998)
        assertNotNull(dist)
        assertTrue("Distance should be around 2400-2500m but was $dist", dist!! in 2300..2600)

        // 零坐标或无效坐标应返回 null
        assertNull(RouteProjectionMath.calculateDistanceMeters(0.0, 0.0, 31.23, 121.47))
    }

    @Test
    fun testFormatDistanceText() {
        assertEquals("350米", RouteProjectionMath.formatDistanceText(350))
        assertEquals("1.2公里", RouteProjectionMath.formatDistanceText(1200))
        assertEquals("", RouteProjectionMath.formatDistanceText(null))
        assertEquals("", RouteProjectionMath.formatDistanceText(0))
    }

    @Test
    fun testEngineMode_propertiesAndCycles() {
        assertEquals("双引擎", com.trackr.domain.model.EngineMode.ALL.shortLabel)
        assertEquals("掌上公交", com.trackr.domain.model.EngineMode.MYGOLBS.shortLabel)
        assertEquals("车来了", com.trackr.domain.model.EngineMode.CHELAILE.shortLabel)
    }

    @Test
    fun testMygolbsNormalizeCityName() {
        assertEquals("北京市", com.trackr.data.remote.mygolbs.MygolbsApiClient.normalizeCityName("北京"))
        assertEquals("福州市", com.trackr.data.remote.mygolbs.MygolbsApiClient.normalizeCityName("福州"))
        assertEquals("玉林市", com.trackr.data.remote.mygolbs.MygolbsApiClient.normalizeCityName("玉林"))
        assertEquals("玉林市", com.trackr.data.remote.mygolbs.MygolbsApiClient.normalizeCityName("玉林市"))
        assertEquals("长宁县", com.trackr.data.remote.mygolbs.MygolbsApiClient.normalizeCityName("长宁县"))
    }

    @Test
    fun testSanitizePlateOrFleetNo_filtersMachineHashAndKeepsValidPlate() {
        // 40 位 SHA-1 内部哈希（如张江1路车来了返回的 busId）必须过滤为空字符串
        assertEquals("", sanitizePlateOrFleetNo("d22101794720cd421783aa69b5cb240e4d68641d"))
        assertEquals("", sanitizePlateOrFleetNo("bcefa94a2a7530d0c41660dbb195ddbbc41b4b42"))
        assertEquals("", sanitizePlateOrFleetNo("5785ab7ebccd56bdd2adb2f4f1eda47e343cd719"))

        // 32 位 MD5 内部哈希
        assertEquals("", sanitizePlateOrFleetNo("c4ca4238a0b923820dcc509a6f75849b"))

        // UUID
        assertEquals("", sanitizePlateOrFleetNo("550e8400-e29b-41d4-a716-446655440000"))

        // 正常中国车牌号（含汉字）应保留
        assertEquals("沪A·D8866", sanitizePlateOrFleetNo("沪A·D8866"))
        assertEquals("桂K03853D", sanitizePlateOrFleetNo("桂K03853D"))
        assertEquals("京A12345", sanitizePlateOrFleetNo("京A12345"))

        // 正常公交自编号（短纯数字或字母数字）应保留
        assertEquals("417", sanitizePlateOrFleetNo("417"))
        assertEquals("K102", sanitizePlateOrFleetNo("K102"))
        assertEquals("038", sanitizePlateOrFleetNo("038"))

        // 空串与 null 处理
        assertEquals("", sanitizePlateOrFleetNo(null))
        assertEquals("", sanitizePlateOrFleetNo(""))
        assertEquals("", sanitizePlateOrFleetNo("   "))
    }

    @Test
    fun testFormatBusChipShortText_preventsOverflowOnHash() {
        // 遇到 40 位哈希时应降级展示为“在途”，防止挤爆走向条
        assertEquals("在途", formatBusChipShortText("d22101794720cd421783aa69b5cb240e4d68641d"))
        assertEquals("在途", formatBusChipShortText(""))

        // 正常车牌号提取核心数字后缀
        assertEquals("8866", formatBusChipShortText("沪A·D8866"))
        assertEquals("3853D", formatBusChipShortText("桂K03853D"))

        // 纯数字自编号保持不变
        assertEquals("417", formatBusChipShortText("417"))
        assertEquals("K102", formatBusChipShortText("K102"))
    }

    @Test
    fun testLiveBusFriendlyPlate() {
        // 40 位哈希构造的 LiveBus，friendlyPlate 应降级为“在途车辆”或“已到站车辆”
        val busEnRoute = LiveBus(
            busId = "d22101794720cd421783aa69b5cb240e4d68641d",
            plateOrFleetNo = "d22101794720cd421783aa69b5cb240e4d68641d",
            order = 5,
            isArrived = false
        )
        assertEquals("在途车辆", busEnRoute.friendlyPlate)

        val busArrived = LiveBus(
            busId = "d22101794720cd421783aa69b5cb240e4d68641d",
            plateOrFleetNo = "",
            order = 5,
            isArrived = true
        )
        assertEquals("已到站车辆", busArrived.friendlyPlate)

        // 具有正常车牌时
        val normalBus = LiveBus(
            busId = "normal_1",
            plateOrFleetNo = "沪A·D8866",
            order = 3,
            isArrived = false
        )
        assertEquals("沪A·D8866", normalBus.friendlyPlate)
    }

    @org.junit.Ignore("Online network probe test - skipped in automated unit testing")
    @Test
    fun testInspectStopDetailOnline() {
        kotlinx.coroutines.runBlocking {
            val client = com.trackr.data.remote.chelaile.ChelaileApiClient(okhttp3.OkHttpClient())
            val rawStops = client.getStopDetail(cityId = "051", stationId = "0594-2854")
            assertEquals(3, rawStops.size)
        }
    }
}
