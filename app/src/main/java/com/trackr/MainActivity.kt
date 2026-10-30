package com.trackr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.TransitCodeProvider
import com.trackr.ui.navigation.PendingDeepLink
import com.trackr.ui.navigation.ScreenKey
import com.trackr.ui.navigation.dropUnlessResumed
import com.trackr.ui.screens.home.HomeScreen
import com.trackr.ui.screens.home.HomeViewModel
import com.trackr.ui.screens.linedetail.LineDetailScreen
import com.trackr.ui.screens.linedetail.LineDetailViewModel
import com.trackr.ui.screens.more.AboutScreen
import com.trackr.ui.screens.more.MoreScreen
import com.trackr.ui.screens.more.MoreViewModel
import com.trackr.ui.screens.search.SearchScreen
import com.trackr.ui.screens.stopdetail.StopDetailScreen
import com.trackr.ui.screens.stopdetail.StopDetailViewModel
import com.trackr.ui.theme.TrackrTheme
import com.trackr.util.TransitCodeHelper
import com.trackr.widget.BusStopWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var pendingDeepLink by mutableStateOf<PendingDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        handleTransitCodeIntent(intent)

        // 仅在首次启动时消费 Deep Link（避免旋转屏幕等配置变更时重复追加到栈顶）
        if (savedInstanceState == null) {
            extractAndConsumeDeepLink(intent)
        }

        setContent {
            TrackrTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TrackrNavApp(
                        pendingDeepLink = pendingDeepLink,
                        onDeepLinkConsumed = { pendingDeepLink = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTransitCodeIntent(intent)
        extractAndConsumeDeepLink(intent)
    }

    private fun extractAndConsumeDeepLink(intent: Intent?) {
        if (intent == null) return
        val cityId = intent.getStringExtra("extra_city_id")
        val lineId = intent.getStringExtra("extra_line_id")
        if (!cityId.isNullOrBlank() && !lineId.isNullOrBlank()) {
            pendingDeepLink = PendingDeepLink(
                cityId = cityId,
                lineId = lineId,
                targetOrder = intent.getIntExtra("extra_target_order", 0),
                stopId = intent.getStringExtra("extra_stop_id").orEmpty(),
                sourceName = intent.getStringExtra("extra_source").orEmpty(),
                lineName = intent.getStringExtra("extra_line_name").orEmpty()
            )
            // 一次性消费：移除 Intent 中的 Deep Link 参数，杜绝后续重放
            intent.removeExtra("extra_city_id")
            intent.removeExtra("extra_line_id")
            intent.removeExtra("extra_target_order")
            intent.removeExtra("extra_stop_id")
            intent.removeExtra("extra_source")
            intent.removeExtra("extra_line_name")
        }
    }

    private fun handleTransitCodeIntent(intent: Intent?) {
        if (intent?.action == "com.trackr.action.OPEN_TRANSIT_CODE" ||
            intent?.action == "com.github.u5151561.trackr.action.OPEN_TRANSIT_CODE"
        ) {
            val providerStr = intent.getStringExtra("extra_provider")
            val provider = if (providerStr == "alipay") TransitCodeProvider.ALIPAY else TransitCodeProvider.UNIONPAY
            TransitCodeHelper.openTransitCode(this, provider)
            intent.removeExtra("extra_provider")
            intent.action = null
        }
    }
}

@Composable
fun TrackrNavApp(
    pendingDeepLink: PendingDeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val homeBackStack = rememberNavBackStack(ScreenKey.Home)
    val moreBackStack = rememberNavBackStack(ScreenKey.More)
    var selectedTab by rememberSaveable { mutableStateOf("home") }

    val activeBackStack = if (selectedTab == "home") homeBackStack else moreBackStack
    val homeViewModel: HomeViewModel = viewModel()

    // 自动同步收藏到桌面快捷方式 (App Shortcuts) 与桌面小组件 (AppWidget)，切离主线程调度
    val favorites by TrackrApp.instance.repository.favorites.collectAsStateWithLifecycle()
    LaunchedEffect(favorites) {
        withContext(Dispatchers.IO) {
            BusStopWidgetProvider.updateDynamicShortcuts(context, favorites)
            BusStopWidgetProvider.triggerRefresh(context)
        }
    }

    // 申请定位与通知权限
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) {
            homeViewModel.refreshLocationAndNearby()
        }
    }

    var showLocationDisclosureDialog by rememberSaveable { mutableStateOf(false) }

    val needsLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    LaunchedEffect(Unit) {
        if (needsLocation) {
            showLocationDisclosureDialog = true
        } else if (needsNotification) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    if (showLocationDisclosureDialog) {
        AlertDialog(
            onDismissRequest = {
                showLocationDisclosureDialog = false
                if (needsNotification) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.DirectionsBus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "位置信息权限说明",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = "为了向您展示附近的公交站点、候车站距离以及途经线路的实时到站动态，追客需要在您使用应用期间获取设备的大致或精准位置信息。\n\n我们承诺：位置信息仅在本地用于附近公交站点匹配，不会用于追踪个人身份或与任何第三方共享。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLocationDisclosureDialog = false
                        val needed = mutableListOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                        if (needsNotification) {
                            needed.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissionLauncher.launch(needed.toTypedArray())
                    }
                ) {
                    Text("同意并授权")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showLocationDisclosureDialog = false
                        if (needsNotification) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    }
                ) {
                    Text("暂不授权")
                }
            }
        )
    }

    // 处理来自桌面小组件、快捷方式或通知栏的直接跳转（一次性消费，防止重复入栈）
    LaunchedEffect(pendingDeepLink) {
        val deepLink = pendingDeepLink ?: return@LaunchedEffect
        val source = runCatching { DataSourceType.valueOf(deepLink.sourceName) }
            .getOrDefault(DataSourceType.CHELAILE)
        val lineDetailKey = ScreenKey.LineDetail(
            cityId = deepLink.cityId,
            lineId = deepLink.lineId,
            targetOrder = deepLink.targetOrder,
            stopId = deepLink.stopId,
            source = source,
            lineName = deepLink.lineName
        )
        selectedTab = "home"
        if (homeBackStack.lastOrNull() != lineDetailKey) {
            homeBackStack.add(lineDetailKey)
        }
        onDeepLinkConsumed()
    }

    val currentKey = activeBackStack.lastOrNull()
    val isTopLevel = (selectedTab == "home" && currentKey is ScreenKey.Home) ||
        (selectedTab == "more" && currentKey is ScreenKey.More)

    // 控制滑动时底部导航栏平滑收起与展开
    var isNavigationBarVisible by rememberSaveable { mutableStateOf(true) }

    // 切换 Tab 或从子页面返回根页面时，确保导航栏重置为显示状态
    LaunchedEffect(isTopLevel, selectedTab) {
        if (isTopLevel) {
            isNavigationBarVisible = true
        }
    }

    val nestedScrollConnection = remember(isTopLevel) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (isTopLevel) {
                    // 手指向上推，浏览下方更多内容 -> 向下滑出隐藏导航栏
                    if (consumed.y < -8f) {
                        isNavigationBarVisible = false
                    }
                    // 手指向下拉，回看上方内容或在顶部下拉 -> 向上滑入显示导航栏
                    else if (consumed.y > 8f || available.y > 8f) {
                        isNavigationBarVisible = true
                    }
                }
                return Offset.Zero
            }
        }
    }

    val navBarTranslationY by animateFloatAsState(
        targetValue = if (isNavigationBarVisible) 0f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "navBarTranslationY"
    )

    val saveableDecorator = rememberSaveableStateHolderNavEntryDecorator<androidx.navigation3.runtime.NavKey>()
    val viewModelStoreDecorator = rememberViewModelStoreNavEntryDecorator<androidx.navigation3.runtime.NavKey>()
    val entryDecorators = remember(saveableDecorator, viewModelStoreDecorator) {
        listOf(saveableDecorator, viewModelStoreDecorator)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(nestedScrollConnection),
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            AnimatedVisibility(
                visible = isTopLevel,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(280)
                ) + fadeIn(animationSpec = tween(200)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(280)
                ) + fadeOut(animationSpec = tween(200))
            ) {
                NavigationBar(
                    modifier = Modifier.graphicsLayer {
                        translationY = navBarTranslationY * size.height
                    }
                ) {
                    NavigationBarItem(
                        selected = selectedTab == "home",
                        onClick = dropUnlessResumed {
                            if (selectedTab == "home") {
                                // 点击当前已处于活跃状态的 Tab 时，平滑返回该 Tab 的根目录
                                while (homeBackStack.size > 1) {
                                    homeBackStack.removeLastOrNull()
                                }
                            } else {
                                selectedTab = "home"
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.DirectionsBus,
                                contentDescription = "首页"
                            )
                        },
                        label = { Text("首页") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == "more",
                        onClick = dropUnlessResumed {
                            if (selectedTab == "more") {
                                // 点击当前已处于活跃状态的 Tab 时，平滑返回该 Tab 的根目录
                                while (moreBackStack.size > 1) {
                                    moreBackStack.removeLastOrNull()
                                }
                            } else {
                                selectedTab = "more"
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "更多"
                            )
                        },
                        label = { Text("更多") }
                    )
                }
            }
        }
    ) { _ ->
        NavDisplay(
            backStack = activeBackStack,
            onBack = {
                if (activeBackStack.size > 1) {
                    activeBackStack.removeLastOrNull()
                } else if (selectedTab != "home") {
                    selectedTab = "home"
                }
            },
            entryDecorators = entryDecorators,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val isTopLevelSwitch = (initialState.key is ScreenKey.Home && targetState.key is ScreenKey.More) ||
                    (initialState.key is ScreenKey.More && targetState.key is ScreenKey.Home)
                if (isTopLevelSwitch) {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                } else {
                    (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300))) togetherWith
                        (slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)))
                }
            },
            popTransitionSpec = {
                val isTopLevelSwitch = (initialState.key is ScreenKey.Home && targetState.key is ScreenKey.More) ||
                    (initialState.key is ScreenKey.More && targetState.key is ScreenKey.Home)
                if (isTopLevelSwitch) {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                } else {
                    (slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300))) togetherWith
                        (slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)))
                }
            },
            predictivePopTransitionSpec = { _ ->
                (slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300))) togetherWith
                    (slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)))
            },
            entryProvider = entryProvider {
                entry<ScreenKey.Home> {
                    HomeScreen(
                        viewModel = homeViewModel,
                        onNavigateToLineDetail = dropUnlessResumed { cityId, lineId, targetOrder, stopId, source, lineName ->
                            val key = ScreenKey.LineDetail(
                                cityId = cityId,
                                lineId = lineId,
                                targetOrder = targetOrder,
                                stopId = stopId,
                                source = source,
                                lineName = lineName
                            )
                            if (activeBackStack.lastOrNull() != key) {
                                activeBackStack.add(key)
                            }
                        },
                        onNavigateToStopDetail = dropUnlessResumed { cityId, stopId, stopName, physicalStId, namesakeStId, lat, lng ->
                            val key = ScreenKey.StopDetail(
                                cityId = cityId,
                                stopId = stopId,
                                stopName = stopName,
                                physicalStId = physicalStId,
                                namesakeStId = namesakeStId,
                                lat = lat,
                                lng = lng
                            )
                            if (activeBackStack.lastOrNull() != key) {
                                activeBackStack.add(key)
                            }
                        },
                        onNavigateToSearch = dropUnlessResumed {
                            if (activeBackStack.lastOrNull() != ScreenKey.Search) {
                                activeBackStack.add(ScreenKey.Search)
                            }
                        }
                    )
                }

                entry<ScreenKey.Search> {
                    SearchScreen(
                        viewModel = homeViewModel,
                        onNavigateBack = dropUnlessResumed {
                            if (activeBackStack.size > 1) {
                                activeBackStack.removeLastOrNull()
                            }
                        },
                        onNavigateToLineDetail = dropUnlessResumed { cityId, lineId, targetOrder, stopId, source, lineName ->
                            val key = ScreenKey.LineDetail(
                                cityId = cityId,
                                lineId = lineId,
                                targetOrder = targetOrder,
                                stopId = stopId,
                                source = source,
                                lineName = lineName
                            )
                            if (activeBackStack.lastOrNull() != key) {
                                activeBackStack.add(key)
                            }
                        },
                        onNavigateToStopDetail = dropUnlessResumed { cityId, stopId, stopName, physicalStId, namesakeStId, lat, lng ->
                            val key = ScreenKey.StopDetail(
                                cityId = cityId,
                                stopId = stopId,
                                stopName = stopName,
                                physicalStId = physicalStId,
                                namesakeStId = namesakeStId,
                                lat = lat,
                                lng = lng
                            )
                            if (activeBackStack.lastOrNull() != key) {
                                activeBackStack.add(key)
                            }
                        }
                    )
                }

                entry<ScreenKey.LineDetail> { key ->
                    val detailViewModel: LineDetailViewModel = viewModel(
                        key = "detail_${key.cityId}_${key.lineId}_${key.targetOrder}",
                        factory = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                return LineDetailViewModel(
                                    cityId = key.cityId,
                                    initialLineId = key.lineId,
                                    initialTargetOrder = key.targetOrder.takeIf { it > 0 },
                                    initialStationId = key.stopId.takeIf { it.isNotBlank() },
                                    initialSource = key.source,
                                    lineNameHint = key.lineName
                                ) as T
                            }
                        }
                    )

                    LineDetailScreen(
                        viewModel = detailViewModel,
                        onNavigateBack = dropUnlessResumed {
                            if (activeBackStack.size > 1) {
                                activeBackStack.removeLastOrNull()
                            }
                        }
                    )
                }

                entry<ScreenKey.StopDetail> { key ->
                    val stopDetailViewModel: StopDetailViewModel = viewModel(
                        key = "stop_${key.cityId}_${key.stopId}_${key.physicalStId}_${key.stopName}",
                        factory = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                return StopDetailViewModel(
                                    cityId = key.cityId,
                                    stopId = key.stopId,
                                    initialStopName = key.stopName,
                                    physicalStId = key.physicalStId,
                                    namesakeStId = key.namesakeStId,
                                    stationLat = key.lat,
                                    stationLng = key.lng
                                ) as T
                            }
                        }
                    )

                    StopDetailScreen(
                        viewModel = stopDetailViewModel,
                        onNavigateBack = dropUnlessResumed {
                            if (activeBackStack.size > 1) {
                                activeBackStack.removeLastOrNull()
                            }
                        },
                        onNavigateToLineDetail = dropUnlessResumed { cityId, lineId, targetOrder, stopId, source, lineName ->
                            val lineDetailKey = ScreenKey.LineDetail(
                                cityId = cityId,
                                lineId = lineId,
                                targetOrder = targetOrder,
                                stopId = stopId,
                                source = source,
                                lineName = lineName
                            )
                            if (activeBackStack.lastOrNull() != lineDetailKey) {
                                activeBackStack.add(lineDetailKey)
                            }
                        }
                    )
                }

                entry<ScreenKey.More> {
                    val moreViewModel: MoreViewModel = viewModel()
                    MoreScreen(
                        viewModel = moreViewModel,
                        onNavigateToAbout = dropUnlessResumed {
                            if (activeBackStack.lastOrNull() != ScreenKey.About) {
                                activeBackStack.add(ScreenKey.About)
                            }
                        },
                        onNavigateToLineDetail = dropUnlessResumed { cityId, lineId, targetOrder, stopId, source, lineName ->
                            val lineDetailKey = ScreenKey.LineDetail(
                                cityId = cityId,
                                lineId = lineId,
                                targetOrder = targetOrder,
                                stopId = stopId,
                                source = source,
                                lineName = lineName
                            )
                            if (activeBackStack.lastOrNull() != lineDetailKey) {
                                activeBackStack.add(lineDetailKey)
                            }
                        }
                    )
                }

                entry<ScreenKey.About> {
                    AboutScreen(
                        onNavigateBack = dropUnlessResumed {
                            if (activeBackStack.size > 1) {
                                activeBackStack.removeLastOrNull()
                            }
                        }
                    )
                }
            }
        )
    }
}
