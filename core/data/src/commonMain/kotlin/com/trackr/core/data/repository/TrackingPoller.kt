package com.trackr.core.data.repository

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.*
import kotlinx.coroutines.*
import kotlin.time.Clock

/** One generation of one session. Platforms own jobs, lifecycle, location and presentation. */
class TrackingPoller(
    private val tracking: TrackingRepository,
    private val realtime: RealtimeRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val monotonicClock: MonotonicClock,
    private val locationEnabled: () -> Boolean,
    private val serviceFix: () -> UserFix?,
    private val onEffect: (TrackingEngine.Effect, String) -> Unit,
    private val onExpired: (TrackingSession) -> Unit = {},
    private val requestDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val watchPoller = WatchTogetherPoller(realtime)

    /** Cancellation and generation checks also reject a provider that returns after cancellation. */
    suspend fun run(sessionId: String, generation: Int, onUpdate: () -> Unit = {}) {
        var failures = 0
        var operating = LineOperatingState.RUNNING
        fun current() = tracking.sessionById(sessionId)?.takeIf {
            it.generation == generation && !it.interrupted && it.state.isActive
        }
        while (currentCoroutineContext().isActive) {
            val session = current() ?: return
            if (TrackingEngine.isTrackingExpired(session, clock.now().toEpochMilliseconds())) {
                onExpired(session)
                tracking.stop(sessionId)
                return
            }
            val target = session.watchTarget
            val plan = TrackingEngine.watchPollPlan(session)
            val result: Result<(TrackingSession) -> TrackingEngine.Result> = try {
                if (target != null && plan != null) {
                    withContext(requestDispatcher) { watchPoller.poll(target, plan, cityFor(target.cityId, target.mygolbsCityCode, target.cityName)) }.map { poll ->
                        currentCoroutineContext().ensureActive()
                        if (current() != null) tracking.recordLineBuses(sessionId, generation, poll.lines.mapValues { it.value.buses })
                        operating = LineOperatingState.RUNNING
                        val reduce: (TrackingSession) -> TrackingEngine.Result = { latest ->
                            TrackingEngine.reduceWatchPoll(latest, poll, locationEnabled(), serviceFix(),
                                clock.now().toEpochMilliseconds(), monotonicClock.elapsedMillis(), settings.autoStopAfterBoarding.value, tracking.latestUserFix)
                        }
                        reduce
                    }
                } else {
                    val leg = session.leg
                    val state = session.state
                    withContext(requestDispatcher) { realtime.getLineDetail(
                        city = cityFor(leg.cityId, leg.mygolbsCityCode), lineId = leg.lineId,
                        targetOrder = when {
                            state.legPhase == LegPhase.WAITING_BOARD && state.boardStopOrder > 0 -> state.boardStopOrder
                            state.targetOrder > 0 -> state.targetOrder
                            else -> leg.targetOrder
                        },
                        stationId = state.targetStopId.ifBlank { leg.targetStopId }, source = leg.source,
                        lineNameHint = state.lineName, directionHint = leg.direction
                    ) }.map { detail ->
                        currentCoroutineContext().ensureActive()
                        if (current() != null) tracking.recordBuses(sessionId, generation, detail.buses)
                        operating = detail.operatingState
                        val reduce: (TrackingSession) -> TrackingEngine.Result = { latest ->
                            val reduced = TrackingEngine.reducePoll(latest, detail, locationEnabled(), serviceFix(), tracking.latestUserFix,
                                clock.now().toEpochMilliseconds(), monotonicClock.elapsedMillis(), settings.autoStopAfterBoarding.value)
                            reduced.copy(session = reduced.session.withTargetStopCoords(detail))
                        }
                        reduce
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            currentCoroutineContext().ensureActive()
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (current() == null) return
            var effects: List<TrackingEngine.Effect> = emptyList()
            val applied = tracking.update(sessionId, generation) { latest ->
                result.fold(onSuccess = { reduce ->
                    failures = 0
                    val reduced = reduce(latest)
                    effects = reduced.effects
                    if (effects.any { it is TrackingEngine.Effect.Finished }) null else reduced.session.copy(lastRealtimeAtMillis = clock.now().toEpochMilliseconds(), refreshFailed = false)
                }, onFailure = {
                    failures++
                    TrackingEngine.reduceFailure(latest, clock.now().toEpochMilliseconds()).copy(refreshFailed = true)
                })
            }
            if (!applied) return
            if (effects.any { it is TrackingEngine.Effect.Finished.Boarded } && settings.autoStopAfterBoarding.value) {
                tracking.sessions.value
                    .filter { sameStopWaiting(session, it) }
                    .forEach { tracking.stop(it.sessionId) }
            }
            effects.forEach { onEffect(it, sessionId) }
            onUpdate()
            val next = current() ?: return
            delay(TrackingEngine.pollingIntervalMillis(next.state, failures, operating))
        }
    }

    /** 同站同时追多条候车线时，确认上了其中一趟并离开该站后，同站其余候车单线路/一起等一并结束 */
    private fun sameStopWaiting(finished: TrackingSession, other: TrackingSession): Boolean {
        if (other.sessionId == finished.sessionId || other.interrupted || !other.state.isActive) return false
        val isWaitingAtStop = other.state.legPhase == LegPhase.SINGLE ||
            (other.watchTarget != null && other.state.legPhase == LegPhase.WAITING_BOARD)
        if (!isWaitingAtStop) return false
        val finishedCity = finished.state.cityId.ifBlank { finished.leg.cityId }
        val otherCity = other.state.cityId.ifBlank { other.leg.cityId }
        if (finishedCity.isNotBlank() && otherCity.isNotBlank() && finishedCity != otherCity) return false
        val finishedStopId = finished.state.targetStopId.ifBlank { finished.leg.targetStopId }
        val otherStopId = other.state.targetStopId.ifBlank { other.leg.targetStopId }
        val finishedStopName = finished.state.boardStopName.ifBlank { finished.state.targetStopName }
        val otherStopName = other.state.boardStopName.ifBlank { other.state.targetStopName }
        // 掌上公交线路详情的站 id 就是站序，不同站也可能相同：站名不同一定不是同一站
        if (finishedStopName.isNotBlank() && otherStopName.isNotBlank() && finishedStopName != otherStopName) return false
        if (finishedStopId.isNotBlank() && otherStopId.isNotBlank() && finishedStopId == otherStopId) return true
        // 同名不同 id：可能是同一站（两个接口给的 id 不一样），也可能是对向站，看坐标；不知道坐标就不结束
        val (finishedLat, finishedLng) = finished.waitingStopCoords()
        val (otherLat, otherLng) = other.waitingStopCoords()
        val distance = RouteProjectionMath.calculateDistanceMeters(finishedLat, finishedLng, otherLat, otherLng)
        return distance != null && distance <= SAME_STOP_MAX_METERS
    }

    private fun TrackingSession.waitingStopCoords(): Pair<Double, Double> {
        val watch = watchTarget
        return if (watch != null && watch.stopLat > 0.0 && watch.stopLng > 0.0) watch.stopLat to watch.stopLng
        else state.targetStopLat to state.targetStopLng
    }

    private fun TrackingSession.withTargetStopCoords(detail: BusLineDetail): TrackingSession {
        if (state.legPhase != LegPhase.SINGLE) return this
        val stop = detail.stops.firstOrNull { it.order == state.targetOrder } ?: return this
        if (stop.wgsLat == state.targetStopLat && stop.wgsLng == state.targetStopLng) return this
        return copy(state = state.copy(targetStopLat = stop.wgsLat, targetStopLng = stop.wgsLng))
    }

    private fun cityFor(id: String, code: String, name: String = ""): TransitCity =
        settings.selectedCity.value.takeIf { it.cityId == id }
            ?: TransitCity(cityId = id, cityName = name, mygolbsCityCode = code.takeIf { it.isNotEmpty() })

    private companion object {
        /** 同名站相距不超过这么远才算同一站；对向站一般隔着马路，比这远 */
        const val SAME_STOP_MAX_METERS = 30
    }
}
