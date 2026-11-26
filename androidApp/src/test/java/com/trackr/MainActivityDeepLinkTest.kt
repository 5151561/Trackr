package com.trackr

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.navigation.deeplink.DeepLinkUris
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeLocationRepository
import com.trackr.core.testing.FakeRealtimeRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.FakeUserLocationStore
import com.trackr.data.repository.FakeScheduledReminderRepository
import com.trackr.deeplink.DeepLinkIntents
import com.trackr.feature.bus.home.BusHomeViewModel
import com.trackr.ui.screens.linedetail.LineDetailViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * 第 18 步：真实的 [MainActivity] 收 Intent（重构计划 §3.6）。
 * 冷启动带 `trackr://line` → 首页栈上开线路详情；热启动 `onNewIntent` 同样；配置变更 / 进程重建恢复时不重放；
 * 乘车码链接不入栈、直接拉起；处理完 Intent 里的链接被清掉。首页、线路详情的 ViewModel 接 Fake，不发真请求。
 */
@Config(qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class MainActivityDeepLinkTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private val city = TransitCity(cityId = "034", cityName = "上海")
    private val settings = FakeSettingsRepository(city)
    private val realtime = FakeRealtimeRepository(currentCity = { settings.selectedCity.value })
    private val openedLines = mutableListOf<ScreenKey.LineDetail>()
    private val homeViewModels = mutableListOf<BusHomeViewModel>()
    private var controller: ActivityController<MainActivity>? = null

    private val line49 = ScreenKey.LineDetail(
        cityId = "034",
        lineId = "line_49",
        targetOrder = 4,
        stopId = "stop_004",
        source = DataSourceType.CUSTOM,
        lineName = "49路"
    )

    private fun lineIntent(): Intent = DeepLinkIntents.activityIntent(
        ApplicationProvider.getApplicationContext(),
        DeepLinkUris.line("034", "line_49", DataSourceType.CUSTOM, "stop_004", 4, "49路")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun launcherIntent(): Intent =
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

    @Before
    fun setUp() {
        shadowOf(ApplicationProvider.getApplicationContext<TrackrApp>()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
        loadKoinModules(
            module {
                viewModel {
                    BusHomeViewModel(
                        settings = settings,
                        // 有一条收藏，首页才显示「编辑」（拿它认首页根页）
                        favorites = FakeFavoritesRepository(initialFavorites = listOf(SAMPLE_FAVORITE)),
                        realtime = realtime,
                        userLocationStore = FakeUserLocationStore(),
                        locationRepository = FakeLocationRepository(),
                        trackingController = get(),
                        trackingRepository = get(),
                        transitCodeLauncher = get(),
                        enableAutoPolling = false
                    ).also { homeViewModels += it }
                }
                viewModel { (key: ScreenKey.LineDetail) ->
                    openedLines += key
                    LineDetailViewModel(
                        cityId = key.cityId,
                        initialLineId = key.lineId,
                        initialTargetOrder = key.targetOrder.takeIf { it > 0 },
                        initialStationId = key.stopId.takeIf { it.isNotBlank() },
                        initialSource = key.source,
                        lineNameHint = key.lineName,
                        settings = settings,
                        favorites = FakeFavoritesRepository(),
                        realtime = realtime,
                        reminderRepository = FakeScheduledReminderRepository(),
                        trackingRepository = get(),
                        trackingController = get(),
                        lineStopsCache = get(),
                        enableAutoPolling = false
                    )
                }
            }
        )
    }

    @After
    fun tearDown() {
        homeViewModels.forEach { it.pausePolling() }
        controller?.pause()?.stop()?.destroy()
    }

    private fun launch(intent: Intent, savedState: Bundle? = null): MainActivity {
        val c = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller = c
        c.create(savedState).start().postCreate(savedState).resume().visible()
        composeTestRule.waitForIdle()
        return c.get()
    }

    @Test
    fun coldStart_withLineLink_opensLineDetail_andConsumesTheIntent() {
        val activity = launch(lineIntent())

        assertEquals(listOf(line49), openedLines)
        assertNull("链接已从 Intent 里清掉", activity.intent.data)
        assertNull("TrackrRoot 落完就清掉", activity.pendingDeepLink)
        composeTestRule.onNodeWithText("编辑").assertDoesNotExist()

        activity.onBackPressedDispatcher.onBackPressed()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("编辑").assertExists()
    }

    @Test
    fun coldStart_withLegacyExtras_stillOpensTheLine() {
        val legacy = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            putExtra("extra_city_id", "034")
            putExtra("extra_line_id", "line_49")
            putExtra("extra_target_order", 4)
            putExtra("extra_stop_id", "stop_004")
            putExtra("extra_source", "LEGACY_SOURCE")
            putExtra("extra_line_name", "49路")
        }
        val activity = launch(legacy)

        assertEquals(listOf(line49), openedLines)
        assertNull(activity.intent.getStringExtra("extra_city_id"))
    }

    @Test
    fun warmStart_onNewIntent_opensLineDetail() {
        launch(launcherIntent())
        composeTestRule.onNodeWithText("编辑").assertExists()
        assertTrue(openedLines.isEmpty())

        val newIntent = lineIntent()
        controller!!.newIntent(newIntent)
        composeTestRule.waitForIdle()

        assertEquals(listOf(line49), openedLines)
        assertNull(newIntent.data)
        composeTestRule.onNodeWithText("编辑").assertDoesNotExist()
    }

    @Test
    fun restoredWithSavedState_doesNotReplayTheLink() {
        // 配置变更 / 进程重建后系统带着原来的 Intent 和保存的状态重建 Activity：不再追加页面
        launch(lineIntent(), savedState = Bundle())

        assertTrue(openedLines.isEmpty())
        composeTestRule.onNodeWithText("编辑").assertExists()
    }

    @Test
    fun transitCodeLink_launchesTransitCode_withoutTouchingTheStack() {
        val activity = launch(launcherIntent())
        val transit = DeepLinkIntents.activityIntent(activity, DeepLinkUris.transitCode(TransitCodeProvider.ALIPAY))

        controller!!.newIntent(transit)
        composeTestRule.waitForIdle()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals("拉起支付宝乘车码", "com.eg.android.AlipayGphone", started?.`package`)
        assertNull(transit.data)
        assertTrue(openedLines.isEmpty())
        composeTestRule.onNodeWithText("编辑").assertExists()
    }

    @Test
    fun foreignLink_isIgnored() {
        val activity = launch(launcherIntent())
        val bad = DeepLinkIntents.activityIntent(activity, "trackr://line/034/L1%0A")

        controller!!.newIntent(bad)
        composeTestRule.waitForIdle()

        assertTrue(openedLines.isEmpty())
        assertNull(shadowOf(activity).nextStartedActivity)
        composeTestRule.onNodeWithText("编辑").assertExists()
    }

    private companion object {
        val SAMPLE_FAVORITE = FavoriteStationLine(
            id = "fav_1",
            cityId = "034",
            cityName = "上海",
            lineId = "l1",
            lineName = "49路",
            startStop = "A",
            endStop = "B",
            targetStopId = "s1",
            targetStopName = "人民广场",
            targetOrder = 2
        )
    }
}
