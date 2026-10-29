# 测试策略与指南

本项目测试涵盖核心算法、ViewModel 业务逻辑、Navigation 3 路由、Compose 界面行为与 JaCoCo 代码覆盖率。

---

## 1. 测试架构概览

- 单元测试框架：JUnit 4 (`junit:junit:4.13.2`), `kotlinx-coroutines-test:1.10.2`
- 无头环境与平台测试：Robolectric (`org.robolectric:robolectric:4.14.1`)，使用预置 `android-all-14-robolectric-10818077` 离线桩
- Compose UI 测试：`androidx.compose.ui:ui-test-junit4`, `androidx.compose.ui:ui-test-manifest`
- 协程与数据流测试：`MainDispatcherRule` (基于 `StandardTestDispatcher` 与 `Dispatchers.setMain`), `Turbine`
- 代码覆盖率：JaCoCo Gradle Plugin (`jacocoTestReport`)

---

## 2. 内存隔离机制 (FakeTransitRepository)

单元测试不发起真实网络请求，也不依赖本地存储。测试套件通过接口 `ITransitRepository` 注入 `FakeTransitRepository`（位于 `app/src/test/java/com/trackr/data/repository/FakeTransitRepository.kt`）。

支持配置以下模拟响应：
- `supportedCitiesResult`：城市列表
- `stopDetailResult`：站点详情与多站台途经线路
- `lineDetailResult`：线路在途公交与反向线路
- `searchResult`：线路与站点混合搜索
- `favorites`：收藏夹变更

### 协程后台轮询参数化

为避免 ViewModel 中 `while(isActive) { delay(...) }` 轮询导致协程测试调度器死循环，所有带后台轮询的 ViewModel 均提供 `enableAutoPolling: Boolean = true` 构造参数：
- 生产环境：使用默认值 `true`，执行 10s 或 15s 轮询。
- 测试环境：传入 `enableAutoPolling = false`，通过 `testScheduler.runCurrent()` 或 `testScheduler.advanceTimeBy(ms)` 控制执行时序。

---

## 3. 测试目录与套件清单

| 测试类 | 覆盖范围 | 运行方式 |
| :--- | :--- | :--- |
| `TransitUnitTest` | 核心算法（`RouteProjectionMath` 坐标插值与站名纠错、`ChelaileCrypto` 加解密与签名） | 本地 JVM |
| `NavigationKeyTest` | Navigation 3 `ScreenKey` 序列化与反序列化 | 本地 JVM |
| `NavigationFlowTest` | 导航栈后退拦截、深度链接跳转与栈顶去重 | 本地 JVM |
| `HomeViewModelTest` | 首页城市切换、城市汉字与拼音前缀过滤、搜索防抖、收藏状态监听 | Robolectric (JVM) |
| `LineDetailViewModelTest` | 线路详情加载、站点选择、单车行程跟踪、走向条横纵向切换、收藏切换 | 本地 JVM |
| `StopDetailViewModelTest` | 站点详情物理站台切换、途经线路实时 ETA、收藏增删 | 本地 JVM |
| `ExpressiveRouteRailUiTest` | 经典横向走向条与纵向走向条 Compose UI 渲染、逐字竖排与点击事件响应 | Robolectric Compose UI |

---

## 4. 常用测试执行命令

### 运行全量测试

```bash
./gradlew test
```

### 运行特定 ViewModel 测试

```bash
./gradlew testDebugUnitTest --tests "com.trackr.ui.screens.linedetail.LineDetailViewModelTest"
./gradlew testDebugUnitTest --tests "com.trackr.ui.screens.home.HomeViewModelTest"
```

### 运行 Compose UI 测试

```bash
./gradlew testDebugUnitTest --tests "com.trackr.ui.components.ExpressiveRouteRailUiTest"
```

### 生成 JaCoCo 代码覆盖率报告

```bash
./gradlew jacocoTestReport
```

报告输出路径：
- JaCoCo HTML：`app/build/reports/jacoco/jacocoTestReport/html/index.html`
- 单元测试 HTML：`app/build/reports/tests/testDebugUnitTest/index.html`
