# 追客 MAD 重构 + 设计稿 v3 改版：合并执行计划（r2：跨平台）

## r2 修订说明（2026-10-31，以本节为准）

用户拍板后的改动。正文已按下表重写的部分：§2.1–2.4、新增 §2.8、§6 全部、§7 全部、§8 中涉及 DI 与模式切换的段落。其余章节里残留的写法按「名称对照」理解。

| # | 用户决定 | 计划里的落点 |
| --- | --- | --- |
| Q1 | **拆模块，一步到位** | 按功能拆 `:feature:*`，见 §2.2 |
| Q2 | **要跨平台：用 Compose Multiplatform（CMP）出 iOS，以后再看鸿蒙** | 共享代码放 `commonMain`；DI 用 **Koin**（Hilt 只支持 Android）；网络用 **Ktor**；导航用 CMP 版 Navigation 3；新增阶段 G（iOS）和鸿蒙评估，见 §2.3、§2.8、§6 |
| Q3 | 存储由我判断 | **Room（KMP）存收藏 / 路线 / 分组 / 提醒 / 搜索历史 / 站表缓存；DataStore 存设置和追踪会话**，理由见 §2.4 |
| Q8 | 一起等不设上限，用户想等几条就几条 | 同一个站的多条线只发 **1 次**站点请求，条数不影响请求数，见 §2.5、第 15 步 |
| Q9 | 切到长辈版直接回首页 | 切换模式时两套栈都重置到首页，见 §8.4 |
| Q12 | 可以升 AGP | 第 1 步先升到最新稳定版 |
| 其余 | 没提异议 | 沿用原推荐（Q4 Glance、Q5 截图、Q6 续追踪、Q7 弹层、Q10、Q11），可再改 |

名称对照（正文残留写法 → r2 含义）：

| 正文写法 | r2 含义 |
| --- | --- |
| `:app` | Android 专属代码在 `:androidApp`；页面和 ViewModel 在对应 `:feature:*` 的 `commonMain` |
| `@HiltViewModel` / `hiltViewModel()` / `@Inject` / `@AndroidEntryPoint` | Koin：`viewModel { }` 定义 + `koinViewModel { parametersOf(key) }`；平台组件用 `KoinComponent` + `by inject()` |
| `@HiltAndroidTest` / `@TestInstallIn` | Koin 测试模块 `testModules` + `loadKoinModules`；ViewModel 单测仍直接构造 |
| 「类型化 DataStore 存收藏」 | 改存 Room，见 §2.4 |
| 「一起等上限 4 条」 | 不设上限 |
| OkHttp / `MockWebServer` | Ktor（Android 用 OkHttp 引擎，iOS 用 Darwin 引擎）/ Ktor `MockEngine` |

> 范围：`/Users/changlepan/Projects/busonroad`（`:app` + `:core:designsystem`，包名 `com.trackr`，applicationId `com.github.u5151561.trackr`）。
> 依据：`AGENTS.md`、`docs/testing.md`、`docs/spec/app_spec_as_is.md`（§7 问题、§8 决策 D1/D2/D3）、`docs/design/redesign_v3.html`、`docs/design/redesign_v3_plan.md`（上一版 17 步，本文在它基础上重排合并），以及下列 skill。
> 本文只是计划，不改代码。写作时基于工作区 `4e37cd1`（另有 4 个未提交的设计文档）。
> 补充需求「长辈版（适老模式）」见 §8；其设计稿 `docs/design/redesign_v3_elder.html` 已写好，r2 修订时已对照。

## 来源标记

每条建议后面用方括号标出处：

| 标记 | 来源 |
| --- | --- |
| [N3] | skill `imported/gemini/navigation-3`（含 recipes：multiple-backstacks、common-ui、results-event、results-state、bottomsheet、deeplinks-*、modular-hilt、passingarguments、animations） |
| [CP] | skill `imported/gemini/jetpack-compose-performance` |
| [E2E] | skill `imported/gemini/edge-to-edge` |
| [NE] | skill `imported/gemini/navigation-event` |
| [TS] | skill `imported/gemini/testing-setup` |
| [GC] | skill `imported/gemini/glance-canonical-layouts` |
| [GU] | skill `imported/gemini/glance-appwidget-updates-and-memory` |
| [ANR] | skill `imported/gemini/anr-audit-and-fix` |
| [AD] | skill `imported/gemini/adaptive` |
| [R8] | skill `imported/gemini/r8-analyzer` |
| [IS] | skill `imported/gemini/android-intent-security` |
| [AGP] | skill `imported/gemini/agp-9-upgrade` |
| [AG] | `AGENTS.md`（MAD、UDF、Flow、`collectAsStateWithLifecycle`、依赖倒置、Fake 测试规则） |
| [TD] | `docs/testing.md` |
| [SPEC] | `docs/spec/app_spec_as_is.md` |
| [V3] | `docs/design/redesign_v3.html` |
| [V3P] | `docs/design/redesign_v3_plan.md` |
| [ELD] | 父会话补充需求（长辈版），设计稿 `docs/design/redesign_v3_elder.html` |

---

## 1. 现状诊断

行号均为当前工作区实测。

### 1.1 构建与依赖

| # | 现状 | 证据 | 不符合 | 来源 |
| --- | --- | --- | --- | --- |
| B1 | Hilt / KSP 只在版本目录里，**没有接入**：`[plugins]` 无 hilt/ksp，`app/build.gradle.kts` 无 `kapt/ksp`、无 `@HiltAndroidApp` | `gradle/libs.versions.toml` 第 4–6 行有 `ksp`/`hilt`/`hiltNavigationCompose`；`[plugins]` 只有 4 个；`app/build.gradle.kts:4-9` | 依赖倒置靠手写默认参数 + 全局单例 | [AG]、[TS] Step 2 |
| B2 | DataStore 依赖声明了但**没人用** | `app/build.gradle.kts` 依赖 `datastore.preferences`；`grep DataStore app/src/main` 无结果 | 持久化仍是 SharedPreferences | [AG] 第 5 条（用现代 Jetpack） |
| B3 | AGP 9.2.1 已用内置 Kotlin（没有 `kotlin-android` 插件）；KSP 2.3.12 ≥ 2.3.6、Hilt 2.60.1 ≥ 2.59.2，满足 AGP 9 要求 | `app/build.gradle.kts:4-9`、`libs.versions.toml` | 无问题，接入 Hilt 不需要升级 | [AGP] Step 1/2 |
| B4 | `compileSdk 37`、`targetSdk 36`、`minSdk 26` | `app/build.gradle.kts` defaultConfig | Predictive back 与 edge-to-edge 前提满足 | [NE]、[E2E] |
| B5 | 没有 Compose compiler metrics/reports、没有 stability 配置、没有 Baseline Profile / Macrobenchmark 模块 | `app/build.gradle.kts` 全文；`settings.gradle.kts` 只 include 两个模块 | 无法量化重组和启动 | [CP] Foundational prerequisites 第 3 条 |
| B6 | R8 keep 规则过宽：`-keep class com.trackr.domain.model.** { *; }`、`-keep class com.trackr.ui.navigation.** { *; }`、所有 Service/Receiver 全 keep | `app/proguard-rules.pro` | kotlinx.serialization 自带规则已覆盖，宽 keep 阻止优化 | [R8] Step 1/2 |
| B7 | `android:usesCleartextTraffic="true"` 全局开启 | `AndroidManifest.xml` `<application>` | 应只对必须 HTTP 的域名放行（network security config） | [IS]（组件与传输最小权限） |

### 1.2 架构分层与依赖注入

| # | 现状 | 证据 | 不符合 | 来源 |
| --- | --- | --- | --- | --- |
| A1 | `TrackrApp.instance` 全局单例在 UI、Service、Widget 里直接取依赖，共 **37 处**（含 `TrackrApp.kt` 自身赋值） | `TrackrApp.kt:47,86-89`；`HomeViewModel.kt:88,92-93`；`StopDetailViewModel.kt:48`；`LineDetailViewModel.kt:74`；`LineDetailScreen.kt:100,430,444`；`FavoritesGroupViewModel.kt:56-62,209,339,470`；`MoreViewModel.kt:39-46`；`AboutViewModel.kt:40-45`；`MainActivity.kt:200`；`BusStopWidgetProvider.kt:62`；`LiveProgressNotificationHelper.kt:48,53`；`ArrivalAlertManager.kt:205,210`；`BusTrackingService.kt:113,149,298,364,381,391` | 依赖倒置；Composable 直接拿仓库违反 UDF | [AG] 第 1、4 条 |
| A2 | Composable 里直接读仓库、直接写仓库 | `LineDetailScreen.kt:100-101`（`repository.activeTrackingSession.collectAsStateWithLifecycle()`）、`:444`（`reminderRepository.saveReminder`） | 状态应只来自 ViewModel 的 UiState | [AG] 第 1、2 条 |
| A3 | `ITransitRepository` 是 99 行的「上帝接口」：城市、引擎、聚合优先级、收藏、追踪会话、定位、搜索历史、乘车码偏好、附近站数、通知开关、实时查询全在一起 | `ITransitRepository.kt:20-99` | 单一职责；Fake 也跟着膨胀（`FakeTransitRepository.kt` 230 行） | [AG] 第 4 条 |
| A4 | 追踪状态有**两个**数据源：`TransitRepository._activeTrackingSession`（内存）+ `TransferAutoSwitchManager._activeGroupSession`（`object` 单例）；都不落盘 | `TransitRepository.kt:97-98,528`；`TransferAutoSwitchManager.kt:26,30,33` | 单一数据源；进程被杀后 UI 与通知状态丢失 | [AG] 第 1 条 |
| A5 | 业务逻辑放在 `object` 里（`TransferAutoSwitchManager`、`GroupLegTracker`、`ArrivalAlertManager`、`AmapSdkManager`），测试只能靠 `internal reset…` 钩子 | `ArrivalAlertManager.kt:190,208`；`GroupLegTracker.kt:16` | 可测试性；无法注入时钟/Fake | [AG] 第 4 条、[TS] Step 4 |
| A6 | `BusTrackingService` 用 19 个 Intent extra 启动、一次只追一条线 | `BusTrackingService.kt:53-131,413-431,441-522` | 启动参数应只带 id，服务从仓库读；v3「一起等」需要多线 | [V3]「一起等」、[V3P] 第 7 步 |
| A7 | `FavoritesGroupViewModel` 1530 行，同时管：分组、收藏、实时 ETA、高德 POI 联想、路线规划、Key 配置、数据迁移、路线追踪 | `FavoritesGroupViewModel.kt:55-1530`（函数清单见 `:235,334,355,442,947,1089,1215,1324`） | 单一职责 | [AG] 第 4 条 |
| A8 | ViewModel 直接用 `SharedPreferences` 和 `Context` | `FavoritesGroupViewModel.kt:62`（prefs）、`:947,971,976,1015`（`fun xxx(context: Context)`） | ViewModel 不应持有 / 接收 Context | [AG] 第 1 条 |
| A9 | 桌面快捷方式与小组件刷新由 UI 层 `LaunchedEffect(favorites)` 驱动，App 不打开就不同步 | `MainActivity.kt:200-205` | 副作用应在数据层 / 应用级作用域 | [AG] 第 1 条、[CP] §5 |
| A10 | 两套设计系统并存：`app/ui/theme/*`、`app/ui/components/common/*` 与 `core/designsystem` 同名文件（`StatePlaceholders`、`DataSourceBadge`、`TrackrBottomSheet`、`RouteRailDrawUtils`、`Theme`） | `app/.../ui/theme/Theme.kt:9-30` 全是 typealias 转发；`app/.../ui/components/rail/RouteRailDrawUtils.kt` 与 `core/.../rail/RouteRailDrawUtils.kt` 各有一份测试 | 重复实现 | [V3P] 第 1、17 步 |

### 1.3 持久化

| # | 现状 | 证据 | 不符合 | 来源 |
| --- | --- | --- | --- | --- |
| P1 | 5 处直接 `getSharedPreferences`（3 个文件：`trackr_prefs`、`trackr_route_groups`、`trackr_scheduled_reminders`），存 JSON 字符串 | `TransitRepository.kt:69`、`RouteGroupRepository.kt:33`、`ScheduledReminderRepository.kt:30`、`AmapKeyStore.kt:21`、`FavoritesGroupViewModel.kt:62` | SharedPreferences 无事务、`apply()` 的 QueuedWork 会在 `onPause`/服务停止时阻塞主线程 | [ANR] Path A、[AG] 第 5 条 |
| P2 | 仓库构造函数里**同步读盘 + JSON 解码**，构造发生在 `Application.onCreate`（主线程） | `TrackrApp.kt:72-83` → `TransitRepository.kt:71-95`（`loadInitialCity/Favorites/...`）、`RouteGroupRepository.kt:44-75` | 冷启动主线程 I/O | [ANR] Path A（已证明执行线程=主线程、调用会阻塞） |
| P3 | 数据迁移逻辑散在 ViewModel 里（`runDataMigrationIfNeeded`、`PREF_KEY_MIGRATION_VERSION`） | `FavoritesGroupViewModel.kt:69-75,235` | 迁移应属于数据层，一次执行 | [AG] 第 1 条 |

### 1.4 导航（Navigation 3）

| # | 现状 | 证据 | 不符合 | 来源 |
| --- | --- | --- | --- | --- |
| N1 | 4 条返回栈 + 字符串 `selectedTab`，Tab 判断散在 4 处 `when` | `MainActivity.kt:184-194`（`"home"/"route"/"group"/"more"`）、`:373-377`、`:480,490` | recipe 用 `NavigationState`+`Navigator` 统一管理，顶层路由用 `NavKey` | [N3] multiple-backstacks |
| N2 | **4 条栈共用一个** `rememberSaveableStateHolderNavEntryDecorator` 和一个 ViewModelStore decorator，且 `NavDisplay` 只拿当前栈 → 切 Tab 时另一个栈的 entry 离开组合，其 ViewModel 被清理、可保存状态依赖 decorator 是否仍记得 | `MainActivity.kt:360-364,468-470` | recipe 要求「每个顶层路由各一个 `SaveableStateHolderNavEntryDecorator`」，并用 `rememberDecoratedNavEntries` 合并 | [N3] multiple-backstacks「Important implementation details」 |
| N3 | `HomeViewModel`、`FavoritesGroupViewModel` 在 Activity 级创建并跨页共享（首页+搜索、收藏+规划） | `MainActivity.kt:196-197,506,547,570,597` | ViewModel 应作用域到 NavEntry；共享状态放仓库 | [N3] passingarguments、[AG] 第 4 条 |
| N4 | 带参页面手写 `ViewModelProvider.Factory` 和字符串 key | `MainActivity.kt:634-650,662-676` | 用 Hilt assisted 注入 + entry 作用域 | [N3] modular-hilt |
| N5 | Deep link：Intent extras 手工解析后塞 `mutableStateOf`，**强制切首页**再压线路页；无 URI、无合成返回栈；外部 App 可以给导出的 `MainActivity` 传任意 extra | `MainActivity.kt:97,108-111,124-148,284-305` | recipe：URI 解析 → `NavKey`，构建合成返回栈；外部输入需校验 | [N3] deeplinks-uriarguments / syntheticbackstack、[IS] |
| N6 | 有 predictive back 过渡（`predictivePopTransitionSpec`），但子组件仍用旧 `BackHandler` | `MainActivity.kt:499`；`GroupEditDialog.kt:96`；`RoutePlanScreen.kt:265` | Nav3 已内置返回；组件内拦截应迁 `NavigationBackHandler` | [NE] Step 1 第 2、4 条 |
| N7 | `NavigationFlowTest` 测的是 `mutableListOf<ScreenKey>`，不是真实导航逻辑 | `NavigationFlowTest.kt:13-60` | 导航测试应覆盖返回、deep link、exit-through-home | [TS] Step 10 |
| N8 | 已做对：`ScreenKey` 是 `@Serializable sealed interface : NavKey`，`rememberNavBackStack` 可跨进程恢复；`dropUnlessResumed` 防连击 | `ScreenKey.kt:7-47`、`NavigationUtils.kt` | 保留 | [N3] basicsaveable |

### 1.5 Compose 性能

