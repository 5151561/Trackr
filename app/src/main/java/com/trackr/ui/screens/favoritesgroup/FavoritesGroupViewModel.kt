package com.trackr.ui.screens.favoritesgroup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.TrackrApp
import com.trackr.data.parser.MapShareParser
import com.trackr.data.provider.TransitProviderCoordinator
import com.trackr.data.provider.model.LineDetailRequest
import com.trackr.data.remote.amap.AmapTipItem
import com.trackr.data.remote.amap.AmapTransitApiClient
import com.trackr.data.remote.amap.AmapTransitSchemeResult
import com.trackr.data.repository.IRouteGroupRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import com.trackr.service.TransferAutoSwitchManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.UUID

/**
 * 路线组与换乘追踪 ViewModel
 */
class FavoritesGroupViewModel(
    private val groupRepository: IRouteGroupRepository = TrackrApp.instance.routeGroupRepository,
    private val transitRepository: ITransitRepository = TrackrApp.instance.repository,
    private val coordinator: TransitProviderCoordinator? = runCatching { TrackrApp.instance.coordinator }.getOrNull(),
    private val okHttpClient: OkHttpClient? = runCatching { TrackrApp.instance.okHttpClient }.getOrNull(),
    private val amapApiClient: AmapTransitApiClient = AmapTransitApiClient(okHttpClient ?: OkHttpClient()),
    enableAutoRefresh: Boolean = true
) : ViewModel() {

    private val _selectedCategory = MutableStateFlow<GroupCategory?>(null)
    val selectedCategory: StateFlow<GroupCategory?> = _selectedCategory.asStateFlow()

    val groups: StateFlow<List<TransitGroup>> = groupRepository.groups

    val filteredGroups: StateFlow<List<TransitGroup>> = combine(
        groups,
        selectedCategory
    ) { allGroups, category ->
        if (category == null) allGroups else allGroups.filter { it.category == category }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, groups.value)

    val activeGroupSession: StateFlow<GroupTrackingSessionState?> =
        TransferAutoSwitchManager.activeGroupSession

    private val _legRealtimeSummary = MutableStateFlow<Map<String, String>>(emptyMap())
    val legRealtimeSummary: StateFlow<Map<String, String>> = _legRealtimeSummary.asStateFlow()

    private val _clipboardSharePrompt = MutableStateFlow<String?>(null)
    val clipboardSharePrompt: StateFlow<String?> = _clipboardSharePrompt.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importErrorMessage = MutableStateFlow<String?>(null)
    val importErrorMessage: StateFlow<String?> = _importErrorMessage.asStateFlow()

    private val _amapOriginTips = MutableStateFlow<List<AmapTipItem>>(emptyList())
    val amapOriginTips: StateFlow<List<AmapTipItem>> = _amapOriginTips.asStateFlow()

    private val _amapDestTips = MutableStateFlow<List<AmapTipItem>>(emptyList())
    val amapDestTips: StateFlow<List<AmapTipItem>> = _amapDestTips.asStateFlow()

    private val _plannedSchemes = MutableStateFlow<List<AmapTransitSchemeResult>>(emptyList())
    val plannedSchemes: StateFlow<List<AmapTransitSchemeResult>> = _plannedSchemes.asStateFlow()

    private val _isPlanningRoute = MutableStateFlow(false)
    val isPlanningRoute: StateFlow<Boolean> = _isPlanningRoute.asStateFlow()

    private val _planErrorMessage = MutableStateFlow<String?>(null)
    val planErrorMessage: StateFlow<String?> = _planErrorMessage.asStateFlow()

    init {
        // 当分组列表加载完毕后，预热刷新一次各分组首程线路的实时到站状态
        if (enableAutoRefresh) {
            viewModelScope.launch {
                groups.collect { groupList ->
                    refreshRealtimeEta(groupList)
                }
            }
        }
    }

    fun selectCategory(category: GroupCategory?) {
        _selectedCategory.value = category
    }

    /**
     * 探测剪贴板内容是否包含高德/百度地图分享路线
     */
    fun checkClipboardForShareContent(clipboardText: String?) {
        if (clipboardText.isNullOrBlank()) return
        if (clipboardText == _clipboardSharePrompt.value) return
        if (MapShareParser.isMapShareContent(clipboardText)) {
            _clipboardSharePrompt.value = clipboardText
        }
    }

    fun dismissClipboardPrompt() {
        _clipboardSharePrompt.value = null
    }

    /**
     * 导入地图分享内容（纯文本或 URL 异步重定向解析并对齐真实线路）
     */
    fun importMapShare(rawText: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            _isImporting.value = true
            _importErrorMessage.value = null
            try {
                val city = transitRepository.selectedCity.value
                val importedGroup = MapShareParser.parseAndAlign(
                    text = rawText,
                    defaultCity = city,
                    coordinator = coordinator,
                    client = okHttpClient
                )

                if (importedGroup != null && importedGroup.legs.isNotEmpty()) {
                    groupRepository.addGroup(importedGroup)
                    _clipboardSharePrompt.value = null
                    onComplete?.invoke(true)
                } else {
                    _importErrorMessage.value = "未能在分享内容中识别出有效公交/地铁路线"
                    onComplete?.invoke(false)
                }
            } catch (e: Exception) {
                _importErrorMessage.value = "解析失败: ${e.message}"
                onComplete?.invoke(false)
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun clearImportError() {
        _importErrorMessage.value = null
    }

    /**
     * 搜索起点关键字候选项
     */
    fun searchOriginTips(keyword: String) {
        if (keyword.isBlank()) {
            _amapOriginTips.value = emptyList()
            return
        }
        viewModelScope.launch {
            val city = transitRepository.selectedCity.value.cityName
            val result = amapApiClient.searchPoiTips(keyword, city)
            _amapOriginTips.value = result.getOrDefault(emptyList())
        }
    }

    /**
     * 搜索终点关键字候选项
     */
    fun searchDestTips(keyword: String) {
        if (keyword.isBlank()) {
            _amapDestTips.value = emptyList()
            return
        }
        viewModelScope.launch {
            val city = transitRepository.selectedCity.value.cityName
            val result = amapApiClient.searchPoiTips(keyword, city)
            _amapDestTips.value = result.getOrDefault(emptyList())
        }
    }

    /**
     * 执行高德智能公交路线规划
     */
    fun planTransitRoute(
        origin: AmapTipItem,
        destination: AmapTipItem,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        viewModelScope.launch {
            _isPlanningRoute.value = true
            _planErrorMessage.value = null
            _plannedSchemes.value = emptyList()

            val city = transitRepository.selectedCity.value.cityName
            val result = amapApiClient.planTransit(
                originLngLat = origin.location,
                destinationLngLat = destination.location,
                originName = origin.name,
                destName = destination.name,
                city = city
            )

            result.onSuccess { schemes ->
                _plannedSchemes.value = schemes
                if (schemes.isEmpty()) {
                    _planErrorMessage.value = "未找到合适的公交换乘方案"
                    onComplete?.invoke(false)
                } else {
                    onComplete?.invoke(true)
                }
            }.onFailure { err ->
                _planErrorMessage.value = "规划路线失败: ${err.message}"
                onComplete?.invoke(false)
            }

            _isPlanningRoute.value = false
        }
    }

    /**
     * 将规划方案直接加入到我的路线组
     */
    fun addPlannedSchemeToGroups(scheme: AmapTransitSchemeResult) {
        viewModelScope.launch {
            groupRepository.addGroup(scheme.group)
        }
    }

    fun clearPlanning() {
        _plannedSchemes.value = emptyList()
        _amapOriginTips.value = emptyList()
        _amapDestTips.value = emptyList()
        _planErrorMessage.value = null
    }

    /**
     * 启动整组全程自动换乘追踪
     */
    fun startGroupTracking(context: Context, group: TransitGroup, startingLegIndex: Int = 0) {
        TransferAutoSwitchManager.startGroupTracking(context, group, startingLegIndex)
    }

    /**
     * 停止换乘追踪
     */
    fun stopGroupTracking(context: Context) {
        TransferAutoSwitchManager.stopGroupTracking(context)
    }

    /**
     * 删除分组
     */
    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            groupRepository.deleteGroup(groupId)
        }
    }

    /**
     * 转存临时分组为正式常用分组
     */
    fun saveOrConvertTemporaryGroup(groupId: String, newName: String? = null, newCategory: GroupCategory? = null) {
        viewModelScope.launch {
            groupRepository.saveOrConvertTemporaryGroup(groupId, newName, newCategory)
        }
    }

    /**
     * 创建或更新自定义分组
     */
    fun saveGroup(group: TransitGroup) {
        viewModelScope.launch {
            groupRepository.addGroup(group)
        }
    }

    /**
     * 刷新路线组中在途车辆实时状态
     */
    fun refreshRealtimeEta(groupList: List<TransitGroup> = groups.value) {
        val coord = coordinator ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val candidates = groupList
                .flatMap { it.legs }
                .mapNotNull { it.primaryLine }
                .distinctBy { "${it.lineId}_${it.toStopOrder}" }

            val deferredResults = candidates.map { candidate ->
                async {
                    val cacheKey = "${candidate.lineId}_${candidate.toStopOrder}"
                    val summary = try {
                        val detail = coord.getLineDetail(
                            city = TransitCity(cityId = candidate.cityId, cityName = candidate.cityName),
                            request = LineDetailRequest(
                                lineId = candidate.lineId,
                                targetOrder = candidate.toStopOrder,
                                source = candidate.source,
                                lineNameHint = candidate.lineName,
                                directionHint = candidate.direction
                            )
                        )
                        val candidateBuses = detail.buses.filter { it.order <= candidate.toStopOrder }
                        val nearest = candidateBuses.maxByOrNull { it.order.toFloat() + it.segmentProgress }
                        if (nearest != null) {
                            val stopsLeft = (candidate.toStopOrder - nearest.order).coerceAtLeast(0)
                            val isArrived = stopsLeft == 0 && (nearest.isArrived || nearest.segmentProgress >= 0.98f)
                            val etaText = RouteProjectionMath.formatEtaText(nearest.etaSeconds, isArrived)
                            if (isArrived) "车辆已到站" else "剩$stopsLeft 站 · $etaText"
                        } else {
                            "暂无在途车辆"
                        }
                    } catch (_: Exception) {
                        "等待调度发车"
                    }
                    cacheKey to summary
                }
            }

            val results = deferredResults.awaitAll()
            val summaryMap = _legRealtimeSummary.value.toMutableMap()
            results.forEach { (cacheKey, summary) ->
                summaryMap[cacheKey] = summary
            }
            _legRealtimeSummary.value = summaryMap
        }
    }
}
