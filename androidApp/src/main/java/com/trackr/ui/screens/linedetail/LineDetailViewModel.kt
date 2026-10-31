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
import com.trackr.domain.model.TransitCodeProvider
import com.trackr.ui.components.RouteRailLayoutMode
import com.trackr.ui.util.CountdownPollingStrategy
import com.trackr.ui.util.PollingCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

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
    val flipRotationDegrees: Float = 0f,
    val preferredTransitCodeProvider: TransitCodeProvider = TransitCodeProvider.ASK_ALWAYS,
    val isTransitCodeSheetOpen: Boolean = false,
    /** 实时接口未给下一班时，从时刻表补齐的下一班始发时间（HH:mm） */
    val fallbackNextDeparture: String? = null
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
    private val repository: ITransitRepository = TrackrApp.instance.repository,
    private val enableAutoPolling: Boolean = true,
    private val nowHHmm: () -> String = {
        ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("HH:mm"))
    },
    /** 单调时钟（毫秒），用于判断恢复页面时数据是否过期；测试可注入 */
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) : ViewModel() {

    private val _uiState = MutableStateFlow(LineDetailUiState())
    val uiState: StateFlow<LineDetailUiState> = _uiState.asStateFlow()

    private var currentLineId: String = initialLineId
    private var currentSource: DataSourceType = initialSource

    /** 进行中的线路请求数（含手动刷新、选站、轮询） */
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
            fetchLineDetail(isInitial = false)
        }
    }

    /** 当前是否有线路请求在进行（测试用） */
    internal val isLoadInFlight: Boolean
        get() = activeLoads.get() > 0

    val isPollingActive: Boolean
        get() = pollingCoordinator.isPollingActive

    init {
        // 监听收藏夹变动
        viewModelScope.launch {
            repository.favorites.collectLatest { favList ->
                checkFavoriteState(favList)
            }
        }

        // 监听乘车码偏好
        viewModelScope.launch {
            repository.preferredTransitCodeProvider.collectLatest { provider ->
                _uiState.update { it.copy(preferredTransitCodeProvider = provider) }
            }
        }

        // 加载初始线路数据
        loadLineDetail(isInitial = true)
    }

    /**
     * 加载当前线路详情与实时车辆（在 viewModelScope 中发起）
     */
    fun loadLineDetail(isInitial: Boolean = false, targetOrderOverride: Int? = null) {
        viewModelScope.launch {
            fetchLineDetail(isInitial = isInitial, targetOrderOverride = targetOrderOverride)
        }
    }

    /**
     * 挂起执行一次线路详情请求并更新 UI 状态
     */
    private suspend fun fetchLineDetail(isInitial: Boolean = false, targetOrderOverride: Int? = null) {
        activeLoads.incrementAndGet()
        lastRefreshAtMillis = clockMillis()
        try {
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

                refreshFallbackNextDeparture(detail, targetStop)

                // 初次加载未指定候车站时，需要再按默认候车站请求一次才能拿到到该站的精确 ETA；
                // 在同一协程内顺序执行并重置倒计时，避免轮询紧接着再发一次
                if (isInitial && targetStop != null && targetOrder == null) {
                    pollingCoordinator.restartCountdown(POLL_INTERVAL_SECONDS)
                    fetchLineDetail(isInitial = false, targetOrderOverride = targetStop.order)
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
        } catch (ce: CancellationException) {
            // 轮询暂停等取消场景：复位加载指示，不显示错误
            _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            throw ce
        } finally {
            activeLoads.decrementAndGet()
        }
    }

    /** 每条线路方向的时刻表缓存，避免 10 秒轮询时反复拉取 */
    private val timetableCache = mutableMapOf<String, LineTimetable>()

    /**
     * 候车站前方无车且实时接口未给下一班时间时，用时刻表补出下一班始发时间。
     */
    private suspend fun refreshFallbackNextDeparture(detail: BusLineDetail, stop: BusStop?) {
        val order = stop?.order ?: run {
            _uiState.update { it.copy(fallbackNextDeparture = null) }
            return
        }
        val hasUpcomingBus = detail.buses.any { it.order <= order }
        if (hasUpcomingBus || !detail.nextDepartureFromStart.isNullOrBlank()) {
            _uiState.update { it.copy(fallbackNextDeparture = null) }
            return
        }

        val lineId = detail.line.lineId
        val timetable = timetableCache[lineId]
            ?: repository.getTimetable(repository.selectedCity.value, detail.line)
                .getOrNull()
                ?.also { timetableCache[lineId] = it }

        val now = nowHHmm()
        val next = timetable?.let { tb ->
            tb.todayDepartures.firstOrNull { it >= now }
                ?: tb.yesterdayDepartures.firstOrNull { it >= now }
                ?: tb.nextDeparture?.takeIf { it >= now }
        }
        // 防止切线后旧请求覆盖新线路
        if (_uiState.value.lineDetail?.line?.lineId == lineId) {
            _uiState.update { it.copy(fallbackNextDeparture = next) }
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
            loadLineDetail(isInitial = false)
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

    /**
     * 手动立即刷新
     */
    fun refreshNow() {
        pollingCoordinator.restartCountdown(POLL_INTERVAL_SECONDS)
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
        // 选站即刷新，同时重置倒计时，避免 1 秒后轮询又发一次
        pollingCoordinator.restartCountdown(POLL_INTERVAL_SECONDS)
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
                        followedBusId = null,
                        fallbackNextDeparture = null
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

    /**
     * 打开乘车码选择弹窗
     */
    fun openTransitCodeSheet() {
        _uiState.update { it.copy(isTransitCodeSheetOpen = true) }
    }

    /**
     * 关闭乘车码选择弹窗
     */
    fun dismissTransitCodeSheet() {
        _uiState.update { it.copy(isTransitCodeSheetOpen = false) }
    }

    /**
     * 设置乘车码默认渠道
     */
    fun setPreferredTransitCodeProvider(provider: TransitCodeProvider) {
        viewModelScope.launch {
            repository.setPreferredTransitCodeProvider(provider)
        }
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
