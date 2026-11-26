package com.trackr.core.data.local

import com.trackr.core.database.dao.LineStopsCacheDao
import com.trackr.core.database.entity.LineStopsCacheEntity
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import kotlinx.coroutines.CancellationException

/**
 * 线路站表的磁盘缓存（Room `line_stops_cache`，重构计划 §2.4「站表缓存」，第 20 步接上）。
 *
 * 只用来「先显示」：打开线路页时先画站序，实时数据回来再补；从不挡住网络请求，过期的照样显示，下次请求后覆盖。
 * 空站表不写（沿用 b4221c6「空站表不缓存」）。读写失败都当没有缓存，不抛异常（取消照常抛）。
 */
interface LineStopsCache {
    suspend fun read(key: String): CachedLineStops?
    suspend fun write(key: String, line: BusLineDirection, reverseDirection: BusLineDirection?, stops: List<BusStop>)

    companion object {
        /** 缓存 key：数据源 + 城市 + 行政区划码 + 线路 id（线路 id 已区分方向） */
        fun keyOf(source: DataSourceType, cityId: String, mygolbsCityCode: String, lineId: String): String =
            "${source.name}|$cityId|$mygolbsCityCode|$lineId"
    }
}

/** 缓存里的一条站表 */
data class CachedLineStops(
    val line: BusLineDirection,
    val reverseDirection: BusLineDirection?,
    val stops: List<BusStop>,
    val fetchedAtMillis: Long
)

/** 没有磁盘缓存（旧存储 / 测试） */
object NoLineStopsCache : LineStopsCache {
    override suspend fun read(key: String): CachedLineStops? = null
    override suspend fun write(key: String, line: BusLineDirection, reverseDirection: BusLineDirection?, stops: List<BusStop>) = Unit
}

/**
 * Room 实现。站表按 [BusLineDetail] 的 JSON 存（车辆列表置空），不另加可序列化类型。
 * @param nowMillis 写入时刻（墙钟毫秒）
 */
class RoomLineStopsCache(
    private val dao: LineStopsCacheDao,
    private val nowMillis: () -> Long
) : LineStopsCache {

    override suspend fun read(key: String): CachedLineStops? = guard {
        val entity = dao.get(key) ?: return@guard null
        val detail = localJson.decodeFromString(BusLineDetail.serializer(), entity.stopsJson)
        if (detail.stops.isEmpty()) {
            null
        } else {
            CachedLineStops(detail.line, detail.reverseDirection, detail.stops, entity.fetchedAt)
        }
    }

    override suspend fun write(key: String, line: BusLineDirection, reverseDirection: BusLineDirection?, stops: List<BusStop>) {
        if (stops.isEmpty()) return
        guard {
            val json = localJson.encodeToString(
                BusLineDetail.serializer(),
                BusLineDetail(line = line, reverseDirection = reverseDirection, stops = stops, buses = emptyList(), updatedAtMillis = 0L)
            )
            dao.upsert(LineStopsCacheEntity(cacheKey = key, stopsJson = json, fetchedAt = nowMillis()))
        }
    }

    private inline fun <T> guard(block: () -> T?): T? = try {
        block()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        null
    }
}
