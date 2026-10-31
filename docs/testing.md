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

单元测试不发起真实网络请求，也不依赖本地存储。测试套件通过接口 `ITransitRepository` 注入 `FakeTransitRepository`（位于 `androidApp/src/test/java/com/trackr/data/repository/FakeTransitRepository.kt`）。

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

`./gradlew test` 共跑 55 个测试类：`:androidApp` 54 个，`:core:designsystem` 1 个。

Koin 由 `TrackrApp.onCreate` 启动，Robolectric 用例里每个用例都会重新启动一次。`TransferAutoSwitchManager`、`ArrivalAlertManager`、`LiveProgressNotificationHelper` 这三个旧 object 转发到 Koin 里的实例，只能在 Robolectric（带 `TrackrApp`）用例里调用；纯 JVM 用例里调用会因为 Koin 没启动而抛 `IllegalStateException`。`ReminderScheduler`、`AmapKeyStore` 转发到伴生对象，不受影响。

`:androidApp` 的测试位于 `androidApp/src/test/java/com/trackr/`，下表按子目录列出全部测试类（辅助类 `FakeTransitRepository`、`FakeScheduledReminderRepository`、`MainDispatcherRule` 不含测试）。`:core:designsystem` 的 `RouteRailDrawUtilsTest` 位于 `core/designsystem/src/test/java/com/trackr/core/designsystem/rail/`，覆盖走向条绘制工具（Robolectric Compose UI）。

