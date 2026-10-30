package com.trackr.ui.screens.favoritesgroup

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.BuildConfig
import com.trackr.TrackrApp
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
import com.trackr.domain.model.BusLineDetail
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.CandidateLineRealtimeInfo
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.FavoriteGroup
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.GroupTrackingSessionState
import com.trackr.domain.model.NearbyStop
import com.trackr.domain.model.TransitCity
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import com.trackr.service.TransferAutoSwitchManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlin.math.roundToInt

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    enableAutoRefresh: Boolean = true
) : ViewModel() {

    companion object {
        const val PREF_KEY_AMAP_KEY = "custom_amap_web_key"
    }

    private val _selectedCategory = MutableStateFlow<GroupCategory?>(null)
    val selectedCategory: StateFlow<GroupCategory?> = _selectedCategory.asStateFlow()

    private val _selectedFavoriteGroupId = MutableStateFlow<String?>(null)
    val selectedFavoriteGroupId: StateFlow<String?> = _selectedFavoriteGroupId.asStateFlow()

    val groups: StateFlow<List<TransitGroup>> = groupRepository.groups
    val favoriteGroups: StateFlow<List<FavoriteGroup>> = groupRepository.favoriteGroups

    val currentCity: StateFlow<TransitCity> = transitRepository.selectedCity

    fun isGroupInFavoriteGroup(group: TransitGroup, favGroupId: String): Boolean {
        return when (favGroupId) {
            "commute" -> group.customGroupId == "commute" || (group.customGroupId == null && group.category == GroupCategory.COMMUTE)
            "home" -> group.customGroupId == "home" || (group.customGroupId == null && group.name.contains("返程"))
            else -> group.customGroupId == favGroupId
        }
    }

    val filteredGroups: StateFlow<List<TransitGroup>> = combine(
        groups,
        selectedCategory,
        selectedFavoriteGroupId
    ) { allGroups, category, favGroupId ->
        when {
            favGroupId != null -> allGroups.filter { isGroupInFavoriteGroup(it, favGroupId) }
            category != null -> allGroups.filter { it.category == category }
            else -> allGroups
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, groups.value)

    val activeGroupSession: StateFlow<GroupTrackingSessionState?> =
        TransferAutoSwitchManager.activeGroupSession

    private val _legRealtimeSummary = MutableStateFlow<Map<String, String>>(emptyMap())
    val legRealtimeSummary: StateFlow<Map<String, String>> = _legRealtimeSummary.asStateFlow()

    private val _candidateRealtimeMap = MutableStateFlow<Map<String, CandidateLineRealtimeInfo>>(emptyMap())
    val candidateRealtimeMap: StateFlow<Map<String, CandidateLineRealtimeInfo>> = _candidateRealtimeMap.asStateFlow()

    private val _userSelectedLines = MutableStateFlow<Map<String, String>>(emptyMap())

    val selectedLineKeys: StateFlow<Map<String, String>> = combine(
        groups,
        _candidateRealtimeMap,
        _userSelectedLines
    ) { groupList, realtimeMap, userSelected ->
        val result = mutableMapOf<String, String>()
        for (group in groupList) {
            for (leg in group.legs) {
                val legKey = "${group.id}_${leg.legIndex}"
                val userChoice = userSelected[legKey]
                if (userChoice != null && leg.candidateLines.any { it.lineId == userChoice }) {
                    result[legKey] = userChoice
                } else {
                    val fastestLine = leg.candidateLines
                        .mapNotNull { line ->
                            val info = realtimeMap["${group.id}_${leg.legIndex}_${line.lineId}"]
                                ?: realtimeMap["${line.lineId}_${line.toStopOrder}"]
                            if (info != null && info.stopsAway < Int.MAX_VALUE) line to info.stopsAway else null
                        }
                        .minByOrNull { it.second }?.first
                    result[legKey] = fastestLine?.lineId ?: leg.primaryLine?.lineId.orEmpty()
                }
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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
        viewModelScope.launch(ioDispatcher) {
            val currentGroups = groupRepository.groups.value
            for (g in currentGroups) {
                var groupModified = false
                var updated = g
                if (updated.customGroupId == null && updated.category == GroupCategory.COMMUTE) {
                    updated = updated.copy(customGroupId = "commute")
                    groupModified = true
                }
                val hasSynthetic = updated.legs.any { leg ->
                    leg.candidateLines.any { it.lineId.startsWith("line_") || it.fromStopOrder >= it.toStopOrder }
                }
                if (hasSynthetic) {
                    updated = alignTransitGroupWithRealtimeSource(updated)
                    groupModified = true
                }
                if (groupModified) {
                    groupRepository.updateGroup(updated)
                }
            }

            val latestGroups = groupRepository.groups.value
            val commuteRoutes = latestGroups.filter { isGroupInFavoriteGroup(it, "commute") }
            val homeRoutes = latestGroups.filter { isGroupInFavoriteGroup(it, "home") }
            for (commute in commuteRoutes) {
                val newOrigin = commute.destinationStopName
                val newDest = commute.originStopName
                val hasReverse = homeRoutes.any {
                    it.originStopName == newOrigin && it.destinationStopName == newDest
                }
                if (!hasReverse && newOrigin.isNotBlank() && newDest.isNotBlank()) {
                    val reversed = buildReverseTransitGroup(commute, targetGroupId = "home")
                    groupRepository.addGroup(reversed)
                }
            }
        }
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
        _selectedFavoriteGroupId.value = null
    }

    fun selectFavoriteGroup(groupId: String?) {
        _selectedFavoriteGroupId.value = groupId
        _selectedCategory.value = null
    }

    fun selectCandidateLine(groupId: String, legIndex: Int, lineId: String) {
        _userSelectedLines.value = _userSelectedLines.value + ("${groupId}_${legIndex}" to lineId)
    }

    fun createFavoriteGroup(name: String) {
        viewModelScope.launch {
            groupRepository.createFavoriteGroup(name)
        }
    }

    fun renameFavoriteGroup(groupId: String, newName: String) {
        viewModelScope.launch {
            groupRepository.renameFavoriteGroup(groupId, newName)
        }
    }

    fun deleteFavoriteGroup(groupId: String) {
        viewModelScope.launch {
            groupRepository.deleteFavoriteGroup(groupId)
            if (_selectedFavoriteGroupId.value == groupId) {
                _selectedFavoriteGroupId.value = null
            }
        }
    }

    fun reorderFavoriteGroups(groupIds: List<String>) {
        viewModelScope.launch {
            groupRepository.reorderFavoriteGroups(groupIds)
        }
    }

    fun addCandidateLineToLeg(groupId: String, legIndex: Int, candidateLine: CandidateLine) {
        viewModelScope.launch {
            val group = groupRepository.getGroupById(groupId)
            val leg = group?.legs?.getOrNull(legIndex)
            val alignedLine = if (leg != null) {
                val city = if (candidateLine.cityId.isNotBlank() && candidateLine.cityId != "033") {
                    TransitCity(candidateLine.cityId, candidateLine.cityName)
                } else transitRepository.selectedCity.value
                val matched = findRealtimeLineMatch(
                    city = city,
                    lineName = candidateLine.lineName,
                    fromStopName = leg.fromStopName,
                    toStopName = leg.toStopName,
                    preferredDirection = candidateLine.direction
                )
                matched ?: candidateLine
            } else candidateLine

            groupRepository.addCandidateLineToLeg(groupId, legIndex, alignedLine)
            refreshRealtimeEta(groupRepository.groups.value)
        }
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
            val searchRes = coord.search(city, keyword, EngineMode.CHELAILE)
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

    fun clearPlanError() {
        _planErrorMessage.value = null
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

            // 1. 解析“我的位置”
            if (effectiveOrigin.name == "我的位置" || effectiveOrigin.name.isBlank()) {
                val loc = transitRepository.userLocation.value
                var resolvedLat: Double? = loc?.first
                var resolvedLng: Double? = loc?.second

                if (resolvedLat == null || resolvedLng == null) {
                    val locManager = runCatching {
                        TrackrApp.instance.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                    }.getOrNull()
                    val lastLoc = locManager?.let { lm ->
                        runCatching {
                            lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        }.getOrNull()
                    }
                    if (lastLoc != null) {
                        resolvedLat = lastLoc.latitude
                        resolvedLng = lastLoc.longitude
                    }
                }

                if (resolvedLat != null && resolvedLng != null) {
                    val nearby = runCatching {
                        transitRepository.getNearbyStops(city, resolvedLat, resolvedLng, 1).getOrNull()?.firstOrNull()
                    }.getOrNull()
                    effectiveOrigin = if (nearby != null) {
                        AmapTipItem(
                            name = "我的位置(${nearby.name})",
                            district = city.cityName,
                            address = nearby.name,
                            location = "$resolvedLng,$resolvedLat"
                        )
                    } else {
                        AmapTipItem(
                            name = "我的位置",
                            district = city.cityName,
                            address = "当前定位",
                            location = "$resolvedLng,$resolvedLat"
                        )
                    }
                }
            }

            // 2. 补全缺少经纬度的起终点坐标
            suspend fun resolveLocation(tip: AmapTipItem): AmapTipItem {
                if (tip.location.contains(",")) return tip
                if (hasAmap) {
                    val sdkFound = amapSdkService?.searchPoiTips(tip.name, city.cityName, customKey)
                        ?.getOrNull()?.firstOrNull { it.location.contains(",") }
                    if (sdkFound != null) return sdkFound

                    val webFound = runCatching {
                        amapApiClient.searchPoiTips(tip.name, city.cityName, customKey)
                    }.getOrNull()?.getOrNull()?.firstOrNull { it.location.contains(",") }
                    if (webFound != null) return webFound
                }
                val localFound = searchLocalStopTips(tip.name, city).firstOrNull { it.location.contains(",") }
                if (localFound != null) return localFound
                return tip
            }

            effectiveOrigin = resolveLocation(effectiveOrigin)
            effectiveDest = resolveLocation(effectiveDest)

            var schemes: List<AmapTransitSchemeResult> = emptyList()

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

                if (schemes.isNotEmpty()) {
                    schemes = alignAmapSchemesWithRealtimeSource(schemes, city)
                }
            }

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

    private suspend fun alignAmapSchemesWithRealtimeSource(
        schemes: List<AmapTransitSchemeResult>,
        city: TransitCity
    ): List<AmapTransitSchemeResult> {
        val coord = coordinator ?: return schemes
        return withContext(ioDispatcher) {
            schemes.map { scheme ->
                val newLegs = scheme.group.legs.map { leg ->
                    val fromCore = RouteProjectionMath.coreStopName(leg.fromStopName)
                    val toCore = RouteProjectionMath.coreStopName(leg.toStopName)

                    val updatedCandidates = leg.candidateLines.map { candidate ->
                        runCatching {
                            val searchRes = coord.search(city, candidate.lineName, EngineMode.CHELAILE)
                            val matchedDirs = searchRes.lines.flatMap { it.directions }.filter { dir ->
                                dir.name.contains(candidate.lineName) || candidate.lineName.contains(dir.name)
                            }
                            var bestLine: CandidateLine? = null
                            for (dir in matchedDirs) {
                                val detail = runCatching {
                                    coord.getLineDetail(
                                        city,
                                        LineDetailRequest(
                                            lineId = dir.lineId,
                                            source = dir.source,
                                            lineNameHint = dir.name,
                                            directionHint = dir.direction
                                        )
                                    )
                                }.getOrNull()
                                if (detail != null) {
                                    val fromStop = detail.stops.find {
                                        it.name.contains(fromCore) || fromCore.contains(RouteProjectionMath.coreStopName(it.name))
                                    }
                                    val toStop = detail.stops.find {
                                        it.name.contains(toCore) || toCore.contains(RouteProjectionMath.coreStopName(it.name))
                                    }
                                    if (fromStop != null && toStop != null && fromStop.order < toStop.order) {
                                        bestLine = candidate.copy(
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
                                        break
                                    }
                                }
                                if (bestLine == null) {
                                    bestLine = candidate.copy(
                                        cityId = city.cityId,
                                        cityName = city.cityName,
                                        lineId = dir.lineId,
                                        direction = dir.direction,
                                        source = dir.source,
                                        mygolbsCityCode = city.mygolbsCityCode
                                    )
                                }
                            }
                            bestLine ?: candidate
                        }.getOrDefault(candidate)
                    }
                    leg.copy(candidateLines = updatedCandidates)
                }
                scheme.copy(group = scheme.group.copy(legs = newLegs))
            }
        }
    }

    private suspend fun planLocalTransitRoute(
        origin: AmapTipItem,
        destination: AmapTipItem,
        city: TransitCity
    ): List<AmapTransitSchemeResult> {
        val coord = coordinator ?: return emptyList()
        return withContext(ioDispatcher) {
            try {
                val originCore = RouteProjectionMath.coreStopName(origin.name)
                val destCore = RouteProjectionMath.coreStopName(destination.name)

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

                val originNearby = mutableListOf<NearbyStop>()
                for (stop in originStops.take(2)) {
                    val details = runCatching {
                        coord.getStopDetail(city, StopDetailRequest(stopId = stop.stopId, stopName = stop.name), EngineMode.CHELAILE)
                    }.getOrNull().orEmpty()
                    originNearby.addAll(details)
                }
                val originLines = originNearby.flatMap { it.lines }.distinctBy { "${it.line.lineId}_${it.line.direction}" }

                val destNearby = mutableListOf<NearbyStop>()
                for (stop in destStops.take(2)) {
                    val details = runCatching {
                        coord.getStopDetail(city, StopDetailRequest(stopId = stop.stopId, stopName = stop.name), EngineMode.CHELAILE)
                    }.getOrNull().orEmpty()
                    destNearby.addAll(details)
                }
                val destLines = destNearby.flatMap { it.lines }.distinctBy { "${it.line.lineId}_${it.line.direction}" }

                val schemes = mutableListOf<AmapTransitSchemeResult>()

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

    /**
     * 将规划方案转存为正式收藏分组
     */
    fun addPlannedSchemeAsFavorite(
        group: TransitGroup,
        customGroupId: String? = null,
        category: GroupCategory = GroupCategory.CUSTOM,
        customName: String? = null
    ) {
        viewModelScope.launch {
            val targetGroupId = customGroupId
                ?: _selectedFavoriteGroupId.value
                ?: if (group.category == GroupCategory.COMMUTE) "commute" else null
            val targetCategory = if (targetGroupId == "commute") GroupCategory.COMMUTE else category
            val preparedGroup = group.copy(
                isTemporary = false,
                customGroupId = targetGroupId,
                category = targetCategory,
                name = customName?.takeIf { it.isNotBlank() } ?: group.name
            )
            val alignedGroup = alignTransitGroupWithRealtimeSource(preparedGroup)
            groupRepository.addGroup(alignedGroup)

            if (targetGroupId == "commute") {
                val newOrigin = alignedGroup.destinationStopName
                val newDest = alignedGroup.originStopName
                val existingHome = groupRepository.groups.value.filter { isGroupInFavoriteGroup(it, "home") }
                val hasReverse = existingHome.any { it.originStopName == newOrigin && it.destinationStopName == newDest }
                if (!hasReverse && newOrigin.isNotBlank() && newDest.isNotBlank()) {
                    val reverseGroup = buildReverseTransitGroup(alignedGroup, targetGroupId = "home")
                    groupRepository.addGroup(reverseGroup)
                }
            }
        }
    }

    fun addPlannedSchemeAsFavorite(
        scheme: AmapTransitSchemeResult,
        customGroupId: String? = null,
        category: GroupCategory = GroupCategory.CUSTOM,
        customName: String? = null
    ) {
        addPlannedSchemeAsFavorite(
            group = scheme.group,
            customGroupId = customGroupId,
            category = category,
            customName = customName
        )
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
    fun saveOrConvertTemporaryGroup(
        groupId: String,
        newName: String? = null,
        newCategory: GroupCategory? = null,
        customGroupId: String? = null
    ) {
        viewModelScope.launch {
            groupRepository.saveOrConvertTemporaryGroup(groupId, newName, newCategory, customGroupId)
        }
    }

    /**
     * 创建或更新自定义分组
     */
    fun saveGroup(group: TransitGroup) {
        viewModelScope.launch {
            val targetGroupId = group.customGroupId
                ?: _selectedFavoriteGroupId.value
                ?: if (group.category == GroupCategory.COMMUTE) "commute" else null
            val targetCategory = if (targetGroupId == "commute") GroupCategory.COMMUTE else group.category
            val preparedGroup = group.copy(
                customGroupId = targetGroupId,
                category = targetCategory
            )
            val alignedGroup = alignTransitGroupWithRealtimeSource(preparedGroup)
            groupRepository.addGroup(alignedGroup)

            if (targetGroupId == "commute") {
                val newOrigin = alignedGroup.destinationStopName
                val newDest = alignedGroup.originStopName
                val existingHome = groupRepository.groups.value.filter { isGroupInFavoriteGroup(it, "home") }
                val hasReverse = existingHome.any { it.originStopName == newOrigin && it.destinationStopName == newDest }
                if (!hasReverse && newOrigin.isNotBlank() && newDest.isNotBlank()) {
                    val reverseGroup = buildReverseTransitGroup(alignedGroup, targetGroupId = "home")
                    groupRepository.addGroup(reverseGroup)
                }
            }
        }
    }

    /**
     * 为指定路线生成反向路线分组
     */
    fun createReverseGroup(group: TransitGroup) {
        viewModelScope.launch {
            val targetGroupId = when (group.customGroupId) {
                "commute" -> "home"
                "home" -> "commute"
                else -> group.customGroupId ?: if (group.category == GroupCategory.COMMUTE) "home" else null
            }
            val reversed = buildReverseTransitGroup(group, targetGroupId)
            groupRepository.addGroup(reversed)
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

        val searchRes = runCatching {
            coordinator?.search(city, lineName, EngineMode.CHELAILE)
        }.getOrNull() ?: runCatching {
            transitRepository.search(city, lineName).getOrNull()
        }.getOrNull()

        val matchedDirs = searchRes?.lines?.flatMap { it.directions }?.filter { dir ->
            dir.name.contains(lineName) || lineName.contains(dir.name)
        }.orEmpty()

        val sortedDirs = if (preferredDirection != null) {
            matchedDirs.sortedByDescending { it.direction == preferredDirection }
        } else {
            matchedDirs
        }

        for (dir in sortedDirs) {
            val detail = runCatching {
                coordinator?.getLineDetail(
                    city,
                    LineDetailRequest(
                        lineId = dir.lineId,
                        source = dir.source,
                        lineNameHint = dir.name,
                        directionHint = dir.direction
                    )
                )
            }.getOrNull() ?: runCatching {
                transitRepository.getLineDetail(
                    city = city,
                    lineId = dir.lineId,
                    source = dir.source,
                    lineNameHint = dir.name,
                    directionHint = dir.direction
                ).getOrNull()
            }.getOrNull()

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
                    val revDetail = runCatching {
                        coordinator?.getLineDetail(
                            city,
                            LineDetailRequest(
                                lineId = rev.lineId,
                                source = rev.source,
                                lineNameHint = rev.name,
                                directionHint = rev.direction
                            )
                        )
                    }.getOrNull() ?: runCatching {
                        transitRepository.getLineDetail(
                            city = city,
                            lineId = rev.lineId,
                            source = rev.source,
                            lineNameHint = rev.name,
                            directionHint = rev.direction
                        ).getOrNull()
                    }.getOrNull()

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

        if (sortedDirs.isNotEmpty()) {
            val best = sortedDirs.first()
            return CandidateLine(
                cityId = city.cityId,
                cityName = city.cityName,
                lineId = best.lineId,
                lineName = best.name.ifBlank { lineName },
                source = best.source,
                direction = best.direction,
                fromStopOrder = 1,
                toStopOrder = 5,
                startStopName = fromStopName,
                endStopName = toStopName,
                mygolbsCityCode = city.mygolbsCityCode
            )
        }

        return null
    }

    /**
     * 将路线组中各段候选线路与实时公交数据源进行站序和真实 lineId 对齐
     */
    suspend fun alignTransitGroupWithRealtimeSource(group: TransitGroup): TransitGroup = withContext(ioDispatcher) {
        val defaultCity = transitRepository.selectedCity.value
        val newLegs = group.legs.map { leg ->
            val newCandidates = leg.candidateLines.map { candidate ->
                val isSynthetic = candidate.lineId.startsWith("line_") || candidate.fromStopOrder >= candidate.toStopOrder
                if (isSynthetic) {
                    val lineCity = if (candidate.cityId.isNotBlank() && candidate.cityId != "033") {
                        TransitCity(candidate.cityId, candidate.cityName)
                    } else defaultCity
                    val matched = findRealtimeLineMatch(
                        city = lineCity,
                        lineName = candidate.lineName,
                        fromStopName = leg.fromStopName,
                        toStopName = leg.toStopName,
                        preferredDirection = candidate.direction
                    )
                    matched ?: candidate.copy(
                        cityId = if (candidate.cityId.isNotBlank()) candidate.cityId else lineCity.cityId,
                        cityName = if (candidate.cityName.isNotBlank()) candidate.cityName else lineCity.cityName,
                        fromStopOrder = candidate.fromStopOrder.coerceAtLeast(1),
                        toStopOrder = maxOf(candidate.fromStopOrder + 1, candidate.toStopOrder, 2)
                    )
                } else {
                    candidate
                }
            }
            leg.copy(candidateLines = newCandidates)
        }
        group.copy(legs = newLegs)
    }

    /**
     * 构建返程路线分组（自动反转行程段、调换起终点与候选线路上下行并对齐真实源数据）
     */
    suspend fun buildReverseTransitGroup(
        forwardGroup: TransitGroup,
        targetGroupId: String? = "home"
    ): TransitGroup = withContext(ioDispatcher) {
        val defaultCity = transitRepository.selectedCity.value
        val reversedOriginalLegs = forwardGroup.legs.reversed()
        val newLegs = reversedOriginalLegs.mapIndexed { reversedIdx, originalLeg ->
            val reversedFromStop = originalLeg.toStopName
            val reversedToStop = originalLeg.fromStopName
            val walkingMeters = if (reversedIdx == 0) 0 else {
                forwardGroup.legs[forwardGroup.legs.size - reversedIdx].walkingDistanceMeters
            }
            val reversedCandidates = originalLeg.candidateLines.map { candidate ->
                val flippedDir = if (candidate.direction == 0) 1 else 0
                val lineCity = if (candidate.cityId.isNotBlank() && candidate.cityId != "033") {
                    TransitCity(candidate.cityId, candidate.cityName)
                } else defaultCity
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

        TransitGroup(
            id = UUID.randomUUID().toString(),
            name = newName,
            category = forwardGroup.category,
            customGroupId = targetGroupId,
            legs = newLegs,
            isTemporary = false,
            sortOrder = forwardGroup.sortOrder + 1,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 刷新路线组中所有候选线路在途车辆实时状态并聚合计算最先到站线路
     */
    fun refreshRealtimeEta(groupList: List<TransitGroup> = groups.value): Job =
        viewModelScope.launch(ioDispatcher) {
            val candidateAlignmentMap = mutableMapOf<String, CandidateLine>()
            for (group in groupList) {
                for (leg in group.legs) {
                    for (candidate in leg.candidateLines) {
                        val key = "${group.id}_${leg.legIndex}_${candidate.lineId}"
                        if (candidate.lineId.startsWith("line_") || candidate.fromStopOrder >= candidate.toStopOrder) {
                            val city = if (candidate.cityId.isNotBlank() && candidate.cityId != "033") {
                                TransitCity(candidate.cityId, candidate.cityName)
                            } else transitRepository.selectedCity.value
                            val matched = findRealtimeLineMatch(
                                city = city,
                                lineName = candidate.lineName,
                                fromStopName = leg.fromStopName,
                                toStopName = leg.toStopName,
                                preferredDirection = candidate.direction
                            )
                            if (matched != null) {
                                candidateAlignmentMap[key] = matched
                            }
                        }
                    }
                }
            }

            val allCandidates = groupList
                .flatMap { g ->
                    g.legs.flatMap { leg ->
                        leg.candidateLines.map { c ->
                            candidateAlignmentMap["${g.id}_${leg.legIndex}_${c.lineId}"] ?: c
                        }
                    }
                }

            if (allCandidates.isEmpty()) return@launch

            data class QueryKey(
                val cityId: String,
                val cityName: String,
                val lineId: String,
                val targetOrder: Int,
                val source: DataSourceType,
                val lineName: String,
                val direction: Int
            )

            val uniqueQueries = allCandidates.map { candidate ->
                val targetOrder = if (candidate.fromStopOrder > 0) candidate.fromStopOrder else candidate.toStopOrder
                QueryKey(
                    cityId = candidate.cityId,
                    cityName = candidate.cityName,
                    lineId = candidate.lineId,
                    targetOrder = targetOrder,
                    source = candidate.source,
                    lineName = candidate.lineName,
                    direction = candidate.direction
                )
            }.distinctBy { "${it.lineId}_${it.targetOrder}" }

            val detailResults = uniqueQueries.map { query ->
                async {
                    val city = TransitCity(cityId = query.cityId, cityName = query.cityName)
                    val detail: BusLineDetail? = runCatching {
                        coordinator?.getLineDetail(
                            city = city,
                            request = LineDetailRequest(
                                lineId = query.lineId,
                                targetOrder = query.targetOrder,
                                source = query.source,
                                lineNameHint = query.lineName,
                                directionHint = query.direction
                            )
                        )
                    }.getOrNull() ?: runCatching {
                        transitRepository.getLineDetail(
                            city = city,
                            lineId = query.lineId,
                            targetOrder = query.targetOrder,
                            source = query.source,
                            lineNameHint = query.lineName,
                            directionHint = query.direction
                        ).getOrNull()
                    }.getOrNull()
                    "${query.lineId}_${query.targetOrder}" to detail
                }
            }.awaitAll().toMap()

            val updatedCandidateMap = _candidateRealtimeMap.value.toMutableMap()
            val updatedSummaryMap = _legRealtimeSummary.value.toMutableMap()

            for (group in groupList) {
                for (leg in group.legs) {
                    val legCandidates = leg.candidateLines
                    if (legCandidates.isEmpty()) continue

                    val parsedInfos = legCandidates.map { candidate ->
                        val effectiveCandidate = candidateAlignmentMap["${group.id}_${leg.legIndex}_${candidate.lineId}"] ?: candidate
                        val targetOrder = if (effectiveCandidate.fromStopOrder > 0) effectiveCandidate.fromStopOrder else effectiveCandidate.toStopOrder
                        val detail = detailResults["${effectiveCandidate.lineId}_${targetOrder}"]

                        val rideStops = (effectiveCandidate.toStopOrder - effectiveCandidate.fromStopOrder).coerceAtLeast(0)
                        val directionText = detail?.line?.endStop?.takeIf { it.isNotBlank() }?.let { "${it}方向" }
                            ?: effectiveCandidate.endStopName.takeIf { it.isNotBlank() }?.let { "${it}方向" }
                            ?: ""

                        if (detail != null) {
                            val candidateBuses = detail.buses.filter { it.order <= targetOrder }
                            val nearest = candidateBuses.maxByOrNull { it.order.toFloat() + it.segmentProgress }
                            if (nearest != null) {
                                val stopsLeft = (targetOrder - nearest.order).coerceAtLeast(0)
                                val isArrived = stopsLeft == 0 && (nearest.isArrived || nearest.segmentProgress >= 0.98f)
                                val etaMinutes = if (nearest.etaSeconds > 0) {
                                    (nearest.etaSeconds / 60.0).roundToInt().coerceAtLeast(1)
                                } else {
                                    (stopsLeft * 2).coerceAtLeast(1)
                                }
                                val etaText = if (isArrived) {
                                    "车辆已到站"
                                } else {
                                    val formattedEta = RouteProjectionMath.formatEtaText(nearest.etaSeconds, isArrived)
                                    "还有 $stopsLeft 站 · $formattedEta"
                                }
                                val shortChipEta = if (isArrived) "已到站" else "${stopsLeft}站"

                                CandidateLineRealtimeInfo(
                                    lineId = candidate.lineId,
                                    lineName = candidate.lineName,
                                    etaText = etaText,
                                    shortChipEta = shortChipEta,
                                    stopsAway = stopsLeft,
                                    estimatedMinutes = etaMinutes,
                                    directionText = directionText,
                                    vehiclePlate = nearest.friendlyPlate,
                                    rideStopsCount = rideStops,
                                    isFastest = false
                                )
                            } else {
                                CandidateLineRealtimeInfo(
                                    lineId = candidate.lineId,
                                    lineName = candidate.lineName,
                                    etaText = "暂无在途车辆",
                                    shortChipEta = "等待发车",
                                    stopsAway = Int.MAX_VALUE,
                                    estimatedMinutes = Int.MAX_VALUE,
                                    directionText = directionText,
                                    vehiclePlate = "",
                                    rideStopsCount = rideStops,
                                    isFastest = false
                                )
                            }
                        } else {
                            CandidateLineRealtimeInfo(
                                lineId = candidate.lineId,
                                lineName = candidate.lineName,
                                etaText = "等待调度发车",
                                shortChipEta = "等待发车",
                                stopsAway = Int.MAX_VALUE,
                                estimatedMinutes = Int.MAX_VALUE,
                                directionText = directionText,
                                vehiclePlate = "",
                                rideStopsCount = rideStops,
                                isFastest = false
                            )
                        }
                    }

                    val minStops = parsedInfos
                        .filter { it.stopsAway < Int.MAX_VALUE }
                        .minOfOrNull { it.stopsAway }

                    val fastestLineId = if (minStops != null) {
                        parsedInfos.find { it.stopsAway == minStops }?.lineId
                    } else null

                    parsedInfos.forEachIndexed { idx, info ->
                        val candidate = legCandidates[idx]
                        val effective = candidateAlignmentMap["${group.id}_${leg.legIndex}_${candidate.lineId}"] ?: candidate
                        val isFastest = (info.lineId == fastestLineId)
                        val finalInfo = info.copy(isFastest = isFastest)

                        updatedCandidateMap["${group.id}_${leg.legIndex}_${candidate.lineId}"] = finalInfo
                        updatedCandidateMap["${group.id}_${leg.legIndex}_${effective.lineId}"] = finalInfo
                        updatedCandidateMap["${candidate.lineId}_${candidate.toStopOrder}"] = finalInfo
                        updatedCandidateMap["${effective.lineId}_${effective.toStopOrder}"] = finalInfo
                        updatedSummaryMap["${candidate.lineId}_${candidate.toStopOrder}"] = finalInfo.etaText
                        updatedSummaryMap["${effective.lineId}_${effective.toStopOrder}"] = finalInfo.etaText
                    }
                }
            }

            _candidateRealtimeMap.value = updatedCandidateMap
            _legRealtimeSummary.value = updatedSummaryMap
        }
}
