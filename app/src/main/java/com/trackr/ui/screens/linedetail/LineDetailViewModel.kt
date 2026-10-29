package com.trackr.ui.screens.linedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.TrackrApp
import com.trackr.data.repository.ITransitRepository
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.BusStop
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.FavoriteStationLine
import com.trackr.domain.model.LineTimetable
import com.trackr.domain.model.LiveBus
import com.trackr.domain.model.TransitCity
import com.trackr.ui.components.RouteRailLayoutMode
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
 * 详情页核心三态卡片面板枚举
 */
enum class PanelMode {
    STATION_CARD, // 站点到站卡
    BUS_LIST,     // 到本站的全部车辆列表
    BUS_FOLLOW    // 单车跟随行程流
}

/**
 * 线路详情页 UI 状态
 */
data class LineDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val lineDetail: BusLineDetail? = null,
    val selectedStop: BusStop? = null,
    val panelMode: PanelMode = PanelMode.STATION_CARD,
    val followedBusId: String? = null,
    val timetable: LineTimetable? = null,
    val isTimetableSheetOpen: Boolean = false,
    val refreshCountdownSeconds: Int = 10,
    val errorMessage: String? = null,
    val isFavorite: Boolean = false,
    val alertThresholdStops: Int = 2,
    val layoutMode: RouteRailLayoutMode = RouteRailLayoutMode.Horizontal,
    val flipRotationDegrees: Float = 0f
)

/**
 * 线路详情 ViewModel：
 * 负责全线站点与在途车辆拉取、每 10 秒自动轮询、精准站点 ETA 计算、单车跟随与反向同名站智能切换
 */
