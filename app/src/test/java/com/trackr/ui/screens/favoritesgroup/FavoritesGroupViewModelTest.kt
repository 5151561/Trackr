package com.trackr.ui.screens.favoritesgroup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.data.repository.RouteGroupRepository
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import com.trackr.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FavoritesGroupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var groupRepository: RouteGroupRepository
    private lateinit var fakeTransitRepository: FakeTransitRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("trackr_route_groups", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        groupRepository = RouteGroupRepository(context, mainDispatcherRule.testDispatcher)
        fakeTransitRepository = FakeTransitRepository(
            initialCity = TransitCity(cityId = "033", cityName = "杭州")
        )
    }

    private fun createViewModel(): FavoritesGroupViewModel {
        return FavoritesGroupViewModel(
            groupRepository = groupRepository,
            transitRepository = fakeTransitRepository,
            enableAutoRefresh = false
        )
    }

    @Test
    fun testInitialGroupsAndCategoryFiltering() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val initialList = viewModel.groups.value
        assertEquals(0, initialList.size)

        // 添加一个通勤分组与一个休闲分组
        val commuteGroup = TransitGroup(
            id = "group_commute",
            name = "早间通勤",
            category = GroupCategory.COMMUTE,
            legs = listOf(TransitLeg(legIndex = 0, fromStopName = "X", toStopName = "Y"))
        )
        viewModel.saveGroup(commuteGroup)

        val leisureGroup = TransitGroup(
            id = "group_leisure",
            name = "周末西湖",
            category = GroupCategory.LEISURE,
            legs = listOf(TransitLeg(legIndex = 0, fromStopName = "A", toStopName = "B"))
        )
        viewModel.saveGroup(leisureGroup)
        testScheduler.runCurrent()

        assertEquals(2, viewModel.groups.value.size)

        // 切换过滤到 LEISURE
        viewModel.selectCategory(GroupCategory.LEISURE)
        testScheduler.runCurrent()
        assertEquals(1, viewModel.filteredGroups.value.size)
        assertEquals("周末西湖", viewModel.filteredGroups.value.first().name)

        // 切换回全部 (null)
        viewModel.selectCategory(null)
        testScheduler.runCurrent()
        assertEquals(2, viewModel.filteredGroups.value.size)
    }

    @Test
    fun testClipboardContentDetection() {
        val viewModel = createViewModel()

        viewModel.checkClipboardForShareContent("【高德地图】从杭州东站到西湖断桥 https://surl.amap.com/123")
        assertNotNull(viewModel.clipboardSharePrompt.value)

        viewModel.dismissClipboardPrompt()
        assertNull(viewModel.clipboardSharePrompt.value)

        viewModel.checkClipboardForShareContent("普通的非地图聊天文本内容")
        assertNull(viewModel.clipboardSharePrompt.value)
    }

    @Test
    fun testDeleteAndSaveTemporaryGroup() = runTest {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val tempGroup = TransitGroup(
            id = "temp_1",
            name = "地图临时方案",
            category = GroupCategory.TEMPORARY,
            isTemporary = true,
            legs = listOf(TransitLeg(legIndex = 0, fromStopName = "起点", toStopName = "终点"))
        )
        viewModel.saveGroup(tempGroup)
        testScheduler.runCurrent()

        viewModel.saveOrConvertTemporaryGroup(
            groupId = "temp_1",
            newName = "已保存的路线",
            newCategory = GroupCategory.CUSTOM
        )
        testScheduler.runCurrent()

        val saved = groupRepository.getGroupById("temp_1")
        assertNotNull(saved)
        assertFalse(saved!!.isTemporary)
        assertEquals("已保存的路线", saved.name)
        assertEquals(GroupCategory.CUSTOM, saved.category)

        viewModel.deleteGroup("temp_1")
        testScheduler.runCurrent()
        assertNull(groupRepository.getGroupById("temp_1"))
    }

    @Test
    fun testCustomAmapApiKeyPersistence() {
        val sharedPrefs = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        val viewModel = FavoritesGroupViewModel(
            groupRepository = groupRepository,
            transitRepository = fakeTransitRepository,
            prefs = sharedPrefs,
            enableAutoRefresh = false
        )

        assertNull(viewModel.customAmapApiKey.value)
        viewModel.saveCustomAmapApiKey("test_key_123456")
        assertEquals("test_key_123456", viewModel.customAmapApiKey.value)
        assertEquals("test_key_123456", sharedPrefs.getString("custom_amap_web_key", null))
    }
}
