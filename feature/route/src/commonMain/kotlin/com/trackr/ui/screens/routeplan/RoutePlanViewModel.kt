package com.trackr.ui.screens.routeplan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.DefaultLocationRepository
import com.trackr.core.data.repository.LocationRepository
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.data.repository.RoutePlanRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.data.repository.UserLocationStore
import com.trackr.core.data.route.RouteGroupSaver
import com.trackr.core.domain.math.CoordTransform
import com.trackr.core.model.AmapTipItem
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitGroup
import com.trackr.data.repository.IRouteGroupRepository
import com.trackr.core.data.route.MAX_VISIBLE_SCHEMES
import com.trackr.ui.screens.routeplan.map.RouteMapState
import com.trackr.ui.screens.routeplan.map.cityCenterGcj02
import com.trackr.ui.screens.routeplan.map.wgs84ToGcj02Point
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 路线规划 ViewModel（第 17 步从 `FavoritesGroupViewModel` 拆出）：
 * 起终点输入提示（防抖 300 ms、太短不查、新词取消旧词）、算路（新的一次取消上一次）、
 * 「我的位置」（定位不到直接报错）、方案存进收藏。缓存与对齐在 [RoutePlanRepository]。
 *
 * 第 24 步：输入框、选中方案、存收藏弹窗也放进来，页面只吃 [uiState]、发 [RoutePlanEvent]；
 * 一次性提示走 [userMessages]（收藏后「已收藏 · 去看看」）。
 *
 * 追踪（开始 / 我上车了 / 结束）、追踪提示走 `RouteDetailViewModel`（和收藏页共用一个实例），和原来一样，
 * 由 `RoutePlanRoute` 分发。
 */
