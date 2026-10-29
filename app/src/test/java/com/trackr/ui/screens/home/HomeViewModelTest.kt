package com.trackr.ui.screens.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusLineSummary
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitSearchResult
import com.trackr.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

        assertEquals(com.trackr.domain.model.EngineMode.ALL, viewModel.uiState.value.engineMode)

        viewModel.toggleEngineMode()
        testScheduler.runCurrent()
        assertEquals(com.trackr.domain.model.EngineMode.MYGOLBS, viewModel.uiState.value.engineMode)

        viewModel.toggleEngineMode()
        testScheduler.runCurrent()
        assertEquals(com.trackr.domain.model.EngineMode.CHELAILE, viewModel.uiState.value.engineMode)

        viewModel.setEngineMode(com.trackr.domain.model.EngineMode.ALL)
        testScheduler.runCurrent()
        assertEquals(com.trackr.domain.model.EngineMode.ALL, viewModel.uiState.value.engineMode)
    }
}
