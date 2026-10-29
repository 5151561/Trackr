package com.trackr

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trackr.domain.model.DataSourceType
import com.trackr.ui.screens.home.HomeScreen
import com.trackr.ui.screens.home.HomeViewModel
import com.trackr.ui.screens.linedetail.LineDetailScreen
import com.trackr.ui.screens.linedetail.LineDetailViewModel
import com.trackr.ui.screens.search.SearchScreen
import com.trackr.ui.theme.TrackrTheme
import com.trackr.widget.BusStopWidgetProvider

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val initialCityId = intent?.getStringExtra("extra_city_id")
        val initialLineId = intent?.getStringExtra("extra_line_id")
        val initialTargetOrder = intent?.getIntExtra("extra_target_order", 0) ?: 0
        val initialStopId = intent?.getStringExtra("extra_stop_id").orEmpty()
        val initialSourceName = intent?.getStringExtra("extra_source").orEmpty()
        val initialLineName = intent?.getStringExtra("extra_line_name").orEmpty()

        setContent {
            TrackrTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TrackrNavApp(
                        deepLinkCityId = initialCityId,
                        deepLinkLineId = initialLineId,
                        deepLinkTargetOrder = initialTargetOrder,
                        deepLinkStopId = initialStopId,
                        deepLinkSourceName = initialSourceName,
                        deepLinkLineName = initialLineName
                    )
                }
            }
        }
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
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel()

    // 自动同步收藏到桌面快捷方式 (App Shortcuts)
    val favorites by TrackrApp.instance.repository.favorites.collectAsState()
    LaunchedEffect(favorites) {
        BusStopWidgetProvider.updateDynamicShortcuts(context, favorites)
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
    LaunchedEffect(deepLinkCityId, deepLinkLineId) {
        if (!deepLinkCityId.isNullOrBlank() && !deepLinkLineId.isNullOrBlank()) {
            val route = buildLineDetailRoute(
                cityId = deepLinkCityId,
                lineId = deepLinkLineId,
                targetOrder = deepLinkTargetOrder,
                stopId = deepLinkStopId,
                source = deepLinkSourceName.ifBlank { DataSourceType.CHELAILE.name },
                lineName = deepLinkLineName
            )
            navController.navigate(route)
        }
    }

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToLineDetail = { cityId, lineId, targetOrder, stopId, source, lineName ->
                    navController.navigate(
                        buildLineDetailRoute(
                            cityId = cityId,
                            lineId = lineId,
                            targetOrder = targetOrder,
                            stopId = stopId,
                            source = source.name,
                            lineName = lineName
                        )
                    )
                },
                onNavigateToSearch = {
                    navController.navigate("search")
                }
            )
        }

        composable("search") {
            SearchScreen(
                viewModel = homeViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToLineDetail = { cityId, lineId, targetOrder, stopId, source, lineName ->
                    navController.navigate(
                        buildLineDetailRoute(
                            cityId = cityId,
                            lineId = lineId,
                            targetOrder = targetOrder,
                            stopId = stopId,
                            source = source.name,
                            lineName = lineName
                        )
                    )
                }
            )
        }

        composable(
            route = "line_detail?cityId={cityId}&lineId={lineId}&targetOrder={targetOrder}&stopId={stopId}&source={source}&lineName={lineName}",
            arguments = listOf(
                navArgument("cityId") { type = NavType.StringType; defaultValue = "034" },
                navArgument("lineId") { type = NavType.StringType; defaultValue = "" },
                navArgument("targetOrder") { type = NavType.IntType; defaultValue = 0 },
                navArgument("stopId") { type = NavType.StringType; defaultValue = "" },
                navArgument("source") { type = NavType.StringType; defaultValue = "CHELAILE" },
                navArgument("lineName") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val cityId = backStackEntry.arguments?.getString("cityId") ?: "034"
            val lineId = Uri.decode(backStackEntry.arguments?.getString("lineId").orEmpty())
            val targetOrder = backStackEntry.arguments?.getInt("targetOrder") ?: 0
            val stopId = Uri.decode(backStackEntry.arguments?.getString("stopId").orEmpty())
            val sourceStr = backStackEntry.arguments?.getString("source") ?: "CHELAILE"
            val lineName = Uri.decode(backStackEntry.arguments?.getString("lineName").orEmpty())
            val source = runCatching { DataSourceType.valueOf(sourceStr) }.getOrDefault(DataSourceType.CHELAILE)

            val detailViewModel: LineDetailViewModel = viewModel(
                key = "detail_${cityId}_${lineId}_${targetOrder}",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return LineDetailViewModel(
                            cityId = cityId,
                            initialLineId = lineId,
                            initialTargetOrder = targetOrder.takeIf { it > 0 },
                            initialStationId = stopId.takeIf { it.isNotBlank() },
                            initialSource = source,
                            lineNameHint = lineName
                        ) as T
                    }
                }
            )

            LineDetailScreen(
                viewModel = detailViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

private fun buildLineDetailRoute(
    cityId: String,
    lineId: String,
    targetOrder: Int,
    stopId: String,
    source: String,
    lineName: String
): String {
    return "line_detail?cityId=${Uri.encode(cityId)}" +
        "&lineId=${Uri.encode(lineId)}" +
        "&targetOrder=$targetOrder" +
        "&stopId=${Uri.encode(stopId)}" +
        "&source=${Uri.encode(source)}" +
        "&lineName=${Uri.encode(lineName)}"
}