| # | 现状 | 证据 | 不符合 | 来源 |
| --- | --- | --- | --- | --- |
| C1 | 线路详情**每秒**改一次 `uiState.refreshCountdownSeconds`，而 `uiState` 在屏幕顶层读取 → 整页（含 922 行的 `ExpressiveRouteRail`）每秒重组 | `LineDetailViewModel.kt:51,89-92`；`LineDetailScreen.kt:99,374` | 高频状态应延迟读取（lambda）或下放到叶子；v3 已删倒计时 | [CP] §1 Phase Deferral、[V3]「刷新自动，下拉也能刷」 |
| C2 | 每次轮询都会产生新的 `BusLineDetail`/`NearbyStop` 实例（含 `List`），强跳过模式下按 `===` 比较必然不等 → 列表项全部重组 | `HomeViewModel.kt` 轮询 → `HomeScreen.kt:232-236`；`TransitModels.kt:223,253` | 应在 ViewModel 里映射成扁平 UI 模型并复用未变化项的实例；**不要**给含集合的模型加 `@Immutable`/换 `ImmutableList` | [CP] §1「Never Add @Stable…to Collection Models」「When to Add…」 |
| C3 | Lazy 列表 key/contentType 大多已正确（首页、搜索、站点、城市选择、提醒列表）；**例外**：`RoutePlanScreen.kt:687` `items(activeTips)` 无 key、无 contentType | 见左 | 所有 lazy item 都要 primitive key + contentType | [CP] §3 |
| C4 | 动画已做对：首页呼吸 `graphicsLayer { alpha = alphaAnim }`、走向条脉冲 `graphicsLayer { scaleX = pulseScale }` | `HomeScreen.kt:354,391`；`ExpressiveRouteRail.kt:430,444-445` | 保留，作为范例 | [CP] §4 |
| C5 | 超大 Composable 文件：`RoutePlanScreen.kt` 2491 行（含 Canvas 示意图、4 个对话框、地图 AndroidView、方案面板）、`HomeScreen.kt` 1109 行、`SearchScreen.kt` 839 行、`ExpressiveRouteRail.kt` 922 行 | `wc -l` | 难以单独测量、预览和测试；跳过粒度粗 | [CP] §1「Make Composables Cheaper」、[V3P] 第 4 步 |
| C6 | `RoutePlanScreen` 本地 `remember { mutableStateOf }` 十几个（起终点、焦点、缩放、偏移），部分应进 ViewModel / `rememberSaveable` | `RoutePlanScreen.kt:213-226` | 进程重建丢失输入 | [AD] Step 6、[N3] basicsaveable |
| C7 | 93 处写死 `fontSize`、约 14 种圆角 | `grep fontSize = app/src/main` = 93；[SPEC] §6.2/6.3 | 设计 token；长辈版需按模式切字号 | [V3] §3、[ELD] |

### 1.6 系统集成（服务、通知、小组件、ANR、Edge-to-edge）

| # | 现状 | 证据 | 不符合 | 来源 |
| --- | --- | --- | --- | --- |
| S1 | 小组件是经典 `AppWidgetProvider`，在 `onUpdate/onReceive` 里 `scope.launch` 拉网络，**没有 `goAsync()`、没有 WorkManager** → 进程可能在请求完成前被回收；`TrackrApp.instance` 取仓库 | `BusStopWidgetProvider.kt:31,44-55,62,73` | 经典 Provider 用 `goAsync()`（<10s）或 `enqueueUniquePeriodicWork`；或迁 Glance | [GU] Step 5 第 3 条 |
| S2 | 小组件刷新广播 `com.trackr.action.WIDGET_REFRESH` 挂在 `exported="true"` 的 receiver 上，任何 App 都能触发网络刷新 | `AndroidManifest.xml` `BusStopWidgetProvider` intent-filter | 自定义 action 应改为显式 PendingIntent，不放进导出的 filter | [IS] |
| S3 | 追踪通知没设 `VISIBILITY_PUBLIC` / `setPublicVersion` | `grep VISIBILITY LiveProgressNotificationHelper.kt` 无结果 | 决策 D3 | [SPEC] §8 D3 |
| S4 | 服务 `START_NOT_STICKY`，会话只在内存 | `BusTrackingService.kt:50`；A4 | 进程被杀后追踪静默消失 | [AG] 第 1 条 |
| S5 | `ScheduledReminderReceiver` 用 `goAsync()` + `CoroutineScope(Dispatchers.IO).launch`，正确但每次新建 scope | `ScheduledReminderReceiver.kt:49-50` | 可接受；改为注入应用级 scope | [ANR] Path A |
| S6 | Edge-to-edge 已开：`enableEdgeToEdge()`、`isNavigationBarContrastEnforced = false`、`adjustResize`；但外层 `Scaffold(contentWindowInsets = WindowInsets(0.dp))` 吞掉 innerPadding（`{ _ -> }`），各页自己处理 inset；悬浮乘车码按钮、底部操作栏是新增元素 | `MainActivity.kt:102-105,465-467`；`LineDetailScreen.kt:135` | 每页列表用 `contentPadding`、FAB 在 Scaffold 内；新组件按清单逐个验 | [E2E] Step 3、Checklist |
| S7 | 定位/通知权限在根 Composable 里每次组合都 `checkSelfPermission` | `MainActivity.kt:221-224` | 可接受，但应收进一个权限状态持有者，便于测试 | [AG] 第 2 条 |

### 1.7 测试

| # | 现状 | 证据 | 不符合 | 来源 |
| --- | --- | --- | --- | --- |
| T1 | 已有：JUnit4、coroutines-test、Turbine、Robolectric 4.14.1（离线 android-all 14）、Compose UI test、JaCoCo、`FakeTransitRepository`、`MainDispatcherRule`、`enableAutoPolling=false` 约定 | [TD] §1–3；`app/build.gradle.kts` testImplementation | 保留 | [TD]、[AG] |
| T2 | `ScreenshotGeneratorTest`（862 行）用 `captureToImage` 生成图片，不是回归比对 | `ScreenshotGeneratorTest.kt:9-11` | 截图测试应有基准图与比对 | [TS] Step 3、8 |
| T3 | 没有 DI 测试替换：Fake 通过 ViewModel 默认参数传入；Service/Receiver 测试要造真 `TrackrApp` | `ScheduledReminderReceiverTest.kt:7,20`、`ArrivalAlertManagerTest.kt:6,21` | Hilt 测试 + Fake 绑定 | [TS] Step 2、4 |
| T4 | 无导航真实流程测试（N7）、无状态恢复测试 | — | [TS] Step 9「Always verify state restoration」、Step 10 | [TS] |

---

## 2. 目标架构

### 2.1 分层

```
UI 层（:feature:*  commonMain）   XxxScreen（无状态：入参 UiState + 事件回调）、ElderXxxScreen（长辈版）
                                   ↑ UiState(StateFlow)        ↓ 事件
                                  XxxRoute（koinViewModel，collectAsStateWithLifecycle）
                                  XxxViewModel（androidx.lifecycle ViewModel，KMP；作用域 = NavEntry）
Domain 层（:core:domain  commonMain，纯 Kotlin）
                                  ArrivalText、TrackingEngine、RouteProjectionMath
                                  平台能力接口：TrackingController、Announcer、TransitCodeLauncher、LocationSource、SpeechInput、Clock
Data 层（:core:data / :core:database / :core:datastore / :core:network  commonMain）
                                  Repository 接口 + 实现（单一数据源，暴露 Flow）
                                  ├─ 本地：Room（列表类数据）、DataStore（设置、追踪会话）
                                  └─ 远程：Ktor → ITransitProvider（车来了 / 掌上公交）、高德 Web 服务
平台层（androidMain / :androidApp / iosApp）
                                  Android：前台服务、通知 / 实时活动、Glance 小组件、高德 Android SDK、TTS、语音输入
                                  iOS：本地通知 / Live Activity、CLLocationManager、AVSpeechSynthesizer、高德 iOS SDK
```

| 规则 | 说明 | 来源 |
| --- | --- | --- |
| UDF | ViewModel 暴露一个 `StateFlow<XxxUiState>`；Screen 只读 state、只发事件；一次性消息放进 UiState 的 `userMessages`，UI 显示后回调 `onMessageShown(id)`（长辈版换成常驻提示，见 §8） | [AG] 第 1–3 条 |
| 收集 | 页面一律 `collectAsStateWithLifecycle()`（`lifecycle-runtime-compose` 已是 KMP）；Lazy 列表外层收集一次 | [AG] 第 3 条、[CP] §3、§5 |
| Screen / Route 分离 | `LineDetailRoute(viewModel)` → `LineDetailScreen(state, onEvent)`。无状态 Screen 是 Compose 测试、截图测试、长辈版共用 ViewModel 的前提 | [AG] 第 2 条、[TS] Step 8–9、[ELD] |
| commonMain 不碰平台 | `commonMain` 里不出现 `Context`、`Intent`、`R.`、`Toast`、`java.*`、`android.*`。需要系统能力时注入 domain 里的接口，实现放 `androidMain` / `iosMain` | [AG] 第 4 条 |
| Domain 层只放共用纯逻辑 | 到站文字、追踪状态机、坐标投影、平台能力接口。不为每个仓库调用包一层 UseCase | [AG] 第 1 条 |

### 2.2 模块划分（Q1：拆，一步到位）

```
busonroad/
├─ build-logic/convention/      约定插件：trackr.android.application / trackr.kmp.library / trackr.kmp.compose / trackr.kmp.feature
├─ androidApp/                  com.android.application（Android 专属入口与系统集成）
├─ iosApp/                      Xcode 工程（SwiftUI 壳 + ComposeUIViewController；Live Activity / WidgetKit 扩展用 Swift）
├─ composeApp/                  KMP + CMP：TrackrRoot()、NavDisplay、页签、Koin 图组装、iosMain 的 MainViewController()
├─ feature/
│   ├─ bus/        公交首页（收藏合一、附近、追踪卡、乘车码悬浮按钮）
│   ├─ search/     搜索
│   ├─ stop/       站点详情 + 一起等选线 + 「上了哪辆？」
│   ├─ line/       线路详情 + 时刻表 + 定时提醒入口
│   ├─ route/      路线规划（地图）+ 路线详情
│   ├─ favorites/  编辑收藏 + 收藏弹层
│   └─ me/         我的：设置、定时提醒编辑、高德 Key、关于
├─ core/
│   ├─ model/        @Serializable 领域模型
│   ├─ domain/       纯逻辑 + 平台能力接口
│   ├─ network/      Ktor 客户端、车来了 / 掌上公交 / 高德 Web API、加解密
│   ├─ database/     Room（KMP）实体、DAO、建库
│   ├─ datastore/    设置、UiMode、追踪会话
│   ├─ data/         仓库接口 + 实现、Provider 选择、旧数据迁移
│   ├─ navigation/   ScreenKey（NavKey）、NavigationState、Navigator、DeepLinkParser、SavedStateConfiguration
│   ├─ designsystem/ TrackrTheme(uiMode)、Token、基础组件、Compose 资源（图标、通用文案）
│   ├─ ui/           业务通用组件：到站大字、线路号、车站行、换乘时间轴（标准 / 长辈两种尺寸）
│   └─ testing/      Fake 仓库、FakeAnnouncer、FakeClock、主调度器替换、Koin 测试模块
└─ baselineprofile/            com.android.test：Baseline Profile + Macrobenchmark
```

| 模块 | 插件 | 目标平台 | 说明 |
| --- | --- | --- | --- |
| `:androidApp` | `com.android.application` | Android | `MainActivity`（只 `setContent { TrackrRoot() }` + 处理 Intent）、`TrackrApplication`（`startKoin`）、`BusTrackingService`、Receiver、通知与厂商适配、Glance 小组件、快捷方式、各平台接口的 Android 实现。AGP 9 不允许 KMP 插件和 `com.android.application` 放在同一模块，所以必须单拆 [AGP] |
| `:composeApp` | `com.android.kotlin.multiplatform.library` + KMP + CMP | Android、iOS | 根 Composable、导航宿主、把各 feature 的 entry 和 Koin 模块组装起来 |
| `:feature:*` | 同上（约定插件 `trackr.kmp.feature`） | Android、iOS | 每个功能：ViewModel、UiState、标准 Screen、长辈 Screen、`xxxEntries()`、Koin `xxxModule`；平台差异放本模块 `androidMain` / `iosMain`（如路线页地图） |
| `:core:*` | `trackr.kmp.library`（UI 类再加 `trackr.kmp.compose`） | Android、iOS | 见上图 |
| `:baselineprofile` | `com.android.test` | Android | [CP] |

依赖规则（用约定插件和 Gradle 依赖约束保证）：

| 规则 | 理由 |
| --- | --- |
| `:feature:*` 只依赖 `:core:*`，**feature 之间不互相依赖** | 页面互跳只用 `:core:navigation` 里的 `ScreenKey`，改一个页面不牵动别的 |
| `:core:data` 依赖 `database`、`datastore`、`network`、`model`、`domain`；feature 只看到 `:core:data` 的接口 | 依赖倒置 [AG] 第 4 条 |
| 长辈版不单独成模块：`ElderXxxScreen` 和标准 Screen 放同一个 feature 的 `elder/` 包，共用同一个 ViewModel | 避免 feature 之间依赖，长辈版天然复用 VM（§8.5） |
| `:composeApp` 依赖全部 feature；`:androidApp` 只依赖 `:composeApp` 和 Android 平台实现要用的 core 模块 | 入口最薄 |

导航组装（不需要 DI 多绑定）：每个 feature 导出一个扩展函数，`:composeApp` 依次调用。

```kotlin
// :feature:line  commonMain
fun EntryProviderScope<NavKey>.lineEntries(nav: Navigator) {
    entry<ScreenKey.LineDetail> { key -> LineDetailRoute(koinViewModel { parametersOf(key) }, nav) }
}
fun EntryProviderScope<NavKey>.elderLineEntries(nav: Navigator) { /* ElderLineDetailRoute */ }

// :composeApp  commonMain
val entries = entryProvider {
    busEntries(nav); searchEntries(nav); stopEntries(nav); lineEntries(nav)
    routeEntries(nav); favoritesEntries(nav); meEntries(nav)
}
```

### 2.3 依赖注入：Koin（Q2：跨平台）

| 项 | 方案 | 理由 / 来源 |
| --- | --- | --- |
| 选型 | **Koin**（`koin-core`、`koin-compose`、`koin-compose-viewmodel`、`koin-android`） | Hilt / Dagger 只支持 Android，不能用在 `commonMain`。Koin 支持 KMP + CMP，有 Compose ViewModel 集成，资料多。备选 Metro（编译期检查，KMP），较新，暂不选 |
| 模块 | 每个 core / feature 一个 `val xxxModule = module { }`；平台实现在 `androidMain` / `iosMain` 各提供一个 `platformModule` | 依赖倒置 [AG] 第 4 条 |
| ViewModel | `viewModel { (key: ScreenKey.LineDetail) -> LineDetailViewModel(key, get(), get(), get()) }`；entry 里 `koinViewModel { parametersOf(key) }`，配合 Nav3 的 ViewModelStore decorator 作用域到 entry | [N3] passingarguments（同样的作用域思路） |
| 替换 `TrackrApp.instance` | ① Koin 提供现有实例，`instance` 暂留；② ViewModel 改注入；③ Service / Receiver / 小组件 / 通知改 `by inject()`，删 `instance` | 每步可编译可回退 |
| 作用域 | `single`：HttpClient、Provider、各 Repository、`TrackingRepository`、应用级 `CoroutineScope`；`factory`：无状态工具 | [AG] 第 3 条 |
| 编译期没有检查 | Koin 缺绑定只在运行时报错 → 加 `KoinGraphVerifyTest`（JVM 上 `module.verify()`），每个模块一条，CI 必跑 | 风险 R4 |
| 测试 | ViewModel 单测直接构造并传 Fake（最快）；整页 / 服务测试用 `testModule` 覆盖 `dataModule` | [TS] Step 2、4 |

