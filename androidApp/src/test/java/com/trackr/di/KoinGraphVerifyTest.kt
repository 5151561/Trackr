package com.trackr.di

import android.app.Application
import android.content.Context
import com.trackr.data.provider.ITransitProvider
import com.trackr.domain.model.DataSourceType
import com.trackr.ui.navigation.ScreenKey
import com.trackr.ui.screens.linedetail.LineDetailViewModel
import com.trackr.ui.screens.stopdetail.StopDetailViewModel
import com.trackr.util.MainDispatcherRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.core.parameter.ParametersHolder
import org.koin.core.parameter.emptyParametersHolder
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.test.verify.verify
import org.robolectric.RobolectricTestRunner
import kotlin.reflect.KClass

/**
 * Koin 没有编译期检查，缺绑定只会在运行时崩（计划 R4）。这里两层把关：
 * 1. 静态：对 [appModules] 里每个模块跑 `verify()`，检查每个定义的构造参数都有绑定；
 * 2. 运行时：在 Robolectric 里用 App 启动时装好的真实 Koin 图，把每个模块的每个定义都实际取一次。
 */
@RunWith(RobolectricTestRunner::class)
class KoinGraphVerifyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Koin 不管、由 Android 或调用方提供的类型 */
    private val extraTypes: List<KClass<*>> = listOf(
        Context::class,
        Application::class,
        OkHttpClient.Builder::class,
        // verify 会检查所有公开构造函数：TransitProviderCoordinator 主构造函数收 Map，次构造函数收两个 ITransitProvider。
        // 第 3 步它仍由 TrackrApp 构建，Koin 只拿现成实例
        Map::class,
        ITransitProvider::class
    )

    /** 静态校验某个模块：别的模块的定义也算可用绑定 */
    private fun verifyModule(target: Module) {
        val others = appModules.filter { it !== target }
        module { includes(listOf(target) + others) }.verify(extraTypes = extraTypes)
    }

    @Test
    fun appModule_verify() = verifyModule(appModule)

    @Test
    fun viewModelModule_verify() = verifyModule(viewModelModule)

    @Test
    fun appModules_containsEveryModuleUnderTest() {
        // 新加模块时要在上面补一条 verify 用例，这里提醒一下
        assertEquals(listOf(appModule, viewModelModule), appModules)
    }

    /** 带参数的定义：按类型给一份样例参数 */
    private fun parametersFor(type: KClass<*>): ParametersHolder = when (type) {
        LineDetailViewModel::class -> parametersOf(
            ScreenKey.LineDetail(
                cityId = "034",
                lineId = "line-1",
                targetOrder = 3,
                stopId = "stop-3",
                source = DataSourceType.CHELAILE,
                lineName = "1路"
            )
        )
        StopDetailViewModel::class -> parametersOf(
            ScreenKey.StopDetail(
                cityId = "034",
                stopId = "stop-3",
                stopName = "人民广场",
                physicalStId = "",
                namesakeStId = "",
                lat = 31.23,
                lng = 121.47
            )
        )
        else -> emptyParametersHolder()
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun everyDefinition_resolvesAtRuntime() {
        val koin = GlobalContext.get()
        val definitions = appModules.flatMap { it.mappings.values }.map { it.beanDefinition }.distinct()
        assertTrue("应至少有一个定义", definitions.isNotEmpty())

        for (definition in definitions) {
            val type = definition.primaryType
            val instance: Any? = koin.getOrNull(type, definition.qualifier) { parametersFor(type) }
            assertNotNull("取不到 ${type.qualifiedName}（qualifier=${definition.qualifier}）", instance)
            assertTrue(
                "${type.qualifiedName} 取到的实例类型不对：${instance!!::class.qualifiedName}",
                type.isInstance(instance)
            )
        }
    }
}
