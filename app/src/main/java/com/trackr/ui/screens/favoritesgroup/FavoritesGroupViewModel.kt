package com.trackr.ui.screens.favoritesgroup

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.BuildConfig
import com.trackr.TrackrApp
import com.trackr.data.parser.MapShareParser
import com.trackr.data.provider.TransitProviderCoordinator
import com.trackr.data.provider.model.LineDetailRequest
import com.trackr.data.provider.model.StopDetailRequest
import com.trackr.data.remote.amap.AmapSdkManager
import com.trackr.data.remote.amap.AmapSdkTransitService
import com.trackr.data.remote.amap.AmapTipItem
import com.trackr.data.remote.amap.AmapTransitApiClient
import com.trackr.data.remote.amap.AmapTransitSchemeResult
import com.trackr.data.repository.IRouteGroupRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.domain.math.RouteProjectionMath
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.NearbyStop
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
import kotlinx.coroutines.withContext
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
    private val amapSdkService: AmapSdkTransitService? = runCatching { AmapSdkTransitService(TrackrApp.instance) }.getOrNull(),
    private val prefs: SharedPreferences? = runCatching { TrackrApp.instance.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE) }.getOrNull(),
    enableAutoRefresh: Boolean = true
) : ViewModel() {

    companion object {
        const val PREF_KEY_AMAP_KEY = "custom_amap_web_key"
    }

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

    private val _customAmapApiKey = MutableStateFlow<String?>(
        prefs?.getString(PREF_KEY_AMAP_KEY, null)?.trim()?.ifBlank { null }
    )
    val customAmapApiKey: StateFlow<String?> = _customAmapApiKey.asStateFlow()

    init {
        runCatching {
            AmapSdkManager.applyApiKey(TrackrApp.instance, _customAmapApiKey.value)
        }
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

    fun saveCustomAmapApiKey(key: String) {
        val trimmed = key.trim().ifBlank { null }
        prefs?.edit()?.putString(PREF_KEY_AMAP_KEY, trimmed)?.apply()
        _customAmapApiKey.value = trimmed
        runCatching {
            AmapSdkManager.applyApiKey(TrackrApp.instance, trimmed)
        }
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
     * 搜索起点关键字候选项（优先高德，失败则智能降级至本地站点搜索）
     */
    fun searchOriginTips(keyword: String) {
        if (keyword.isBlank()) {
            _amapOriginTips.value = emptyList()
            return
        }
        viewModelScope.launch {
            val city = transitRepository.selectedCity.value
            val customKey = _customAmapApiKey.value
            val sdkResult = amapSdkService?.searchPoiTips(keyword, city.cityName, customKey)
            val tips = sdkResult?.getOrNull()
                ?: runCatching { amapApiClient.searchPoiTips(keyword, city.cityName, customKey) }.getOrNull()?.getOrNull().orEmpty()

            if (tips.isNotEmpty()) {
                _amapOriginTips.value = tips
            } else {
                _amapOriginTips.value = searchLocalStopTips(keyword, city)
            }
        }
    }

    /**
     * 搜索终点关键字候选项（优先高德 SDK，失败则降级至 Web API 或本地站点搜索）
     */
    fun searchDestTips(keyword: String) {
        if (keyword.isBlank()) {
            _amapDestTips.value = emptyList()
            return
        }
        viewModelScope.launch {
            val city = transitRepository.selectedCity.value
            val customKey = _customAmapApiKey.value
            val sdkResult = amapSdkService?.searchPoiTips(keyword, city.cityName, customKey)
            val tips = sdkResult?.getOrNull()
                ?: runCatching { amapApiClient.searchPoiTips(keyword, city.cityName, customKey) }.getOrNull()?.getOrNull().orEmpty()

            if (tips.isNotEmpty()) {
                _amapDestTips.value = tips
            } else {
                _amapDestTips.value = searchLocalStopTips(keyword, city)
            }
        }
    }

    private suspend fun searchLocalStopTips(keyword: String, city: TransitCity): List<AmapTipItem> {
        val coord = coordinator ?: return emptyList()
        return try {
            val searchRes = coord.search(city, keyword, com.trackr.domain.model.EngineMode.CHELAILE)
            val stopTips = searchRes.stops.map { stop ->
                val loc = if (stop.wgsLat != 0.0 && stop.wgsLng != 0.0) "${stop.wgsLng},${stop.wgsLat}" else ""
                AmapTipItem(
                    name = stop.name,
                    district = city.cityName,
                    address = stop.name,
                    location = loc
                )
            }
            if (stopTips.isNotEmpty()) {
                stopTips
            } else {
                searchRes.lines.mapNotNull { line ->
                    val dir = line.directions.firstOrNull() ?: return@mapNotNull null
                    AmapTipItem(
                        name = dir.name,
                        district = city.cityName,
                        address = "${dir.startStop} ➔ ${dir.endStop}",
                        location = ""
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 执行公交路线规划（高德云端规划 + 实时公交站点直达/换乘双引擎）
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

            val city = transitRepository.selectedCity.value
            val customKey = _customAmapApiKey.value
            val hasAmap = !customKey.isNullOrBlank() || BuildConfig.AMAP_ANDROID_KEY.isNotBlank()

            var effectiveOrigin = origin
            var effectiveDest = destination

            // 1. 如果启用了高德且用户输入的坐标为空（打字未点下拉框），自动补全经纬度
            if (hasAmap) {
                if (!effectiveOrigin.location.contains(",")) {
                    val found = amapSdkService?.searchPoiTips(effectiveOrigin.name, city.cityName, customKey)?.getOrNull()?.firstOrNull { it.location.contains(",") }
                    if (found != null) effectiveOrigin = found
                }
                if (!effectiveDest.location.contains(",")) {
                    val found = amapSdkService?.searchPoiTips(effectiveDest.name, city.cityName, customKey)?.getOrNull()?.firstOrNull { it.location.contains(",") }
                    if (found != null) effectiveDest = found
                }
            }

            var schemes: List<AmapTransitSchemeResult> = emptyList()

            // 2. 优先尝试高德 Android SDK 规划（若失败再尝试 Web API 备用）
            if (effectiveOrigin.location.contains(",") && effectiveDest.location.contains(",")) {
                val sdkResult = amapSdkService?.planTransit(
                    originLngLat = effectiveOrigin.location,
                    destinationLngLat = effectiveDest.location,
                    originName = effectiveOrigin.name,
                    destName = effectiveDest.name,
                    city = city.cityName,
                    customApiKey = customKey
                )
                schemes = sdkResult?.getOrNull().orEmpty()

                if (schemes.isEmpty()) {
                    val webResult = amapApiClient.planTransit(
                        originLngLat = effectiveOrigin.location,
                        destinationLngLat = effectiveDest.location,
                        originName = effectiveOrigin.name,
                        destName = effectiveDest.name,
                        city = city.cityName,
                        apiKey = customKey
                    )
                    schemes = webResult.getOrNull().orEmpty()
                }

                // 对齐高德方案中的线路 ID 为车来了/掌上公交真实调度 ID
                if (schemes.isNotEmpty()) {
                    schemes = alignAmapSchemesWithRealtimeSource(schemes, city)
                }
            }

            // 3. 若高德未配置 Key、调用异常或结果为空，平滑降级至本地公交真实站点直达/换乘引擎
            if (schemes.isEmpty()) {
                schemes = planLocalTransitRoute(effectiveOrigin, effectiveDest, city)
            }

            if (schemes.isNotEmpty()) {
                _plannedSchemes.value = schemes
                onComplete?.invoke(true)
            } else {
                _planErrorMessage.value = if (!hasAmap) {
                    "未查到经过【${effectiveOrigin.name}】与【${effectiveDest.name}】的直达或换乘公交。\n建议输入具体公交站名，或配置高德 Key 启用全城地标与地铁算路。"
                } else {
                    "在当前城市未找到合适的公交换乘方案，请尝试更换更精准的站名或地名。"
                }
                onComplete?.invoke(false)
            }

            _isPlanningRoute.value = false
        }
    }

    /**
     * 将高德规划出的方案与车来了真实线路数据对齐（解决规划后无法获取实时车辆到站的问题）
     */
    private suspend fun alignAmapSchemesWithRealtimeSource(
        schemes: List<AmapTransitSchemeResult>,
        city: TransitCity
    ): List<AmapTransitSchemeResult> {
        val coord = coordinator ?: return schemes
        return withContext(Dispatchers.IO) {
            schemes.map { scheme ->
                val newLegs = scheme.group.legs.map { leg ->
                    val primary = leg.primaryLine
                    if (primary != null && primary.lineId.length > 20) { // 高德生成的冗长临时 ID
                        val searchRes = runCatching { coord.search(city, primary.lineName, EngineMode.CHELAILE) }.getOrNull()
                        val matchedDir = searchRes?.lines?.flatMap { it.directions }?.find {
                            it.name.contains(primary.lineName) || primary.lineName.contains(it.name)
                        }
                        if (matchedDir != null) {
                            val updatedLine = primary.copy(
                                lineId = matchedDir.lineId,
                                direction = matchedDir.direction,
                                source = matchedDir.source,
                                cityId = city.cityId,
                                cityName = city.cityName
                            )
                            leg.copy(candidateLines = listOf(updatedLine))
                        } else {
                            leg
                        }
                    } else {
                        leg
                    }
                }
                scheme.copy(group = scheme.group.copy(legs = newLegs))
            }
        }
    }

    /**
     * 本地公交换乘计算引擎（纯公交真实调度网络，无需高德 Key，支持直达与一次换乘）
     */
    private suspend fun planLocalTransitRoute(
        origin: AmapTipItem,
        destination: AmapTipItem,
        city: TransitCity
    ): List<AmapTransitSchemeResult> {
        val coord = coordinator ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val originCore = RouteProjectionMath.coreStopName(origin.name)
                val destCore = RouteProjectionMath.coreStopName(destination.name)

                // 1. 检索起点和终点匹配的公交车站台
                val originSearch = coord.search(city, origin.name, EngineMode.CHELAILE)
                val destSearch = coord.search(city, destination.name, EngineMode.CHELAILE)

                val originStops = originSearch.stops.filter {
                    it.name.contains(originCore) || originCore.contains(RouteProjectionMath.coreStopName(it.name))
                }.ifEmpty { originSearch.stops.take(2) }

                val destStops = destSearch.stops.filter {
                    it.name.contains(destCore) || destCore.contains(RouteProjectionMath.coreStopName(it.name))
                }.ifEmpty { destSearch.stops.take(2) }

                if (originStops.isEmpty() || destStops.isEmpty()) {
                    return@withContext emptyList()
                }

                // 2. 获取起点停靠的所有真实线路
                val originNearby = mutableListOf<NearbyStop>()
                for (stop in originStops.take(2)) {
                    val details = runCatching {
                        coord.getStopDetail(city, StopDetailRequest(stopId = stop.stopId, stopName = stop.name), EngineMode.CHELAILE)
                    }.getOrNull().orEmpty()
                    originNearby.addAll(details)
                }
                val originLines = originNearby.flatMap { it.lines }.distinctBy { "${it.line.lineId}_${it.line.direction}" }

                // 3. 获取终点停靠的所有真实线路
                val destNearby = mutableListOf<NearbyStop>()
                for (stop in destStops.take(2)) {
                    val details = runCatching {
                        coord.getStopDetail(city, StopDetailRequest(stopId = stop.stopId, stopName = stop.name), EngineMode.CHELAILE)
                    }.getOrNull().orEmpty()
                    destNearby.addAll(details)
                }
                val destLines = destNearby.flatMap { it.lines }.distinctBy { "${it.line.lineId}_${it.line.direction}" }

                val schemes = mutableListOf<AmapTransitSchemeResult>()

                // 4. 直达方案计算（0次换乘）
                for (origLine in originLines) {
                    val matchingDest = destLines.find {
                        (it.line.name == origLine.line.name || it.line.lineId == origLine.line.lineId) &&
                        it.line.direction == origLine.line.direction &&
                        origLine.targetOrder < it.targetOrder
                    }

                    if (matchingDest != null) {
                        val stopCount = (matchingDest.targetOrder - origLine.targetOrder).coerceAtLeast(1)
                        val durationMinutes = (stopCount * 3 + 2).coerceAtLeast(5)
                        val primaryLine = CandidateLine(
                            cityId = city.cityId,
                            cityName = city.cityName,
                            lineId = origLine.line.lineId,
                            lineName = origLine.line.name,
                            source = origLine.line.source,
                            direction = origLine.line.direction,
                            fromStopOrder = origLine.targetOrder,
                            toStopOrder = matchingDest.targetOrder,
                            startStopName = origLine.targetStopName,
                            endStopName = matchingDest.targetStopName,
                            mygolbsCityCode = city.mygolbsCityCode
                        )
                        val leg = TransitLeg(
                            legIndex = 0,
                            fromStopName = origLine.targetStopName,
                            toStopName = matchingDest.targetStopName,
                            candidateLines = listOf(primaryLine),
                            walkingDistanceMeters = 0
                        )
                        val title = "${origLine.line.name} 直达 · 共${stopCount}站"
                        val group = TransitGroup(
                            id = UUID.randomUUID().toString(),
                            name = "${origin.name} ➔ ${destination.name} ($title)",
                            category = GroupCategory.TEMPORARY,
                            legs = listOf(leg),
                            isTemporary = true,
                            sortOrder = 0
                        )
                        schemes.add(
                            AmapTransitSchemeResult(
                                title = title,
                                durationMinutes = durationMinutes,
                                walkingMeters = 50,
                                costYuan = "2.0",
                                transferCount = 0,
                                group = group
                            )
                        )
                    }
                }

                // 5. 若直达方案不足 3 条，计算 1 次换乘（寻找交汇车站）
                if (schemes.size < 3) {
                    val origDetails = originLines.take(3).mapNotNull { l ->
                        runCatching {
                            coord.getLineDetail(city, LineDetailRequest(lineId = l.line.lineId, source = l.line.source, lineNameHint = l.line.name, directionHint = l.line.direction))
                        }.getOrNull()
                    }
                    val destDetails = destLines.take(3).mapNotNull { l ->
                        runCatching {
                            coord.getLineDetail(city, LineDetailRequest(lineId = l.line.lineId, source = l.line.source, lineNameHint = l.line.name, directionHint = l.line.direction))
                        }.getOrNull()
                    }

                    transferLoop@ for (d1 in origDetails) {
                        for (d2 in destDetails) {
                            if (d1.line.lineId == d2.line.lineId) continue
                            val d1Stops = d1.stops
                            val d2Stops = d2.stops

                            val fromStopInD1 = d1Stops.find { it.name.contains(originCore) || originCore.contains(RouteProjectionMath.coreStopName(it.name)) } ?: continue
                            val toStopInD2 = d2Stops.find { it.name.contains(destCore) || destCore.contains(RouteProjectionMath.coreStopName(it.name)) } ?: continue

                            for (s1 in d1Stops) {
                                if (s1.order <= fromStopInD1.order) continue
                                val s1Core = RouteProjectionMath.coreStopName(s1.name)
                                val s2 = d2Stops.find { RouteProjectionMath.coreStopName(it.name) == s1Core }
                                if (s2 != null && s2.order < toStopInD2.order) {
                                    val leg1 = TransitLeg(
                                        legIndex = 0,
                                        fromStopName = fromStopInD1.name,
                                        toStopName = s1.name,
                                        candidateLines = listOf(
                                            CandidateLine(
                                                cityId = city.cityId,
                                                cityName = city.cityName,
                                                lineId = d1.line.lineId,
                                                lineName = d1.line.name,
                                                source = d1.line.source,
                                                direction = d1.line.direction,
                                                fromStopOrder = fromStopInD1.order,
                                                toStopOrder = s1.order,
                                                startStopName = fromStopInD1.name,
                                                endStopName = s1.name
                                            )
                                        )
                                    )
                                    val leg2 = TransitLeg(
                                        legIndex = 1,
                                        fromStopName = s2.name,
                                        toStopName = toStopInD2.name,
                                        candidateLines = listOf(
                                            CandidateLine(
                                                cityId = city.cityId,
                                                cityName = city.cityName,
                                                lineId = d2.line.lineId,
                                                lineName = d2.line.name,
                                                source = d2.line.source,
                                                direction = d2.line.direction,
                                                fromStopOrder = s2.order,
                                                toStopOrder = toStopInD2.order,
                                                startStopName = s2.name,
                                                endStopName = toStopInD2.name
                                            )
                                        )
                                    )
                                    val stopsTotal = (s1.order - fromStopInD1.order) + (toStopInD2.order - s2.order)
                                    val durationMinutes = (stopsTotal * 3 + 6).coerceAtLeast(15)
                                    val title = "${d1.line.name} ➔ ${d2.line.name} · 1次换乘(${s1.name})"
                                    val group = TransitGroup(
                                        id = UUID.randomUUID().toString(),
                                        name = "${origin.name} ➔ ${destination.name} ($title)",
                                        category = GroupCategory.TEMPORARY,
                                        legs = listOf(leg1, leg2),
                                        isTemporary = true,
                                        sortOrder = 0
                                    )
                                    schemes.add(
                                        AmapTransitSchemeResult(
                                            title = title,
                                            durationMinutes = durationMinutes,
                                            walkingMeters = 80,
                                            costYuan = "4.0",
                                            transferCount = 1,
                                            group = group
                                        )
                                    )
                                    if (schemes.size >= 5) break@transferLoop
                                }
                            }
                        }
                    }
                }

                schemes.distinctBy { it.title }
            } catch (_: Exception) {
                emptyList()
            }
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
