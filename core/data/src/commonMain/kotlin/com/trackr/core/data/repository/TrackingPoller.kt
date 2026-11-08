package com.trackr.core.data.repository

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
                                clock.now().toEpochMilliseconds(), monotonicClock.elapsedMillis(), settings.autoStopAfterBoarding.value)
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
                            TrackingEngine.reducePoll(latest, detail, locationEnabled(), serviceFix(), tracking.latestUserFix,
                                clock.now().toEpochMilliseconds(), monotonicClock.elapsedMillis(), settings.autoStopAfterBoarding.value)
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
            effects.forEach { onEffect(it, sessionId) }
            onUpdate()
            val next = current() ?: return
            delay(TrackingEngine.pollingIntervalMillis(next.state, failures, operating))
        }
    }

    private fun cityFor(id: String, code: String, name: String = ""): TransitCity =
        settings.selectedCity.value.takeIf { it.cityId == id }
            ?: TransitCity(cityId = id, cityName = name, mygolbsCityCode = code.takeIf { it.isNotEmpty() })
}