```kotlin
// :core:data  commonMain
val dataModule = module {
    single<FavoritesRepository> { RoomFavoritesRepository(get(), get()) }
    single<SettingsRepository> { DataStoreSettingsRepository(get()) }
    single<RealtimeRepository> { DefaultRealtimeRepository(get(), get()) }
    single<TrackingRepository> { DefaultTrackingRepository(get(), get(named("app"))) }
}

// :feature:line  commonMain
class LineDetailViewModel(
    private val key: ScreenKey.LineDetail,
    private val realtime: RealtimeRepository,
    private val favorites: FavoritesRepository,
    private val tracking: TrackingController,
    private val announcer: Announcer,              // 长辈版「读给我听」
    private val enableAutoPolling: Boolean = true, // 测试传 false，见 AGENTS.md
) : ViewModel()
val lineModule = module {
    viewModel { (key: ScreenKey.LineDetail) -> LineDetailViewModel(key, get(), get(), get(), get()) }
}

// :androidApp
class TrackrApplication : Application() {
    override fun onCreate() { super.onCreate()
        startKoin { androidContext(this@TrackrApplication); modules(appModules + androidPlatformModule) }
    }
}
```

### 2.4 持久化（Q3：Room + DataStore）

| 数据 | 存储 | 理由 |
| --- | --- | --- |
| 收藏的车、路线（路线 → 程 → 候选线 三张表）、分组、定时提醒、搜索历史 | **Room（KMP）** | v3 的功能大多是「改一部分」：拖动排序、按分组筛、删分组后收藏变「不分组」、给某一程加减一条线、改一个提醒。Room 用外键（`ON DELETE SET NULL`）和事务直接表达；DataStore 每次都要读出整个对象、改完整个写回，一致性靠手写 |
| 线路站表缓存（新） | **Room** | 打开线路页先显示缓存的站序，实时数据回来再补；弱网也能看站。几百条线 × 每条几十站，放 DataStore 单文件会越来越大，每改一条都要整个重写 |
| 设置（城市、数据源、乘车码平台、通知 / 锁屏开关、提醒站数、UiMode、高德 Key、迁移版本） | **Preferences DataStore** | 键值少、要 Flow、启动时读；不需要查询 |
| 追踪会话 | **DataStore（JSON 单对象）** | 只有一条，每次轮询整条覆盖写，从不查询；进程重建后读回 |
| 静态城市表 | 内存 + 现有网络缓存 | 可重新拉 |

为什么这样分：

| 方面 | Room | DataStore |
| --- | --- | --- |
| 读 | 只读查询到的行；Flow 只在相关表变化时重发 | 每次读整份文件 |
| 写 | 改一行写一行，事务保证一致 | 每次整份重写；数据小时很快 |
| 查询 / 排序 / 关联 | SQL、索引、外键 | 没有，在内存里自己算 |
| 结构升级 | 有 schema 导出和迁移测试工具 | 自己写版本号和转换 |
| 跨平台 | Android / iOS / JVM 都支持（iOS 用自带 SQLite 驱动 `BundledSQLiteDriver`，包会变大，执行时实测） | Android / iOS / JVM 都支持 |
| 代价 | 每个平台都要跑 KSP，编译变慢 | 几乎没有 |

```kotlin
// :core:database  commonMain
@Database(entities = [FavoriteItemEntity::class, RouteEntity::class, LegEntity::class, CandidateLineEntity::class,
    FavoriteGroupEntity::class, ReminderEntity::class, SearchHistoryEntity::class, LineStopsCacheEntity::class], version = 1)
@ConstructedBy(TrackrDatabaseConstructor::class)
abstract class TrackrDatabase : RoomDatabase() { abstract fun favorites(): FavoritesDao /* … */ }

fun buildDatabase(builder: RoomDatabase.Builder<TrackrDatabase>) = builder
    .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
// androidMain / iosMain 各自给出 Room.databaseBuilder(...) 的文件路径

@Dao interface FavoritesDao {
    @Query("SELECT * FROM favorite_item ORDER BY sortOrder") fun observeAll(): Flow<List<FavoriteItemRow>>
    @Query("UPDATE favorite_item SET groupId = :groupId WHERE id = :id") suspend fun setGroup(id: String, groupId: String?)
    @Query("UPDATE favorite_item SET sortOrder = :order WHERE id = :id") suspend fun setSortOrder(id: String, order: Int)
    @Transaction suspend fun move(ids: List<String>) { ids.forEachIndexed { i, id -> setSortOrder(id, i) } }
}
```

旧数据迁移（只在 Android，一次性，失败不删旧数据）：
- `LegacyLibraryMigration`（`:core:data` 的 `androidMain`）：读旧 SharedPreferences 里的 JSON（`trackr_prefs/favorites_json`、`trackr_prefs/search_history_json`、`trackr_route_groups/groups_list_json`、`trackr_route_groups/favorite_groups_list_json`、`trackr_scheduled_reminders/reminders_list_json`），在一个 Room 事务里写入，成功后在 DataStore 记 `legacyMigrated = 1`。
- 设置用 DataStore 自带的 `SharedPreferencesMigration`（D1：旧聚合配置落成主数据源）。
- 旧 key 不删，保留一个发版周期；回退旧版本仍能读到。
- 首次读取不在主线程（修 P2）。[ANR] Path A
- 每个旧 key 写一条「读旧 JSON」测试；损坏的 JSON 不崩、跳过该项。
- 开关 `USE_NEW_STORE`：关掉时回到旧 SharedPreferences 实现（风险 R1）。

### 2.5 仓库拆分与 `ITransitProvider` 边界

| 新接口 | 职责 | 来自 |
| --- | --- | --- |
| `SettingsRepository` | 城市、数据源（单选，D1）、乘车码平台、通知/锁屏开关、提醒站数、`uiMode`、高德 Key、`hasMapKey: Flow<Boolean>`（D2） | `ITransitRepository` 设置部分 + `AmapKeyStore` + `FavoritesGroupViewModel` Key 部分 |
| `FavoritesRepository` | 收藏的车 + 路线 + 分组合一：`favoriteItems: Flow<List<FavoriteItem>>`、`move`、`setGroup`、`toggleStop`、`saveRoute` | `ITransitRepository.favorites` + `IRouteGroupRepository` |
| `RealtimeRepository` | 城市列表、附近、站点、线路、时刻表、搜索；按当前数据源选 Provider | `ITransitRepository` 查询部分 + `TransitProviderCoordinator` |
| `SearchHistoryRepository` | 搜索历史 | `ITransitRepository` |
| `ReminderRepository` | 定时提醒（已有接口，换存储） | `IScheduledReminderRepository` |
| `LocationRepository` | 当前定位 `Flow`，替代 `updateUserLocation` + `TransferAutoSwitchManager.updateUserLocation` 两处写入 | `ITransitRepository.userLocation` |
| `RoutePlanRepository` | 高德 POI 联想、公交规划；`AmapSdkTransitService` / `AmapTransitApiClient` 在其后 | `FavoritesGroupViewModel.kt:355-930` |
| `TrackingRepository` | 追踪会话单一数据源（见 2.6） | A4 |

`ITransitProvider` 边界：
- 保持现在的形状（`ITransitProvider.kt:16-52`）：一个数据源一个实现，只做「请求 → 领域模型」映射，不持有状态、不知道 UI。
- D1 后 `TransitProviderCoordinator` 退化为「按 `SettingsRepository.dataSource` 选一个 Provider」，删聚合分支和 `SearchResultAggregator`。[SPEC] §8 D1、[V3P] 第 3 步
- 一起等多线：`RealtimeRepository.getLineDetails(requests: List<LineDetailRequest>)` 改为：等车阶段只请求 1 次上车站的 `getStopDetail`（已带该站每条线的来车），条数不设上限；站点接口缺某条线时才对那条线单独请求（并发限 4，用户看不到）。Provider 接口不变。[V3]、Q8

### 2.6 前台服务与追踪状态：单一数据源

```
           ┌────────────── TrackingRepository（Koin single）──────────────┐
UI(VM) ──▶ │ session: StateFlow<TrackingSession?>   ← DataStore 持久化    │ ◀── 通知动作 Receiver
           │ start(target: TrackingTarget) / boarded(busId?) / next() / stop() │
           └──────────────┬──────────────────────────────────────────────┘
                          │ start 时用 sessionId 启动服务（不再传 19 个 extra）
                          ▼
              BusTrackingService（androidApp，by inject()）
                 轮询 RealtimeRepository → TrackingEngine.reduce(session, polls) → repository.update()
                 收集 session → 通知 / 实时活动 / 焦点通知 / 流体云 / 震动
```

| 设计点 | 方案 | 来源 |
| --- | --- | --- |
| 追踪目标 | `sealed interface TrackingTarget { SingleLine(lineKey, stopOrder) ; Route(groupId, startLeg) ; WatchTogether(stopId, lines: List<LineKey>) }`；一起等就是「只有 1 程、无下车站」的临时路线 | [V3]「一起等」、[V3P] 第 7 步 |
| 状态机 | `TrackingEngine`（纯函数，`:core:domain`）：`reduce(session, polls: Map<LineKey, List<LiveBus>>, now) -> session + effects`，effects = 震动 / 换乘提醒 / 结束；替代 `GroupLegTracker`、`TransferAutoSwitchManager` 两个 `object` | A4、A5 |
| 多线等车 | `watchedLines: List<WatchedLine>` 按 ETA 排序，第一条为主显示；「我上车了」= 此刻在站的那辆；两辆都在站 → `boardChoice` 非空，UI 弹「上了哪辆？」 | [V3]「上了哪辆？」 |
| 进程重建 | 会话写 DataStore；Service 改 `START_STICKY`，重建时从仓库读会话继续；若系统不允许重启前台服务，下次打开 App 首页追踪卡显示「追踪已中断 · 继续」 | S4；风险 R5 |
| UI 层入口 | `TrackingController` 接口（`start/stop/boarded`）注入 ViewModel，实现里调 `ContextCompat.startForegroundService`；ViewModel 不再收 `Context`（A8） | [AG] 第 4 条 |
| 通知 | `LiveProgressNotificationHelper` 改成可注入类，输入 `TrackingSession` + `ArrivalText`；D3：`VISIBILITY_PUBLIC`，关掉锁屏开关时 `VISIBILITY_PRIVATE` + `setPublicVersion` | [SPEC] D3、[V3P] 第 5 步 |

### 2.7 通知与小组件

| 项 | 推荐 | 理由 | 来源 |
| --- | --- | --- | --- |
| 小组件 | **迁 Glance**（`GlanceAppWidget` + `GlanceAppWidgetReceiver`），布局用 canonical layout「Text with image」或「Action list」改造为「收藏的车」列表；`SizeMode.Exact` | Compose 写法与主 App 共用 token；`GlanceAppWidgetReceiver` 自带会话 worker，避免经典 Provider 的 WorkManager 循环；状态用 `updateAppWidgetState` + `update()` | [GC]、[GU] Step 1、5 |
| 刷新 | 收藏变化时由数据层（`FavoritesRepository` 的应用级收集器）调 `TrackrWidget().updateAll(context)`；周期刷新用 `enqueueUniquePeriodicWork(KEEP)`；刷新按钮用 `actionRunCallback<RefreshAction>()`（无参构造 + R8 keep `<init>()`） | 消除 A9、S1 | [GU] Step 5 |
| 小组件图片 | 只用矢量图标，不放位图，不涉及内存上限 | — | [GU] Critical mandates |
| 不迁的备选 | 若暂不迁 Glance：`onReceive` 里 `goAsync()` 包住网络请求（<10s），刷新广播改成非导出显式 Intent | S1、S2 | [GU] Step 5 第 3 条、[IS] |
| 动态快捷方式 | 同样挪到数据层应用级收集器 | A9 | [AG] |

---

### 2.8 跨平台边界（CMP：Android + iOS，鸿蒙待评估）

共享与平台各自实现：

| 能力 | commonMain | Android 实现 | iOS 实现 | 备注 |
| --- | --- | --- | --- | --- |
| 页面、ViewModel、导航 | 全部 | — | — | CMP 版 Navigation 3（JetBrains 发布的 `navigation3-ui` 与 `lifecycle-viewmodel-navigation3`，执行时核对版本） |
| 主题 | `TrackrTheme(uiMode)` | 不用动态取色（v3 已定固定主色） | 同 | CMP 的 Material 3 版本可能晚于 Android，`MaterialExpressiveTheme` 不一定可用 → 风险 R15 |
| 网络 | Ktor `HttpClient` + kotlinx.serialization | OkHttp 引擎（沿用现有拦截器思路） | Darwin 引擎 | |
| 加解密（车来了） | `expect fun aesDecrypt / md5`；Base64 用 `kotlin.io.encoding.Base64` | `javax.crypto`、`MessageDigest` | CommonCrypto | 现 `ChelaileCrypto.kt` 用了 `javax.crypto` 和 `java.util.Base64` |
| 时间 / UUID | `kotlinx-datetime`、`kotlin.uuid.Uuid` | — | — | 现有 5 个文件用 `java.time` / `SimpleDateFormat` |
| 存储 | Room、DataStore | 文件路径 `context.filesDir` | `NSDocumentDirectory` | |
| 定位 | `LocationSource` 接口 | 高德定位 / 系统定位 | `CLLocationManager` | 坐标转换 `CoordTransform` 放 common |
| 地图（路线页） | `expect @Composable fun RouteMap(...)` | 高德 Android SDK + `AndroidView` | 高德 iOS SDK + `UIKitView`（第 38 步） | 规划算路走高德 Web 服务（Ktor，common）；Android 保留 SDK 算路作为后备 |
| 乘车码 | `TransitCodeLauncher` 接口 | Intent 打开支付宝 / 云闪付 | URL Scheme（需在 Info.plist 声明可查询的 scheme） | |
| 追踪 | `TrackingEngine`、`TrackingRepository`（common）；`TrackingController` 接口 | 前台服务 + 常驻通知 + 实时活动 / 焦点通知 / 流体云 | App 在前台时轮询；本地通知；Live Activity（Swift）——后台限制见 Q13 | |
| 锁屏 | — | 通知 `VISIBILITY_PUBLIC`（D3） | Live Activity 本身就在锁屏 | |
| 朗读 / 语音输入 | `Announcer`、`SpeechInput` 接口 | `TextToSpeech`、`RecognizerIntent` | `AVSpeechSynthesizer`、`SFSpeechRecognizer` | 长辈版用 |
| 小组件 | — | Glance | WidgetKit（Swift，App Group 共享数据库，第 39 步，可选） | |
| 定时提醒 | `ReminderRepository`（common） | `AlarmManager` + 开机广播 | `UNCalendarNotificationTrigger` | |

鸿蒙：

| 事实 | 影响 |
| --- | --- |
| Kotlin 官方没有鸿蒙（HarmonyOS NEXT / OpenHarmony）编译目标，JetBrains 的 CMP 也不支持 | 现在不能直接出鸿蒙版 |
| 有厂商和社区在做 KMP / Compose 到鸿蒙的移植 | 第 40 步做一次评估，比较可行方案再定 |
| 现在能做的准备 | `commonMain` 严格不碰 JVM / Android API（§2.1 规则）；业务逻辑都在 domain / data，UI 只是一层。将来不管走哪条路，共享代码都能复用 |

## 3. Navigation 3 整体方案

### 3.1 Tab 结构

| Tab（顶层 NavKey） | 根页 | 子页 | 出现条件 | 来源 |
| --- | --- | --- | --- | --- |
| 公交 `ScreenKey.Bus` | 首页（搜索、追踪卡、收藏、附近） | Search、LineDetail、StopDetail、RouteDetail、FavoritesEdit | 总是 | [V3] §2 |
| 路线 `ScreenKey.RoutePlan` | 地图规划 | LineDetail、RouteDetail | `SettingsRepository.hasMapKey == true` | [SPEC] D2 |
| 我的 `ScreenKey.Me` | 设置 | About、ReminderEdit、AmapKey | 总是 | [V3] §4「我的」 |

- 「收藏」Tab 删除，收藏并入公交首页；`ScreenKey.FavoritesGroup` 在过渡期保留，最后一步删除。[V3P] 第 8、17 步
- Key 被清空且当前在「路线」Tab：`Navigator` 切回「公交」，路线栈清空。[V3P] 第 4 步
- 返回规则沿用 recipe 的 exit-through-home：非起始 Tab 在根页按返回 → 回「公交」；「公交」根页按返回 → 退出。[N3] multiple-backstacks

