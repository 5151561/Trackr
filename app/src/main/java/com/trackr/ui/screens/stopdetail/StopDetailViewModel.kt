package com.trackr.ui.screens.stopdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.TrackrApp
import com.trackr.data.repository.ITransitRepository
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.NearbyStopLineStatus
import com.trackr.domain.model.TransitCity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private val repository: ITransitRepository = TrackrApp.instance.repository,
    private val enableAutoPolling: Boolean = true
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        StopDetailUiState(stopName = initialStopName.ifBlank { "站点详情" })
    )
    val uiState: StateFlow<StopDetailUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var countdownJob: Job? = null

    init {
        // 监听收藏夹变动
        viewModelScope.launch {
            repository.favorites.collectLatest { list ->
                val ids = list.map { it.id }.toSet()
                _uiState.update { it.copy(favoriteIds = ids) }
            }
        }

        loadStopDetail(isInitial = true)
        if (enableAutoPolling) {
            startAutoPolling()
        }
    }

    /**
     * 加载当前站点详情与实时车辆
     */
    fun loadStopDetail(isInitial: Boolean = false) {
        viewModelScope.launch {
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
        }
    }

    fun selectStopIndex(index: Int) {
        if (index in _uiState.value.stops.indices) {
            _uiState.update { it.copy(selectedStopIndex = index) }
            val targetStop = _uiState.value.stops[index]
            if (targetStop.lines.isEmpty() || targetStop.lines.all { it.line.lineId.isBlank() }) {
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

    private fun startAutoPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000L)
                loadStopDetail(isInitial = false)
            }
        }

        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (isActive) {
                for (sec in 10 downTo 1) {
                    _uiState.update { it.copy(refreshCountdownSeconds = sec) }
                    delay(1000L)
                }
                _uiState.update { it.copy(refreshCountdownSeconds = 10) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        countdownJob?.cancel()
    }
}