@OptIn(FlowPreview::class)
class RoutePlanViewModel(
    private val routePlan: RoutePlanRepository,
    private val settings: SettingsRepository,
    private val realtime: RealtimeRepository,
    private val userLocationStore: UserLocationStore,
    private val groupRepository: IRouteGroupRepository,
    private val groupSaver: RouteGroupSaver,
    /**
     * 高德 Key 两槽；为 null 时两个 Key 都当没填（测试用）。
     * 第 11 步起这里只读：Key 在「更多」页改，改完由 `AmapSdkKeySync` 注入 SDK。
     */
    private val amapKeys: AmapKeyRepository? = null,
    /** 「我的位置」兜底用的系统 lastKnown 定位；为 null 时没有兜底 */
    private val locationRepository: LocationRepository? = null
) : ViewModel() {

    companion object {
        /** 起点为"我的位置"时的占位名称 */
        const val MY_LOCATION_NAME = "我的位置"

        /** 输入提示防抖 */
        const val TIPS_DEBOUNCE_MS = 300L

        /** 存进收藏后的提示（带「去看看」） */
        const val SAVED_MESSAGE = "已收藏"

        /** 系统 lastKnown 定位兜底的最大年龄 */
        const val MAX_LAST_KNOWN_AGE_MS = DefaultLocationRepository.MAX_LAST_KNOWN_AGE_MS

        /** 输入提示是否值得发请求：至少 2 个字符，或包含 1 个汉字 */
        fun isTipsKeywordEligible(keyword: String): Boolean {
            val kw = keyword.trim()
            if (kw.isEmpty()) return false
            return kw.length >= 2 || kw.any { it in '\u4e00'..'\u9fff' }
        }
    }

    val favoriteGroups: StateFlow<List<FavoriteGroup>> = groupRepository.favoriteGroups

    val currentCity: StateFlow<TransitCity> = settings.selectedCity

    /** 当前设备定位（WGS-84，由首页定位链路写入），供地图初始视角使用 */
    val userLocation: StateFlow<Pair<Double, Double>?> = userLocationStore.userLocation

    private val _amapOriginTips = MutableStateFlow<List<AmapTipItem>>(emptyList())
    val amapOriginTips: StateFlow<List<AmapTipItem>> = _amapOriginTips.asStateFlow()

    private val _amapDestTips = MutableStateFlow<List<AmapTipItem>>(emptyList())
    val amapDestTips: StateFlow<List<AmapTipItem>> = _amapDestTips.asStateFlow()

    /** 输入框原始关键字（已 trim），经防抖后驱动输入提示请求 */
    private val originTipsQuery = MutableStateFlow("")
    private val destTipsQuery = MutableStateFlow("")

    private var planJob: Job? = null

    private val _plannedSchemes = MutableStateFlow<List<AmapTransitSchemeResult>>(emptyList())
    val plannedSchemes: StateFlow<List<AmapTransitSchemeResult>> = _plannedSchemes.asStateFlow()

    private val _isPlanningRoute = MutableStateFlow(false)
    val isPlanningRoute: StateFlow<Boolean> = _isPlanningRoute.asStateFlow()

    private val _planErrorMessage = MutableStateFlow<String?>(null)
    val planErrorMessage: StateFlow<String?> = _planErrorMessage.asStateFlow()

    /**
     * 用户填的高德 Android 平台 Key（地图渲染 / 搜索 / 定位 SDK），与包名 + SHA-1 绑定；严格分槽，不回落 Web 槽位。
     * 直接跟着仓库走：在「更多」页改了，这里马上是新值。
     */
    val customAmapApiKey: StateFlow<String?> = amapKeys?.androidKey ?: MutableStateFlow(null)

    /** 用户填的高德 Web 服务 Key（restapi.amap.com 公交算路 / 输入提示），不绑定包名 */
    val customAmapWebApiKey: StateFlow<String?> = amapKeys?.webKey ?: MutableStateFlow(null)

    /** 路线页是否画地图（有 Android Key，§2.5）；只有 Web Key 时只出搜索和方案（Q18） */
    val canShowMap: StateFlow<Boolean> = amapKeys?.canShowMap ?: MutableStateFlow(false)

    /**
     * 输入框文字用 Compose 快照状态（同步更新），不走 Flow：输入框的值必须当帧改好，
     * 否则中文输入法组字时光标会跳。Route 读它填进 [RoutePlanUiState]。
     */
    var originText: String by mutableStateOf("")
        private set
    var destText: String by mutableStateOf("")
        private set

    /** 两个输入框选中的提示、提示是否被收起（原来是页面里的 remember） */
    private data class InputState(
        val selectedOrigin: AmapTipItem? = null,
        val selectedDest: AmapTipItem? = null,
        val dismissOriginTips: Boolean = false,
        val dismissDestTips: Boolean = false
    )

    private val input = MutableStateFlow(InputState())
    private val selectedTab = MutableStateFlow(0)
    private val showSaveDialog = MutableStateFlow(false)

    /** 方案标签和它们的方案（地图画这几个）；一次算好，列表实例只在方案变了时换 */
    private data class TabsPart(
        val tabs: List<SchemeTabUi> = emptyList(),
        val schemes: List<AmapTransitSchemeResult> = emptyList()
    )

    /** 方案标签：前 [MAX_VISIBLE_SCHEMES] 个方案（和对齐范围一致）里选最多 3 个 */
    private val tabsPart: StateFlow<TabsPart> = _plannedSchemes
        .map { schemes ->
            val tabs = pickSchemeTabs(schemes.take(MAX_VISIBLE_SCHEMES))
            TabsPart(tabs, tabs.map { it.scheme })
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TabsPart())

    private val _userMessages = MutableStateFlow<List<RoutePlanMessage>>(emptyList())

    /** 一次性提示：页面显示第一条，显示完发 [RoutePlanEvent.MessageShown] */
    val userMessages: StateFlow<List<RoutePlanMessage>> = _userMessages.asStateFlow()
    private var nextMessageId = 1L

    private data class PlanPart(
        val tabs: TabsPart,
        val selectedTab: Int,
        val isPlanning: Boolean,
        val planError: String?,
        val showSaveDialog: Boolean
    )

    private data class MapPart(
        val canShowMap: Boolean,
        val androidKey: String?,
        val userLocationWgs: Pair<Double, Double>?,
        val city: TransitCity
    )

    private val planPart = combine(
        tabsPart,
        selectedTab,
        _isPlanningRoute,
        _planErrorMessage,
        showSaveDialog
    ) { tabs, selected, planning, error, saveDialog ->
        PlanPart(tabs, selected.coerceIn(0, (tabs.tabs.size - 1).coerceAtLeast(0)), planning, error, saveDialog)
    }

    private val mapPart = combine(canShowMap, customAmapApiKey, userLocation, currentCity) { show, key, location, city ->
        MapPart(show, key, location, city)
    }

    private val tipsPart = combine(input, _amapOriginTips, _amapDestTips) { inputState, originTips, destTips ->
        // 和原来一样：终点提示优先；选中了某条提示、被收起或没结果时不显示
        val destVisible = !inputState.dismissDestTips && inputState.selectedDest == null && destTips.isNotEmpty()
        val originVisible = !inputState.dismissOriginTips && inputState.selectedOrigin == null && originTips.isNotEmpty()
        val field = when {
            destVisible -> TipsField.Dest
            originVisible -> TipsField.Origin
            else -> null
        }
        Triple(inputState, field, if (field == TipsField.Dest) destTips else if (field == TipsField.Origin) originTips else emptyList())
    }

    /** 页面状态；输入框文字（[originText] / [destText]）和追踪会话由 Route 填进来 */
    val uiState: StateFlow<RoutePlanUiState> = combine(
        tipsPart,
        planPart,
        mapPart,
        favoriteGroups
    ) { (inputState, tipsField, tips), plan, mapState, groups ->
        RoutePlanUiState(
            tipsField = tipsField,
            tips = tips,
            isPlanning = plan.isPlanning,
            planError = plan.planError,
            schemeTabs = plan.tabs.tabs,
            selectedTab = plan.selectedTab,
            canShowMap = mapState.canShowMap,
            map = RouteMapState(
                schemes = plan.tabs.schemes,
                selectedIndex = plan.selectedTab,
                myLocation = mapState.userLocationWgs?.let { (lat, lng) -> wgs84ToGcj02Point(lat, lng) },
                cityCenter = cityCenterGcj02(mapState.city.cityName),
                androidKey = mapState.androidKey
            ),
            favoriteGroups = groups,
            showSaveDialog = plan.showSaveDialog
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RoutePlanUiState())

    init {
        observeTipsQuery(originTipsQuery, _amapOriginTips)
        observeTipsQuery(destTipsQuery, _amapDestTips)
        // 新的提示到了就重新显示（原来页面里的 LaunchedEffect(originTips / destTips)）
        viewModelScope.launch {
            _amapOriginTips.collect { tips ->
                if (tips.isNotEmpty()) input.update { it.copy(dismissOriginTips = false) }
            }
        }
        viewModelScope.launch {
            _amapDestTips.collect { tips ->
                if (tips.isNotEmpty()) input.update { it.copy(dismissDestTips = false) }
            }
        }
    }

    /** 页面事件（追踪三件事和打开线路由 Route 处理，这里忽略） */
    fun onEvent(event: RoutePlanEvent) {
        when (event) {
            is RoutePlanEvent.OriginChanged -> {
                originText = event.text
                input.update { it.copy(selectedOrigin = null, dismissOriginTips = false) }
                searchOriginTips(event.text)
            }
            is RoutePlanEvent.DestChanged -> {
                destText = event.text
                input.update { it.copy(selectedDest = null, dismissDestTips = false) }
                searchDestTips(event.text)
            }
            RoutePlanEvent.ClearOrigin -> {
                originText = ""
                input.update { it.copy(selectedOrigin = null) }
                searchOriginTips("")
                clearPlanError()
            }
            RoutePlanEvent.ClearDest -> {
                destText = ""
                input.update { it.copy(selectedDest = null) }
                searchDestTips("")
                clearPlanError()
            }
            RoutePlanEvent.SwapOriginDest -> {
                val oldOrigin = originText
                originText = destText
                destText = oldOrigin
                input.update { it.copy(selectedOrigin = it.selectedDest, selectedDest = it.selectedOrigin) }
                submitPlan()
            }
            RoutePlanEvent.Submit -> submitPlan()
            RoutePlanEvent.Retry -> {
                // 和原来一样：起终点都填了才重算
                if (originText.isNotBlank() && destText.isNotBlank()) {
                    val state = input.value
                    planTransitRoute(
                        state.selectedOrigin ?: AmapTipItem(name = originText),
                        state.selectedDest ?: AmapTipItem(name = destText)
                    )
                }
            }
            is RoutePlanEvent.PickTip -> pickTip(event.field, event.tip)
            is RoutePlanEvent.PickMyLocation -> pickTip(event.field, myLocationTip())
            RoutePlanEvent.DismissTips -> dismissTips()
            is RoutePlanEvent.SelectScheme -> selectedTab.value = event.index
            RoutePlanEvent.OpenSaveDialog -> {
                if (currentScheme() != null) showSaveDialog.value = true
            }
            RoutePlanEvent.DismissSaveDialog -> showSaveDialog.value = false
            is RoutePlanEvent.ConfirmSave -> saveCurrentScheme(event.groupId, event.name)
            is RoutePlanEvent.CreateGroup -> createFavoriteGroup(event.name)
            is RoutePlanEvent.MessageShown -> _userMessages.update { list -> list.filterNot { it.id == event.id } }
            is RoutePlanEvent.OpenLine,
            is RoutePlanEvent.StartTracking,
            RoutePlanEvent.ConfirmBoarded,
            RoutePlanEvent.StopTracking -> Unit
        }
    }

    /** 加一条一次性提示（追踪对齐失败等 `RouteDetailViewModel` 的提示也经这里显示） */
    fun postMessage(text: String, action: RoutePlanMessageAction? = null) {
        val message = RoutePlanMessage(id = nextMessageId++, text = text, action = action)
        _userMessages.update { it + message }
    }

    /** 键盘「搜索」/ 规划按钮 / 互换后：终点填了才算；起点空着就从「我的位置」出发 */
    private fun submitPlan() {
        if (destText.isBlank()) return
        val state = input.value
        val origin = if (originText.isBlank()) {
            myLocationTip()
        } else {
            state.selectedOrigin ?: AmapTipItem(name = originText)
        }
        planTransitRoute(origin, state.selectedDest ?: AmapTipItem(name = destText))
    }

    /** 「我的位置」只带名字：算路时再换成实时定位坐标（[resolveMyLocationTip]） */
    private fun myLocationTip(): AmapTipItem = AmapTipItem(name = MY_LOCATION_NAME, location = "")

    /** 点一条提示：填进输入框、收起提示；另一个框也填了就直接算（和原来一样，起点空着时选终点不自动算） */
    private fun pickTip(field: TipsField, tip: AmapTipItem) {
        when (field) {
            TipsField.Dest -> {
                destText = tip.name
                input.update { it.copy(selectedDest = tip, dismissDestTips = true) }
                searchDestTips("")
                if (originText.isNotBlank()) {
                    planTransitRoute(input.value.selectedOrigin ?: AmapTipItem(name = originText), tip)
                }
            }
            TipsField.Origin -> {
                originText = tip.name
                input.update { it.copy(selectedOrigin = tip, dismissOriginTips = true) }
                searchOriginTips("")
                if (destText.isNotBlank()) {
                    planTransitRoute(tip, input.value.selectedDest ?: AmapTipItem(name = destText))
                }
            }
        }
    }

    /** 现在显示哪个框的提示（同步算，不等 [uiState] 下一帧） */
    private fun visibleTipsField(): TipsField? {
        val state = input.value
        return when {
            !state.dismissDestTips && state.selectedDest == null && _amapDestTips.value.isNotEmpty() -> TipsField.Dest
            !state.dismissOriginTips && state.selectedOrigin == null && _amapOriginTips.value.isNotEmpty() -> TipsField.Origin
            else -> null
        }
    }

    /** 选中的方案（同步算） */
    private fun currentScheme(): AmapTransitSchemeResult? =
        pickSchemeTabs(_plannedSchemes.value.take(MAX_VISIBLE_SCHEMES)).getOrNull(selectedTab.value)?.scheme

    /** 返回键：先收起终点提示，再收起起点提示 */
    private fun dismissTips() {
        when (visibleTipsField()) {
            TipsField.Dest -> {
                input.update { it.copy(dismissDestTips = true) }
                searchDestTips("")
            }
            TipsField.Origin -> {
                input.update { it.copy(dismissOriginTips = true) }
                searchOriginTips("")
            }
            null -> Unit
        }
    }

    /** 存进收藏后留在本页，提示「已收藏 · 去看看」（v3：不再自动跳收藏页） */
    private fun saveCurrentScheme(groupId: String?, name: String) {
        val scheme = currentScheme() ?: return
        showSaveDialog.value = false
        viewModelScope.launch {
            groupSaver.saveAsFavorite(scheme.group, groupId, GroupCategory.CUSTOM, name)
            postMessage(SAVED_MESSAGE, RoutePlanMessageAction.GoToFavorites)
        }
    }

    fun createFavoriteGroup(name: String) {
        viewModelScope.launch {
            groupRepository.createFavoriteGroup(name)
        }
    }

    /**
     * 搜索起点关键字候选项（优先高德，失败则智能降级至本地站点搜索）。
     * 只记录关键字；真正的请求经 300ms 防抖、去重后在 [observeTipsQuery] 里发出，旧请求会被取消。
     */
    fun searchOriginTips(keyword: String) {
        val kw = keyword.trim()
        originTipsQuery.value = kw
        if (!isTipsKeywordEligible(kw)) {
            _amapOriginTips.value = emptyList()
        }
    }

    /**
     * 搜索终点关键字候选项（优先高德 SDK，失败则降级至 Web API 或本地站点搜索）
     */
    fun searchDestTips(keyword: String) {
        val kw = keyword.trim()
        destTipsQuery.value = kw
        if (!isTipsKeywordEligible(kw)) {
            _amapDestTips.value = emptyList()
        }
    }

    /**
     * 输入提示管道：debounce → distinctUntilChanged → collectLatest（新关键字到来时取消旧请求）。
     * 结果回来时若输入框已变化则丢弃，避免旧前缀的结果覆盖新结果。
     */
    private fun observeTipsQuery(
        query: MutableStateFlow<String>,
        target: MutableStateFlow<List<AmapTipItem>>
    ) {
        viewModelScope.launch {
            query
                .debounce(TIPS_DEBOUNCE_MS)
                // 同一关键字不重复请求；但若提示已被清空（如关闭后重新输入同一个词），允许再查一次（命中缓存）
                .distinctUntilChanged { old, new -> old == new && target.value.isNotEmpty() }
                .collectLatest { keyword ->
                    if (!isTipsKeywordEligible(keyword)) {
                        target.value = emptyList()
                        return@collectLatest
                    }
                    val tips = routePlan.searchTips(keyword, settings.selectedCity.value, currentGcjLngLat())
                    if (query.value == keyword) {
                        target.value = tips
                    }
                }
        }
    }

    /** 当前设备定位转成高德需要的 GCJ-02 "lng,lat"；无定位时返回 null */
    private fun currentGcjLngLat(): String? {
        val (lat, lng) = userLocationStore.userLocation.value ?: return null
        val (gcjLat, gcjLng) = CoordTransform.wgs84ToGcj02(lat, lng)
        return "$gcjLng,$gcjLat"
    }

    fun clearPlanError() {
        _planErrorMessage.value = null
    }

    /**
     * 执行公交路线规划（高德云端规划 + 实时公交站点直达/换乘双引擎）。
     *
     * - 新的规划会取消上一个仍在进行的规划；
     * - 结果按「城市 + 6 位小数坐标 + 15 分钟时间桶」缓存（LRU 16 条）；
     * - 高德 SDK 成功但无方案时不再用 Web 重复算路，只有 SDK 调用失败才回落 Web；
     * - 起点为「我的位置」但定位不可用时直接报错，不拿这四个字去搜 POI。
     */
    fun planTransitRoute(
        origin: AmapTipItem,
        destination: AmapTipItem,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        planJob?.cancel()
        planJob = viewModelScope.launch {
            _isPlanningRoute.value = true
            _planErrorMessage.value = null
            _plannedSchemes.value = emptyList()

            val city = settings.selectedCity.value
            val hasAmap = routePlan.isConfigured()

            var effectiveOrigin = origin
            var effectiveDest = destination

            // 1. 解析"我的位置"：定位不到就直接提示，不再拿"我的位置"四个字去搜 POI
            val originIsMyLocation = isMyLocationTip(effectiveOrigin)
            val destIsMyLocation = isMyLocationTip(effectiveDest)
            if (originIsMyLocation || destIsMyLocation) {
                val resolved = resolveMyLocationTip(city)
                if (resolved == null) {
                    val role = if (originIsMyLocation) "出发地" else "目的地"
                    _planErrorMessage.value = "定位不可用，无法以【$MY_LOCATION_NAME】作为$role。请开启定位后重试，或手动输入$role。"
                    _isPlanningRoute.value = false
                    onComplete?.invoke(false)
                    return@launch
                }
                if (originIsMyLocation) effectiveOrigin = resolved
                if (destIsMyLocation) effectiveDest = resolved
            }

            // 2. 补全缺少经纬度的起终点坐标（高德 SDK 成功但无结果时同样不再走 Web）
            effectiveOrigin = routePlan.resolveLocation(effectiveOrigin, city, currentGcjLngLat(), useAmap = hasAmap)
            effectiveDest = routePlan.resolveLocation(effectiveDest, city, currentGcjLngLat(), useAmap = hasAmap)

            // 3. 缓存 → 高德算路 + 对齐 → 本地兜底（在仓库里）
            val schemes = routePlan.planTransit(effectiveOrigin, effectiveDest, city, useAmap = hasAmap)

            if (schemes.isNotEmpty()) {
                // 新的规划结果回到第一个标签
                selectedTab.value = 0
                _plannedSchemes.value = schemes
                onComplete?.invoke(true)
            } else {
                _planErrorMessage.value = if (!hasAmap) {
                    "未查到经过【${effectiveOrigin.name}】与【${effectiveDest.name}】的直达或换乘公交。\n试试输入具体的公交站名；在「我的」里填上高德 Key，就能按地名和地铁规划。"
                } else {
                    "在当前城市未找到合适的公交换乘方案，请尝试更换更精准的站名或地名。"
                }
                onComplete?.invoke(false)
            }

            _isPlanningRoute.value = false
        }
    }

    private fun isMyLocationTip(tip: AmapTipItem): Boolean =
        tip.name == MY_LOCATION_NAME || tip.name.isBlank()

    /**
     * 把"我的位置"解析为带 GCJ-02 坐标的起点；定位不可用时返回 null。
     * 优先用首页定位链路写入的坐标，其次是系统 lastKnown（需有权限且不超过 10 分钟）。
     */
    private suspend fun resolveMyLocationTip(city: TransitCity): AmapTipItem? {
        val (lat, lng) = userLocationStore.userLocation.value
            ?: locationRepository?.lastKnown(MAX_LAST_KNOWN_AGE_MS)?.let { it.lat to it.lng }
            ?: return null
        val nearby = runCatching {
            realtime.getNearbyStops(city, lat, lng, 1).getOrNull()?.firstOrNull()
        }.getOrNull()
        // 系统定位是 WGS-84，高德算路要求 GCJ-02，必须显式转换后再拼参数
        val (originGcjLat, originGcjLng) = CoordTransform.wgs84ToGcj02(lat, lng)
        return if (nearby != null) {
            AmapTipItem(
                name = "$MY_LOCATION_NAME(${nearby.name})",
                district = city.cityName,
                address = nearby.name,
                location = "$originGcjLng,$originGcjLat"
            )
        } else {
            AmapTipItem(
                name = MY_LOCATION_NAME,
                district = city.cityName,
                address = "当前定位",
                location = "$originGcjLng,$originGcjLat"
            )
        }
    }

    /**
     * 将规划方案转存为正式收藏分组；没选分组时回落到收藏页当前选中的分组（和原来一样）
     */
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

    fun addPlannedSchemeAsFavorite(
        group: TransitGroup,
        customGroupId: String? = null,
        category: GroupCategory = GroupCategory.CUSTOM,
        customName: String? = null
    ) {
        viewModelScope.launch {
            groupSaver.saveAsFavorite(group, customGroupId, category, customName)
        }
    }

    fun clearPlanning() {
        planJob?.cancel()
        _isPlanningRoute.value = false
        originTipsQuery.value = ""
        destTipsQuery.value = ""
        _plannedSchemes.value = emptyList()
        _amapOriginTips.value = emptyList()
        _amapDestTips.value = emptyList()
        _planErrorMessage.value = null
        originText = ""
        destText = ""
        input.value = InputState()
        selectedTab.value = 0
        showSaveDialog.value = false
    }
}
