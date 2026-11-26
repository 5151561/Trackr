package com.trackr.core.database

import com.trackr.core.database.dao.RouteRows
import com.trackr.core.database.entity.CandidateLineEntity
import com.trackr.core.database.entity.FavoriteGroupEntity
import com.trackr.core.database.entity.FavoriteItemEntity
import com.trackr.core.database.entity.FavoriteLineEntity
import com.trackr.core.database.entity.LineStopsCacheEntity
import com.trackr.core.database.entity.ReminderEntity
import com.trackr.core.database.entity.RouteEntity
import com.trackr.core.database.entity.RouteLegEntity
import com.trackr.core.database.entity.SearchHistoryEntity
import com.trackr.core.database.entity.StoreMetaEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 各 DAO 在内存库上的读写（Android 主机测试 + iOS 模拟器） */
class DaoTest : PlatformDatabaseTest() {

    private lateinit var db: TrackrDatabase

    @BeforeTest
    fun setUp() {
        db = createTestDatabase()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun favorite(id: String, position: Int) = FavoriteLineEntity(
        id = id, position = position, cityId = "051", cityName = "莆田", lineId = "L$id", lineName = "59",
        lineNo = "r1", direction = 1, startStop = "公交南站", endStop = "莆田学院", targetStopId = "s$id",
        targetStopName = "擢英中学", targetOrder = 7, totalStops = 36, wgsLat = 25.42, wgsLng = 119.0,
        source = "LEGACY_SOURCE", mygolbsCityCode = "", addedAtMillis = 1_000L + position
    )

    private fun route(id: String, position: Int, groupId: String? = "home", legs: Int = 2): RouteRows {
        val route = RouteEntity(
            id = id, position = position, name = "路线$id", category = "CUSTOM", isTemporary = false,
            sortOrder = position + 1, customGroupId = groupId, createdAt = 1L, updatedAt = 2L
        )
        val legRows = (0 until legs).map { p ->
            RouteLegEntity(
                routeId = id, position = p, legIndex = p, fromStopName = "A$p", fromStopId = "", toStopName = "B$p",
                toStopId = "", fromStopLat = 1.0, fromStopLng = 2.0, toStopLat = 3.0, toStopLng = 4.0,
                walkingDistanceMeters = 100 * p, ridePathJson = "[{\"lat\":1.0,\"lng\":2.0}]", walkPathBeforeJson = "[]"
            )
        }
        val candidates = (0 until legs).flatMap { leg ->
            (0 until 2).map { c ->
                CandidateLineEntity(
                    routeId = id, legPosition = leg, position = c, cityId = "051", cityName = "莆田",
                    lineId = "line-$leg-$c", lineName = "${leg}${c}路", source = "LEGACY_SOURCE", direction = 0,
                    fromStopOrder = 1, toStopOrder = 5, startStopName = "起", endStopName = "终", mygolbsCityCode = null
                )
            }
        }
        return RouteRows(route, legRows, candidates)
    }

    private fun reminder(id: String, position: Int, trigger: Long? = null) = ReminderEntity(
        id = id, position = position, enabled = true, hour = 7, minute = 30, daysOfWeek = "1,2,3,4,5",
        cityId = "051", cityName = "莆田", lineId = "L1", lineName = "59", direction = 1, endStopName = "终点",
        targetStopId = "s1", targetStopName = "擢英中学", targetOrder = 7, totalStops = 36,
        dataSourceType = "LEGACY_SOURCE", mygolbsCityCode = null, autoStartLiveTracking = false,
        lastScheduledTriggerAt = trigger
    )

    @Test
    fun favoriteLines_replaceAll_keepsPositionOrder() = runTest {
        val dao = db.favoriteLines()
        dao.replaceAll(listOf(favorite("b", 0), favorite("a", 1), favorite("c", 2)))
        assertEquals(listOf("b", "a", "c"), dao.getAll().map { it.id })
        assertEquals(listOf("b", "a", "c"), dao.observeAll().first().map { it.id })

        dao.replaceAll(listOf(favorite("c", 0)))
        assertEquals(listOf(favorite("c", 0)), dao.getAll())
    }

    @Test
    fun favoriteGroups_replaceAll() = runTest {
        val dao = db.favoriteGroups()
        dao.replaceAll(
            listOf(
                FavoriteGroupEntity("commute", 0, "上班通勤", 0, true),
                FavoriteGroupEntity("home", 1, "回家线路", 1, true)
            )
        )
        assertEquals(2, dao.count())
        assertEquals(listOf("commute", "home"), dao.getAll().map { it.id })
    }

    @Test
    fun routes_roundTripWithLegsAndCandidates() = runTest {
        val dao = db.routes()
        val rows = listOf(route("r2", 0, legs = 1), route("r1", 1, groupId = null, legs = 2))
        dao.replaceAll(rows)

        val loaded = dao.getAll()
        assertEquals(rows, loaded)
        assertEquals(2, dao.count())
    }

    @Test
    fun routes_replaceAll_removesOrphanLegsAndCandidates() = runTest {
        val dao = db.routes()
        dao.replaceAll(listOf(route("r1", 0, legs = 3), route("r2", 1)))
        dao.replaceAll(listOf(route("r2", 0, legs = 1)))

        val loaded = dao.getAll()
        assertEquals(listOf("r2"), loaded.map { it.route.id })
        assertEquals(1, loaded.single().legs.size)
        assertEquals(2, loaded.single().candidates.size)
        assertEquals(1, dao.getLegs().size)
        assertEquals(2, dao.getCandidates().size)
    }

    @Test
    fun routes_replaceRoutesAndGroups_isOneTransaction() = runTest {
        val dao = db.routes()
        dao.replaceRoutesAndGroups(
            listOf(route("r1", 0, groupId = null)),
            listOf(FavoriteGroupEntity("home", 0, "回家线路", 1, true))
        )
        assertNull(dao.getAll().single().route.customGroupId)
        assertEquals(listOf("home"), db.favoriteGroups().getAll().map { it.id })
    }

    @Test
    fun reminders_replaceAllKeepingTriggers_preservesScheduledTimes() = runTest {
        val dao = db.reminders()
        dao.replaceAll(listOf(reminder("r1", 0, trigger = 111L), reminder("r2", 1, trigger = null)))
        dao.setLastScheduledTriggerAt("r2", 222L)

        // 改提醒列表时传进来的触发时刻一律忽略，保留库里的
        dao.replaceAllKeepingTriggers(
            listOf(reminder("r2", 0, trigger = 999L).copy(hour = 9), reminder("r3", 1, trigger = 999L))
        )
        val loaded = dao.getAll()
        assertEquals(listOf("r2", "r3"), loaded.map { it.id })
        assertEquals(9, loaded[0].hour)
        assertEquals(222L, dao.getLastScheduledTriggerAt("r2"))
        assertNull(dao.getLastScheduledTriggerAt("r3"))
        // 删掉的提醒，触发时刻一起没了
        assertNull(dao.getLastScheduledTriggerAt("r1"))

        dao.setLastScheduledTriggerAt("r3", null)
        assertNull(dao.getLastScheduledTriggerAt("r3"))
    }

    @Test
    fun searchHistory_replaceAll_keepsOrder() = runTest {
        val dao = db.searchHistory()
        dao.replaceAll(listOf(SearchHistoryEntity("59", 0), SearchHistoryEntity("7路", 1)))
        assertEquals(listOf("59", "7路"), dao.getAll().map { it.query })
        dao.replaceAll(emptyList())
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun lineStopsCache_upsertAndPrune() = runTest {
        val dao = db.lineStopsCache()
        dao.upsert(LineStopsCacheEntity("k1", "[1]", fetchedAt = 100L))
        dao.upsert(LineStopsCacheEntity("k1", "[1,2]", fetchedAt = 200L))
        dao.upsert(LineStopsCacheEntity("k2", "[3]", fetchedAt = 50L))
        assertEquals("[1,2]", dao.get("k1")?.stopsJson)

        assertEquals(1, dao.deleteOlderThan(100L))
        assertNull(dao.get("k2"))
        assertEquals(200L, dao.get("k1")?.fetchedAt)
    }

    @Test
    fun storeMeta_putAndGet() = runTest {
        val dao = db.storeMeta()
        assertNull(dao.get("legacy_import_gen"))
        dao.put(StoreMetaEntity("legacy_import_gen", "0"))
        dao.put(StoreMetaEntity("legacy_import_gen", "1"))
        assertEquals("1", dao.get("legacy_import_gen"))
    }

    // ---- 收藏合一（第 14 步） ----

    private fun item(id: String, order: Int, groupId: String? = null) =
        FavoriteItemEntity(id = id, type = if (id.startsWith("stop_")) "STOP" else "ROUTE", refId = id.substringAfter('_'), groupId = groupId, sortOrder = order)

    @Test
    fun favorites_write_replacesOnlyGivenParts() = runTest {
        val dao = db.favorites()
        dao.write(
            stops = listOf(favorite("a", 0)),
            routes = listOf(route("r1", 0)),
            groups = listOf(FavoriteGroupEntity("home", 0, "回家线路", 0, true)),
            items = listOf(item("stop_a", 0), item("route_r1", 1, "home"))
        )
        // 只给 stops：路线、分组、索引都不动
        dao.write(stops = listOf(favorite("b", 0), favorite("a", 1)))
        val all = dao.loadAll()
        assertEquals(listOf("b", "a"), all.stops.map { it.id })
        assertEquals(listOf("r1"), all.routes.map { it.route.id })
        assertEquals(2, all.routes.single().legs.size)
        assertEquals(4, all.routes.single().candidates.size)
        assertEquals(listOf("home"), all.groups.map { it.id })
        assertEquals(listOf("stop_a", "route_r1"), all.items.map { it.id })
    }

    @Test
    fun favorites_itemUpdates_upsertOnlyThoseRows() = runTest {
        val dao = db.favorites()
        dao.write(items = listOf(item("stop_a", 0), item("stop_b", 1), item("route_r1", 2)))
        dao.write(itemUpdates = listOf(item("route_r1", -1, "home"), item("stop_c", 5)))
        assertEquals(
            listOf(item("route_r1", -1, "home"), item("stop_a", 0), item("stop_b", 1), item("stop_c", 5)),
            dao.getItems()
        )
    }

    @Test
    fun favorites_setGroupAndMove_updateRows() = runTest {
        val dao = db.favorites()
        dao.write(items = listOf(item("stop_a", 0), item("stop_b", 1), item("route_r1", 2)))
        dao.setGroup("stop_b", "gym")
        dao.move(listOf("route_r1", "stop_a", "stop_b"))
        assertEquals(listOf("route_r1", "stop_a", "stop_b"), dao.getItems().map { it.id })
        assertEquals("gym", dao.getItems().first { it.id == "stop_b" }.groupId)
        assertEquals(listOf("route_r1", "stop_a", "stop_b"), dao.observeItems().first().map { it.id })
    }

    @Test
    fun favorites_writeItemsNull_keepsItems_writeItemsEmpty_clears() = runTest {
        val dao = db.favorites()
        dao.write(items = listOf(item("stop_a", 0)))
        dao.write(groups = emptyList())
        assertEquals(1, dao.getItems().size)
        dao.write(items = emptyList())
        assertTrue(dao.getItems().isEmpty())
    }
}
