package com.trackr.data.parser

import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitCity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MapShareParserTest {

    @Test
    fun testIsMapShareContent() {
        assertTrue(MapShareParser.isMapShareContent("【高德地图】从杭州东站到西湖断桥 https://surl.amap.com/123"))
        assertTrue(MapShareParser.isMapShareContent("【百度地图】从市民中心到火车东站 http://j.map.baidu.com/456"))
        assertTrue(MapShareParser.isMapShareContent("从翠苑新村到武林广场乘坐156路"))
        assertFalse(MapShareParser.isMapShareContent("今天天气真好，出去散步吧"))
        assertFalse(MapShareParser.isMapShareContent(""))
    }

    @Test
    fun testExtractMapUrl() {
        val amapText = "去高德地图查看路线：https://surl.amap.com/abcdef123 复制后打开"
        val extractedAmap = MapShareParser.extractMapUrl(amapText)
        assertEquals("https://surl.amap.com/abcdef123", extractedAmap)

        val baiduText = "百度地图查看：http://j.map.baidu.com/xyz987"
        val extractedBaidu = MapShareParser.extractMapUrl(baiduText)
        assertEquals("http://j.map.baidu.com/xyz987", extractedBaidu)
    }

    @Test
    fun testParseShareTextMultiLegTransfer() {
        val text = "【公交路线】从 杭州东站 到 灵隐。乘坐 地铁1号线(湘湖方向) 到 龙翔桥 ，在 龙翔桥 换乘 7路 到 灵隐。耗时约50分钟。"
        val plan = MapShareParser.parseShareText(text)

        assertNotNull(plan)
        assertEquals("杭州东站", plan?.originName)
        assertEquals("灵隐", plan?.destinationName)
        assertEquals(2, plan?.legs?.size)

        val leg1 = plan?.legs?.get(0)
        assertEquals("杭州东站", leg1?.fromStop)
        assertEquals("龙翔桥", leg1?.toStop)
        assertEquals("地铁1号线", leg1?.lineName)

        val leg2 = plan?.legs?.get(1)
        assertEquals("龙翔桥", leg2?.fromStop)
        assertEquals("灵隐", leg2?.toStop)
        assertEquals("7路", leg2?.lineName)
    }

    @Test
    fun testParseShareTextSingleLegDirect() {
        val text = "从 翠苑新村 到 城站火车站 乘坐 156路。"
        val plan = MapShareParser.parseShareText(text)

        assertNotNull(plan)
        assertEquals("翠苑新村", plan?.originName)
        assertEquals("城站火车站", plan?.destinationName)
        assertEquals(1, plan?.legs?.size)

        val leg = plan?.legs?.first()
        assertEquals("翠苑新村", leg?.fromStop)
        assertEquals("城站火车站", leg?.toStop)
        assertEquals("156路", leg?.lineName)
    }

    @Test
    fun testEnrichPlanFromUrl() {
        val url = "https://uri.amap.com/transit?sname=%E6%9D%AD%E5%B7%9E%E4%B8%9C%E7%AB%99&dname=%E8%A5%BF%E6%B9%96"
        val plan = MapShareParser.enrichPlanFromUrl(null, url)

        assertNotNull(plan)
        assertEquals("杭州东站", plan?.originName)
        assertEquals("西湖", plan?.destinationName)
    }

    @Test
    fun testEnrichPlanFromBaiduUrlWithComplexParams() {
        val baiduUrl = "https://map.baidu.com/mobile/webapp/index/index#route/transit?origin=name%3A%E6%9D%AD%E5%B7%9E%E4%B8%9C%E7%AB%99%7Clatlng%3A30.29%2C120.21&destination=name%3A%E7%81%B5%E9%9A%90%7Clatlng%3A30.24%2C120.10"
        val plan = MapShareParser.enrichPlanFromUrl(null, baiduUrl)

        assertNotNull(plan)
        assertEquals("杭州东站", plan?.originName)
        assertEquals("灵隐", plan?.destinationName)
    }

    @Test
    fun testParseShareTextWithEndpointPrefix() {
        val text = "起点：翠苑新村，终点：武林广场，乘坐156路。"
        val plan = MapShareParser.parseShareText(text)

        assertNotNull(plan)
        assertEquals("翠苑新村", plan?.originName)
        assertEquals("武林广场", plan?.destinationName)
        assertEquals(1, plan?.legs?.size)
        assertEquals("156路", plan?.legs?.first()?.lineName)
    }

    @Test
    fun testParseShareTextArrowFormat() {
        val text = "杭州东站 -> 灵隐 7路"
        val plan = MapShareParser.parseShareText(text)

        assertNotNull(plan)
        assertEquals("杭州东站", plan?.originName)
        assertEquals("灵隐", plan?.destinationName)
        assertEquals(1, plan?.legs?.size)
        assertEquals("7路", plan?.legs?.first()?.lineName)
    }

    @Test
    fun testParseAndAlignWithoutNetwork() = runTest {
        val text = "从 翠苑 到 市民中心 乘坐 156路 到 武林广场 ，换乘 地铁4号线 到 市民中心"
        val city = TransitCity(cityId = "033", cityName = "杭州")

        val group = MapShareParser.parseAndAlign(
            text = text,
            defaultCity = city,
            coordinator = null,
            client = null
        )

        assertNotNull(group)
        assertTrue(group!!.isTemporary)
        assertEquals(GroupCategory.TEMPORARY, group.category)
        assertEquals(2, group.legs.size)
        assertEquals("翠苑", group.legs[0].fromStopName)
        assertEquals("武林广场", group.legs[0].toStopName)
        assertEquals("156路", group.legs[0].primaryLine?.lineName)
        assertEquals("武林广场", group.legs[1].fromStopName)
        assertEquals("市民中心", group.legs[1].toStopName)
        assertEquals("地铁4号线", group.legs[1].primaryLine?.lineName)
    }
}
