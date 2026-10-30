package com.trackr.ui.screens.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusLineSummary
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitSearchResult
import com.trackr.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var app: Application
    private lateinit var fakeRepository: FakeTransitRepository
    private val shanghai = TransitCity(cityId = "034", cityName = "上海", pinyin = "shanghai", province = "直辖市")
    private val beijing = TransitCity(cityId = "001", cityName = "北京", pinyin = "beijing", province = "直辖市")
    private val hangzhou = TransitCity(cityId = "0571", cityName = "杭州", pinyin = "hangzhou", province = "浙江省")

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        fakeRepository = FakeTransitRepository(initialCity = shanghai)
        fakeRepository.supportedCitiesResult = Result.success(listOf(shanghai, beijing, hangzhou))
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            application = app,
            repository = fakeRepository,
            enableAutoPolling = false
        )
    }

    @Test
    fun testInitialState_loadsSelectedCityAndObservesRepository() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertEquals("上海", viewModel.uiState.value.currentCity.cityName)
        assertEquals("034", viewModel.uiState.value.currentCity.cityId)
    }

    @Test
    fun testSelectCity_updatesCurrentCityInRepositoryAndState() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.selectCity(beijing)
        testScheduler.runCurrent()

        assertEquals("北京", viewModel.uiState.value.currentCity.cityName)
        assertEquals("001", fakeRepository.selectedCity.value.cityId)
    }

    @Test
    fun testFilterCities_queryMatchesCityNameAndPinyin() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.setCityPickerOpen(true)
        testScheduler.runCurrent()

        assertEquals(3, viewModel.uiState.value.cities.size)

        // Filter by pinyin prefix "hangz" (since "shanghai" contains "hang")
        viewModel.updateCitySearchQuery("hangz")
        val filteredPinyin = viewModel.uiState.value.filteredCities
        assertEquals(1, filteredPinyin.size)
        assertEquals("杭州", filteredPinyin.first().cityName)

        // Filter by unique city character "京"
        viewModel.updateCitySearchQuery("京")
        val filteredName = viewModel.uiState.value.filteredCities
        assertEquals(1, filteredName.size)
        assertEquals("北京", filteredName.first().cityName)
    }

    @Test
    fun testSearchQuery_triggersSearchInRepository() = runTest {
        fakeRepository.searchResult = Result.success(
            TransitSearchResult(
                keyword = "49",
                lines = listOf(
                    BusLineSummary(
                        name = "49路",
                        lineNo = "49",
                        directions = listOf(
                            BusLineDirection(
                                lineId = "l1",
                                name = "49路",
                                startStop = "汉口路",
                                endStop = "上海体育馆"
                            )
                        )
                    )
                )
            )
        )

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.updateSearchQuery("49")
        testScheduler.advanceTimeBy(350L)
        testScheduler.runCurrent()

        val searchResult = viewModel.uiState.value.searchResult
        assertEquals("49", searchResult?.keyword)
        assertEquals(1, searchResult?.lines?.size)
        assertEquals("49路", searchResult?.lines?.first()?.name)
    }

    @Test
    fun testFavorites_observesRepositoryChanges() = runTest {
        val fav = FavoriteStationLine(
            id = "fav_1",
            cityId = "034",
            cityName = "上海",
            lineId = "l1",
            lineName = "49路",
            direction = 0,
            startStop = "A",
            endStop = "B",
            targetStopId = "s1",
            targetStopName = "人民广场",
            targetOrder = 2,
            source = DataSourceType.CHELAILE
        )

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        fakeRepository.toggleFavorite(fav)
        testScheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.favorites.size)
        assertEquals("人民广场", viewModel.uiState.value.favorites.first().targetStopName)
    }

    @Test
    fun testEngineModeSwitching() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertEquals(com.trackr.domain.model.EngineMode.CHELAILE, viewModel.uiState.value.engineMode)

        viewModel.toggleEngineMode()
        testScheduler.runCurrent()
        assertEquals(com.trackr.domain.model.EngineMode.MYGOLBS, viewModel.uiState.value.engineMode)

        viewModel.toggleEngineMode()
        testScheduler.runCurrent()
        assertEquals(com.trackr.domain.model.EngineMode.CHELAILE, viewModel.uiState.value.engineMode)

        viewModel.setEngineMode(com.trackr.domain.model.EngineMode.MYGOLBS)
        testScheduler.runCurrent()
        assertEquals(com.trackr.domain.model.EngineMode.MYGOLBS, viewModel.uiState.value.engineMode)
    }

    @Test
    fun testEngineModeSwitching_immediatelyRefreshesNearbyStops() = runTest {
        val stopA = NearbyStop(stopId = "stop_a", name = "站点A")
        val stopB = NearbyStop(stopId = "stop_b", name = "站点B")
        fakeRepository.nearbyStopsResult = Result.success(listOf(stopA))

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertEquals("站点A", viewModel.uiState.value.nearbyStops.firstOrNull()?.name)

        // 切换引擎模式前更新 mock 数据，模拟切换后数据源的数据
        fakeRepository.nearbyStopsResult = Result.success(listOf(stopB))
        viewModel.setEngineMode(com.trackr.domain.model.EngineMode.MYGOLBS)
        testScheduler.runCurrent()

        // 验证无需重启，周边站点即刻被重新拉取并更新为站点B
        assertEquals("站点B", viewModel.uiState.value.nearbyStops.firstOrNull()?.name)
    }

    @Test
    fun testAggregatedPrioritySwitching_immediatelyRefreshesNearbyStopsInAllMode() = runTest {
        val stopCL = NearbyStop(stopId = "stop_cl", name = "车来了站点")
        val stopMG = NearbyStop(stopId = "stop_mg", name = "掌上公交站点")
        fakeRepository.nearbyStopsResult = Result.success(listOf(stopCL))

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertEquals("车来了站点", viewModel.uiState.value.nearbyStops.firstOrNull()?.name)
        assertEquals(com.trackr.domain.model.AggregatedSourcePriority.CHELAILE_FIRST, viewModel.uiState.value.aggregatedPriority)

        // 切换聚合优先级前更新 mock 数据，模拟优先源变更后的数据
        fakeRepository.nearbyStopsResult = Result.success(listOf(stopMG))
        viewModel.setAggregatedPriority(com.trackr.domain.model.AggregatedSourcePriority.MYGOLBS_FIRST)
        testScheduler.runCurrent()

        assertEquals(com.trackr.domain.model.AggregatedSourcePriority.MYGOLBS_FIRST, viewModel.uiState.value.aggregatedPriority)
        assertEquals("掌上公交站点", viewModel.uiState.value.nearbyStops.firstOrNull()?.name)
    }

    @Test
    fun testNearbyStopsCountChange_refreshesNearbyStops() = runTest {
        val stop1 = NearbyStop(stopId = "stop_1", name = "站点1")
        val stop2 = NearbyStop(stopId = "stop_2", name = "站点2")
        fakeRepository.nearbyStopsResult = Result.success(listOf(stop1))

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertEquals(1, viewModel.uiState.value.nearbyStops.size)

        fakeRepository.nearbyStopsResult = Result.success(listOf(stop1, stop2))
        fakeRepository.setNearbyStopsCount(8)
        testScheduler.runCurrent()

        assertEquals(2, viewModel.uiState.value.nearbyStops.size)
    }

    @Test
    fun testFilteredCities_filtersByEngineMode() = runTest {
        val clCity = TransitCity(cityId = "034", cityName = "上海", mygolbsCityCode = null)
        val mgCity = TransitCity(cityId = "", cityName = "玉林", mygolbsCityCode = "450900")
        val bothCity = TransitCity(cityId = "051", cityName = "深圳", mygolbsCityCode = "440300")
        fakeRepository.supportedCitiesResult = Result.success(listOf(clCity, mgCity, bothCity))

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        // 1. CHELAILE 模式包含车来了城市 (clCity, bothCity)
        viewModel.setEngineMode(com.trackr.domain.model.EngineMode.CHELAILE)
        testScheduler.runCurrent()
        assertEquals(2, viewModel.uiState.value.filteredCities.size)

        // 2. MYGOLBS 模式只包含有 mygolbsCityCode 的城市 (mgCity, bothCity)
        viewModel.setEngineMode(com.trackr.domain.model.EngineMode.MYGOLBS)
        testScheduler.runCurrent()
        val mgList = viewModel.uiState.value.filteredCities
        assertEquals(2, mgList.size)
        assertTrue(mgList.any { it.cityName == "玉林" })
        assertTrue(mgList.any { it.cityName == "深圳" })

        // 3. CHELAILE 模式只包含有 cityId 的城市 (clCity, bothCity)
        viewModel.setEngineMode(com.trackr.domain.model.EngineMode.CHELAILE)
        testScheduler.runCurrent()
        val clList = viewModel.uiState.value.filteredCities
        assertEquals(2, clList.size)
        assertTrue(clList.any { it.cityName == "上海" })
        assertTrue(clList.any { it.cityName == "深圳" })
    }

    @Test
    fun testSearchHistory_addDeduplicateAndOrder() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertEquals(0, viewModel.uiState.value.searchHistory.size)

        viewModel.addSearchHistory("71路")
        testScheduler.runCurrent()
        assertEquals(listOf("71路"), viewModel.uiState.value.searchHistory)

        viewModel.addSearchHistory("人民广场")
        testScheduler.runCurrent()
        assertEquals(listOf("人民广场", "71路"), viewModel.uiState.value.searchHistory)

        // Adding existing item re-orders it to front
        viewModel.addSearchHistory("71路")
        testScheduler.runCurrent()
        assertEquals(listOf("71路", "人民广场"), viewModel.uiState.value.searchHistory)

        // Blank string is ignored
        viewModel.addSearchHistory("   ")
        testScheduler.runCurrent()
        assertEquals(listOf("71路", "人民广场"), viewModel.uiState.value.searchHistory)
    }

    @Test
    fun testSearchHistory_removeItem() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.addSearchHistory("71路")
        viewModel.addSearchHistory("2号线")
        testScheduler.runCurrent()

        assertEquals(listOf("2号线", "71路"), viewModel.uiState.value.searchHistory)

        viewModel.removeSearchHistory("2号线")
        testScheduler.runCurrent()

        assertEquals(listOf("71路"), viewModel.uiState.value.searchHistory)
    }

    @Test
    fun testSearchHistory_clearAll() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.addSearchHistory("1路")
        viewModel.addSearchHistory("2路")
        testScheduler.runCurrent()
        assertEquals(2, viewModel.uiState.value.searchHistory.size)

        viewModel.clearSearchHistory()
        testScheduler.runCurrent()
        assertEquals(0, viewModel.uiState.value.searchHistory.size)
    }

    @Test
    fun testSearchHistory_maxCapacityLimit() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        for (i in 1..25) {
            viewModel.addSearchHistory("线路$i")
        }
        testScheduler.runCurrent()

        assertEquals(20, viewModel.uiState.value.searchHistory.size)
        assertEquals("线路25", viewModel.uiState.value.searchHistory.first())
        assertEquals("线路6", viewModel.uiState.value.searchHistory.last())
    }

    @Test
    fun testRefresh_triggersRelocationAndRefreshesData() = runTest {
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val lm = app.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val shadowLm = org.robolectric.Shadows.shadowOf(lm)
        shadowLm.setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
        val testLocation = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = 30.2741
            longitude = 120.1551
            time = System.currentTimeMillis()
            accuracy = 3.0f
        }
        shadowLm.setLastKnownLocation(android.location.LocationManager.GPS_PROVIDER, testLocation)

        val dummyStop = com.trackr.domain.model.NearbyStop(
            stopId = "stop_hz_1",
            name = "武林广场",
            distanceMeters = 100,
            lines = emptyList()
        )
        fakeRepository.nearbyStopsResult = Result.success(listOf(dummyStop))

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.refresh()
        testScheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.isRefreshing)
        assertEquals(false, viewModel.uiState.value.isLocating)
        assertEquals(1, viewModel.uiState.value.nearbyStops.size)
        assertEquals("武林广场", viewModel.uiState.value.nearbyStops.first().name)
        // 验证用户定位坐标被同步更新到 repository
        assertEquals(30.2741, fakeRepository.userLocation.value?.first ?: 0.0, 0.0001)
        assertEquals(120.1551, fakeRepository.userLocation.value?.second ?: 0.0, 0.0001)
    }

    @Test
    fun testRefreshLocationAndNearby_delegatesToRefresh() = runTest {
        val dummyStop = com.trackr.domain.model.NearbyStop(
            stopId = "stop_sh_1",
            name = "人民广场",
            distanceMeters = 50,
            lines = emptyList()
        )
        fakeRepository.nearbyStopsResult = Result.success(listOf(dummyStop))

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.refreshLocationAndNearby()
        testScheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.isRefreshing)
        assertEquals(false, viewModel.uiState.value.isLocating)
        assertEquals(1, viewModel.uiState.value.nearbyStops.size)
        assertEquals("人民广场", viewModel.uiState.value.nearbyStops.first().name)
    }

    @Test
    fun testRefresh_whenCacheIsStale_fallbackToBestStale() = runTest {
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val lm = app.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val shadowLm = org.robolectric.Shadows.shadowOf(lm)
        shadowLm.setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
        // 模拟 1 分钟前的旧位置（超过 forceFresh 的 10 秒阈值）
        val staleLocation = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = 31.2304
            longitude = 121.4737
            time = System.currentTimeMillis() - 60_000L
            accuracy = 10.0f
        }
        shadowLm.setLastKnownLocation(android.location.LocationManager.GPS_PROVIDER, staleLocation)

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.refresh()
        // 推进时间让 realTimeFix 超时 (4500ms)
        testScheduler.advanceTimeBy(5000L)
        testScheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.isRefreshing)
        assertEquals(false, viewModel.uiState.value.isLocating)
        assertEquals(31.2304, fakeRepository.userLocation.value?.first ?: 0.0, 0.0001)
        assertEquals(121.4737, fakeRepository.userLocation.value?.second ?: 0.0, 0.0001)
    }

    @Test
    fun testPollingLifecycle_pauseResumeAndExplicitControl() = runTest {
        val vmWithAutoPolling = HomeViewModel(
            application = app,
            repository = fakeRepository,
            enableAutoPolling = true
        )
        assertTrue(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.pausePolling()
        assertFalse(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.resumePolling()
        assertTrue(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.setPollingActive(false)
        assertFalse(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.setPollingActive(true)
        assertTrue(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.pausePolling()
    }

    @Test
    fun testPollingLifecycle_disabledByDefaultInTest() = runTest {
        val viewModel = createViewModel()
        assertFalse(viewModel.isPollingActive)

        viewModel.setPollingActive(true)
        assertTrue(viewModel.isPollingActive)

        viewModel.pausePolling()
        assertFalse(viewModel.isPollingActive)
    }
}
