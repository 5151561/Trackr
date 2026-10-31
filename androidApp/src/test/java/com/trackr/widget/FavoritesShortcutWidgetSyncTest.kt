package com.trackr.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.domain.model.FavoriteStationLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 应用级收藏同步：只在收藏内容真变时刷新快捷方式和小组件 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FavoritesShortcutWidgetSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val calls = mutableListOf<List<String>>()

    private fun favorite(id: String) = FavoriteStationLine(
        id = id,
        cityId = "034",
        cityName = "上海",
        lineId = "line-$id",
        lineName = "${id}路",
        startStop = "起点",
        endStop = "终点",
        targetStopId = "stop-$id",
        targetStopName = "人民广场",
        targetOrder = 3
    )

    private fun TestScope.newSync(repository: FakeTransitRepository): FavoritesShortcutWidgetSync {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return FavoritesShortcutWidgetSync(
            context = context,
            repository = repository,
            appScope = backgroundScope,
            ioDispatcher = dispatcher
        ) { _, favorites -> calls += favorites.map { it.id } }
    }

    @Test
    fun start_doesNotRefreshForCurrentValue() = runTest {
        val repository = FakeTransitRepository(initialFavorites = listOf(favorite("1")))
        newSync(repository).start()
        testScheduler.runCurrent()

        assertEquals(emptyList<List<String>>(), calls)
    }

    @Test
    fun favoritesChanged_refreshesOncePerChange() = runTest {
        val repository = FakeTransitRepository(initialFavorites = listOf(favorite("1")))
        val sync = newSync(repository)
        sync.start()
        sync.start() // 重复调用不开第二个收集
        testScheduler.runCurrent()

        repository.toggleFavorite(favorite("2"))
        testScheduler.runCurrent()
        assertEquals(listOf(listOf("1", "2")), calls)

        repository.removeFavorite("1")
        testScheduler.runCurrent()
        assertEquals(listOf(listOf("1", "2"), listOf("2")), calls)
    }
}