| 目录 | 测试类 | 覆盖范围 | 运行方式 |
| :--- | :--- | :--- | :--- |
| 根目录 | `TransitUnitTest` | 核心算法（`RouteProjectionMath` 坐标插值与站名纠错、`ChelaileCrypto` 签名/双密钥解密回退/信封括号截取、线路与城市模型） | 本地 JVM |
|  | `NavigationKeyTest` | Navigation 3 `ScreenKey` 序列化与反序列化 | 本地 JVM |
| `data/provider` | `TransitProviderCoordinatorTest` | 引擎模式下搜索/站点详情的数据源路由与回退 | 本地 JVM |
|  | `TransitProviderTest` | 车来了 Provider 多物理站台并发补全、附近站过滤与站点状态映射 | Robolectric (JVM) |
|  | `TransitProviderCacheTest` | Provider 层省请求：车来了附近站补全短缓存、其他站台只在首次加载补全、空主查询下次跳过；掌上公交 cmd103 缓存只轮询 cmd104、搜索回退结果线路与站点共用、附近站短缓存、反向地理编码空结果短缓存 | Robolectric (JVM) |
| `data/remote/amap` | `AmapLruCacheTest` | 高德结果 LRU 缓存满时淘汰最久未用的条目 | 本地 JVM |
|  | `AmapSdkManagerTest` | 高德 Key 优先级、隐私合规与 Key 注入 | Robolectric (JVM) |
|  | `AmapSdkTransitServiceTest` | 高德 SDK 公交路径转换为方案、无 Key 时的提示 | Robolectric (JVM) |
|  | `AmapTransitApiClientTest` | 高德 Web API 换乘方案解析 | Robolectric (JVM) |
| `data/remote/common` | `BaseTransitApiClientTest` | 地铁站名识别、距离计算、车牌清洗与 HTTP/JSON 执行封装 | Robolectric (JVM) |
|  | `SingleFlightCacheTest` | 同 key 并发只加载一次、取消一个等待者不影响其他、全部取消后下次重新加载、失败不缓存、TTL 内复用与过期、TTL 为 0 只去重、条目数上限、手动失效 | 本地 JVM |
| `data/remote/update` | `GithubUpdateCheckerTest` | 更新检查多源回退、强制更新判定、下载地址 https 与域名白名单校验 | Robolectric (JVM) |
| `data/repository` | `CountyLevelCityMatchTest` | 县级市/县城匹配与省份校验 | 本地 JVM |
|  | `RouteGroupRepositoryTest` | 路线分组与收藏分组增删改排序、并发写入不丢更新、JSON 损坏备份 | Robolectric (JVM) |
|  | `ScheduledReminderRepositoryTest` | 定时提醒增删改与持久化、并发写入不丢更新、JSON 损坏备份、计划触发时间记录 | Robolectric (JVM) |
|  | `TransitRepositoryLineDetailCacheTest` | 线路详情同 key 并发只请求一次上游、TTL 内复用与过期重拉、目标站顺序不同算不同 key、失败不缓存、取消的调用方不影响其他等待者 | Robolectric (JVM) |
| `di` | `KoinGraphVerifyTest` | Koin 依赖图：每个模块 `verify()` 静态检查构造参数都有绑定；Robolectric 里把每个定义实际取一次（带参数的 ViewModel 用样例 key） | Robolectric (JVM) |
| `domain/arrival` | `ArrivalTextTest` | 到站文字 8 种状态（在路上、快到/已到、车都过了、还没发车、末班已过、停运/无数据、加载中、没网）的大字/小字/色调/朗读文本，到站按 `ArrivalRules` 判，边界：0 站、≤3 分钟、末班刚过 | 本地 JVM |
| `receiver` | `ScheduledReminderReceiverTest` | 定时提醒触发、自动跟车需精确闹钟、前台服务启动失败降级 | Robolectric (JVM) |
|  | `BootCompletedReceiverTest` | 开机禁用已错过的一次性提醒、时间变化重新调度、无关广播忽略 | Robolectric (JVM) |
| `service` | `ArrivalAlertManagerTest` | 到站/临近提醒与实时通知并存时的去重 | Robolectric (JVM) |
|  | `BoardingDetectionTest` | 定位跟车检测（待确认、上车、没赶上、超时兜底、错锁纠正）与定位频率档位 | 本地 JVM |
|  | `GroupLegTrackerTest` | 换乘分组单段跟车状态机（候车锁车、乘车到站、信号丢失） | 本地 JVM |
|  | `SingleLegTrackerTest` | 单线路追踪选车、换车重置提醒、车辆短暂消失保持原车 | 本地 JVM |
|  | `LiveProgressNotificationHelperTest` | 实时进度通知进度计算与文案 | Robolectric (JVM) |
|  | `TransferAutoSwitchManagerTest` | 自适应轮询退避与下车到站判定 | Robolectric (JVM) |
| `service/vendor` | `LiveUpdatePromotionTest` | Android 16 Live Update 推广通知构建与厂商识别 | Robolectric (JVM) |
|  | `XiaomiFocusAdapterTest` | 小米焦点通知 JSON 负载构建 | Robolectric (JVM) |
| `ui` | `ScreenshotGeneratorTest` | 主要页面明暗主题截图生成 | Robolectric Compose UI |
| `ui/components` | `BusArrivalBadgeTest` | 到站状态徽标文字与高亮（走 `ArrivalText`） | 本地 JVM |
|  | `ExpressiveRouteRailUiTest` | 经典横向与纵向走向条渲染、逐字竖排与点击事件响应 | Robolectric Compose UI |
| `ui/components/citypicker` | `CityPickerItemRowTest` | 城市选择列表项渲染与点击 | Robolectric Compose UI |
| `ui/components/rail` | `RouteRailDrawUtilsTest` | 走向条绘制工具常量与绘制不崩溃 | Robolectric Compose UI |
| `ui/components/timetable` | `TimetableBottomSheetTest` | 时刻表底部弹窗渲染 | Robolectric Compose UI |
| `ui/navigation` | `NavigationFlowTest` | 导航栈后退拦截、深度链接跳转与栈顶去重 | 本地 JVM |
| `ui/screens/favoritesgroup` | `FavoritesGroupNavigationEventTest` | 分组编辑弹窗未保存时的返回确认 | Robolectric Compose UI |
|  | `FavoritesGroupScreenTest` | 收藏分组卡片渲染、候选线路切换与分组管理 | Robolectric Compose UI |
|  | `FavoritesGroupViewModelTest` | 分组筛选、候选线路、多线路 ETA 聚合、临时分组与自定义高德 Key | Robolectric (JVM) |
|  | `TransitGroupCardTextTest` | 分组卡片乘车站数、摘要与上车时间文案 | 本地 JVM |
|  | `TransitGroupCardTrackingTest` | 分组卡片跟车中各阶段展示 | Robolectric Compose UI |
| `ui/screens/home` | `HomeViewModelTest` | 首页城市切换、城市汉字与拼音前缀过滤、搜索防抖、收藏状态监听 | Robolectric (JVM) |
| `ui/screens/linedetail` | `BusFollowContentTest` | 跟车内容渲染与空车辆处理 | Robolectric Compose UI |
|  | `LineDetailViewModelTest` | 线路详情加载、站点选择、单车行程跟踪、走向条横纵向切换、收藏切换 | 本地 JVM |
|  | `StationArrivalContentTest` | 线路页到站大字：在路上、到站了、车都过了只给下一班始发时间、首班、末班已过 | Robolectric Compose UI |
| `ui/screens/more` | `AboutViewModelTest` | 关于页更新检查状态流转 | Robolectric (JVM) |
|  | `MoreViewModelTest` | 设置项读写与定时提醒开关/删除 | Robolectric (JVM) |
| `ui/screens/routeplan` | `RoutePlanScreenTest` | 路线规划搜索台、起终点互换、方案卡片与特征标签 | Robolectric Compose UI |
| `ui/screens/stopdetail` | `StopDetailViewModelTest` | 站点详情物理站台切换、途经线路实时 ETA、收藏增删 | 本地 JVM |
| `ui/util` | `PollingCoordinatorTest` | 定时/倒计时轮询策略与生命周期控制 | 本地 JVM |
| `util` | `CoordTransformTest` | WGS-84 / GCJ-02 坐标转换 | 本地 JVM |
|  | `ReminderSchedulerTest` | 提醒下次触发时间计算、错过的一次性提醒判定 | 本地 JVM |
|  | `SignatureUtilsTest` | 包名与签名 SHA-1（取签名历史中的当前证书） | Robolectric (JVM) |
|  | `TransitCodeHelperTest` | 支付宝/云闪付乘车码跳转 Intent | Robolectric (JVM) |
| `widget` | `FavoritesShortcutWidgetSyncTest` | 应用级收藏同步：启动时不刷新，收藏真变一次只刷新一次快捷方式与小组件，重复 `start()` 不开第二个收集 | Robolectric (JVM) |

---

## 4. 常用测试执行命令

### 运行全量测试

```bash
./gradlew test
```

### 运行特定 ViewModel 测试

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.trackr.ui.screens.linedetail.LineDetailViewModelTest"
./gradlew :androidApp:testDebugUnitTest --tests "com.trackr.ui.screens.home.HomeViewModelTest"
```

### 运行 Compose UI 测试

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.trackr.ui.components.ExpressiveRouteRailUiTest"
```

### 生成 JaCoCo 代码覆盖率报告

```bash
./gradlew jacocoTestReport
```

报告输出路径：
- JaCoCo HTML：`androidApp/build/reports/jacoco/jacocoTestReport/html/index.html`
- 单元测试 HTML：`androidApp/build/reports/tests/testDebugUnitTest/index.html`
