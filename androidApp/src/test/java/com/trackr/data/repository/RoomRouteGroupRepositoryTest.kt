package com.trackr.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.core.data.local.RoomFavoritesStore
import com.trackr.core.data.local.toRows
import com.trackr.core.database.TrackrDatabase
import com.trackr.core.database.createInMemoryTrackrDatabase
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.GeoPoint
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 新存储（Room：route / route_leg / route_candidate_line / favorite_group）上的 RouteGroupRepository。
 * 旧存储上的用例在 [RouteGroupRepositoryTest]；这里重点是读回逐字段一致、删分组一个事务、并发写不丢。
 */
@RunWith(RobolectricTestRunner::class)
class RoomRouteGroupRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: TrackrDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = createInMemoryTrackrDatabase(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newRepository() = RouteGroupRepository(RoomFavoritesStore(db), Dispatchers.IO)

    private fun transferRoute(id: String, groupId: String? = "home") = TransitGroup(
        id = id,
        name = "家 ➔ 公司 (23路 ➔ 331路 · 1次换乘)",
        category = GroupCategory.CUSTOM,
        legs = listOf(
            TransitLeg(
                legIndex = 0,
                fromStopName = "荔枝公园",
                fromStopId = "s1",
                toStopName = "中转站",
                toStopId = "s2",
                fromStopLat = 25.42,
                fromStopLng = 119.01,
                toStopLat = 25.41,
                toStopLng = 119.02,
                candidateLines = listOf(
                    CandidateLine(cityId = "051", cityName = "莆田", lineId = "l23", lineName = "23路夜班", direction = 1, fromStopOrder = 3, toStopOrder = 9, startStopName = "起", endStopName = "终", mygolbsCityCode = "0"),
                    CandidateLine(cityId = "051", lineId = "l7", lineName = "7路", source = DataSourceType.CUSTOM)
                ),
                walkingDistanceMeters = 120,
                ridePath = listOf(GeoPoint(25.42, 119.01), GeoPoint(25.415, 119.015), GeoPoint(25.41, 119.02)),
                walkPathBefore = listOf(GeoPoint(25.421, 119.009))
            ),
            TransitLeg(
                legIndex = 1,
                fromStopName = "中转站",
                toStopName = "万达广场",
                candidateLines = listOf(CandidateLine(cityId = "051", lineId = "l331", lineName = "331路")),
                walkingDistanceMeters = 80
            )
        ),
        isTemporary = false,
        sortOrder = 3,
        customGroupId = groupId,
        createdAt = 1_793_411_491_753L,
        updatedAt = 1_793_411_517_420L
    )

    @Test
    fun defaultFavoriteGroups_areSeededAndPersisted() = runBlocking {
        newRepository()
        assertEquals(listOf("commute", "home"), db.favoriteGroups().getAll().map { it.id })
        assertEquals(listOf("commute", "home"), newRepository().favoriteGroups.value.map { it.id })
    }

    @Test
    fun storedRoute_readsBackFieldByField() = runBlocking {
        // 直接写库（就像迁移写进去的），仓库读出来的必须和原对象完全相等
        val original = transferRoute("r1")
        db.routes().replaceAll(listOf(original.toRows(0)))

        assertEquals(listOf(original), newRepository().groups.value)
    }

    @Test
    fun sampleData_isSanitizedOnLoad() = runBlocking {
        db.routes().replaceAll(
            listOf(
                TransitGroup(id = "sample_commute_group", name = "假数据").toRows(0),
                transferRoute("keep").toRows(1)
            )
        )
        assertEquals(listOf("keep"), newRepository().groups.value.map { it.id })
        assertEquals(listOf("keep"), db.routes().getAll().map { it.route.id })
    }

    @Test
    fun deleteFavoriteGroup_detachesRoutesInSameWrite() = runBlocking {
        val repository = newRepository()
        val fav = repository.createFavoriteGroup("外勤出行")
        repository.addGroup(transferRoute("field", groupId = fav.id))
        repository.addGroup(transferRoute("other", groupId = "home"))

        repository.deleteFavoriteGroup(fav.id)

        val reloaded = newRepository()
        assertNull(reloaded.favoriteGroups.value.find { it.id == fav.id })
        assertNull(reloaded.getGroupById("field")!!.customGroupId)
        assertEquals("home", reloaded.getGroupById("other")!!.customGroupId)
        assertEquals(reloaded.groups.value, repository.groups.value)
    }

    @Test
    fun reorderAndCandidateChanges_persist() = runBlocking {
        val repository = newRepository()
        repository.addGroup(transferRoute("g1"))
        repository.addGroup(transferRoute("g2"))
        repository.reorderGroups(listOf("g2", "g1"))
        repository.addCandidateLineToLeg("g1", 1, CandidateLine(cityId = "051", lineId = "l99", lineName = "99路"))

        val reloaded = newRepository()
        assertEquals(listOf("g2", "g1"), reloaded.groups.value.map { it.id })
        assertEquals(listOf("l331", "l99"), reloaded.getGroupById("g1")!!.legs[1].candidateLines.map { it.lineId })
        assertEquals(repository.groups.value, reloaded.groups.value)
    }

    @Test
    fun concurrentAddGroupsAndFavoriteGroups_doNotLoseUpdates() = runBlocking {
        val repository = newRepository()
        val count = 40
        coroutineScope {
            val addGroups = (1..count).map { index ->
                async(Dispatchers.Default) {
                    repository.addGroup(TransitGroup(id = "concurrent_$index", name = "并发分组$index"))
                }
            }
            val createFavorites = (1..count).map { index ->
                async(Dispatchers.Default) { repository.createFavoriteGroup("收藏$index") }
            }
            (addGroups + createFavorites).awaitAll()
        }

        assertEquals(count, repository.groups.value.size)
        assertEquals(count + 2, repository.favoriteGroups.value.size)

        val reloaded = newRepository()
        assertEquals(count, reloaded.groups.value.size)
        assertEquals(count + 2, reloaded.favoriteGroups.value.size)
        assertTrue(reloaded.groups.value.map { it.id }.toSet().size == count)
        assertNotNull(reloaded.getGroupById("concurrent_1"))
    }
}
