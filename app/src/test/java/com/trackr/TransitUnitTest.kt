package com.trackr

import com.trackr.data.remote.chelaile.ChelaileCrypto
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusLineSummary
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.LineOperatingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitUnitTest {

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
}