class LineDetailViewModel(
    val cityId: String,
    initialLineId: String,
    private val initialTargetOrder: Int? = null,
    private val initialStationId: String? = null,
    private val initialSource: DataSourceType = if (initialLineId.startsWith("mg:")) DataSourceType.MYGOLBS else DataSourceType.CHELAILE,
    private val lineNameHint: String = "",
    private val repository: ITransitRepository = TrackrApp.instance.repository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LineDetailUiState())
    val uiState: StateFlow<LineDetailUiState> = _uiState.asStateFlow()

    private var currentLineId: String = initialLineId
    private var currentSource: DataSourceType = initialSource
    private var pollingJob: Job? = null
    private var countdownJob: Job? = null

    init {
        // 监听收藏夹变动
        viewModelScope.launch {
            repository.favorites.collectLatest { favList ->
                checkFavoriteState(favList)
            }
        }

        // 加载初始线路数据并启动轮询
        loadLineDetail(isInitial = true)
        startAutoPolling()
    }

    /**
     * 加载当前线路详情与实时车辆
     */
    fun loadLineDetail(isInitial: Boolean = false, targetOrderOverride: Int? = null) {
        viewModelScope.launch {
            if (isInitial) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            } else {
                _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            }

            val currentCity = repository.selectedCity.value
            val targetOrder = targetOrderOverride
                ?: _uiState.value.selectedStop?.order
                ?: initialTargetOrder

            val result = repository.getLineDetail(
                city = currentCity,
                lineId = currentLineId,
                targetOrder = targetOrder,
                stationId = initialStationId,
                source = currentSource,
                lineNameHint = lineNameHint
            )

            result.onSuccess { detail ->
                // 初次加载或无选中站时，计算默认选中的候车站（首车前面一站）
                val targetStop = if (_uiState.value.selectedStop == null || isInitial) {
                    if (initialTargetOrder != null && initialTargetOrder > 0) {
                        detail.stops.find { it.order == initialTargetOrder } ?: calculateDefaultStop(detail)
                    } else {
                        calculateDefaultStop(detail)
                    }
                } else {
                    // 保留当前选中的站点（若线路发生变动则按站序寻找）
                    detail.stops.find { it.order == _uiState.value.selectedStop?.order }
                        ?: calculateDefaultStop(detail)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        lineDetail = detail,
                        selectedStop = targetStop,
                        errorMessage = null
                    )
                }

                checkFavoriteState(repository.favorites.value)

                // 若之前初次加载未指定 targetOrder，现在已确立选中站，则补充一次精确到该站的 ETA 计算
                if (isInitial && targetStop != null && targetOrder == null) {
                    selectStop(targetStop)
                }
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = "获取实时公交数据失败：${ex.localizedMessage ?: "网络异常"}"
                    )
                }
            }
        }
    }

    /**
     * 计算默认选中的候车站：
     * 取「首辆车前面一站」（若车辆已在第 N 站，则看第 N+1 站）；无在途车退回首站。
     * 对齐 yulin-bus-activity 核心逻辑
     */
    private fun calculateDefaultStop(detail: BusLineDetail): BusStop? {
        val stops = detail.stops
        if (stops.isEmpty()) return null
        val buses = detail.buses
        if (buses.isEmpty()) return stops.first()

        val leadOrder = buses.maxOfOrNull { it.order } ?: return stops.first()
        return stops.find { it.order == leadOrder + 1 }
            ?: stops.find { it.order == leadOrder }
            ?: stops.first()
    }

    /**
     * 启动 10 秒自动轮询与倒计时指示
     */
    private fun startAutoPolling() {
        countdownJob?.cancel()
        pollingJob?.cancel()

        countdownJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                val current = _uiState.value.refreshCountdownSeconds
                if (current <= 1) {
                    _uiState.update { it.copy(refreshCountdownSeconds = 10) }
                    // 触发刷新
                    loadLineDetail(isInitial = false)
                } else {
                    _uiState.update { it.copy(refreshCountdownSeconds = current - 1) }
                }
            }
        }
    }

    /**
     * 手动立即刷新
     */
    fun refreshNow() {
        _uiState.update { it.copy(refreshCountdownSeconds = 10) }
        loadLineDetail(isInitial = false)
    }

    /**
     * 选中站点：以该站为 targetOrder 计算精确到站 ETA
     */
    fun selectStop(stop: BusStop) {
        _uiState.update {
            it.copy(
                selectedStop = stop,
                panelMode = PanelMode.STATION_CARD,
                followedBusId = null
            )
        }
        checkFavoriteState(repository.favorites.value)
        loadLineDetail(isInitial = false, targetOrderOverride = stop.order)
    }

    /**
     * 选中并跟随某辆公交车
     */
    fun selectBusToFollow(bus: LiveBus) {
        _uiState.update {
            it.copy(
                followedBusId = bus.busId,
                panelMode = PanelMode.BUS_FOLLOW
            )
        }
    }

    /**
     * 展开全线车辆列表
     */
    fun openBusList() {
        _uiState.update {
            it.copy(
                panelMode = PanelMode.BUS_LIST,
                followedBusId = null
            )
        }
    }

    /**
     * 从车辆列表或单车跟随返回站点到站卡
     */
    fun backToStationCard() {
        _uiState.update {
            it.copy(
                panelMode = PanelMode.STATION_CARD,
                followedBusId = null
            )
        }
    }

    /**
     * 智能切换反向同名站：
     * 利用 RouteProjectionMath.findOppositeStop 找到反向线路上对应的同名候车站，
     * 并平滑旋转按钮角度 180°。
     */
    fun switchOppositeDirection() {
        val detail = _uiState.value.lineDetail ?: return
        val reverse = detail.reverseDirection ?: return
        val currentStop = _uiState.value.selectedStop ?: return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    flipRotationDegrees = it.flipRotationDegrees + 180f
                )
            }

            // 更新当前线路标识
            currentLineId = reverse.lineId
            currentSource = reverse.source

            val currentCity = repository.selectedCity.value
            val reverseResult = repository.getLineDetail(
                city = currentCity,
                lineId = reverse.lineId,
                source = reverse.source,
                lineNameHint = reverse.name
            )

            reverseResult.onSuccess { revDetail ->
                // 在反向线路站表中查找同名候车站
                val oppositeStop = RouteProjectionMath.findOppositeStop(
                    currentStop = currentStop,
                    reverseStops = revDetail.stops
                ) ?: calculateDefaultStop(revDetail)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lineDetail = revDetail,
                        selectedStop = oppositeStop,
                        panelMode = PanelMode.STATION_CARD,
                        followedBusId = null
                    )
                }

                checkFavoriteState(repository.favorites.value)

                // 若找到了对应站，立即拉取到该站的精确 ETA
                if (oppositeStop != null) {
                    loadLineDetail(isInitial = false, targetOrderOverride = oppositeStop.order)
                }
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "切换反向线路失败：${ex.localizedMessage ?: "网络错误"}"
                    )
                }
            }
        }
    }

    /**
     * 收藏或取消收藏当前「线路 + 候车站」组合
     */
    fun toggleFavoriteStop() {
        val detail = _uiState.value.lineDetail ?: return
        val stop = _uiState.value.selectedStop ?: return
        val line = detail.line
        val city = repository.selectedCity.value

        val favItem = FavoriteStationLine(
            id = "${city.cityId}_${line.lineId}_${stop.order}",
            cityId = city.cityId,
            cityName = city.cityName,
            lineId = line.lineId,
            lineName = line.name,
            lineNo = line.lineNo,
            direction = line.direction,
            startStop = line.startStop,
            endStop = line.endStop,
            targetStopId = stop.stopId,
            targetStopName = stop.name,
            targetOrder = stop.order,
            totalStops = detail.stops.size,
            wgsLat = stop.wgsLat,
            wgsLng = stop.wgsLng,
            source = line.source,
            mygolbsCityCode = line.mygolbsCityCode
        )

        viewModelScope.launch {
            val isNowFav = repository.toggleFavorite(favItem)
            _uiState.update { it.copy(isFavorite = isNowFav) }
        }
    }

    /**
     * 检查当前选中站点是否已收藏
     */
    private fun checkFavoriteState(favList: List<FavoriteStationLine>) {
        val detail = _uiState.value.lineDetail ?: return
        val stop = _uiState.value.selectedStop ?: return
        val city = repository.selectedCity.value
        val favId = "${city.cityId}_${detail.line.lineId}_${stop.order}"
        val isFav = favList.any { it.id == favId }
        _uiState.update { it.copy(isFavorite = isFav) }
    }

    /**
     * 加载全天发车时刻表
     */
    fun loadTimetable() {
        val detail = _uiState.value.lineDetail ?: return
        viewModelScope.launch {
            val city = repository.selectedCity.value
            val res = repository.getTimetable(city, detail.line)
            res.onSuccess { tb ->
                _uiState.update {
                    it.copy(timetable = tb, isTimetableSheetOpen = true)
                }
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(errorMessage = "获取发车时刻表失败：${ex.localizedMessage ?: "无班表"}")
                }
            }
        }
    }

    /**
     * 关闭时刻表底部弹窗
     */
    fun dismissTimetable() {
        _uiState.update { it.copy(isTimetableSheetOpen = false) }
    }

    /**
     * 设置震动到站预警提前站数阈值（1 / 2 / 3 站）
     */
    fun setAlertThreshold(threshold: Int) {
        _uiState.update { it.copy(alertThresholdStops = threshold.coerceIn(1, 3)) }
    }

    /**
     * 切换横向走向条 vs 纵向走向条布局
     */
    fun toggleLayoutMode() {
        _uiState.update {
            it.copy(
                layoutMode = if (it.layoutMode == RouteRailLayoutMode.Horizontal) {
                    RouteRailLayoutMode.Vertical
                } else {
                    RouteRailLayoutMode.Horizontal
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        pollingJob?.cancel()
    }
}
