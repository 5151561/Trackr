# 测试策略与指南 (Testing Strategy & Guide)

本项目（BusOnRoad / Trackr）采用分层测试架构，涵盖领域数学/加解密单元测试、ViewModel 业务逻辑单元测试、Navigation 3 导航路由测试、Jetpack Compose 行为 UI 测试以及 JaCoCo 代码覆盖率统计。

---

## 1. 测试架构概览

- **单元测试框架**：JUnit 4 (`junit:junit:4.13.2`) + `kotlinx-coroutines-test:1.10.2`
- **无头 UI / Platform 测试**：Robolectric (`org.robolectric:robolectric:4.14.1`)，使用预置 `android-all-14-robolectric-10818077` 离线桩
- **Compose UI 测试**：`androidx.compose.ui:ui-test-junit4` + `androidx.compose.ui:ui-test-manifest`
- **协程/数据流测试**：`MainDispatcherRule` (基于 `StandardTestDispatcher` 与 `Dispatchers.setMain`) + `Turbine`
- **代码覆盖率**：JaCoCo Gradle Plugin (`jacocoTestReport`)

---

## 2. 内存 Fake 隔离机制 (`FakeTransitRepository`)

为了避免单元测试产生真实网络请求或依赖本地存储，测试套件统一通过接口 `ITransitRepository` 注入可控的 `FakeTransitRepository`（位于 `app/src/test/java/com/trackr/data/repository/FakeTransitRepository.kt`）。

- 支持自定义并模拟各种网络响应：
  - `supportedCitiesResult`: 城市列表
  - `stopDetailResult`: 站点详情与多站台途经线路
  - `lineDetailResult`: 线路在途公交与反向线路
  - `searchResult`: 线路与站点混合搜索
  - `favorites`: 收藏夹增删变动

### 协程后台轮询参数化规范
为了防止 ViewModels 中的 `while(isActive) { delay(...) }` 自动轮询导致协程测试调度器进入死循环，所有带后台轮询的 ViewModel 均提供 `enableAutoPolling: Boolean = true` 构造参数：
- **生产环境**：保持默认 `true`，自动执行 10s/15s 轮询。
- **测试环境**：传入 `enableAutoPolling = false`，通过 `testScheduler.runCurrent()` 或 `testScheduler.advanceTimeBy(ms)` 获得 100% 确定性执行。

---

## 3. 测试目录与套件清单

| 测试类 | 覆盖范围 | 运行方式 |
| :--- | :--- | :--- |
| `TransitUnitTest` | 核心算法（`RouteProjectionMath` 坐标插值与站名纠错、`ChelaileCrypto` 加解密与签名） | 本地 JVM |
| `NavigationKeyTest` | Navigation 3 `ScreenKey` 序列化与反序列化 | 本地 JVM |
| `NavigationFlowTest` | 导航栈后退拦截、深度链接（Deep Link）跳转与栈顶去重 | 本地 JVM |
| `HomeViewModelTest` | 首页城市切换、城市汉字/拼音前缀过滤、搜索防抖、收藏状态监听 | Robolectric (JVM) |
| `LineDetailViewModelTest` | 线路详情加载、站点选择、单车行程跟踪、走向条横纵向切换、收藏切换 | 本地 JVM |
| `StopDetailViewModelTest` | 站点详情物理站台切换、途经线路实时 ETA、收藏增删 | 本地 JVM |
| `ExpressiveRouteRailUiTest` | 经典横向走向条与纵向走向条 Compose UI 渲染、逐字竖排与点击事件响应 | Robolectric Compose UI |

---

## 4. 常用测试执行命令

### 运行所有单元测试与 UI 测试：
```bash
./gradlew test
```

### 仅运行特定 ViewModel 单元测试：
```bash
./gradlew testDebugUnitTest --tests "com.trackr.ui.screens.linedetail.LineDetailViewModelTest"
./gradlew testDebugUnitTest --tests "com.trackr.ui.screens.home.HomeViewModelTest"
```

### 仅运行 Compose UI 行为测试：
```bash
./gradlew testDebugUnitTest --tests "com.trackr.ui.components.ExpressiveRouteRailUiTest"
```

### 生成 JaCoCo 测试代码覆盖率报告：
```bash
./gradlew jacocoTestReport
```
报告输出路径：`app/build/reports/jacoco/jacocoTestReport/html/index.html`
HTML 单元测试详细结果路径：`app/build/reports/tests/testDebugUnitTest/index.html`