### 3.2 NavKey 设计

```kotlin
@Serializable sealed interface ScreenKey : NavKey {
    // 顶层
    @Serializable data object Bus : ScreenKey           // 原 Home
    @Serializable data object RoutePlan : ScreenKey
    @Serializable data object Me : ScreenKey            // 原 More

    // 公交 Tab 子页
    @Serializable data object Search : ScreenKey
    @Serializable data class LineDetail(val line: LineRef, val stopOrder: Int = 0, val stopId: String = "") : ScreenKey
    @Serializable data class StopDetail(val stop: StopRef) : ScreenKey
    @Serializable data class RouteDetail(val groupId: String) : ScreenKey         // 新
    @Serializable data object FavoritesEdit : ScreenKey                          // 新
    // 弹层型目的地（BottomSheetSceneStrategy，见 3.7）
    @Serializable data class SaveFavorite(val target: FavoriteDraftRef) : ScreenKey  // 新：只问名字和分组
    @Serializable data class ChooseBoardedBus(val sessionId: String) : ScreenKey      // 新：「上了哪辆？」

    // 我的 Tab 子页
    @Serializable data object About : ScreenKey
    @Serializable data class ReminderEdit(val reminderId: String?) : ScreenKey    // 新：提醒能改
}

/** 线路引用：替代原 LineDetail 里散开的 cityId/lineId/source/lineName 6 个字段 */
@Serializable data class LineRef(val cityId: String, val lineId: String, val source: DataSourceType, val nameHint: String = "")
```

| 规则 | 说明 | 来源 |
| --- | --- | --- |
| Key 只放 id 和必要的展示提示 | 实体从仓库读；Key 要能序列化进 Bundle（进程重建） | [N3] basicsaveable、passingarguments |
| 原 `ScreenKey.LineDetail(cityId, lineId, targetOrder, stopId, source, lineName)` 迁到 `LineRef` | 保留旧序列化名的兼容解析只需在 deep link 层做；`rememberNavBackStack` 里的旧 Key 在升级后第一次冷启动时会因类名变化恢复失败 → 回落到根页，可接受 | [N3] basicsaveable |
| 删掉 6 / 7 参数的 `dropUnlessResumed` 重载 | 回调改成 `onOpen(key: ScreenKey)`，一个参数即可用 lifecycle 自带的 `dropUnlessResumed {}` | `NavigationUtils.kt:13-37` |

### 3.3 多 Tab 各自返回栈（标准写法）

按 [N3] multiple-backstacks recipe 改写，加上「Tab 动态出现」：

```kotlin
// ui/navigation/NavigationState.kt
@Composable
fun rememberNavigationState(start: ScreenKey, topLevel: Set<ScreenKey>): NavigationState {
    val current = rememberSerializable(serializer = NavKeySerializer()) { mutableStateOf<NavKey>(start) } // 跨进程恢复当前 Tab
    val stacks = topLevel.associateWith { rememberNavBackStack(it) }  // 3 条栈总是创建，可见性另算
    return remember(start, topLevel) { NavigationState(start, current, stacks) }
}

class NavigationState(val start: NavKey, current: MutableState<NavKey>, val stacks: Map<NavKey, NavBackStack<NavKey>>) {
    var topLevel: NavKey by current

    @Composable
    fun toDecoratedEntries(entryProvider: (NavKey) -> NavEntry<NavKey>): List<NavEntry<NavKey>> {
        val decorated = stacks.mapValues { (_, stack) ->
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),  // 每条栈一个（修 N2）
                    rememberViewModelStoreNavEntryDecorator(),       // ViewModel 作用域 = entry
                ),
                entryProvider = entryProvider,
            )
        }
        val inUse = if (topLevel == start) listOf(start) else listOf(start, topLevel)
        return inUse.flatMap { decorated.getValue(it) }
    }
}

// ui/navigation/Navigator.kt —— 纯 Kotlin 逻辑，单元测试直接测（修 N7）
class Navigator(private val state: NavigationState) {
    private val _reselect = MutableSharedFlow<NavKey>(extraBufferCapacity = 1)
    val reselect = _reselect.asSharedFlow()          // 再点当前 Tab：列表回顶 / 栈回根

    fun selectTab(tab: NavKey) {
        if (tab == state.topLevel) { state.stacks.getValue(tab).popToRoot(); _reselect.tryEmit(tab) }
        else state.topLevel = tab
    }
    fun open(key: NavKey) {
        val stack = state.stacks.getValue(state.topLevel)
        if (stack.lastOrNull() != key) stack.add(key)          // 栈顶去重（保留现有行为）
    }
    fun back(): Boolean {                                       // 返回 false 表示交给系统退出
        val stack = state.stacks.getValue(state.topLevel)
        return when {
            stack.size > 1 -> { stack.removeLastOrNull(); true }
            state.topLevel != state.start -> { state.topLevel = state.start; true }
            else -> false
        }
    }
    fun onTabsChanged(visible: Set<NavKey>) {                  // D2：Key 被清空
        if (state.topLevel !in visible) { state.stacks.getValue(state.topLevel).popToRoot(); state.topLevel = state.start }
    }
    fun applyDeepLink(stack: List<NavKey>) { /* 见 3.5 */ }
}
```

```kotlin
// MainActivity 中的根 Composable（缩到 ~120 行）
@Composable
fun TrackrStandardApp(shell: AppShellState, navigator: Navigator, state: NavigationState) {
    val tabs = if (shell.hasMapKey) listOf(Bus, RoutePlan, Me) else listOf(Bus, Me)
    LaunchedEffect(tabs) { navigator.onTabsChanged(tabs.toSet()) }
    Scaffold(bottomBar = { if (state.isAtTabRoot) TrackrNavigationBar(tabs, state.topLevel, navigator::selectTab) }) { padding ->
        NavDisplay(
            entries = state.toDecoratedEntries(standardEntryProvider(navigator)),
            onBack = { navigator.back() },
            sceneStrategy = remember { BottomSheetSceneStrategy<NavKey>() },   // 3.7
            transitionSpec = TrackrTransitions.push, popTransitionSpec = TrackrTransitions.pop,
            predictivePopTransitionSpec = TrackrTransitions.predictivePop,     // 3.6
        )
    }
}
```

`isTopLevelSwitch` 那段 4 个 `is` 判断（`MainActivity.kt:480,490`）改成 `key in TOP_LEVEL`。[N3] animations

### 3.4 ViewModel 作用域

| 页面 | ViewModel | 作用域 | 跨页共享方式 | 来源 |
| --- | --- | --- | --- | --- |
| Bus 首页 | `BusHomeViewModel` | entry | 收藏、追踪会话、定位都从仓库 Flow 读 | [N3] passingarguments |
| Search | `SearchViewModel`（新，从 `HomeViewModel` 拆出搜索、历史、防抖） | entry；输入框文字放 `SavedStateHandle` | 城市从 `SettingsRepository` 读 | N3（修 N3 问题） |
| LineDetail / StopDetail | 现有 VM，改 Koin `parametersOf(key)` 注入 | entry，按 Key 区分实例 | — | [N3] modular-hilt |
| RouteDetail | `RouteDetailViewModel`（新） | entry | 路线从 `FavoritesRepository`，实时从 `RealtimeRepository`，追踪从 `TrackingRepository` | [V3P] 第 8 步 |
| FavoritesEdit | `FavoritesEditViewModel`（新） | entry | 写 `FavoritesRepository` | 同上 |
| RoutePlan | `RoutePlanViewModel`（新，从 `FavoritesGroupViewModel` 拆出规划、POI、方案） | entry（Tab 根，Tab 存在期间常驻） | 收藏写 `FavoritesRepository` | 同上 |
| Me | `MeViewModel`（原 `MoreViewModel`） | entry | `SettingsRepository`、`ReminderRepository` | — |
| 应用壳 | `AppShellViewModel`：`hasMapKey`、`uiMode`、权限引导状态、追踪中提示 | Activity | 只放跨所有页面的壳状态 | [AG] |

地图 `AmapMapSession` 仍挂在 Activity 级（`MainActivity.kt:178`），因为 GL 视图昂贵、要跨 Tab 复用；但用 `remember` 包装成 `rememberAmapMapSession()`，只在 RoutePlan Tab 存在时创建。

### 3.5 跨页结果传递

| 场景 | 方案 | 理由 | 来源 |
| --- | --- | --- | --- |
| 线路页点 ☆ → Snackbar「已收藏到 上班 · 换分组」→ 选分组 | 选分组弹层直接写 `FavoritesRepository.setGroup(id, groupId)`；线路页和首页都观察仓库 | 结果本身是持久数据，走仓库最简单，进程重建不丢 | [AG] 第 1 条（单一数据源） |
| 规划页「收藏」→ `SaveFavorite` 弹层 → 回到规划页显示「已收藏 · 去看看」 | 弹层保存后用 **ResultEventBus** 回传 `SavedFavorite(id)` 事件；规划页 `ResultEffect<SavedFavorite>` 里加一条 userMessage | 「去看看」需要一次性事件，不是持久状态 | [N3] results-event |
| 「编辑收藏」里给一条收藏选分组 | 同页弹层，本地状态，不需要导航结果 | — | — |

```kotlin
// 发送方（SaveFavorite 弹层）
val bus = LocalResultEventBus.current
Button(onClick = { vm.save { id -> bus.sendResult(SavedFavorite(id)); navigator.back() } }) { Text("收藏") }
// 接收方（RoutePlan）
ResultEffect<SavedFavorite> { vm.onFavoriteSaved(it.id) }
```

> `navigation3-runtime` 1.2.0 是否已内置 `androidx.navigation3.runtime.result.*`，需在第 18 步开工时核实；没有则按 recipe 拷贝 `ResultEventBus` + `ResultEventBusNavEntryDecorator`（约 60 行）到 `ui/navigation/result/`。[N3] results-event

### 3.6 Deep link

现在 3 个入口（小组件、动态快捷方式、通知）都用 6 个 extra（`MainActivity.kt:124-148`）。统一为 URI，并在一个纯函数里解析：

| URI | 解析出的合成返回栈 | 用于 |
| --- | --- | --- |
| `trackr://line/{cityId}/{lineId}?source=&stop=&order=` | 公交：`[Bus, LineDetail]` | 小组件「收藏的车」、动态快捷方式、单线追踪通知 |
| `trackr://route/{groupId}` | 公交：`[Bus, RouteDetail]` | 路线追踪通知、首页追踪卡（[V3]「路线去路线详情」） |
| `trackr://stop/{cityId}/{stopId}` | 公交：`[Bus, StopDetail]` | 一起等通知 |
| `trackr://tracking/board?session=` | 当前栈 + `ChooseBoardedBus` 弹层 | 通知「我上车了」在两辆同时在站时 |
| `trackr://transit-code?provider=` | 不入栈，直接拉起乘车码 | 静态快捷方式（替代 `OPEN_TRANSIT_CODE` action，旧 action 兼容一个版本） |

```kotlin
object DeepLinkParser {   // 纯 Kotlin，JVM 单测
    fun parse(uri: Uri): DeepLink? = when (uri.host) {
        "line" -> uri.pathSegments.takeIf { it.size == 2 }?.let { (city, line) ->
            DeepLink.Stack(Bus, listOf(LineDetail(LineRef(city.validId(), line.validId(), uri.source()), uri.int("order"), uri.str("stop"))))
        }
        "route" -> DeepLink.Stack(Bus, listOf(RouteDetail(uri.lastPathSegment!!.validId())))
        // …
        else -> null
    }
}
```

| 规则 | 说明 | 来源 |
| --- | --- | --- |
| 合成返回栈 | deep link 进入时把目标 Tab 栈重置为 `[根, 目标]`，按返回回到该 Tab 根页而不是退出 App | [N3] deeplinks-syntheticbackstack |
| 一次性消费 | 冷启动 `savedInstanceState == null` 才处理 `intent`；`onNewIntent` 总是处理；处理完 `intent.data = null`（保留现有防重放，`MainActivity.kt:108-111,141-147`） | [N3] deeplinks-staticuri |
| 外部输入校验 | `MainActivity` 是导出的：id 只允许 `[A-Za-z0-9:_-]{1,64}`，未知 host 忽略；不支持把外部 Intent 转发给内部组件 | [IS] |
| PendingIntent | 全部 `FLAG_IMMUTABLE` + 显式组件（现状已是 IMMUTABLE，`BusStopWidgetProvider.kt:161,173`，保留） | [IS] |
| 两种 UI 模式共用 | 解析器只产出 `DeepLink`，由当前模式的导航器决定怎么落栈（见 §8.4） | [ELD] |

不在 Manifest 注册 `trackr://` 的 `<intent-filter>`（只内部 PendingIntent 用显式 Intent + data），避免对外暴露。若以后要外部 App 打开，再加 filter。[IS]

### 3.7 Predictive back 与弹层

| 项 | 方案 | 来源 |
| --- | --- | --- |
| 页面级返回 | 交给 `NavDisplay(onBack)` 内置的 predictive back；保留 `predictivePopTransitionSpec` | [NE] Step 1 第 2 条 |
| 组件内拦截 | `GroupEditDialog.kt:96`（未保存确认）、`RoutePlanScreen.kt:265`（联想下拉）从 `BackHandler` 迁 `NavigationBackHandler`；同一个 `NavigationEventState` 只绑一个 handler，条件分支写在 `onBackCompleted` 里 | [NE] Troubleshooting 4、Checklist |
| Manifest | `enableOnBackInvokedCallback="true"` 已设，保留 | [NE] Checklist |
| 弹层是否用 Scene | **按「是否需要从别处直接打开 / 进程重建后还在」区分**：`SaveFavorite`、`ChooseBoardedBus`（会被通知 deep link 打开）做成 NavKey + `BottomSheetSceneStrategy`；时刻表、城市选择、乘车码平台选择等页内小弹层保持 `ModalBottomSheet` 本地状态 | 前者需要进栈才能被 deep link 和返回手势统一管理；后者进栈反而增加样板 | [N3] bottomsheet |
| 弹层返回 | `ModalBottomSheet` / `Dialog` 自带 dispatcher owner，不要再手动 `CompositionLocalProvider` | [NE] Troubleshooting 2 |
| 平板 / 折叠屏 | 本轮不做双栏；`NavDisplay` 已接受 `sceneStrategy`，以后加 list-detail（收藏列表 + 线路详情）不需要改 Key | [AD] Step 3、[N3] scenes-listdetail |

### 3.8 进程重建后的状态恢复

| 状态 | 保存位置 | 验证 |
| --- | --- | --- |
| 3 条返回栈 | `rememberNavBackStack`（Key 可序列化） | `NavigationStateRestorationTest`：`StateRestorationTester.emulateSavedInstanceStateRestore()` 后栈内容一致 |
| 当前 Tab | `rememberSerializable` | 同上 |
| 页内输入（搜索词、起终点文字、站点页已勾选的线） | `SavedStateHandle`（VM）或 `rememberSaveable` | 各 VM 测试构造带值的 `SavedStateHandle` |
| 列表滚动位置 | `rememberSaveableStateHolderNavEntryDecorator`（每栈一个） | 同上 |
| 追踪会话 | DataStore（2.6） | `TrackingRepositoryTest`：新建仓库实例后会话仍在 |
| UiMode | DataStore（§8） | `AppShellViewModelTest` |

来源：[N3] multiple-backstacks、basicsaveable；[TS] Step 9「Always verify state restoration」；[AD] Step 6。

---

## 4. Compose 性能方案

### 4.1 稳定性

