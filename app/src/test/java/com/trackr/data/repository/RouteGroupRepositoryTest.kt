package com.trackr.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouteGroupRepositoryTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: RouteGroupRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("trackr_route_groups", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        repository = RouteGroupRepository(context, testDispatcher)
    }

    @Test
    fun testInitialPresetSampleGroup() {
        val groups = repository.groups.value
        assertEquals(1, groups.size)
        val sample = groups.first()
        assertEquals("早高峰上班通勤", sample.name)
        assertEquals(GroupCategory.COMMUTE, sample.category)
        assertFalse(sample.isTemporary)
        assertEquals(2, sample.legs.size)
    }

    @Test
    fun testAddAndRetrieveGroup() = runTest(testDispatcher) {
        val newGroup = TransitGroup(
            id = "group_weekend",
            name = "周末西湖游玩",
            category = GroupCategory.LEISURE,
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "杭州东站",
                    toStopName = "龙翔桥",
                    candidateLines = listOf(
                        CandidateLine(
                            cityId = "033",
                            cityName = "杭州",
                            lineId = "m1",
                            lineName = "地铁1号线",
                            source = DataSourceType.CHELAILE,
                            fromStopOrder = 5,
                            toStopOrder = 10
                        )
                    )
                )
            )
        )

        repository.addGroup(newGroup)

        val list = repository.groups.value
        assertEquals(2, list.size)
        val retrieved = repository.getGroupById("group_weekend")
        assertNotNull(retrieved)
        assertEquals("周末西湖游玩", retrieved?.name)
        assertEquals(GroupCategory.LEISURE, retrieved?.category)

        // 重新构建实例测试持久化读取
        val reloadedRepo = RouteGroupRepository(context, testDispatcher)
        val reloaded = reloadedRepo.getGroupById("group_weekend")
        assertNotNull(reloaded)
        assertEquals("周末西湖游玩", reloaded?.name)
    }

    @Test
    fun testUpdateGroup() = runTest(testDispatcher) {
        val group = repository.groups.value.first()
        val updated = group.copy(name = "日常上下班新名称")

        repository.updateGroup(updated)

        val retrieved = repository.getGroupById(group.id)
        assertEquals("日常上下班新名称", retrieved?.name)
    }

    @Test
    fun testDeleteGroup() = runTest(testDispatcher) {
        val sample = repository.groups.value.first()
        repository.deleteGroup(sample.id)

        val list = repository.groups.value
        assertTrue(list.isEmpty())
        assertNull(repository.getGroupById(sample.id))
    }

    @Test
    fun testSaveOrConvertTemporaryGroup() = runTest(testDispatcher) {
        val tempGroup = TransitGroup(
            id = "temp_imported_1",
            name = "高德导入路线",
            category = GroupCategory.TEMPORARY,
            isTemporary = true,
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "西溪湿地",
                    toStopName = "黄龙体育中心"
                )
            )
        )
        repository.addGroup(tempGroup)
        assertTrue(repository.getGroupById("temp_imported_1")!!.isTemporary)

        repository.saveOrConvertTemporaryGroup(
            groupId = "temp_imported_1",
            newName = "西溪到黄龙固定路线",
            newCategory = GroupCategory.CUSTOM
        )

        val converted = repository.getGroupById("temp_imported_1")
        assertNotNull(converted)
        assertFalse(converted!!.isTemporary)
        assertEquals("西溪到黄龙固定路线", converted.name)
        assertEquals(GroupCategory.CUSTOM, converted.category)
    }

    @Test
    fun testReorderGroups() = runTest(testDispatcher) {
        val group1 = TransitGroup(id = "g1", name = "G1")
        val group2 = TransitGroup(id = "g2", name = "G2")
        repository.addGroup(group1)
        repository.addGroup(group2)

        repository.reorderGroups(listOf("g2", "g1"))

        val list = repository.groups.value
        val g2 = list.find { it.id == "g2" }
        val g1 = list.find { it.id == "g1" }
        assertNotNull(g2)
        assertNotNull(g1)
        assertTrue(g2!!.sortOrder < g1!!.sortOrder)
    }
}
