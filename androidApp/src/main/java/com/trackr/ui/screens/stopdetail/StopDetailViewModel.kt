package com.trackr.ui.screens.stopdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.data.repository.ITransitRepository
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.NearbyStopLineStatus
import com.trackr.domain.model.TransitCity
import com.trackr.ui.util.CountdownPollingStrategy
import com.trackr.ui.util.PollingCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 站点详情页 UI 状态
 */
data class StopDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val stopName: String = "",
    val stops: List<NearbyStop> = emptyList(),
    val selectedStopIndex: Int = 0,
    val refreshCountdownSeconds: Int = 10,
    val errorMessage: String? = null,
    val favoriteIds: Set<String> = emptySet()
)

/**
 * 站点详情 ViewModel：
 * 负责拉取站点详情（各物理站台全部途经线路与实时到站）、10秒自动轮询、收藏切换与站台切换
 */
class StopDetailViewModel(
    val cityId: String,
    private val stopId: String,
    private val initialStopName: String,
    private val physicalStId: String = "",
    private val namesakeStId: String = "",
    private val stationLat: Double = 0.0,
    private val stationLng: Double = 0.0,
    private val repository: ITransitRepository,
    private val enableAutoPolling: Boolean = true,
    /** 单调时钟（毫秒），用于判断恢复页面时数据是否过期；测试可注入 */
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        StopDetailUiState(stopName = initialStopName.ifBlank { "站点详情" })
    )
    val uiState: StateFlow<StopDetailUiState> = _uiState.asStateFlow()

    /** 进行中的站点请求数（含手动刷新、切换站台、轮询） */
    private val activeLoads = AtomicInteger(0)

    /** 最近一次发起刷新的时刻（[clockMillis]），null 表示尚未请求过 */
    @Volatile
    private var lastRefreshAtMillis: Long? = null

    internal val pollingCoordinator = PollingCoordinator(
        scope = viewModelScope,
        strategy = CountdownPollingStrategy(
            intervalSeconds = POLL_INTERVAL_SECONDS,
            onTick = { remaining ->
                _uiState.update { it.copy(refreshCountdownSeconds = remaining) }
            }
        ),
        initialEnabled = enableAutoPolling
    ) {
        // 直接在轮询协程内挂起执行：暂停轮询时请求随之取消；上一轮未完成就跳过
        if (activeLoads.get() == 0) {
            fetchStopDetail(isInitial = false)
        }
    }

    /** 当前是否有站点请求在进行（测试用） */
    internal val isLoadInFlight: Boolean
        get() = activeLoads.get() > 0

    val isPollingActive: Boolean
        get() = pollingCoordinator.isPollingActive

    init {
        // 监听收藏夹变动
        viewModelScope.launch {
            repository.favorites.collectLatest { list ->
                val ids = list.map { it.id }.toSet()
                _uiState.update { it.copy(favoriteIds = ids) }
            }
        }

        loadStopDetail(isInitial = true)
    }

    /**
     * 加载当前站点详情与实时车辆
     */
    fun loadStopDetail(isInitial: Boolean = false) {
        viewModelScope.launch {
            fetchStopDetail(isInitial)
        }
    }

    /**
     * 手动立即刷新，并重置倒计时
     */
    fun refreshNow() {
        pollingCoordinator.restartCountdown(POLL_INTERVAL_SECONDS)
        loadStopDetail(isInitial = false)
    }

    /**
     * 挂起执行一次站点详情请求并更新 UI 状态
     */
    private suspend fun fetchStopDetail(isInitial: Boolean) {
        activeLoads.incrementAndGet()
        lastRefreshAtMillis = clockMillis()
        try {
            if (isInitial) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            } else {
                _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            }

            val currentCity = repository.selectedCity.value
            val city = if (currentCity.cityId == cityId) {
                currentCity
            } else {
                TransitCity(cityId = cityId, cityName = "")
            }

            val userLoc = repository.userLocation.value
            val currentSelected = _uiState.value.stops.getOrNull(_uiState.value.selectedStopIndex)
            val queryPhysicalStId = currentSelected?.physicalStId?.takeIf { it.isNotBlank() } ?: physicalStId.takeIf { it.isNotBlank() }
            val queryNamesakeStId = currentSelected?.namesakeStId?.takeIf { it.isNotBlank() } ?: namesakeStId.takeIf { it.isNotBlank() }
            val queryStopId = currentSelected?.stopId?.takeIf { it.isNotBlank() } ?: stopId.takeIf { it.isNotBlank() }

            val result = repository.getStopDetail(
                city = city,
                stopId = queryStopId,
                stopName = initialStopName.takeIf { it.isNotBlank() },
                physicalStId = queryPhysicalStId,
                namesakeStId = queryNamesakeStId,
                stationLat = stationLat.takeIf { it > 0.0 },
                stationLng = stationLng.takeIf { it > 0.0 },
                userLat = userLoc?.first,
                userLng = userLoc?.second
            )

            result.onSuccess { rawStopList ->
                // 若站点缺少 distanceMeters，且用户位置已知，计算实际测地距离
                val stopList = if (userLoc != null) {
                    rawStopList.map { s ->
                        if (s.distanceMeters == null || s.distanceMeters <= 0) {
                            val dist = RouteProjectionMath.calculateDistanceMeters(
                                userLoc.first, userLoc.second, s.wgsLat, s.wgsLng
                            )
                            s.copy(distanceMeters = dist)
                        } else s
                    }
                } else rawStopList

                val resolvedName = stopList.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                    ?: initialStopName.ifBlank { "站点详情" }
                val currentPhyId = currentSelected?.physicalStId
                val newIndex = if (currentPhyId != null && stopList.isNotEmpty()) {
                    val idx = stopList.indexOfFirst { it.physicalStId == currentPhyId }
                    if (idx >= 0) idx else _uiState.value.selectedStopIndex.coerceIn(0, stopList.size - 1)
                } else if (stopList.isNotEmpty()) {
                    _uiState.value.selectedStopIndex.coerceIn(0, stopList.size - 1)
                } else 0

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        stopName = resolvedName,
                        stops = stopList,
                        selectedStopIndex = newIndex,
                        errorMessage = if (stopList.isEmpty()) "暂未查询到该站点的途经线路" else null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = err.message ?: "获取站点信息失败，请重试"
                    )
                }
            }
        } catch (ce: CancellationException) {
            // 轮询暂停等取消场景：复位加载指示，不显示错误
            _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            throw ce
        } finally {
            activeLoads.decrementAndGet()
        }
    }

    fun selectStopIndex(index: Int) {
        if (index in _uiState.value.stops.indices) {
            val changed = index != _uiState.value.selectedStopIndex
            _uiState.update { it.copy(selectedStopIndex = index) }
            val targetStop = _uiState.value.stops[index]
            val lacksLines = targetStop.lines.isEmpty() || targetStop.lines.all { it.line.lineId.isBlank() }
            // 其他物理站台的数据来自缓存补全，切换后以该站台为主查询立即刷新一次，并重置倒计时
            if (changed || lacksLines) {
                pollingCoordinator.restartCountdown(POLL_INTERVAL_SECONDS)
                loadStopDetail(isInitial = false)
            }
        }
    }

    fun toggleFavorite(stop: NearbyStop, lineStatus: NearbyStopLineStatus) {
        viewModelScope.launch {
            val city = repository.selectedCity.value
            val cityName = if (city.cityId == cityId) city.cityName else ""
            val favItem = FavoriteStationLine(
                id = "${cityId}_${lineStatus.line.lineId}_${lineStatus.targetOrder}",
                cityId = cityId,
                cityName = cityName,
                lineId = lineStatus.line.lineId,
                lineName = lineStatus.line.name,
                lineNo = lineStatus.line.lineNo,
                direction = lineStatus.line.direction,
                startStop = lineStatus.line.startStop,
                endStop = lineStatus.line.endStop,
                targetStopId = lineStatus.targetStopId.ifBlank { stop.stopId },
                targetStopName = lineStatus.targetStopName.ifBlank { stop.name },
                targetOrder = lineStatus.targetOrder,
                source = lineStatus.line.source
            )
            repository.toggleFavorite(favItem)
        }
    }

    /**
     * 设置轮询是否激活（生命周期感知或显式开关）
     */
    fun setPollingActive(active: Boolean) {
        pollingCoordinator.setActive(active)
    }

    /**
     * 页面回到前台（Lifecycle RESUMED）：恢复轮询；若距上次刷新已超过一个周期，立即刷新一次。
     * 测试或预览中 enableAutoPolling = false 时不做任何事。
     */
    fun onScreenResumed() {
        if (!enableAutoPolling) return
        pollingCoordinator.setActive(true)
        val last = lastRefreshAtMillis ?: return
        if (activeLoads.get() == 0 && clockMillis() - last >= POLL_INTERVAL_SECONDS * 1000L) {
            pollingCoordinator.restartCountdown(POLL_INTERVAL_SECONDS)
            loadStopDetail(isInitial = false)
        }
    }

    /**
     * 页面离开前台（Lifecycle PAUSED / 移出组合）：停止轮询，进行中的轮询请求随之取消。
     */
    fun onScreenPaused() {
        pollingCoordinator.setActive(false)
    }

    /**
     * 暂停后台轮询，安全取消当前轮询 Job
     */
    fun pausePolling() {
        pollingCoordinator.pause()
    }

    /**
     * 恢复/启动 10 秒自动轮询与倒计时指示（幂等控制）
     */
    fun resumePolling() {
        pollingCoordinator.resume()
    }

    override fun onCleared() {
        super.onCleared()
        pausePolling()
    }

    companion object {
        /** 自动轮询周期（秒） */
        const val POLL_INTERVAL_SECONDS = 10
    }
}