| 做法 | 说明 | 来源 |
| --- | --- | --- |
| 依赖 Strong Skipping（Kotlin 2.x Compose 编译器默认开） | 不追求「全部可跳过」；先让 composable 便宜 | [CP] §1「Skipping Isn't Free」 |
| **不**给含 `List` 的领域模型加 `@Immutable`/`@Stable`，**不**换 `ImmutableList`，**不**把集合写进 `stability_config.txt` | `BusLineDetail`、`NearbyStop`、`TransitGroup` 都含集合，`.equals()` 是 O(N) | [CP] §1「Never Add…to Collection Models」 |
| UI 层映射成扁平 UI 模型 | 例：`ArrivalRowUi(key: String, lineName: String, toward: String, big: String, small: String, tone: Tone)`，在 `:app`（Compose 模块）里定义，编译器自动推断稳定 | [CP] §1「Flat data classes … already inferred as stable」 |
| 轮询结果复用旧实例 | ViewModel 合并新数据时，按 key 比较，`equals` 相同就沿用旧对象，列表引用本身只在有变化时替换 → 强跳过的 `===` 生效 | [CP] §1「When to Add…」（数据源每次都分配新实例的场景） |
| `stability_config.txt` | 只列 `:core:model` 里**扁平、无集合**、且直接作为 Composable 参数的类：`com.trackr.domain.model.TransitCity`、`BusStop`、`LiveBus`、`CandidateLine`、`FavoriteGroup`、各枚举。它们搬到非 Compose 模块后会被判为不稳定 | [CP] §1「only add … when the compiler cannot infer stability (classes from non-Compose modules)」 |

```kotlin
// app/build.gradle.kts（第 1 步加）
composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf"))
    if (providers.gradleProperty("composeReports").isPresent) {
        reportsDestination = layout.buildDirectory.dir("compose_reports")
        metricsDestination = layout.buildDirectory.dir("compose_metrics")
    }
}
```

### 4.2 大文件拆分

| 文件 | 拆成 | 来源 |
| --- | --- | --- |
| `MainActivity.kt` 740 行 | `MainActivity`（~60 行：edge-to-edge、intent → DeepLinkParser）、`ui/app/TrackrApp.kt`（模式分发）、`ui/navigation/{NavigationState,Navigator,StandardEntryProvider,DeepLinkParser,TrackrTransitions}.kt`、`ui/app/PermissionOnboarding.kt` | [N3] multiple-backstacks |
| `FavoritesGroupViewModel.kt` 1530 行 | `RoutePlanViewModel`、`RouteDetailViewModel`、`FavoritesEditViewModel`；Key 配置 → `MeViewModel` + `SettingsRepository`；迁移 → 数据层；实时匹配 `findRealtimeLineMatch`/`alignTransitGroupWithRealtimeSource`/`buildReverseTransitGroup` → `:core:data` 的 `RouteAlignmentService` | [V3P] 第 8 步、[AG] 第 4 条 |
| `RoutePlanScreen.kt` 2491 行 | `routeplan/RoutePlanRoute.kt`、`RoutePlanScreen.kt`（布局）、`map/AmapMapView.kt`（`AndroidView` + 4 个 `LaunchedEffect`）、`panel/SchemePanel.kt`、`panel/SegmentBar.kt`、`dialog/SaveFavoriteSheet.kt`、`ui/components/amap/{AmapKeyDialog,MapDiagnosticsDialog}.kt`；删 `RoutePlanVectorMapCanvas`、`drawMarkerBadge`、`drawTransferBadge`、`MapUnavailableNotice`（D2） | [SPEC] D2、[CP] §1 |
| `HomeScreen.kt` 1109 行 | `bus/home/{BusHomeScreen,TrackingCard,FavoritesSection,NearbySection}.kt`；删 `TransitCodeQuickCard`，新增 `ui/components/TransitCodeFab.kt` | [V3] 首页 |
| `SearchScreen.kt` 839 行 | `SearchScreen` + `SearchResultList` + `SearchHistory`；删推荐词与双引擎卡 | [V3] 搜索 |
| `ExpressiveRouteRail.kt` 922 行 | 只留竖向（删 `RouteRailLayoutMode.Horizontal`），拆 `RailNode`、`RailBusMarker`、`RailDraw`（`drawWithCache`） | [V3] §6「走向条只留竖向」、[CP] §6 |

### 4.3 Lazy 列表

| 规则 | 落点 | 来源 |
| --- | --- | --- |
| 所有 `items` 都给 primitive `key` + `contentType` | 现状大多已满足；补 `RoutePlanScreen.kt:687`；新页面（路线详情时间轴、编辑收藏、站点选线）按此写 | [CP] §3 |
| 首页收藏合一列表 | `contentType = { it::class.simpleName }`（`Stop`/`Route` 两种），key 用 `"stop_${id}"`/`"route_${id}"` | [CP] §3 |
| 小列表不用 Lazy | 路线详情每程候选线（通常不多）、追踪卡里折叠后的一起等列表；站点页选线条数不设上限，超过 8 条改 `LazyColumn`用 `Column` + `forEach` | [CP] §3「Small & Bounded Lists」 |
| 选择状态 | 站点页勾选用 `Set<String>`（VM 内），item 拿 `isChecked: () -> Boolean` | [CP] §3「Observable Selection」 |
| `LocalContext` 等不在 item 里读 | 首页附近站卡片现在在 item 内取 context 打开地图/详情的写法改为父级传回调 | [CP] §3「Propagate CompositionLocals」 |
| 编辑收藏拖动排序 | `Modifier.animateItem()` + 稳定 key | [CP] §3 |

### 4.4 高频刷新（实时到站）把重组限制在最小范围

| 手段 | 说明 | 来源 |
| --- | --- | --- |
| 删每秒倒计时 | v3 不显示倒计时，`CountdownPollingStrategy` 改 `FixedDelayPollingStrategy`；如果保留「n 秒前更新」，用一个独立 `StateFlow<Long>` 只在显示它的 `Text` 里读 | C1、[CP] §1 Phase Deferral、[V3] |
| 状态按变化频率拆 | `LineDetailUiState` 拆成 `static`（线路、站表，几乎不变）+ `live`（车辆位置、ETA，每 10s）；走向条只接收 `live.buses`，头部只接收 `static` | [CP] §1、§2 |
| 车辆位置用延迟读取 | 走向条上车的移动 `Modifier.offset { IntOffset(…progress()…) }`，传 `progress: () -> Float` | [CP] §2「Phase Deferral for Offsets」、§4「Lambda Providers」 |
| ETA 数字动画 | `AnimatedContent` 只包住大数字 `Text`，不包整卡 | [CP] §4 |
| 首页收藏行 | 每行 `ArrivalRowUi` 扁平模型 + 复用实例（4.1），只有 ETA 变了的行重组 | [CP] §1 |
| 轮询生命周期 | `PollingCoordinator` 绑定 `repeatOnLifecycle(STARTED)`（经 `SharingStarted.WhileSubscribed(5_000)` 驱动），页面不可见停止轮询；测试仍用 `enableAutoPolling = false` | [AG] 第 3 条、[TD] §2 |

### 4.5 动画

| 项 | 方案 | 来源 |
| --- | --- | --- |
| 已正确的保留 | 呼吸、脉冲在 `graphicsLayer {}` 里读（C4） | [CP] §4 |
| 悬浮乘车码按钮伸缩 | 用列表 `LazyListState` 派生 `expanded`（`remember { derivedStateOf { state.lastScrolledBackward || state.firstVisibleItemIndex == 0 } }`），按钮宽度用 `animateContentSize` 或 M3 `ExtendedFloatingActionButton(expanded=)` | [CP] §1 derivedStateOf |
| 底栏下沉隐藏 | 现有 `NestedScrollConnection` + `graphicsLayer { translationY }` 已是延迟读取，保留（`MainActivity.kt:330-357,385-388`） | [CP] §4 |
| Tab / 页面切换 | 统一放 `TrackrTransitions`，用 `MotionScheme.expressive()` 的 spec | [N3] animations |

### 4.6 Baseline Profile 与 Macrobenchmark

| 项 | 方案 | 来源 |
| --- | --- | --- |
| 模块 | 新增 `:baselineprofile`（`com.android.test` + `androidx.baselineprofile` 插件），`:app` 加 `profileinstaller` | [CP] Foundational prerequisites 第 3 条 |
| 关键旅程 | ① 冷启动到首页收藏可见；② 首页滚动；③ 打开线路详情并等走向条渲染；④ 切到「我的」 | 同上 |
| 基准 | `StartupBenchmark`（`StartupTimingMetric`）、`ScrollBenchmark`（`FrameTimingMetric`），对比 `CompilationMode.None` 与 `Partial(BaselineProfile)` | 同上 |
| 网络 | benchmark 变体用 `FakeTransitProvider`（`benchmark` buildType 启动时加载 Koin 的 `fakeDataModule`） | 稳定可复现 |
| 只测 release | benchmark buildType `initWith(release)` + `isDebuggable = false`、R8 开 | [CP] Foundational prerequisites 第 1–2 条 |

### 4.7 怎么测量

| 工具 | 用法 | 什么时候 |
| --- | --- | --- |
| Compose compiler reports | `./gradlew :androidApp:assembleRelease -PcomposeReports`，看各模块 `build/compose_reports/*-composables.txt` 里 `restartable` 但非 `skippable` 的、`*-classes.txt` 里 `unstable` 的类 | 第 1 步建基线；每个页面步骤结束对比 |
| Layout Inspector 重组计数 | Debug 包，线路详情停留 30 秒，记录走向条 / 主卡的重组次数（目标：轮询一次只重组 ETA 相关节点） | 第 20 步前后 |
| Macrobenchmark | 第 27 步之后每个大改动跑一次，记录 `timeToInitialDisplayMs`、`frameDurationCpuMs P90` | 第 27 步起 |
| 系统 trace / Perfetto | 只在 benchmark 退化时用 | 按需 |

---

## 5. 测试策略

### 5.1 分层

| 类型 | 范围 | 运行方式 | 框架 | 来源 |
| --- | --- | --- | --- | --- |
| 纯逻辑单测 | `:core:domain`（`ArrivalText`、`TrackingEngine`、`RouteProjectionMath`、`DeepLinkParser`、`Navigator`） | 本地 JVM，无 Robolectric | JUnit4 | [TS] Step 5 |
| 数据层 | 各 Repository（DataStore 用临时目录文件）、迁移（造旧 SharedPreferences 再跑迁移）、Provider（`MockWebServer` 或现有 `BaseTransitApiClientTest` 的方式） | Robolectric（DataStore 迁移需要 Context） | JUnit4 + Turbine | [TS] Step 5、[TD] |
| ViewModel | 每个 VM 直接 new，传 `:core:testing` 里的 Fake；轮询 VM 传 `enableAutoPolling = false`，`advanceTimeBy` 控时 | 本地 JVM / Robolectric | coroutines-test + Turbine + `MainDispatcherRule` | [AG]、[TD] §2 |
| Compose UI 行为 | 无状态 `XxxScreen(state, onEvent)`：给不同 state 断言显示、点击回调；语义匹配优先，>3 个 matcher 才用 `testTag`；每屏至少一个状态恢复测试 | Robolectric（`test` 源集） | `createComposeRule` | [TS] Step 6、9 |
| 导航 | `NavigatorTest`（纯 JVM）：Tab 切换、再点当前 Tab 回根、exit-through-home、D2 Tab 消失、deep link 合成栈、栈顶去重；`TrackrAppNavigationTest`（Robolectric + Compose）：返回手势、状态恢复 | 两者 | — | [TS] Step 10 |
| 截图 | 见 5.3 | — | — | [TS] Step 3、8 |
| 服务与通知 | `BusTrackingServiceTest`（Robolectric `ServiceController`，Koin 测试模块注入 Fake 仓库）；`LiveProgressNotificationHelperTest` 加 VISIBILITY 用例 | Robolectric | Koin `testModule` | [TS] Step 2、12 |
| 小组件 | Glance `runGlanceAppWidgetUnitTest {}` 断言文本；`updateAll` 被调用 | Robolectric | glance-appwidget-testing | [GU] |
| 端到端 | 约 5%：冷启动 → 收藏 → 追踪 → 通知「结束」。需要设备 | `androidTest` | UI Automator | [TS] Step 12 |

### 5.2 Fake 与 DI 配合

```kotlin
// :core:testing
class FakeFavoritesRepository : FavoritesRepository {
    val items = MutableStateFlow<List<FavoriteItem>>(emptyList())
    override val favoriteItems: Flow<List<FavoriteItem>> = items
    override suspend fun setGroup(id: String, groupId: String?) { items.update { … } }
}

@Module @TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
abstract class FakeDataModule {
    @Binds abstract fun favorites(f: FakeFavoritesRepository): FavoritesRepository
    // …
}
```

- ViewModel 单测**不用** Koin，直接构造，最快；纯逻辑和 ViewModel 测试放 `commonTest`，Android 和 iOS 都跑。[TS] Step 4
- `FakeTransitRepository`（`app/src/test/.../FakeTransitRepository.kt`）在仓库拆分（第 8 步）时按新接口拆成多个 Fake，移入 `:core:testing`；旧类保留到所有测试迁完再删，保证 [AG]「数据层测试用 FakeTransitRepository」的约定在过渡期一直成立（AGENTS.md 第 1 条在第 8 步同步改为「用 `:core:testing` 里的 Fake」）。
- 新增 `FakeClock`、`FakeAnnouncer`、`FakeTrackingController`、`FakeTransitCodeLauncher`。
- Mockk 不引入，除非遇到无法写 Fake 的第三方类（高德 SDK 已有封装接口）。[TS] Step 3

### 5.3 截图测试是否引入

| 选项 | 说明 | 推荐 |
| --- | --- | --- |
| Compose Preview Screenshot Testing（AGP 插件） | [TS] 默认推荐。AGP 9.2.1 < 9.5.0-alpha03，只能用**已标记 deprecated 的独立插件** `com.android.compose.screenshot`；升到 9.5 后再迁 test suites | 推荐（Q5 待拍板） |
| Roborazzi（Robolectric） | 和现有 Robolectric 栈一致，可复用 `ScreenshotGeneratorTest` 的数据构造 | 备选 |
| 不引入 | 保留 `ScreenshotGeneratorTest` 只生成图 | 不推荐：v3 + 长辈版两套主题，视觉回归风险高 |

截图覆盖：每屏 400×500dp 标准主题浅色/深色、字体 1.5 倍、长辈版主题；组件级（ETA 大字 8 种状态、线路卡、追踪卡）。全尺寸 9 宫格（400/610/900 × 400/500/1000）只给首页和线路页。[TS] Step 8

---

## 6. 分阶段执行计划

### 6.0 总规则

| 规则 | 说明 | 来源 |
| --- | --- | --- |
| 一步一个提交 | 不同功能分开提交；提交信息沿用仓库现有风格「模块：做了什么」 | 父任务要求、[V3P] |
| 每步结束 | 能编译、能装机运行、`./gradlew test` 通过；步骤里列的专项测试先跑，内存紧张时只跑专项，阶段最后一步跑全量 | [AG]、[V3P] 头部 |
| 不破坏旧数据 | 涉及存储的步骤都带「读旧数据」测试；迁移不删旧 key | [V3P] 风险表 |
| 并行 | 标「∥」的步骤之间不碰同一批文件，可交给不同子代理；同组内仍各自一个提交，合并顺序按步号 | — |
| 回退 | 每步是一个提交，回退 = `git revert <该提交>`；标了「开关」的步骤另有运行时开关 | — |

依赖总览（→ 表示「必须在之后」）：

```
0 → 1 → 2 → 3 → 4
          ↘ 5 ∥ 6（可与 3、4 并行）
4,6 → 7 → 8 → 9 → {10 ∥ 11 ∥ 13} ; 9 → 12 → 15 ; 9 → 14 → 15
8 → 16 → 17 → 18
17,14,6,5 → 19 ; 17,6 → 20 ; 17,15 → 21 ; 17,14,15 → 22 ; 17 → 23 ; 17,11 → 24 ; 17,13 → 25
19..25 → 26 ∥ 27 ∥ 28 → 29
5,9,18,19,20 → 30 → 31 → 32 → {33 ∥ 34}
```

阶段与步数：**7 个阶段（0、A–F），共 35 步（第 0–34 步）**。

### 阶段 0：开工前

| 步 | 目标 | 改动 | 依赖 | 验证 | 风险 / 回退 |
| --- | --- | --- | --- | --- | --- |
| 0 | 干净的起点 + 基线 | 提交设计与计划文档；记录基线：`./gradlew test` 用时与用例数、`./gradlew :app:assembleRelease` APK 大小、冷启动时间 | — | `git status` 干净；基线数字写进附录 | 无 |

### 阶段 A：工具链与工程骨架（无用户可见变化）

