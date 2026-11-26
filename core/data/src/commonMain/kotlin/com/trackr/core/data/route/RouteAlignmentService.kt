package com.trackr.core.data.route

import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.EngineMode
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 规划页方案列表最多显示几个（只对齐这几个；规划页和 [DefaultRoutePlanRepository] 共用） */
const val MAX_VISIBLE_SCHEMES = 4

/**
 * 路线（换乘组）与实时公交源的对齐（重构计划 §2.5、§2.10 的 RouteAlignmentService）。
 *
 * 第 17 步从 `FavoritesGroupViewModel` 原样搬来，逻辑不变：找真实线路走向与站序、对齐整组 / 规划方案、
 * 生成返程路线，以及合成候选线路的对齐备忘（命中 24 小时、没命中 10 分钟）。
 * Koin `single`：规划页、路线详情、编辑收藏共用一份备忘。
 *
 * @param coordinator 实时数据源搜索 / 线路详情（不写搜索历史）；为 null 时搜索走 [realtime]（测试用）
 */
class RouteAlignmentService(
    private val settings: SettingsRepository,
    private val realtime: RealtimeRepository,
    private val coordinator: TransitProviderCoordinator? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 墙钟（毫秒）：对齐备忘的过期、返程路线的创建时间；测试可替换 */
    private val wallClockMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }
) {

    companion object {
        /**
         * 早期版本写死的默认城市 ID 占位值。
         * 存量候选线路上的这个值视为"未指定城市"，回落到当前所选城市。
         */
        const val LEGACY_PLACEHOLDER_CITY_ID = "033"

        /** 合成候选线路对齐结果缓存：命中保留 24 小时，未命中 10 分钟后重试 */
        private const val ALIGN_HIT_TTL_MS = 24 * 60 * 60 * 1000L
        private const val ALIGN_MISS_TTL_MS = 10 * 60 * 1000L

        /** 方案与实时源对齐的最大并发 */
        private const val ALIGN_CONCURRENCY = 4
    }

    private data class AlignmentCacheEntry(val match: CandidateLine?, val savedAtMs: Long)

    /** 合成候选线路的对齐结果（内存缓存，避免每次 groups 变化都重新 search + getLineDetail） */
    private val syntheticAlignmentCache = HashMap<String, AlignmentCacheEntry>()

    // 原来是 ConcurrentHashMap；commonMain 里换成加锁的 HashMap，读写语义相同
    private val cacheLock = SynchronizedObject()

    /** 方案线路与实时源的匹配结果：[fromStop]/[toStop] 为空表示只对齐了线路，没对齐站序 */
    private data class SchemeLineMatch(
        val direction: BusLineDirection,
        val fromStop: BusStop?,
        val toStop: BusStop?
    )

    /**
     * 把高德方案里的候选线路对齐到实时公交源（真实 lineId / 方向 / 站序）。
     * 只处理界面可见的前 [MAX_VISIBLE_SCHEMES] 个方案；同一「线路名 + 上车站 + 下车站」只查一次；
     * 并发不超过 [ALIGN_CONCURRENCY]。
     */
    suspend fun alignAmapSchemesWithRealtimeSource(
        schemes: List<AmapTransitSchemeResult>,
        city: TransitCity
    ): List<AmapTransitSchemeResult> {
        val coord = coordinator ?: return schemes
        val visible = schemes.take(MAX_VISIBLE_SCHEMES)
        val rest = schemes.drop(MAX_VISIBLE_SCHEMES)
        return withContext(ioDispatcher) {
            val semaphore = Semaphore(ALIGN_CONCURRENCY)
            val memo = LinkedHashMap<String, Deferred<SchemeLineMatch?>>()

            fun memoKey(lineName: String, fromCore: String, toCore: String) = "$lineName|$fromCore|$toCore"

            // 1. 去重后并发查询
            for (scheme in visible) {
                for (leg in scheme.group.legs) {
                    val fromCore = RouteProjectionMath.coreStopName(leg.fromStopName)
                    val toCore = RouteProjectionMath.coreStopName(leg.toStopName)
                    for (candidate in leg.candidateLines) {
                        val key = memoKey(candidate.lineName, fromCore, toCore)
                        if (!memo.containsKey(key)) {
                            val lineName = candidate.lineName
                            memo[key] = async {
                                semaphore.withPermit { matchSchemeLine(coord, city, lineName, fromCore, toCore) }
                            }
                        }
                    }
                }
            }

            // 2. 套用匹配结果
            val aligned = visible.map { scheme ->
                val newLegs = scheme.group.legs.map { leg ->
                    val fromCore = RouteProjectionMath.coreStopName(leg.fromStopName)
                    val toCore = RouteProjectionMath.coreStopName(leg.toStopName)
                    val updatedCandidates = leg.candidateLines.map { candidate ->
                        val match = memo[memoKey(candidate.lineName, fromCore, toCore)]?.await()
                        if (match != null) applySchemeLineMatch(candidate, match, city) else candidate
                    }
                    leg.copy(candidateLines = updatedCandidates)
                }
                scheme.copy(group = scheme.group.copy(legs = newLegs))
            }
            aligned + rest
        }
    }

    /** 在实时源里查找线路：站序能对上的方向优先，否则退而取第一个同名方向 */
    private suspend fun matchSchemeLine(
        coord: TransitProviderCoordinator,
        city: TransitCity,
        lineName: String,
        fromCore: String,
        toCore: String
    ): SchemeLineMatch? = runCatching {
        val searchRes = coord.search(city, lineName, EngineMode.CUSTOM)
        val matchedDirs = searchRes.lines.flatMap { it.directions }.filter { dir ->
            dir.name.contains(lineName) || lineName.contains(dir.name)
        }
        var fallback: SchemeLineMatch? = null
        for (dir in matchedDirs) {
            // 线路详情只走 realtime 一条路径，失败不再对同一上游重复请求
            val detail = realtime.getLineDetail(
                city = city,
                lineId = dir.lineId,
                source = dir.source,
                lineNameHint = dir.name,
                directionHint = dir.direction
            ).getOrNull()
            if (detail != null) {
                val fromStop = detail.stops.find {
                    it.name.contains(fromCore) || fromCore.contains(RouteProjectionMath.coreStopName(it.name))
                }
                val toStop = detail.stops.find {
                    it.name.contains(toCore) || toCore.contains(RouteProjectionMath.coreStopName(it.name))
                }
                if (fromStop != null && toStop != null && fromStop.order < toStop.order) {
                    return@runCatching SchemeLineMatch(dir, fromStop, toStop)
                }
            }
            if (fallback == null) {
                fallback = SchemeLineMatch(dir, null, null)
            }
        }
        fallback
    }.getOrNull()

    private fun applySchemeLineMatch(
        candidate: CandidateLine,
        match: SchemeLineMatch,
        city: TransitCity
    ): CandidateLine {
        val dir = match.direction
        val fromStop = match.fromStop
        val toStop = match.toStop
        return if (fromStop != null && toStop != null) {
            candidate.copy(
                cityId = city.cityId,
                cityName = city.cityName,
                lineId = dir.lineId,
                direction = dir.direction,
                source = dir.source,
                fromStopOrder = fromStop.order,
                toStopOrder = toStop.order,
                startStopName = fromStop.name,
                endStopName = toStop.name,
                mygolbsCityCode = city.mygolbsCityCode
            )
        } else {
            candidate.copy(
                cityId = city.cityId,
                cityName = city.cityName,
                lineId = dir.lineId,
                direction = dir.direction,
                source = dir.source,
                mygolbsCityCode = city.mygolbsCityCode
            )
        }
    }

    /**
     * 在实时公交数据源中查找精准匹配的线路走向与起止站序
     */
    suspend fun findRealtimeLineMatch(
        city: TransitCity,
        lineName: String,
        fromStopName: String,
        toStopName: String,
        preferredDirection: Int? = null
    ): CandidateLine? {
        val fromCore = RouteProjectionMath.coreStopName(fromStopName)
        val toCore = RouteProjectionMath.coreStopName(toStopName)

        // 只走一条路径：有 coordinator 时直接搜索（不写搜索历史），否则走仓库；失败不再对同一上游重复请求
        val coord = coordinator
        val searchRes = if (coord != null) {
            runCatching { coord.search(city, lineName, EngineMode.CUSTOM) }.getOrNull()
        } else {
            runCatching { realtime.search(city, lineName).getOrNull() }.getOrNull()
        }

        val matchedDirs = searchRes?.lines?.flatMap { it.directions }?.filter { dir ->
            dir.name.contains(lineName) || lineName.contains(dir.name)
        }.orEmpty()

        val sortedDirs = if (preferredDirection != null) {
            matchedDirs.sortedByDescending { it.direction == preferredDirection }
        } else {
            matchedDirs
        }

        for (dir in sortedDirs) {
            val detail = fetchLineDetailOnce(city, dir)

            if (detail != null) {
                val fromStop = detail.stops.find {
                    it.name.contains(fromCore) || fromCore.contains(RouteProjectionMath.coreStopName(it.name))
                }
                val toStop = detail.stops.find {
                    it.name.contains(toCore) || toCore.contains(RouteProjectionMath.coreStopName(it.name))
                }
                if (fromStop != null && toStop != null && fromStop.order < toStop.order) {
                    return CandidateLine(
                        cityId = city.cityId,
                        cityName = city.cityName,
                        lineId = dir.lineId,
                        lineName = dir.name.ifBlank { lineName },
                        source = dir.source,
                        direction = dir.direction,
                        fromStopOrder = fromStop.order,
                        toStopOrder = toStop.order,
                        startStopName = fromStop.name,
                        endStopName = toStop.name,
                        mygolbsCityCode = city.mygolbsCityCode
                    )
                }

                val rev = detail.reverseDirection
                if (rev != null) {
                    val revDetail = fetchLineDetailOnce(city, rev)

                    if (revDetail != null) {
                        val revFromStop = revDetail.stops.find {
                            it.name.contains(fromCore) || fromCore.contains(RouteProjectionMath.coreStopName(it.name))
                        }
                        val revToStop = revDetail.stops.find {
                            it.name.contains(toCore) || toCore.contains(RouteProjectionMath.coreStopName(it.name))
                        }
                        if (revFromStop != null && revToStop != null && revFromStop.order < revToStop.order) {
                            return CandidateLine(
                                cityId = city.cityId,
                                cityName = city.cityName,
                                lineId = rev.lineId,
                                lineName = rev.name.ifBlank { lineName },
                                source = rev.source,
                                direction = rev.direction,
                                fromStopOrder = revFromStop.order,
                                toStopOrder = revToStop.order,
                                startStopName = revFromStop.name,
                                endStopName = revToStop.name,
                                mygolbsCityCode = city.mygolbsCityCode
                            )
                        }
                    }
                }
            }
        }

        // 匹配不到真实站序：返回 null，保持未对齐，绝不使用写死的假站序（原先的 1->5）
        return null
    }

    /** 线路详情统一只走 realtime（仓库层负责去重 / 缓存），失败不再换通道重复请求同一上游 */
    private suspend fun fetchLineDetailOnce(city: TransitCity, dir: BusLineDirection): BusLineDetail? =
        runCatching {
            realtime.getLineDetail(
                city = city,
                lineId = dir.lineId,
                source = dir.source,
                lineNameHint = dir.name,
                directionHint = dir.direction
            ).getOrNull()
        }.getOrNull()

    /** 候选线路所属城市；未填或为历史占位 ID 时回落到 [defaultCity] */
    fun resolveCandidateCity(candidate: CandidateLine, defaultCity: TransitCity): TransitCity =
        if (candidate.cityId.isNotBlank() && candidate.cityId != LEGACY_PLACEHOLDER_CITY_ID) {
            TransitCity(candidate.cityId, candidate.cityName)
        } else {
            defaultCity
        }

    /**
     * 将路线中各段候选线路与实时公交数据源进行站序和真实 lineId 对齐
     */
    suspend fun alignTransitGroupWithRealtimeSource(group: TransitGroup): TransitGroup = withContext(ioDispatcher) {
        val defaultCity = settings.selectedCity.value
        val newLegs = group.legs.map { leg ->
            val newCandidates = leg.candidateLines.map { candidate ->
                val isSynthetic = candidate.lineId.startsWith("line_") || candidate.fromStopOrder >= candidate.toStopOrder
                if (isSynthetic) {
                    val lineCity = resolveCandidateCity(candidate, defaultCity)
                    val matched = findRealtimeLineMatch(
                        city = lineCity,
                        lineName = candidate.lineName,
                        fromStopName = leg.fromStopName,
                        toStopName = leg.toStopName,
                        preferredDirection = candidate.direction
                    )
                    // 匹配不到则保持原候选（仍是合成/未对齐），由启动追踪处拦截并提示，不编造假站序
                    matched ?: candidate
                } else {
                    candidate
                }
            }
            leg.copy(candidateLines = newCandidates)
        }
        group.copy(legs = newLegs)
    }

    /**
     * 构建返程路线（自动反转行程段、调换起终点与候选线路上下行并对齐真实源数据）
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun buildReverseTransitGroup(
        forwardGroup: TransitGroup,
        targetGroupId: String? = "home"
    ): TransitGroup = withContext(ioDispatcher) {
        val defaultCity = settings.selectedCity.value
        val reversedOriginalLegs = forwardGroup.legs.reversed()
        val newLegs = reversedOriginalLegs.mapIndexed { reversedIdx, originalLeg ->
            val reversedFromStop = originalLeg.toStopName
            val reversedToStop = originalLeg.fromStopName
            val walkingMeters = if (reversedIdx == 0) 0 else {
                forwardGroup.legs[forwardGroup.legs.size - reversedIdx].walkingDistanceMeters
            }
            val reversedCandidates = originalLeg.candidateLines.map { candidate ->
                val flippedDir = if (candidate.direction == 0) 1 else 0
                val lineCity = resolveCandidateCity(candidate, defaultCity)
                val matched = findRealtimeLineMatch(
                    city = lineCity,
                    lineName = candidate.lineName,
                    fromStopName = reversedFromStop,
                    toStopName = reversedToStop,
                    preferredDirection = flippedDir
                )
                matched ?: candidate.copy(
                    cityId = lineCity.cityId,
                    cityName = lineCity.cityName,
                    direction = flippedDir,
                    startStopName = candidate.endStopName.ifEmpty { reversedFromStop },
                    endStopName = candidate.startStopName.ifEmpty { reversedToStop },
                    fromStopOrder = 1,
                    toStopOrder = maxOf(2, (candidate.toStopOrder - candidate.fromStopOrder) + 1)
                )
            }
            TransitLeg(
                legIndex = reversedIdx,
                fromStopName = reversedFromStop,
                fromStopId = originalLeg.toStopId,
                toStopName = reversedToStop,
                toStopId = originalLeg.fromStopId,
                fromStopLat = originalLeg.toStopLat,
                fromStopLng = originalLeg.toStopLng,
                toStopLat = originalLeg.fromStopLat,
                toStopLng = originalLeg.fromStopLng,
                candidateLines = reversedCandidates,
                walkingDistanceMeters = walkingMeters
            )
        }

        val newOrigin = newLegs.firstOrNull()?.fromStopName.orEmpty()
        val newDest = newLegs.lastOrNull()?.toStopName.orEmpty()
        val newName = when {
            forwardGroup.name.contains("➔") || forwardGroup.name.contains("->") -> {
                if (newOrigin.isNotBlank() && newDest.isNotBlank()) {
                    "$newOrigin ➔ $newDest"
                } else {
                    "${forwardGroup.name} (返程)"
                }
            }
            newOrigin.isNotBlank() && newDest.isNotBlank() -> "$newOrigin ➔ $newDest (${forwardGroup.name}·返程)"
            else -> "${forwardGroup.name} (返程)"
        }

        // 原来两处各取一次 System.currentTimeMillis()，这里取一次两处共用（同一毫秒内，结果一样）
        val now = wallClockMillis()
        TransitGroup(
            id = Uuid.random().toString(),
            name = newName,
            category = forwardGroup.category,
            customGroupId = targetGroupId,
            legs = newLegs,
            isTemporary = false,
            sortOrder = forwardGroup.sortOrder + 1,
            createdAt = now,
            updatedAt = now
        )
    }

    /** 合成候选线路的对齐（带内存缓存：命中 24 小时，未命中 10 分钟内不重查） */
    suspend fun alignSyntheticCandidateCached(leg: TransitLeg, candidate: CandidateLine): CandidateLine? {
        val city = resolveCandidateCity(candidate, settings.selectedCity.value)
        val cacheKey = listOf(
            city.cityId, city.cityName, candidate.lineName, leg.fromStopName, leg.toStopName, candidate.direction
        ).joinToString("|")
        val now = wallClockMillis()
        synchronized(cacheLock) { syntheticAlignmentCache[cacheKey] }?.let { entry ->
            val ttl = if (entry.match != null) ALIGN_HIT_TTL_MS else ALIGN_MISS_TTL_MS
            if (now - entry.savedAtMs in 0 until ttl) return entry.match
        }
        val matched = findRealtimeLineMatch(
            city = city,
            lineName = candidate.lineName,
            fromStopName = leg.fromStopName,
            toStopName = leg.toStopName,
            preferredDirection = candidate.direction
        )
        currentCoroutineContext().ensureActive()
        synchronized(cacheLock) { syntheticAlignmentCache[cacheKey] = AlignmentCacheEntry(matched, now) }
        return matched
    }
}
