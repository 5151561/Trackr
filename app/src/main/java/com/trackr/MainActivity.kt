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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.trackr.domain.model.DataSourceType
import com.trackr.ui.navigation.ScreenKey
import com.trackr.ui.screens.home.HomeScreen
import com.trackr.ui.screens.home.HomeViewModel
import com.trackr.ui.screens.linedetail.LineDetailScreen
import com.trackr.ui.screens.linedetail.LineDetailViewModel
import com.trackr.ui.screens.search.SearchScreen
import com.trackr.ui.theme.TrackrTheme
import com.trackr.widget.BusStopWidgetProvider

class MainActivity : ComponentActivity() {

    private var currentIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        currentIntent = intent

        setContent {
            TrackrTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val activeIntent = currentIntent
                    val cityId = activeIntent?.getStringExtra("extra_city_id")
                    val lineId = activeIntent?.getStringExtra("extra_line_id")
                    val targetOrder = activeIntent?.getIntExtra("extra_target_order", 0) ?: 0
                    val stopId = activeIntent?.getStringExtra("extra_stop_id").orEmpty()
                    val sourceName = activeIntent?.getStringExtra("extra_source").orEmpty()
                    val lineName = activeIntent?.getStringExtra("extra_line_name").orEmpty()

                    TrackrNavApp(
                        deepLinkCityId = cityId,
                        deepLinkLineId = lineId,
                        deepLinkTargetOrder = targetOrder,
                        deepLinkStopId = stopId,
                        deepLinkSourceName = sourceName,
                        deepLinkLineName = lineName
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntent = intent
    }
}

@Composable
fun TrackrNavApp(
    deepLinkCityId: String? = null,
    deepLinkLineId: String? = null,
    deepLinkTargetOrder: Int = 0,
    deepLinkStopId: String = "",
    deepLinkSourceName: String = "",
    deepLinkLineName: String = ""
) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack(ScreenKey.Home)
    val homeViewModel: HomeViewModel = viewModel()

    // 自动同步收藏到桌面快捷方式 (App Shortcuts) 与桌面小组件 (AppWidget)
    val favorites by TrackrApp.instance.repository.favorites.collectAsStateWithLifecycle()
    LaunchedEffect(favorites) {
        BusStopWidgetProvider.updateDynamicShortcuts(context, favorites)
        BusStopWidgetProvider.triggerRefresh(context)
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

    LaunchedEffect(Unit) {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // 处理来自桌面小组件、快捷方式或通知栏的直接跳转
    LaunchedEffect(deepLinkCityId, deepLinkLineId, deepLinkTargetOrder, deepLinkStopId, deepLinkSourceName, deepLinkLineName) {
        if (!deepLinkCityId.isNullOrBlank() && !deepLinkLineId.isNullOrBlank()) {
            val source = runCatching { DataSourceType.valueOf(deepLinkSourceName) }
                .getOrDefault(DataSourceType.CHELAILE)
            val lineDetailKey = ScreenKey.LineDetail(
                cityId = deepLinkCityId,
                lineId = deepLinkLineId,
                targetOrder = deepLinkTargetOrder,
                stopId = deepLinkStopId,
                source = source,
                lineName = deepLinkLineName
            )
            if (backStack.lastOrNull() != lineDetailKey) {
                backStack.add(lineDetailKey)
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = entryProvider {
            entry<ScreenKey.Home> {
                HomeScreen(
                    viewModel = homeViewModel,
                    onNavigateToLineDetail = { cityId, lineId, targetOrder, stopId, source, lineName ->
                        backStack.add(
                            ScreenKey.LineDetail(
                                cityId = cityId,
                                lineId = lineId,
                                targetOrder = targetOrder,
                                stopId = stopId,
                                source = source,
                                lineName = lineName
                            )
                        )
                    },
                    onNavigateToSearch = {
                        backStack.add(ScreenKey.Search)
                    }
                )
            }

            entry<ScreenKey.Search> {
                SearchScreen(
                    viewModel = homeViewModel,
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onNavigateToLineDetail = { cityId, lineId, targetOrder, stopId, source, lineName ->
                        backStack.add(
                            ScreenKey.LineDetail(
                                cityId = cityId,
                                lineId = lineId,
                                targetOrder = targetOrder,
                                stopId = stopId,
                                source = source,
                                lineName = lineName
                            )
                        )
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
                    onNavigateBack = { backStack.removeLastOrNull() }
                )
            }
        }
    )
}