| 步 | 目标 | 改动文件 | 依赖 | 验证 | 风险 / 回退 |
| --- | --- | --- | --- | --- | --- |
| 1 | 升级工具链 + 诊断（Q12） | AGP 升到当时最新稳定版（9.5 已稳定就到 9.5，可用 test suites 截图）、Kotlin、KSP、Gradle、Compose BOM；版本目录加 CMP、Koin、Ktor、Room、DataStore、`kotlinx-datetime` 条目（暂不应用）；`composeCompiler { stabilityConfigurationFiles; reports(-PcomposeReports) }`；根目录 `compose_stability.conf` | 0 | `./gradlew test`、`./gradlew :app:assembleRelease`；装机冒烟；compose reports 存一份基线 | 新 AGP 默认行为变化 → 按 [AGP] 逐项核对；单独提交，失败 revert |
| 2 | 约定插件 + 拆出 `:androidApp` | 新增 `build-logic/convention`（`trackr.android.application`、`trackr.kmp.library`：Android KMP 库插件 + `iosArm64` + `iosSimulatorArm64` + 开启 Android 主机测试、`trackr.kmp.compose`、`trackr.kmp.feature`）；`git mv app androidApp`，`applicationId` 不变；更新 `AGENTS.md`、`docs/testing.md` 的命令 | 1 | `./gradlew test` 用例数与基线一致；`./gradlew :androidApp:assembleRelease`；装机覆盖安装旧版，数据还在 | 纯搬家；revert |
| 3 | Koin 接入 + ViewModel 注入 | `:androidApp` 加 Koin；`di/AppModule.kt` 提供现有实例；`TrackrApp.instance` 暂留；6 个 ViewModel 改构造注入（带参数的用 `parametersOf`）；`MainActivity.kt:196-197,634-676,704` 的手写 Factory 删掉；`HomeViewModel` 不再继承 `AndroidViewModel`；`LineDetailScreen.kt:100,430,444` 的仓库访问移进 VM | 2 | 6 个 ViewModel 测试（仍直接 new）；新增 `KoinGraphVerifyTest` | 共享 VM 行为不变（第 17 步才拆）；revert |
| 4 | 平台组件注入，删 `TrackrApp.instance` | Service / Receiver / 小组件用 `KoinComponent` + `by inject()`；`ArrivalAlertManager`、`LiveProgressNotificationHelper`、`TransferAutoSwitchManager` 从 `object` 改成类（先留同名 `object` 转发，第 12 步删）；快捷方式 / 小组件同步移到应用级收集器 | 3 | `LiveProgressNotificationHelperTest`、`ArrivalAlertManagerTest`、`TransferAutoSwitchManagerTest`、`ScheduledReminderReceiverTest`；`grep -rn "TrackrApp.instance" androidApp/src` 无结果 | revert |
| 5 ∥ | 设计系统改 CMP + v3 Token | `:core:designsystem` 改成 KMP + CMP 模块；v3 蓝 `#1F5FD6` 浅 / 深两套、去掉动态取色；`TrackrColors(go/goContainer/warn/warnContainer/walk)`（旧字段留别名）；Typography 加 `eta` / `item`；`Shape.kt`（8/16/28）、`Dimens.kt`；**API 一次定型为 `TrackrTheme(uiMode: UiMode = Standard)`**；图标与通用文案进 Compose 资源；删 `app/ui/theme/*` 等重复件 | 2 | `./gradlew test`；`:core:designsystem:compileKotlinIosSimulatorArm64`；截图目视对比颜色 | `MaterialExpressiveTheme` 在 CMP 不可用时退回 `MaterialTheme` + 自定义动效（R15）；revert |
| 6 ∥ | 到站文字统一 | `ArrivalText`（8 种状态 → 大字 / 小字 / 色调 / 朗读文本），只用 `kotlinx-datetime`，第 7 步直接搬进 `:core:domain`；替换 `BusArrivalBadge`、`StationArrivalContent`、`TransitGroupCardText`、`LiveProgressNotificationHelper`、`BusStopWidgetProvider` 的文案 [V3] | 2 | 新增 `ArrivalTextTest`；改 4 个相关测试的期望文字 | 通知文案变化；revert |

### 阶段 B：跨平台核心、数据与追踪

| 步 | 目标 | 改动文件 | 依赖 | 验证 | 风险 / 回退 |
| --- | --- | --- | --- | --- | --- |
| 7 | 纯逻辑模块（KMP） | 新增 `:core:model`、`:core:domain`、`:core:testing`；搬 `domain/model/*`、`RouteProjectionMath`、`ArrivalText`、`GroupLegTracker`；`java.time` → `kotlinx-datetime`、`UUID` → `kotlin.uuid.Uuid`；domain 里定义平台能力接口（`TrackingController`、`Announcer`、`TransitCodeLauncher`、`LocationSource`、`SpeechInput`、`Clock`）；`compose_stability.conf` 写入模型清单 | 4、6 | `./gradlew test`；`./gradlew :core:domain:iosSimulatorArm64Test`（需 Xcode） | 搬文件 diff 大但无逻辑变化；R8 规则跟着改包路径；revert |
| 8 | `:core:network` + `:core:data`，拆上帝接口 | OkHttp → Ktor（Android 用 OkHttp 引擎）；`ChelaileApiClient`、`MygolbsApiClient`、高德 Web 客户端进 `:core:network`；`ChelaileCrypto` 改 `expect/actual`；高德 Android SDK 封装留 `androidMain`；新增接口 `SettingsRepository`、`FavoritesRepository`、`RealtimeRepository`、`SearchHistoryRepository`、`LocationRepository`、`RoutePlanRepository`（`TransitRepository` 先同时实现它们）；`FakeTransitRepository` 按接口拆到 `:core:testing`；更新 `AGENTS.md` 测试准则 | 7 | Provider 测试改用 Ktor `MockEngine` + 录好的真实响应；`ChelaileCryptoTest` 用已知密文对比；`:core:network:iosSimulatorArm64Test` | Ktor 与 OkHttp 在请求头、压缩、超时上的差异 → 录制响应逐个对比（R17）；revert |
| 9 | 存储迁移（Q3） | `:core:database`（Room KMP）、`:core:datastore`；`LegacyLibraryMigration`（androidMain）；各仓库换新实现；`FavoritesGroupViewModel.runDataMigrationIfNeeded` 移进迁移；`hasSavedCity` 改 Flow | 8 | 新增 `LegacyLibraryMigrationTest`（每个旧 key 一条、损坏 JSON 不崩、旧数据保留）、`SettingsMigrationTest`、各 DAO 测试（内存库，Android 主机测试 + iOS 模拟器）；装机覆盖安装旧版 APK 后数据还在 | **最高风险之一**（R1）；`USE_NEW_STORE` 开关；旧 key 不删 |
| 10 ∥ | D1 去聚合 | 删 `AggregatedSourcePriority`、`SearchResultAggregator`、聚合分支；数据源二选一；删搜索页双引擎卡 [SPEC] D1 | 9 | `TransitProviderCoordinatorTest`、`TransitProviderTest`、`MoreViewModelTest`、`SettingsMigrationTest`（旧聚合配置 → 主数据源） | 低；revert |
| 11 ∥ | D2 没 Key 不显示规划 | `SettingsRepository.hasMapKey: Flow<Boolean>`；底栏按它显示页签；删内置示意图和「配置Key」胶囊；Key 对话框移到「更多」 [SPEC] D2 | 9 | `RoutePlanScreenTest`、`MoreViewModelTest`、`NavigationFlowTest`（有 / 无 Key） | 低；revert |
| 12 | 追踪单一数据源 | `:core:data` 的 `TrackingRepository`（会话存 DataStore）；`:core:domain` 的 `TrackingEngine`（合并 `GroupLegTracker` + `TransferAutoSwitchManager` 纯逻辑，注入 `Clock`）；`BusTrackingService` 只收 `sessionId`，删 19 个 extra；`TrackingController` 的 Android 实现；通知动作 Receiver 改调仓库；删第 4 步留下的 `object` 转发 | 9 | 新增 `TrackingEngineTest`（迁入原两个测试的全部用例再扩充，commonTest）、`TrackingRepositoryTest`、`BusTrackingServiceTest`（Robolectric） | **高风险**（R2）；装机走「开始追踪 → 锁屏 → 到站震动 → 结束」；revert |
| 13 ∥ | D3 锁屏 | 通知 `VISIBILITY_PUBLIC`；关掉开关时 `VISIBILITY_PRIVATE` + `setPublicVersion`；`showOnLockScreen` 默认开 [SPEC] D3 | 9 | `LiveProgressNotificationHelperTest` 加两种可见性 | 低；revert |
| 14 | 收藏合一（数据） | Room 表 `favorite_item`（类型：车 / 路线；`groupId`、`sortOrder`）；`FavoriteItem` sealed interface；`FavoritesRepository`：`favoriteItems`、`move`、`setGroup`、删分组后变「不分组」；`GroupCategory` 不再给用户选 | 9 | `FavoritesRepositoryTest`（合并排序、改分组、删分组、旧数据迁入后顺序不变） | 中；revert |
| 15 | 一起等多条线（Q8：不设上限） | `TrackingTarget.WatchTogether`；会话加 `watchedLines`、`boardChoice`；**等车阶段每次轮询只请求 1 次上车站的站点接口**（`getStopDetail` 已带该站每条线的来车），按到站时间排序，条数不影响请求数；数据源的站点接口缺某条线时，才对那条线单独请求（并发数限 4，用户看不到这个限制）；「我上车了」取此刻在站的车，两辆都在站写 `boardChoice`；上车后只追那一辆的线路接口；路线每程的候选线同理；通知动作「我上车了 / 乘车码 / 结束」 [V3] | 12、14 | `TrackingEngineTest` 新用例：多线取最快、先后变化、只有一辆在站、两辆在站、某线无数据、10 条线仍只发 1 次请求、只有 1 程无下车站；`BusTrackingServiceTest` 多线轮询 | 中：条数多时追踪卡和通知要折叠显示（R6）；revert |

### 阶段 C：导航与功能模块

| 步 | 目标 | 改动文件 | 依赖 | 验证 | 风险 / 回退 |
| --- | --- | --- | --- | --- | --- |
| 16 | `:core:navigation`（CMP 版 Navigation 3） | `ScreenKey` 搬入；`NavigationState`、`Navigator`、`TrackrTransitions`、`TopLevel`（3.3 骨架）；每条栈各自一组 decorator（修 N2）；`SavedStateConfiguration` 注册 `ScreenKey` 的多态序列化（非 Android 平台恢复返回栈必需）；`MainActivity` 改用它（仍 4 页签） [N3] | 7 | 新增 `NavigatorTest`（commonTest）、`NavigationStateRestorationTest`；删只测 `mutableListOf` 的旧用例 | 中；revert |
| 17 | `:composeApp` + 功能模块 + 3 页签（Q1） | 新增 `:composeApp`（`TrackrRoot()`、`NavDisplay`、3 页签）；新增 `:feature:bus/search/stop/line/route/favorites/me` 和 `:core:ui`；**ViewModel 搬进各 feature 的 `commonMain`**（此时已不碰 `Context`）；拆 `FavoritesGroupViewModel` → `RoutePlanViewModel`、`RouteDetailViewModel`、`FavoritesEditViewModel`；从 `HomeViewModel` 拆出 `SearchViewModel`；**旧 Screen 先放各 feature 的 `androidMain`**，保证功能不断，阶段 D 逐页重写进 `commonMain`；新页面先放空壳；`MainActivity` 只剩 `setContent { TrackrRoot() }` | 16、8、9 | `NavigationKeyTest`、`NavigatorTest`（3 页签）；`FavoritesGroupViewModelTest` 拆成 3 个新测试；新增 `SearchViewModelTest`；`KoinGraphVerifyTest` 覆盖全部模块；各 feature `compileKotlinIosSimulatorArm64` | **高风险**（R3、R18）：先搬方法不改逻辑；旧收藏页保留到第 22 步；revert |
| 18 | Deep link 统一 | `DeepLinkParser`（common，3.6 表）；`MainActivity` 只调解析器；小组件、快捷方式、通知、提醒的 PendingIntent 改发 `trackr://`；旧 action 兼容一个版本；`ResultEventBus` 与 `BottomSheetSceneStrategy` 接入；组件内返回拦截迁 `NavigationBackHandler`；小组件刷新 action 移出导出 filter（S2） [N3]、[NE]、[IS] | 17 | `DeepLinkParserTest`（commonTest）、`NavigatorTest` 合成栈、`TrackrAppNavigationTest`（Robolectric 冷 / 热启动） | 中；revert |

### 阶段 D：v3 页面（每页一个提交，写进 feature 的 `commonMain`）

统一要求：`XxxRoute` + 无状态 `XxxScreen(state, onEvent)`；列表 key / contentType；`innerPadding` 给列表 `contentPadding`；一次性消息走 `userMessages`；**该 feature 的 `androidMain` 旧 Screen 删掉**；验证里都含 `:feature:x:compileKotlinIosSimulatorArm64`（证明没用 Android API）。

| 步 | 页面 | 主要改动 | 依赖 | 验证 | 风险 / 回退 |
| --- | --- | --- | --- | --- | --- |
| 19 ∥ | 公交首页 `:feature:bus` | 标题改城市名；删乘车码卡；`TransitCodeFab`（下滑缩小、上滑展开；`TransitCodeLauncher`）；收藏合一列表 + 分组 chips + 「编辑」；追踪卡支持一起等（条数多时折叠为「还有 N 条」）；附近站固定 6 个 [V3] | 17、14、6、5 | `BusHomeViewModelTest`、`TransitCodeFabTest`、`BusHomeScreenTest` | 中；revert |
| 20 ∥ | 线路详情 `:feature:line` | 删底部 5 图标工具栏；顶栏时钟 + ☆；☆ 一点即收藏 + 「换分组」；主卡用 `ArrivalText`；删预警 chips 和每秒倒计时（C1）；UiState 拆静态 / 实时（4.4）；走向条只留竖向；站表先读 Room 缓存；底栏 = 主按钮 + 乘车码；下拉刷新 | 17、6 | `LineDetailViewModelTest`、`StationArrivalContentTest`、`ExpressiveRouteRailUiTest` | 中；revert |
| 21 ∥ | 站点详情 + 一起等 `:feature:stop` | 站台按方向命名（无方向数据退回「站台 1 / 2」）；删「查看全线」；下拉刷新；选线模式（不限条数，收藏 / 一起等）；一起等进行中视图；「上了哪辆？」弹层 [V3] | 17、15 | `StopDetailViewModelTest`（多选、存成 1 程路线、启动一起等）、`ChooseBoardedBusTest` | 中（R7）；revert |
| 22 | 路线详情 + 编辑收藏 + 收藏弹层 | `:feature:route` 的路线详情、`:feature:favorites` 的编辑页（拖动排序）和收藏弹层（只问名字和分组） [V3] | 17、14、15 | `RouteDetailViewModelTest`、`FavoritesEditViewModelTest`、`SaveFavoriteSheetTest`；首页「编辑」切到新页 | 中；revert |
| 23 ∥ | 搜索 `:feature:search` | 删推荐词、双引擎卡；每方向一行；输入存 `SavedStateHandle` [V3] | 17 | `SearchViewModelTest`、`SearchScreenTest` | 低；revert |
| 24 ∥ | 路线规划 `:feature:route` | 拆 2491 行的 `RoutePlanScreen.kt`；地图改 `expect fun RouteMap`，Android 实现放 `androidMain`（高德 SDK + `AndroidView`），iOS 先放「暂不支持地图」占位；方案标签固定 3 个；收藏后留在本页显示「去看看」 [V3] | 17、11 | `RoutePlanScreenTest`、`RoutePlanViewModelTest` | 中：地图生命周期（R11）；revert |
| 25 ∥ | 我的 `:feature:me` | 按 v3 分组；删调试项；「到站前提醒」全局设置；定时提醒可编辑；锁屏开关；高德 Key；长辈版开关位置预留 [V3] | 17、13 | `MeViewModelTest`、`ReminderEditViewModelTest`、`ScheduledReminderRepositoryTest` | 低；revert |

### 阶段 E：Android 系统集成、性能与收尾

