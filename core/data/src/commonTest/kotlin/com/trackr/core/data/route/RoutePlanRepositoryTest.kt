package com.trackr.core.data.route

import com.trackr.core.model.AmapTipItem
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.testing.FakeAmapRouteGateway
import com.trackr.core.testing.FakeClock
import com.trackr.core.testing.FakeRealtimeRepository
import com.trackr.core.testing.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 第 17 步：[DefaultRoutePlanRepository] 的缓存（原 `FavoritesGroupViewModelTest` 经 ViewModel 端到端测的那部分），
 * 这里直接测仓库：算路按「城市 + 6 位小数坐标 + 15 分钟时间桶」缓存，输入提示按「城市 + 关键字」缓存。
 */
class RoutePlanRepositoryTest {

    private val hangzhou = TransitCity(cityId = "033", cityName = "杭州")
    private val shanghai = TransitCity(cityId = "034", cityName = "上海")

    /** 默认起点 2026-11-01T00:00:00Z，正好是一个 15 分钟时间桶的开头 */
    private val clock = FakeClock()

    private fun repository(gateway: FakeAmapRouteGateway): DefaultRoutePlanRepository {
        val settings = FakeSettingsRepository(hangzhou)
        val alignment = RouteAlignmentService(
            settings = settings,
            realtime = FakeRealtimeRepository(currentCity = { settings.selectedCity.value }),
            coordinator = null,
            ioDispatcher = Dispatchers.Unconfined,
            wallClockMillis = { clock.nowMillis }
        )
        return DefaultRoutePlanRepository(
            gateway = gateway,
            alignment = alignment,
            coordinator = null,
            ioDispatcher = Dispatchers.Unconfined,
            wallClockMillis = { clock.nowMillis }
        )
    }

    private fun scheme(title: String) = AmapTransitSchemeResult(
        title = title,
        durationMinutes = 20,
        walkingMeters = 100,
        costYuan = "2.0",
        transferCount = 0,
        group = TransitGroup(
            id = "scheme_$title",
            name = title,
            legs = listOf(TransitLeg(legIndex = 0, fromStopName = "甲站", toStopName = "乙站"))
        )
    )

    private val origin = AmapTipItem(name = "起点", location = "120.1234567,30.1234567")
    private val dest = AmapTipItem(name = "C", location = "120.3,30.3")

    @Test
    fun planTransit_sameCityCoordinatesAndBucket_hitsCache() = runTest {
        var round = 0
        val gateway = FakeAmapRouteGateway().apply { planResult = { listOf(scheme("第${++round}次")) } }
        val repo = repository(gateway)

        val first = repo.planTransit(origin, dest, hangzhou, useAmap = true)
        // 坐标第 7 位小数不同：按 6 位小数算是同一个点
        val second = repo.planTransit(origin, dest.copy(location = "120.3000001,30.3000001"), hangzhou, useAmap = true)

        assertEquals(1, gateway.planCalls.size)
        assertEquals(listOf("第1次"), first.map { it.title })
        assertEquals(first, second)

        // 换了城市：不是同一个缓存键，重新算
        repo.planTransit(origin, dest, shanghai, useAmap = true)
        assertEquals(2, gateway.planCalls.size)
    }

    @Test
    fun planTransit_crossingFifteenMinuteBucket_replans() = runTest {
        var round = 0
        val gateway = FakeAmapRouteGateway().apply { planResult = { listOf(scheme("第${++round}次")) } }
        val repo = repository(gateway)

        repo.planTransit(origin, dest, hangzhou, useAmap = true)

        // 同一个时间桶的最后一毫秒：仍命中缓存
        clock.advanceBy(15.minutes - 1.milliseconds)
        val sameBucket = repo.planTransit(origin, dest, hangzhou, useAmap = true)
        assertEquals(1, gateway.planCalls.size)
        assertEquals(listOf("第1次"), sameBucket.map { it.title })

        // 跨进下一个 15 分钟时间桶：重新请求高德，拿到新结果
        clock.advanceBy(1.milliseconds)
        val nextBucket = repo.planTransit(origin, dest, hangzhou, useAmap = true)
        assertEquals(2, gateway.planCalls.size)
        assertEquals(listOf("第2次"), nextBucket.map { it.title })

        // 新的时间桶里再查一次：命中新缓存
        clock.advanceBy(30.seconds)
        repo.planTransit(origin, dest, hangzhou, useAmap = true)
        assertEquals(2, gateway.planCalls.size)
    }

