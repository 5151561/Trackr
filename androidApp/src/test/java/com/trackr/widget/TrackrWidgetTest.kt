package com.trackr.widget

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import com.trackr.R
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TransitCity
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeRealtimeRepository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 桌面小组件（第 26 步迁 Glance）：保留 0353590 的五条行为，界面文案和旧 RemoteViews 一样。
 */
@RunWith(RobolectricTestRunner::class)
class TrackrWidgetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun favorite(
        source: DataSourceType = DataSourceType.CUSTOM,
        mygolbsCityCode: String = "",
        targetOrder: Int = 5
    ) = FavoriteStationLine(
        id = "034_line-1_$targetOrder",
        cityId = "034",
        cityName = "上海",
        lineId = if (source == DataSourceType.CUSTOM) "mg:line-1" else "line-1",
        lineName = "71路",
        startStop = "延安东路外滩",
        endStop = "申昆路枢纽站",
        targetStopId = "stop-5",
        targetStopName = "人民广场",
        targetOrder = targetOrder,
        source = source,
        mygolbsCityCode = mygolbsCityCode
    )

    private fun detail(vararg buses: LiveBus) = BusLineDetail(
        line = BusLineDirection(lineId = "line-1", name = "71路", startStop = "延安东路外滩", endStop = "申昆路枢纽站"),
        stops = emptyList(),
        buses = buses.toList(),
        updatedAtMillis = 0L
    )

    /** 记下每次 getLineDetail 的城市，其余方法交给 [FakeRealtimeRepository] */
    private class RecordingRealtime(
        private val fake: FakeRealtimeRepository = FakeRealtimeRepository()
    ) : RealtimeRepository by fake {
        val cities = mutableListOf<TransitCity>()
        val calls get() = fake.lineDetailCalls

        override suspend fun getLineDetail(
            city: TransitCity,
            lineId: String,
            targetOrder: Int?,
            stationId: String?,
            wgsLat: Double?,
            wgsLng: Double?,
            source: DataSourceType,
            lineNameHint: String,
            directionHint: Int
        ): Result<BusLineDetail> {
            cities += city
            return fake.getLineDetail(city, lineId, targetOrder, stationId, wgsLat, wgsLng, source, lineNameHint, directionHint)
        }

        fun respond(result: Result<BusLineDetail>) {
            fake.lineDetailResult = result
        }

        fun hang() {
            fake.lineDetailHook = { awaitCancellation() }
        }
    }

    private fun updater(
        favorite: FavoriteStationLine?,
        realtime: RealtimeRepository,
        now: Long = 1_000L
    ) = BusStopWidgetUpdater(
        favoritesRepository = FakeFavoritesRepository(initialFavorites = listOfNotNull(favorite)),
        realtime = realtime,
        nowMillis = { now }
    )

    @Test
    fun twoInstances_shareOneRequest() = runTest {
        val realtime = RecordingRealtime().apply { respond(Result.success(detail())) }
        val published = mutableListOf<Pair<List<Int>, BusStopWidgetEta>>()

        updater(favorite(), realtime).refresh(listOf(11, 12)) { ids, eta -> published += ids to eta }

        assertEquals(1, realtime.calls.size)
        // 先推基础布局「查询中…」，再推结果；两次都同时给两个实例
        assertEquals(listOf(listOf(11, 12), listOf(11, 12)), published.map { it.first })
        assertEquals(BusStopWidgetUpdater.ETA_LOADING, published.first().second.etaText)
        assertEquals(0, published.first().second.progressPercent)
    }

    @Test
    fun noInstancesOrNoFavorite_noRequest() = runTest {
        val realtime = RecordingRealtime()
        val published = mutableListOf<BusStopWidgetEta>()

        updater(favorite(), realtime).refresh(emptyList<Int>()) { _, eta -> published += eta }
        updater(null, realtime).refresh(listOf(1)) { _, eta -> published += eta }

        assertEquals(0, realtime.calls.size)
        assertTrue(published.isEmpty())
    }

    @Test
    fun mygolbsFavorite_passesCityCodeAndGetsData() = runTest {
        val realtime = RecordingRealtime().apply {
            respond(Result.success(detail(LiveBus(busId = "b1", plateOrFleetNo = "沪A1", order = 3, segmentProgress = 0.5f))))
        }
        val published = mutableListOf<BusStopWidgetEta>()

        updater(favorite(source = DataSourceType.CUSTOM, mygolbsCityCode = "3100"), realtime)
            .refresh(listOf(1)) { _, eta -> published += eta }

        assertEquals("3100", realtime.cities.single().mygolbsCityCode)
        assertEquals(listOf("mg:line-1"), realtime.calls)
        val result = published.last()
        assertTrue(result.etaText != BusStopWidgetUpdater.ETA_TIMEOUT && result.etaText != BusStopWidgetUpdater.ETA_LOADING)
        assertTrue(result.progressPercent > 0)
    }

    @Test
    fun customFavorite_blankCityCodeIsNull() = runTest {
        val realtime = RecordingRealtime().apply { respond(Result.success(detail())) }

        updater(favorite(), realtime).refresh(listOf(1)) { _, _ -> }

        assertEquals(null, realtime.cities.single().mygolbsCityCode)
    }

    @Test
    fun twoBuses_picksTheNearerOne() {
        val far = LiveBus(busId = "far", plateOrFleetNo = "沪A1", order = 2, segmentProgress = 0.5f)
        val near = LiveBus(busId = "near", plateOrFleetNo = "沪A2", order = 4, segmentProgress = 0.2f)
        // 过了目标站的车不算
        val passed = LiveBus(busId = "passed", plateOrFleetNo = "沪A3", order = 6, segmentProgress = 0.9f)
        val fav = favorite(targetOrder = 5)

        val both = widgetEtaOf(fav, detail(far, near, passed))

        assertEquals(widgetEtaOf(fav, detail(near)), both)
        // (4 - 1 + 0.2) / 5 = 64%
        assertEquals(64, both.second)
    }

    @Test
    fun timeout_showsTapToRefresh() = runTest {
        val realtime = RecordingRealtime().apply { hang() }
        val published = mutableListOf<BusStopWidgetEta>()

        updater(favorite(), realtime).refresh(listOf(1)) { _, eta -> published += eta }

        // runTest 的虚拟时间：7 秒限时到了就出超时文案
        assertEquals(BusStopWidgetUpdater.ETA_TIMEOUT, published.last().etaText)
        assertEquals(0, published.last().progressPercent)
        assertEquals(7_000L, BusStopWidgetUpdater.FETCH_TIMEOUT_MS)
    }

    @Test
    fun failedResult_showsTapToRefresh_exception_showsNetworkError() = runTest {
        val failed = RecordingRealtime().apply { respond(Result.failure(IllegalStateException("boom"))) }
        val published = mutableListOf<BusStopWidgetEta>()
        updater(favorite(), failed).refresh(listOf(1)) { _, eta -> published += eta }
        assertEquals(BusStopWidgetUpdater.ETA_TIMEOUT, published.last().etaText)

        val throwing = object : RealtimeRepository by FakeRealtimeRepository() {
            override suspend fun getLineDetail(
                city: TransitCity,
                lineId: String,
                targetOrder: Int?,
                stationId: String?,
                wgsLat: Double?,
                wgsLng: Double?,
                source: DataSourceType,
                lineNameHint: String,
                directionHint: Int
            ): Result<BusLineDetail> = throw IllegalStateException("socket")
        }
        updater(favorite(), throwing).refresh(listOf(1)) { _, eta -> published += eta }
        assertEquals(BusStopWidgetUpdater.ETA_NETWORK_ERROR, published.last().etaText)
    }

    @Test
    fun content_showsFavoriteAndEta() = runGlanceAppWidgetUnitTest {
        setContext(context)
        val fav = favorite()
        provideComposable {
            BusStopWidgetContent(
                favorite = fav,
                eta = BusStopWidgetEta(fav.id, BusStopWidgetUpdater.ETA_TIMEOUT, 0, 0L)
            )
        }
        onNode(hasText("71路")).assertExists()
        onNode(hasText("上海 · ${fav.source.displayName}")).assertExists()
        onNode(hasText("开往 申昆路枢纽站")).assertExists()
        onNode(hasText("候车站: 人民广场")).assertExists()
        onNode(hasText(BusStopWidgetUpdater.ETA_TIMEOUT)).assertExists()
    }

    @Test
    fun content_etaOfAnotherFavorite_showsLoading() = runGlanceAppWidgetUnitTest {
        setContext(context)
        val fav = favorite()
        provideComposable {
            BusStopWidgetContent(
                favorite = fav,
                eta = BusStopWidgetEta("old-favorite", "3 分钟 · 还有 1 站", 80, 0L)
            )
        }
        onNode(hasText(BusStopWidgetUpdater.ETA_LOADING)).assertExists()
        onNode(hasText("3 分钟 · 还有 1 站")).assertDoesNotExist()
    }

    @Test
    fun content_noFavorite_showsEmptyState() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { BusStopWidgetContent(favorite = null, eta = null) }
        onNode(hasText("暂无收藏的候车站，点击打开 App 添加")).assertExists()
    }

    /** 升级不丢小组件（R9）：receiver 还是旧类名、旧配置文件，只是换成了 Glance 的 receiver */
    @Test
    fun receiver_keepsOldNameAndConfig() {
        val component = ComponentName(context, BusStopWidgetProvider::class.java)
        assertEquals("com.trackr.widget.BusStopWidgetProvider", component.className)
        assertTrue(GlanceAppWidgetReceiver::class.java.isAssignableFrom(BusStopWidgetProvider::class.java))

        val info = context.packageManager.getReceiverInfo(component, PackageManager.GET_META_DATA)
        assertEquals(R.xml.bus_stop_widget_info, info.metaData.getInt("android.appwidget.provider"))
    }
}