| 步 | 目标 | 改动文件 | 依赖 | 验证 | 风险 / 回退 |
| --- | --- | --- | --- | --- | --- |
| 26 ∥ | 小组件迁 Glance（Q4） | `:androidApp/widget/*`；保留旧类名 `BusStopWidgetProvider` 作为 `GlanceAppWidgetReceiver` 子类；周期刷新 `enqueueUniquePeriodicWork(KEEP)`；收藏变化时 `updateAll` [GC]、[GU] | 19–25 | `TrackrWidgetTest`（`runGlanceAppWidgetUnitTest`）；装机：旧小组件升级后仍显示 | 中（R9）；revert |
| 27 ∥ | Baseline Profile + Macrobenchmark | `:baselineprofile`；`profileinstaller`；启动 / 滚动基准 [CP] | 19–25 | 冷启动与滚动 P90 对比第 0 步基线 | 只增不改；revert |
| 28 ∥ | 文案 | 删 emoji 和营销语；按 v3「统一叫法」替换；文案进 Compose 资源 `composeResources/values/strings.xml` | 19–25 | `rg -n '⚙|💡|🟢|🚩|⚡|路线组|同达|换乘组合|定时通勤|跟车'` 无结果 | 低；revert |
| 29 | 清理 + R8 收紧 | 删旧文件和 feature `androidMain` 里残留的旧 Screen；写死的 `fontSize` / 圆角 / 颜色清零；R8 宽规则收紧（[R8]）；`usesCleartextTraffic` 改白名单 | 26–28 | `./gradlew test`、`./gradlew jacocoTestReport`；release 冒烟；APK 大小对比基线 | R10：单独提交，失败只 revert 这一个 |

### 阶段 F：长辈版（详见 §8）

| 步 | 目标 | 改动文件 | 依赖 | 验证 | 风险 / 回退 |
| --- | --- | --- | --- | --- | --- |
| 30 | UiMode + 主题切换 + 字号放大提示 | `SettingsRepository.uiMode`（DataStore）；`TrackrTheme(uiMode)` 加 `Elder` 分支（高对比、大字号、`minTouchTarget = 64.dp`）；`AppShellViewModel`（`:composeApp`）读模式；「我的」加开关；系统字号 ≥ 1.3 倍时提示一次 | 5、9、18、19、20 | `UiModeSettingsTest`、`TrackrThemeTest`、`FontScalePromptTest` | 低；开关即回退 |
| 31 | 长辈版导航（单栈，Q9） | `:core:navigation` 加 `ElderNavigator`；`TrackrRoot` 按模式选标准 / 长辈；**切换模式时两边都重置到首页**；不支持的 Key（`RoutePlan`、`FavoritesEdit`）落到首页 | 30、18 | `ElderNavigatorTest`、`UiModeSwitchNavigationTest`（任意页切换后栈 = `[Bus]`） | 低；开关回退 |
| 32 | 长辈版首页、线路页、换站、出示乘车码 | 各 feature 的 `elder/` 包：`ElderBusHomeScreen`、`ElderLineDetailScreen`、`ElderPickStopScreen`；`:core:ui` 的长辈尺寸组件；共用原 ViewModel | 31、19、20 | `ElderBusHomeScreenTest`、`ElderLineDetailScreenTest`（可点元素 ≥ 64dp、无底部页签、无地图入口）；截图：长辈主题 × 字号 1.0/1.5/2.0 | 中；开关回退 |
| 33 ∥ | 读给我听 | `Announcer` 的 Android 实现（`TextToSpeech`，懒初始化，音频焦点）；`ArrivalText.spoken`；`FakeAnnouncer` | 32 | `LineDetailViewModelTest` 读给我听用例；`AndroidTtsAnnouncerTest`（无 TTS 引擎时降级为提示） | 低；revert |
| 34 ∥ | 语音搜索 + 常驻消息 + 长辈版搜索 / 站点 / 提醒中 / 车到站 | `SpeechInput` 的 Android 实现（`RecognizerIntent`）；`UserMessageHost`（标准用 Snackbar，长辈用需点「知道了」的常驻提示）；`ElderSearchScreen`、`ElderStopDetailScreen`、到站整屏提醒（App 在前台时） | 32 | `ElderSearchScreenTest`、`UserMessageHostTest` | 低；revert |

### 阶段 G：iOS 与鸿蒙评估（新增）

| 步 | 目标 | 改动文件 | 依赖 | 验证 | 风险 / 回退 |
| --- | --- | --- | --- | --- | --- |
| 35 | iOS 工程跑起来 | 新增 `iosApp/`（Xcode 工程）；`:composeApp` 的 `iosMain` 加 `MainViewController()` 和 Koin 启动；Room / DataStore 的 iOS 文件路径；`trackr://` 链接交给 `DeepLinkParser` | 29（最早可在 25 后） | 模拟器上看到「公交」首页和真实到站数据；`./gradlew :composeApp:iosSimulatorArm64Test` | 低：不影响 Android；revert |
| 36 ∥ | iOS 平台能力 | `LocationSource`（`CLLocationManager`）、`TransitCodeLauncher`（支付宝 / 云闪付 URL Scheme，Info.plist 声明）、`Announcer`（`AVSpeechSynthesizer`）、`SpeechInput`（`SFSpeechRecognizer`）、权限说明文案 | 35 | 各实现的 iOS 测试（用接口 Fake 驱动 VM）；真机试定位与乘车码跳转 | 中：URL Scheme 可能随第三方 App 变化；revert |
| 37 | iOS 追踪与提醒（Q13） | `TrackingController` 的 iOS 实现：前台轮询 + `TrackingEngine`；快到站发本地通知；定时提醒用 `UNCalendarNotificationTrigger`；Live Activity 用 Swift 写（ActivityKit + Widget Extension），由 Kotlin 侧通过接口驱动 | 36 | 模拟器 / 真机：前台追踪到站提醒、锁屏 Live Activity 显示 | **高**（R16）：iOS 不允许 App 在后台一直轮询 |
| 38 ∥ | iOS 地图（Q15） | `RouteMap` 的 iOS 实现：高德 iOS SDK + `UIKitView`；未接入前 iOS 不显示「路线」页签（同 D2 规则） | 35 | 真机看规划结果画线 | 中：SDK 集成方式（CocoaPods / SPM）；revert |
| 39 ∥ | iOS 小组件（可选） | WidgetKit（Swift）；数据库放 App Group 共享目录 | 35 | 桌面小组件显示收藏的车 | 中；可延后 |
| 40 ∥ | 鸿蒙评估 | 写 `docs/design/harmony_spike.md`：列出可行方案（官方进展、社区 / 厂商的 KMP 与 Compose 移植），各用一个最小 Demo 跑通 `:core:domain` + 一个页面，比较维护成本 | 29 | 文档 + Demo 结论，再决定是否立项 | 只是评估，不改主工程 |

### 6.1 并行分组（给子代理）

| 波次 | 可并行的步 | 各自独占的文件 | 注意 |
| --- | --- | --- | --- |
| W1 | 3+4（同一代理顺序做）∥ 5 ∥ 6 | 3/4：ViewModel、Service、Receiver、`MainActivity`；5：`core/designsystem`、主题 import；6：到站文案调用点 | 5 改 import 会碰 3/4 的文件头 → 5 最后合并并 rebase；6 只改函数体 |
| W2 | 10 ∥ 11 ∥ 13 | 10：Provider、数据源设置、搜索双引擎卡；11：规划页、Key 相关、底栏；13：通知 | 10 与 11 都碰「更多」页不同区块，按步号合并 |
| W3 | 19 ∥ 20 ∥ 21 ∥ 23 ∥ 24 ∥ 25，然后 22 | 各自的 `:feature:*` 模块 | 模块已拆开，冲突只在 `:core:ui`；共享组件由 19 先落 |
| W4 | 26 ∥ 27 ∥ 28 | 26：`androidApp/widget`；27：`:baselineprofile`；28：字符串资源 | 26 与 27 都改 `androidApp/build.gradle.kts` 不同区块 |
| W5 | 33 ∥ 34 | 33：TTS；34：语音输入、消息宿主、长辈搜索 / 站点 | — |
| W6 | 36 ∥ 38 ∥ 39 ∥ 40（35 完成后） | 各自的 `iosMain` / Swift 目标 / 评估目录 | 37 依赖 36 |

---

## 7. 风险清单与决策

### 7.1 风险

| # | 风险 | 影响 | 应对 | 所在步 |
| --- | --- | --- | --- | --- |
| R1 | 搬到 Room / DataStore 时读丢或写坏旧数据 | 用户收藏清空，不可逆 | 迁移不删旧 key；每个旧 key 一条测试；`USE_NEW_STORE` 开关；覆盖安装旧版 APK 实测 | 9 |
| R2 | 追踪状态机重写引入回归（到站不震动、换乘不切程、通知不消失） | 核心功能失效 | 先把现有两个追踪测试的用例全部迁进 `TrackingEngineTest` 并通过，再加新行为；装机走单线 + 路线 + 锁屏 | 12、15 |
| R3 | 拆 1530 行 `FavoritesGroupViewModel` 漏方法或改变时序 | 规划、收藏、路线追踪出错 | 先搬方法不改逻辑，测试按方法迁；旧收藏页保留到第 22 步 | 17 |
| R4 | Koin 缺绑定只在运行时报错 | 某页一打开就崩 | `KoinGraphVerifyTest` 覆盖每个模块，CI 必跑；每步装机点一遍改过的页面 | 3、17 |
| R5 | Android 进程被杀后前台服务无法自动重启 | 追踪静默中断 | 会话持久化；首页提示「追踪已中断 · 继续」；小米 / OPPO / Pixel 真机验证 | 12 |
| R6 | 一起等条数很多时界面放不下 | 追踪卡和通知变长 | 请求数不随条数增加（同站 1 次）；追踪卡只放大最先到的 1 条，其余超过 3 条折叠为「还有 N 条」；通知只写前 2 条 | 15、19 |
| R7 | 站台方向数据缺失 | 站点页无法按方向命名 | 退回「站台 1 / 2」 | 21 |
| R8 | NavKey 改变后首次冷启动恢复不了旧返回栈 | 回到首页（不崩） | 恢复失败兜底到根页并加测试 | 16、17 |
| R9 | 小组件迁 Glance 后桌面上的旧小组件失效 | 用户要重新添加 | 保留旧 receiver 类名与配置文件名 | 26 |
| R10 | R8 收紧导致 release 序列化失败 | release 崩溃 | 单独提交；release 冒烟清单 | 29 |
| R11 | 拆规划页时地图生命周期改变（白屏、重复初始化） | 规划页地图不可用 | 地图会话仍挂 Activity；拆分只移动 Composable；装机测切页签往返 | 24 |
| R12 | 长辈版与标准版共用 ViewModel，互相牵动 | 两套 UI 互相拖累 | UiState 只描述数据，不描述布局 | 30–34 |
| R13 | 长辈版大字叠加系统字号放大后溢出 | 布局破裂 | 字号 1.5 / 2.0 截图测试；关键大字用自动缩放限最大值 | 30、32 |
| R14 | 升级 AGP 引入构建行为变化 | 构建失败或包行为变 | 第 1 步单独提交；按 [AGP] 与 JetBrains 的 KMP AGP 9 迁移 skill（`kotlin-tooling-agp9-migration`）逐项核对 | 1、2 |
| R15 | CMP 的 Material 3 / 组件晚于 Android 版（如 `MaterialExpressiveTheme`、部分 Expressive 组件） | 某些效果做不出 | 用前先查 CMP 对应版本；缺的退回普通 M3 + 自定义动效；不在 commonMain 引用 Android 专属 API | 5、19–25 |
| R16 | iOS 不允许 App 在后台持续轮询 | iOS 锁屏后追踪不更新 | 见 Q13：先做前台追踪 + 本地通知；要后台更新必须加推送服务端 | 37 |
| R17 | OkHttp 换 Ktor 后请求细节不同（请求头、压缩、超时、Cookie） | 接口偶发失败 | Android 用 Ktor 的 OkHttp 引擎；录制真实响应做回归；先对比再切换 | 8 |
| R18 | 第 17 步一次建 9 个模块，构建时间变长、配置出错 | 开发变慢 | 约定插件统一配置；开 Gradle 配置缓存；本地默认只编 Android，iOS 目标按需编译 | 2、17 |
| R19 | 鸿蒙没有官方 Kotlin / CMP 支持 | 时间不可控 | 只做评估（第 40 步），不进主线；保持 commonMain 纯净 | 40 |

### 7.2 决策

已定（用户 2026-10-31）：

| # | 问题 | 决定 | 落点 |
| --- | --- | --- | --- |
| Q1 | 是否拆 feature 模块 | **拆**，一步到位 | §2.2、第 17 步 |
| Q2 | DI 方案 | **跨平台（CMP，先 iOS，再看鸿蒙）→ Koin** | §2.3、§2.8、阶段 G |
| Q3 | 存储 | **Room 存列表类数据 + DataStore 存设置和追踪会话** | §2.4、第 9 步 |
| Q8 | 一起等几条 | **用户想等几条就几条**，同站只发 1 次请求 | 第 15 步 |
| Q9 | 切换长辈版时导航 | **直接回首页** | §8.4、第 31 步 |
| Q12 | 是否升级 AGP | **升** | 第 1 步 |

沿用推荐（用户未提异议，可再改）：

| # | 问题 | 推荐 |
| --- | --- | --- |
| Q4 | 小组件 | 迁 Glance（Android） |
| Q5 | 截图回归测试 | Compose Preview Screenshot；升 AGP 后用 test suites 版本，不再用已弃用的独立插件 |
| Q6 | 进程被杀后追踪 | 能续就自动续，续不了在首页提示「继续」 |
| Q7 | 底部弹层是否进返回栈 | 只有「收藏」和「上了哪辆？」进 |
| Q10 | 长辈版能否看已收藏路线 | 能看详情，没有地图规划（长辈版设计稿还没画这一页，第 32 步前补） |
| Q11 | 追踪时自动朗读 | 先只做按钮，自动朗读放进设置 |

新增待定（iOS 相关，到阶段 G 再定）：

| # | 问题 | 选项 | 推荐 |
| --- | --- | --- | --- |
| Q13 | iOS 后台追踪 | A 只在前台轮询 + 本地通知；B 加推送服务端，后台用推送更新 Live Activity | **先 A**；B 要服务器和苹果开发者账号 |
| Q14 | 鸿蒙路线 | A 等官方 / 社区方案成熟；B 现在就选一个移植方案立项 | **A + 第 40 步评估** |
| Q15 | iOS 地图 | A 接高德 iOS SDK；B iOS 先不显示「路线」页签 | **先 B，再 A** |

---

## 8. 长辈版（适老模式）

> 需求来自父会话（[ELD]）。设计稿 `docs/design/redesign_v3_elder.html` 已写好；第 30 步开工前逐页对照。长辈版不做「一起等」、「我上车了」即结束（设计稿第 4 节）。

### 8.1 要点与落点

| 需求 | 落点 | 步 |
| --- | --- | --- |
| 「我的」开关切换；系统字体放大时提示开启 | `SettingsRepository.uiMode` + `FontScalePrompt` | 30 |
| 同一套数据层和 ViewModel，只换 UI 层 | 共享 `XxxViewModel` 与 `UiState`；分叉在 `XxxScreen` 一层（8.5） | 30–34 |
| 更大字号、触控 ≥ 64dp、高对比主题 | `TrackrTheme(UiMode.Elder)` | 30 |
| 简化首页和线路页 | `ElderHomeScreen`、`ElderLineDetailScreen` | 32 |
| 没有底部 Tab 和地图规划 | `TrackrElderApp` 单栈 | 31 |
| 常驻「出示乘车码」大按钮 | `ShowTransitCodeButton`（底部固定，复用 `TransitCodeLauncher`） | 32 |
| 「读给我听」系统 TTS 朗读到站 | `Announcer` 接口 + `AndroidTtsAnnouncer` + `FakeAnnouncer` | 33 |
| 搜索支持系统语音输入 | `RecognizerIntent` | 34 |
| 不用会自动消失的 Snackbar | `UserMessageHost` 按模式选常驻提示 | 34 |

### 8.2 UiMode 存在哪