    @Test
    fun searchTips_cachedPerCityAndKeyword_emptyResultNotCached() = runTest {
        val gateway = FakeAmapRouteGateway()
        val repo = repository(gateway)

        repo.searchTips("西湖", hangzhou, null)
        val cached = repo.searchTips("西湖", hangzhou, null)
        assertEquals(listOf("西湖"), gateway.tipQueries)
        assertEquals("西湖站", cached.firstOrNull()?.name)

        // 换了城市：重新查
        repo.searchTips("西湖", shanghai, null)
        assertEquals(listOf("西湖", "西湖"), gateway.tipQueries)

        // 没有结果（也没有本地兜底）时不缓存，下次还会再查
        gateway.tipsProvider = { emptyList() }
        repo.searchTips("断桥", hangzhou, null)
        repo.searchTips("断桥", hangzhou, null)
        assertEquals(listOf("西湖", "西湖", "断桥", "断桥"), gateway.tipQueries)
    }

    @Test
    fun searchTips_prioritizesCurrentCity_whileKeepingIntercityTips() = runTest {
        val gateway = FakeAmapRouteGateway().apply {
            tipsProvider = {
                listOf(
                    AmapTipItem(name = "昆山南站", district = "江苏省苏州市昆山市", location = "120.95,31.35", adcode = "320583"),
                    AmapTipItem(name = "上海虹桥站", district = "上海市闵行区", location = "121.32,31.19", adcode = "310112"),
                    AmapTipItem(name = "嘉善南站", district = "浙江省嘉兴市嘉善县", location = "120.93,30.79", adcode = "330421")
                )
            }
        }
        val repo = repository(gateway)
        val sh = shanghai.copy(mygolbsCityCode = "310100")

        val tips = repo.searchTips("南站", sh, null)
        // #66：本城排第一，同时保留相邻城市的结果
        assertEquals(listOf("上海虹桥站", "昆山南站", "嘉善南站"), tips.map { it.name })
    }

    @Test
    fun planTransit_intercity_resolvesOriginCityAndCitydCorrectly() = runTest {
        val gateway = FakeAmapRouteGateway().apply { planResult = { listOf(scheme("城际")) } }
        val repo = repository(gateway)
        val sh = shanghai.copy(mygolbsCityCode = "310100")

        val shOrigin = AmapTipItem(name = "虹桥站", district = "上海市闵行区", location = "121.32,31.19", adcode = "310112")
        val ksDest = AmapTipItem(name = "昆山南站", district = "江苏省苏州市昆山市", location = "120.95,31.35", adcode = "320583")
        val jxDest = AmapTipItem(name = "嘉善南站", district = "浙江省嘉兴市嘉善县", location = "120.93,30.79", adcode = "330421")
        val myLoc = AmapTipItem(name = "我的位置", district = "上海", location = "121.47,31.23", adcode = "")

        // A → B（上海 → 昆山/苏州）
        repo.planTransit(shOrigin, ksDest, sh, useAmap = true)
        assertEquals("310100", gateway.planCalls.last().cityName)
        assertEquals("320500", gateway.planCalls.last().cityd)

        // B → A（昆山/苏州 → 上海，即使首页当前城市是上海，起点城市仍应传苏州 320500）
        repo.planTransit(ksDest, shOrigin, sh, useAmap = true)
        assertEquals("320500", gateway.planCalls.last().cityName)
        assertEquals("310100", gateway.planCalls.last().cityd)

        // B → C（昆山/苏州 → 嘉善/嘉兴）
        repo.planTransit(ksDest, jxDest, sh, useAmap = true)
        assertEquals("320500", gateway.planCalls.last().cityName)
        assertEquals("330400", gateway.planCalls.last().cityd)

        // 我的位置(上海) → 昆山/苏州
        repo.planTransit(myLoc, ksDest, sh, useAmap = true)
        assertEquals("上海", gateway.planCalls.last().cityName)
        assertEquals("320500", gateway.planCalls.last().cityd)

        // 昆山/苏州 → 我的位置(上海)
        repo.planTransit(ksDest, myLoc, sh, useAmap = true)
        assertEquals("320500", gateway.planCalls.last().cityName)
        assertEquals("310100", gateway.planCalls.last().cityd)

        // 同城（上海 → 上海）：cityd 为 null
        repo.planTransit(shOrigin, shOrigin.copy(location = "121.47,31.23"), sh, useAmap = true)
        assertEquals("310100", gateway.planCalls.last().cityName)
        assertEquals(null, gateway.planCalls.last().cityd)
    }
}
