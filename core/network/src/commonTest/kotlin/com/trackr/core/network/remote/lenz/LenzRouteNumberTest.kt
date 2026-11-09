package com.trackr.core.network.remote.lenz

import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.TransitCity
import com.trackr.core.network.provider.lenz.LenzTransitProvider
import com.trackr.core.network.testing.unusedHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 范县线路号（按当地公交公众号 1-12 路冬季时间表） */
class LenzRouteNumberTest {

    @Test
    fun testFanxianRouteNumbers() {
        assertEquals("1路", LenzApiClient.routeNumberFor("范县", "范县至王楼镇"))
        assertEquals("2路", LenzApiClient.routeNumberFor("范县", "范县至濮城镇"))
        assertEquals("3路", LenzApiClient.routeNumberFor("范县", "范县至彭楼水库"))
        assertEquals("5路", LenzApiClient.routeNumberFor("范县", "范县至杨集乡"))
        assertEquals("6路", LenzApiClient.routeNumberFor("范县", "范县至陈庄"))
        assertEquals("7路", LenzApiClient.routeNumberFor("范县", "范县至龙王庄镇"))
        assertEquals("8路", LenzApiClient.routeNumberFor("范县", "范县至陆集乡（线路1东行）"))
        assertEquals("10路", LenzApiClient.routeNumberFor("范县", "范县至颜村铺乡"))
        assertEquals("12路", LenzApiClient.routeNumberFor("范县", "范县至陈庄（石大庙）"))
    }

    @Test
    fun testSameRouteNumberIsNotReusedByVariants() {
        // 时间表里没有的支线 / 旅游专线 / 城际线不编号，避免 6 路出现好几次
        assertEquals(null, LenzApiClient.routeNumberFor("范县", "范县至陈庄后张枣坡"))
        assertEquals(null, LenzApiClient.routeNumberFor("范县", "范县至陈庄荷花园旅游专线"))
        assertEquals(null, LenzApiClient.routeNumberFor("范县", "范县-杨集-二厂"))
        assertEquals(null, LenzApiClient.routeNumberFor("范县", "范县至颜村铺旅游专线"))
        assertEquals(null, LenzApiClient.routeNumberFor("范县", "范县至台前"))
    }

    @Test
    fun testTwoElevenRoutesGetDistinguishingNotes() {
        assertEquals("11路 范县至高码头镇（线路1）", LenzApiClient.displayRouteName("范县", "范县至高码头镇"))
        assertEquals("11路 范县-仲庄-高码头（线路2）", LenzApiClient.displayRouteName("范县", "范县-仲庄-高码头"))
        assertEquals("8路 范县至陆集乡（线路1东行）", LenzApiClient.displayRouteName("范县", "范县至陆集乡（线路1东行）"))
    }

    @Test
    fun testUnknownCityKeepsOriginalName() {
        assertEquals(null, LenzApiClient.routeNumberFor("武夷", "武夷1路"))
        assertEquals("范县至台前", LenzApiClient.displayRouteName("范县", "范县至台前"))
        assertEquals("1路 范县至王楼镇", LenzApiClient.displayRouteName("范县", "范县至王楼镇"))
    }

    @Test
    fun testFanxianTimetablesLoadedCorrectly() {
        val schedule1Up = findLenzScheduledTimetable("范县", "范县至王楼镇", 0)
        assertEquals("06:00", schedule1Up?.departures?.first())
        assertEquals("18:00", schedule1Up?.departures?.last())
        assertEquals(25, schedule1Up?.departures?.size)

        val schedule1Down = findLenzScheduledTimetable("范县", "范县至王楼镇", 1)
        assertEquals("07:00", schedule1Down?.departures?.first())
        assertEquals("18:50", schedule1Down?.departures?.last())

        // 8路 线路1东行（半小时一班） vs 线路2南行（一小时一班）
        val schedule8East = findLenzScheduledTimetable("范县", "范县至陆集乡（线路1东行）", 0)
        assertEquals(24, schedule8East?.departures?.size)
        assertEquals(30, schedule8East?.intervalMinutes)

        val schedule8South = findLenzScheduledTimetable("范县", "范县至陆集乡（线路2南行）", 0)
        assertEquals(12, schedule8South?.departures?.size)
        assertEquals(60, schedule8South?.intervalMinutes)

        // 11路 线路1（乔洼）与 线路2（仲庄）
        val schedule11Line1 = findLenzScheduledTimetable("范县", "范县至高码头镇", 0)
        assertEquals("06:30", schedule11Line1?.departures?.first())
        assertEquals("18:00", schedule11Line1?.departures?.last())

        val schedule11Line2 = findLenzScheduledTimetable("范县", "范县-仲庄-高码头", 0)
        assertEquals("07:00", schedule11Line2?.departures?.first())
        assertEquals("17:30", schedule11Line2?.departures?.last())

        // 12路（石大庙）
        val schedule12Up = findLenzScheduledTimetable("范县", "范县至陈庄（石大庙）", 0)
        assertEquals("06:50", schedule12Up?.departures?.first())
        assertEquals("18:00", schedule12Up?.departures?.last())
        assertEquals(13, schedule12Up?.departures?.size)

        val schedule12Down = findLenzScheduledTimetable("范县", "范县至陈庄（石大庙）", 1)
        assertEquals("06:30", schedule12Down?.departures?.first())
        assertEquals(14, schedule12Down?.departures?.size)

        // 未收录城市返回 null
        assertEquals(null, findLenzScheduledTimetable("武夷", "武夷1路", 0))
    }

    @Test
    fun testLenzTransitProviderGetTimetableForFanxian() = runTest {
        val client = LenzApiClient(unusedHttpClient())
        val provider = LenzTransitProvider(client)
        val city = TransitCity(cityId = "", cityName = "范县")
        val line = BusLineDirection(
            lineId = LenzIds.lineId("120.76.98.113", 8619, "937", 0, "范县至高码头镇"),
            name = "范县至高码头镇",
            direction = 0,
            startStop = "范县",
            endStop = "乔洼村",
            source = DataSourceType.LENZ
        )
        val timetable = provider.getTimetable(city, line, atTimeHHMM = "08:10")
        assertEquals(24, timetable.todayDepartures.size)
        assertEquals("06:30", timetable.todayDepartures.first())
        assertEquals("18:00", timetable.todayDepartures.last())
        assertEquals("08:30", timetable.nextDeparture)
        assertEquals("计划发车", timetable.nextDepartureSource)
        assertTrue(timetable.depDesc.contains("官方冬季时刻表"))
    }
}