```kotlin
// :core:model
enum class UiMode { Standard, Elder }

// :core:data —— SettingsRepository（Preferences DataStore，第 9 步已就位）
val uiMode: Flow<UiMode>            // key "ui_mode"，默认 Standard
val fontScalePromptDismissed: Flow<Boolean>
suspend fun setUiMode(mode: UiMode)

// :composeApp —— 根级壳状态（Koin：viewModel { AppShellViewModel(get()) }）
class AppShellViewModel(settings: SettingsRepository) : ViewModel() {
    val shell: StateFlow<AppShellState?> = combine(settings.uiMode, settings.hasMapKey, …) { … }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)    // null = 还没读到
}

// MainActivity（Android）；iOS 在 MainViewController 里先显示启动图
installSplashScreen().setKeepOnScreenCondition { shellVm.shell.value == null }  // 避免先闪标准版
```

| 决定 | 理由 | 来源 |
| --- | --- | --- |
| 放 DataStore，不放 `rememberSaveable` | 是用户设置，要跨启动；小组件和通知也可读（长辈版通知用更大字号的 `RemoteViews` 可作为后续） | [AG] 第 1 条 |
| Activity 级 `AppShellViewModel` 持有 | UiMode 决定整棵导航树，必须在 `NavDisplay` 之上 | [N3]（导航结构在 Activity 级） |
| 不跟随系统字体自动切换 | 系统字体放大只**提示**：`LocalConfiguration.current.fontScale >= 1.3f && !dismissed` 时在「我的」顶部和首页各提示一次「字太小？试试长辈版」，按钮直接开启 | [ELD] |

### 8.3 主题 / Typography / Shapes 按模式切换

```kotlin
// :core:designsystem
@Composable
fun TrackrTheme(uiMode: UiMode = UiMode.Standard, darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val tokens = when (uiMode) { UiMode.Standard -> StandardTokens; UiMode.Elder -> ElderTokens }
    CompositionLocalProvider(
        LocalUiMode provides uiMode,
        LocalTrackrColors provides tokens.colors(darkTheme),
        LocalTrackrDimens provides tokens.dimens,                       // minTouchTarget 48 / 64，screenPadding 12 / 16
        LocalMinimumInteractiveComponentSize provides tokens.dimens.minTouchTarget,  // M3 组件自动放大点击区
    ) {
        MaterialExpressiveTheme(
            colorScheme = tokens.colorScheme(darkTheme),                // Elder：高对比，正文对背景 ≥ 7:1
            typography = tokens.typography,                              // Elder：eta 56sp，item 22sp，body 20sp，caption 18sp
            shapes = tokens.shapes,                                      // Elder：圆角同级但按钮更高
            motionScheme = tokens.motion,                                // Elder：MotionScheme.standard()，减少弹跳
            content = content,
        )
    }
}
```

| 项 | 标准 | 长辈 | 说明 |
| --- | --- | --- | --- |
| `eta` | 40sp Bold 等宽 | 56sp Bold 等宽 | 都用 sp，系统字体放大再叠加；R13 |
| `item` / `body` / `caption` | 15 / 14 / 12sp | 22 / 20 / 18sp | 长辈版不出现 12sp 以下 |
| 最小触控 | 48dp | **64dp** | 通过 `LocalMinimumInteractiveComponentSize` + 自定义组件读 `LocalTrackrDimens` |
| 配色 | v3 蓝 + 4 语义色 | 同一套语义，提高对比：背景纯白/纯黑，正文纯黑/纯白，`go`/`warn` 加深 | 语义名不变，组件不需要知道模式 |
| 动效 | `MotionScheme.expressive()` | `MotionScheme.standard()` | 减少弹簧回弹 |

组件只读 `MaterialTheme.typography.*`、`TrackrTheme.colors.*`、`TrackrTheme.dimens.*`，不判断 `LocalUiMode`；只有「结构不同」的地方（8.5 表里标分叉的）才在 Screen 层分开写。这也是第 29 步清零写死 `fontSize` 的直接收益。[V3] §3、[CP] §1（CompositionLocal 只在主题根提供一次）

### 8.4 Navigation 3 下两套导航结构

| | 标准版 | 长辈版 |
| --- | --- | --- |
| 结构 | 3 条栈（公交 / 路线 / 我的），`NavigationState` + `Navigator` | **1 条栈** `rememberNavBackStack(ScreenKey.Bus)`，`ElderNavigator` |
| 根页 | 公交首页 | 长辈首页（同一个 `ScreenKey.Bus`，不同 entry 内容） |
| 「我的」 | Tab | 首页右上角「设置」按钮 → 压栈 `ScreenKey.Me`（长辈版 Me 只显示：长辈版开关、数据源、乘车码平台、读给我听、关于） |
| NavKey | **共用同一个 `sealed interface ScreenKey`** | 同左 |
| Entry provider | `standardEntryProvider(navigator)` | `elderEntryProvider(navigator)`：同一批 Key → 长辈版 Screen |
| 不支持的 Key | — | `RoutePlan`、`FavoritesEdit`、`ReminderEdit` → 回退规则：替换为 `Bus` 并显示常驻提示「长辈版不支持此功能，可在设置里切回标准版」 |
| Deep link | `DeepLinkParser` → `DeepLink.Stack(tab, keys)` → 选 Tab + 合成栈 | 同一解析结果 → `[Bus] + keys.filter(supported)` |
| Predictive back | `NavDisplay(onBack)` | 同左；单栈天然 exit-through-home |

```kotlin
@Composable
fun TrackrApp(shell: AppShellState, deepLinks: Flow<DeepLink>) {
    TrackrTheme(uiMode = shell.uiMode) {
        when (shell.uiMode) {
            UiMode.Standard -> {
                val state = rememberNavigationState(ScreenKey.Bus, TOP_LEVEL)
                val nav = remember(state) { Navigator(state) }
                CollectDeepLinks(deepLinks) { nav.applyDeepLink(it) }
                TrackrStandardApp(shell, nav, state)
            }
            UiMode.Elder -> {
                val stack = rememberNavBackStack(ScreenKey.Bus)
                val nav = remember(stack) { ElderNavigator(stack) }
                CollectDeepLinks(deepLinks) { nav.applyDeepLink(it) }
                NavDisplay(
                    backStack = stack,
                    onBack = { nav.back() },
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                    entryProvider = elderEntryProvider(nav),
                )
            }
        }
    }
}

class ElderNavigator(private val stack: NavBackStack<NavKey>) {
    fun open(key: NavKey) { val k = ElderRoutes.map(key); if (stack.lastOrNull() != k) stack.add(k) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeLastOrNull(); true } else false
    fun applyDeepLink(link: DeepLink) { stack.clear(); stack.add(ScreenKey.Bus); link.keys.map(ElderRoutes::map).filter { it != ScreenKey.Bus }.forEach(stack::add) }
}
```

切换模式时（Q9 已定：直接回首页）：不论当前在哪一页，新模式都只落首页 `[ScreenKey.Bus]`；切回标准版时 3 条页签栈也都重置到各自根页。两套栈各自 `rememberNavBackStack`，切换时旧树离开组合，其 entry 的 ViewModel 随 decorator 一起清理。[N3] multiple-backstacks、deeplinks-syntheticbackstack

### 8.5 哪些 Composable 共享、哪些分叉

| 层 | 共享 | 分叉（长辈版单独写） |
| --- | --- | --- |
| ViewModel / UiState | 全部：`BusHomeViewModel`、`LineDetailViewModel`、`StopDetailViewModel`、`SearchViewModel`、`RouteDetailViewModel`、`MeViewModel` | 无。长辈版需要的额外动作（`onReadAloud`）作为普通事件加在共享 VM 上，标准版不调用 |
| Route（拿 VM、收集 state） | `XxxRoute(viewModel, onOpen)`，多一个参数 `screen: @Composable (UiState, (Event) -> Unit) -> Unit` 或由 entry provider 直接调不同 Screen | — |
| Screen（页面布局） | `AboutScreen`（只受主题影响） | `ElderHomeScreen`（搜索 + 收藏的车大卡片 + 附近 3 个站，无追踪多线细节、无分组 chips）、`ElderLineDetailScreen`（大字 ETA + 上车站 + 「读给我听」+「追踪这班车」，无走向条，或只显示前后各 2 站的简化竖条）、`ElderStopDetailScreen`、`ElderSearchScreen`（带麦克风）、`ElderMeScreen` |
| 叶子组件（`core/designsystem` 或 `ui/components`） | `EtaText`（读 `typography.eta`）、`ArrivalTextView`（8 种状态，用 `ArrivalText`）、`LineBadge`、`StatePlaceholders`、`TransitCodeLauncher` 调用、`ChooseBoardedBus` 弹层内容、`UserMessageHost`（内部按 `LocalUiMode` 选 Snackbar / 常驻） | `ShowTransitCodeButton`（长辈版底部常驻大按钮，标准版用 `TransitCodeFab`） |
| 导航 | `ScreenKey`、`DeepLinkParser`、`TrackrTransitions` | `Navigator` vs `ElderNavigator`；`standardEntryProvider` vs `elderEntryProvider` |
| 平台 | 通知、小组件、追踪服务（不分模式） | — |

### 8.6 TTS 放在哪一层

```kotlin
// :core:domain —— 接口（纯 Kotlin，可被 VM 依赖、可 Fake）
interface Announcer {
    val isAvailable: StateFlow<Boolean>
    suspend fun speak(text: String)        // 打断上一句
    fun stop()
}

// :androidApp/platform/speech —— Android 实现（iOS 实现用 AVSpeechSynthesizer，见第 36 步）
// Koin：single<Announcer> { AndroidTtsAnnouncer(androidContext(), get(named("app"))) }
class AndroidTtsAnnouncer(ctx: Context, scope: CoroutineScope) : Announcer {
    // 懒初始化 TextToSpeech(ctx) { status -> … }，Locale.CHINA；不可用时 isAvailable=false
    // speak 时申请 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK，UtteranceProgressListener 完成后释放
    // 进程级单例，App 进入后台 5 分钟无使用则 shutdown()
}

// :core:testing
class FakeAnnouncer : Announcer {
    val spoken = mutableListOf<String>()
    override val isAvailable = MutableStateFlow(true)
    override suspend fun speak(text: String) { spoken += text }
    override fun stop() {}
}

// LineDetailViewModel
fun onReadAloud() = viewModelScope.launch {
    if (!announcer.isAvailable.value) { addMessage(UserMessage.TtsUnavailable); return@launch }
    announcer.speak(ArrivalText.spoken(state.value.live, state.value.static))   // 「325 路，还有 2 站，约 4 分钟到科技园站」
}
```

| 决定 | 理由 | 来源 |
| --- | --- | --- |
| 接口在 domain、实现在 app | ViewModel 只依赖接口（依赖倒置）；`TextToSpeech` 是平台服务 | [AG] 第 4 条、[TS] Step 4（无 Fake 时先建接口 + Default 实现 + Fake） |
| 朗读文本由 `ArrivalText.spoken` 生成 | 和屏幕大字、通知同一套规则，纯函数可测 | [V3]「8 种状态共用一套文字」 |
| 初始化不在主线程阻塞 | `TextToSpeech` 构造是异步回调；不在 `Application.onCreate` 创建 | [ANR] Path A |

### 8.7 对应测试

| 测试类 | 内容 | 类型 | 步 |
| --- | --- | --- | --- |
| `UiModeSettingsTest` | 默认 Standard；写入后新实例读回；旧版本无该 key | Robolectric（DataStore） | 30 |
| `TrackrThemeTest` | 两种模式下 `typography.eta.fontSize`、`LocalMinimumInteractiveComponentSize` | Compose | 30 |
| `FontScalePromptTest` | `DeviceConfigurationOverride.FontScale(1.5f)` 下显示提示；点「不用了」后不再显示；1.0 不显示 | Compose | 30 |
| `ElderNavigatorTest` | 单栈返回到根后返回 false；deep link 落栈；`RoutePlan` 回退到 `Bus` | JVM | 31 |
| `UiModeSwitchNavigationTest` | 在线路页切模式，新模式栈为 `[Bus, LineDetail]`；在规划页切，落根页 | Robolectric Compose | 31 |
| `ElderHomeScreenTest` / `ElderLineDetailScreenTest` | 所有可点元素 `assertHeightIsAtLeast(64.dp)`、`assertWidthIsAtLeast(64.dp)`；无 `NavigationBar`；「出示乘车码」始终可见；状态恢复 | Robolectric Compose | 32 |
| `LineDetailViewModelTest`（新增用例） | `onReadAloud` 调 `FakeAnnouncer`，文本正确；TTS 不可用时加 userMessage | JVM | 33 |
| `ArrivalTextTest`（新增用例） | 8 种状态的 `spoken` 文本 | JVM | 33 |
| `AndroidTtsAnnouncerTest` | Robolectric `ShadowTextToSpeech`：初始化成功/失败 | Robolectric | 33 |
| `ElderSearchScreenTest` | 测试用 `ActivityResultRegistry` 返回识别结果，查询词进入 `SearchViewModel` | Robolectric Compose | 34 |
| `UserMessageHostTest` | 标准模式 Snackbar 超时消失；长辈模式推进 10 秒后仍在，点「知道了」才消失 | Compose | 34 |
| 截图 | 长辈首页、线路页、站点页：浅/深 × 字体 1.0/1.5/2.0 | 截图（Q5） | 32、34 |

来源：[TS] Step 8（字体 1.5 倍、各主题截图）、Step 9（语义匹配、状态恢复）、Step 10（导航）、Step 11（`DeviceConfigurationOverride`）。

### 8.8 在执行序列中的位置

长辈版排在**第 30–34 步**（阶段 F）。前置条件：
- 第 5 步已把 `TrackrTheme(uiMode)` 的 API 定型 → 第 30 步只加 `ElderTokens`。长辈版页面是新写的，不受旧页面里写死字号的影响；共享叶子组件里的写死字号在第 19、20 步改 token。
- 第 9 步 DataStore 就位 → `uiMode` 有地方存。
- 第 18 步 `DeepLinkParser` 与导航基建 → 两种模式共用。
- 第 19、20 步首页和线路页已拆成 Route + 无状态 Screen → 长辈版直接复用 VM。

如果要提前交付长辈版，最早可在第 20 步之后启动第 30–32 步（与第 21–29 步并行，文件不冲突：长辈版只新增 `feature/elder/*` 与主题分支），第 33、34 步随后。

---

## 附录 A：v3 计划 17 步与本计划的对应

| v3 计划步 | 本计划步 | 变化 |
| --- | --- | --- |
| 0 开工前 | 0 | 同 |
| 1 主题与 Token | 5 | 合并重复设计系统；API 预留 `uiMode` |
| 2 到站文字 | 6 | 增加 `spoken` 文本 |
| 3 D1 | 10 | 推后到 DataStore 之后，迁移写在 DataStore 迁移里 |
| 4 D2 | 11 | 同上；`hasMapKey` 来自 `SettingsRepository` |
| 5 D3 | 13 | 同 |
| 6 收藏合一 | 14 | 基于 `FavoritesRepository` |
| 7 一起等（服务） | 12 + 15 | 先做追踪单一数据源，再加多线 |
| 8 页签与新页面 Key | 16 + 17 | 先换标准多栈写法，再改 3 Tab |
| 9–15 页面 | 19–25 | 每页同时做 Route/Screen 拆分与性能规则 |
| 16 文案 | 28 | 同 |
| 17 清理 | 29 | 加 R8 收紧、cleartext 收紧 |
| — | 1–4、7–9、18、26、27 | 新增：工具链升级、Koin、KMP 模块、Room + DataStore、deep link、Glance、Baseline Profile、iOS（35–40） |
| — | 30–34 | 新增：长辈版 |

## 附录 B：常用命令

```bash
./gradlew test                                                     # 全量（AGENTS.md）
./gradlew testDebugUnitTest --tests "com.trackr.ui.navigation.NavigatorTest"
./gradlew :core:domain:allTests                                    # 公共逻辑，Android 主机 + iOS 模拟器
./gradlew :composeApp:iosSimulatorArm64Test                        # iOS（需 Xcode）
./gradlew :androidApp:assembleRelease -PcomposeReports                   # Compose compiler reports
./gradlew jacocoTestReport
./gradlew :androidApp:generateBaselineProfile                     # 第 27 步后，需设备
```
