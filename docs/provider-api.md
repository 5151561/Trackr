# 数据源 Provider API 与接入指南

本文档定义实时公交数据源统一契约 `ITransitProvider`，说明各接口的输入输出语义、通用约束，以及在工程中接入一个全新数据源所需修改的全部模块与清单。

---

## 1. 架构概览

实时公交查询采用 **单源分发、按需合并城市** 的分层架构（不做多源实时聚合）：

```text
UI / ViewModel (:feature:*)
        │
        ▼
RealtimeRepository (:core:data)
        │  负责线路详情短缓存、收藏刷新、状态封装
        ▼
TransitProviderCoordinator (:core:data)
        │  按当前 EngineMode 或线路/站点 ID 前缀路由至唯一 Provider
        │  城市列表并发拉取所有 Provider 并合并去重
        ▼
ITransitProvider / BaseTransitProvider (:core:network)
        │  标准化契约：ID 归属、能力声明、业务编排与局部兜底
        ▼
XxxApiClient / BaseTransitApiClient (:core:network)
        │  协议编解码、Ktor HttpClient / 自定义 Socket、坐标与字段归一化
        ▼
上游数据源服务
```

### 现有 Provider 参考对照

| 数据源 | `DataSourceType` | ID 前缀 | 传输方式 | 能力特征 | 参考实现 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 车来了 | `CHELAILE` | 无前缀（默认兜底） | HTTPS + 加解密 | 全能力（附近站、站点含车序、逆地理省市区、时刻表） | `ChelaileTransitProvider` |
| 掌上公交 | `MYGOLBS` | `mg:` | HTTPS JSON | 支持附近站、逆地理城市、时刻表；站点接口不含车序（`stopDetailIncludesBuses = false`） | `MygolbsTransitProvider` |
| 掌上出行（朗斯） | `LENZ` | `lz:` | HTTP 清单 + 私有 TCP Socket | 每城独立服务器（支持 `isCityReachable`、`listAllLines`）；无附近站/站点详情/逆地理/完整时刻表 | `LenzTransitProvider` |

---

## 2. `ITransitProvider` 标准契约

接口定义在 `core/network/src/commonMain/kotlin/com/trackr/core/network/provider/ITransitProvider.kt`。新数据源建议继承抽象骨架 `BaseTransitProvider`，由基类统一处理 `supportsLine`、`supportsStop`、`supportsLocationRadar` 及城市省份补全。

### 2.1 标识与归属识别

| 成员 | 必填 | 说明 |
| :--- | :--- | :--- |
| `val sourceType: DataSourceType` | 是 | 当前 Provider 对应的数据源枚举值。 |
| `fun ownsId(id: String): Boolean` | 是（继承 `BaseTransitProvider`） | 判断一个 `lineId` / `stopId` 是否属于本数据源（通常判断协议前缀，如 `id.startsWith("xx:")`）。 |
| `val missingIdMeansOwned: Boolean` | 否（默认 `false`） | `StopDetailRequest` 未携带任何 ID 时是否认领。仅 `CHELAILE` 为 `true`，新数据源保持默认 `false`。 |
| `fun supportsLine(lineId: String): Boolean` | 由基类实现 | 转发到 `ownsId(lineId)`。 |
| `fun supportsStop(request: StopDetailRequest): Boolean` | 由基类实现 | 检查 `stopId`、`physicalStId`、`namesakeStId` 任一命中 `ownsId`。 |

### 2.2 能力声明（可选覆写）

上游能力不完整时，通过能力声明告知协调器与业务层，避免发出无效请求或展示空白页：

| 方法 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `fun supportsLocationRadar(): Boolean` | `true` | 是否支持基于经纬度的位置雷达（附近站、逆地理定位）。 |
| `fun stopDetailIncludesBuses(): Boolean` | `true` | `getStopDetail` 返回的 `NearbyStopLineStatus.approachingBuses` 是否包含车辆在全线的站序。返回 `false` 时，「一起等」轮询器（`WatchTogetherPoller`）会自动改为并发查线路详情。 |
| `suspend fun isCityReachable(city: TransitCity): Boolean` | `true` | 城市服务是否在线可达。统一公网入口保持默认 `true`；每城独立部署的源可实现真实探活，供城市选择器标记「离线」。 |
| `suspend fun listAllLines(city: TransitCity): List<BusLineSummary>?` | `null` | 一次性列出该城市的全部线路（适合线路数量少的县级市在首页直接铺开）。不支持返回 `null`，调用方退回搜索入口。 |

### 2.3 城市与逆地理

| 方法 | 说明与约定 |
| :--- | :--- |
| `suspend fun loadSupportedCities(): List<TransitCity>` | `BaseTransitProvider` 抽象方法。返回本数据源支持的城市列表；基类 `getSupportedCities()` 会自动调用 `ProvinceCityCatalog.inferProvince()` 为缺失省份的城市补全省份。 |
| `suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String?` | 根据 **WGS-84** 坐标逆地理返回城市名（如 `"上海"`、`"上海市"`）。不支持返回 `null`。 |
| `suspend fun resolveDistrictByLocation(wgsLat: Double, wgsLng: Double): String?` | 根据 **WGS-84** 坐标逆地理返回区县名（未清洗，如 `"北流市"`、`"玉州区"`），用于县级城市精准匹配。默认返回 `null`。 |

### 2.4 核心查询接口

#### 搜索：`search`
```kotlin
suspend fun search(
    city: TransitCity,
    keyword: String,
    userLat: Double? = null,
    userLng: Double? = null
): TransitSearchResult
```
- **入参**：当前城市、关键字、可选用户位置（WGS-84，用于计算搜索结果中站点的距离）。
- **返回**：`TransitSearchResult(keyword, lines, stops)`。
  - 若上游只支持搜线路不支持搜站点，`stops` 返回 `emptyList()` 即可。
  - 协调器会自动过滤地铁线路和纯地铁站，并按 `RouteProjectionMath.compareLineNames` 对线路自然排序。

#### 附近站点：`getNearbyStops`
```kotlin
suspend fun getNearbyStops(
    city: TransitCity,
    wgsLat: Double,
    wgsLng: Double,
    limit: Int = 4
): List<NearbyStop>
```
- **入参**：当前城市、用户 **WGS-84** 坐标、期望返回站点数上限。
- **返回**：按距离升序排列的附近站列表，每站包含途经线路及实时到站状态（`NearbyStopLineStatus`）。
- 若上游无附近站接口，返回 `emptyList()`（并在协调器决定是否降级）。

#### 站点详情：`getStopDetail`
```kotlin
suspend fun getStopDetail(
    city: TransitCity,
    request: StopDetailRequest
): List<NearbyStop>
```
- **入参 `StopDetailRequest`**：
  - `stopId` / `stopName` / `physicalStId` / `namesakeStId`：站点标识与名称（不同上游侧重不同，按需取用）。
  - `stationLat` / `stationLng`：已知站点 WGS-84 坐标（上游不回传坐标时可直接回填，避免出现 `(0.0, 0.0)` 导致距离算成上万公里）。
  - `userLat` / `userLng`：用户 WGS-84 坐标，用于计算站点距离。
- **返回**：同一站名下可能存在多个物理站台（如对向站台），因此返回 `List<NearbyStop>`；不支持站点详情的源返回 `emptyList()`。

#### 线路详情：`getLineDetail`
```kotlin
suspend fun getLineDetail(
    city: TransitCity,
    request: LineDetailRequest
): BusLineDetail
```
- **入参 `LineDetailRequest`**：
  - `lineId`：必填，带本数据源协议前缀的线路方向 ID。
  - `targetOrder` / `stationId`：可选，用户当前关注的候车站（1-based 站序或站 ID），上游据此返回到该站的 ETA。
  - `wgsLat` / `wgsLng`：可选，用户位置。
  - `source`：可选，显式数据源标记（协调器优先按它路由）。
  - `lineNameHint` / `directionHint`：可选，用于 ID 信息不足时的兜底还原。
- **返回**：`BusLineDetail`，包含当前方向信息、反向方向 `reverseDirection`（单程线为 `null`）、全线站点列表 `stops`、在途车辆 `buses`、运营状态 `operatingState` 等。

#### 运营时刻表：`getTimetable`
```kotlin
suspend fun getTimetable(
    city: TransitCity,
    line: BusLineDirection,
    atTimeHHMM: String? = null
): LineTimetable
```
- **入参**：线路方向对象、可选基准时间（`"HH:mm"` 格式）。
- **返回**：`LineTimetable`。
- 若上游不提供完整发车时刻表（如朗斯仅提供首末班与下一班时间），应**如实返回已有字段**并在 `depDesc` 说明原因，不要推算或编造中间班次。

---

## 3. 核心领域模型与字段语义

模型定义在 `core/model/src/commonMain/kotlin/com/trackr/core/model/TransitModels.kt`。Provider 解析上游响应时需严格遵守以下语义，否则下游的到站文案、追踪状态机和上车判定会出错。

| 模型 | 关键字段与语义要求 |
| :--- | :--- |
| `BusLineDirection` | `lineId` 必须全局唯一且带本源前缀；`direction` 统一映射为 `0`（正向）或 `1`（反向）；`source` **必须**设为本数据源枚举，不可沿用默认值 `CHELAILE`。 |
| `BusLineSummary` | 按线路聚合正反方向 `directions`；地铁线需标 `isSubway = true`；`source` 设为本数据源枚举。 |
| `BusStop` | `order` **严格 1-based**（首站为 `1`，依次递增）；坐标为 **WGS-84**（无坐标填 `0.0`）。 |
| `LiveBus` | `busId` 需在同一次行程内稳定（用于追踪锁车与「追这辆车」）；`plateOrFleetNo` 建议经 `sanitizePlateOrFleetNo()` 过滤机器哈希；`order` 为正在接近或已停靠的 1-based 站序；`isArrived = true` 表示已到站停靠；`segmentProgress` 为 `(order - 1) -> order` 区间插值 `[0.0, 1.0]`（已到站时为 `1.0f`，**注意**：当车坐标为 `0.0, 0.0` 时 `hasValidProgress` 为 `false`，仅用于 UI 展示）；`etaSeconds <= 0` 表示无预测或已过站。 |
| `BusLineDetail` | `operatingState` 映射到 `RUNNING` / `WAITING` / `SUSPENDED` / `ENDED` / `NO_REALTIME`；单程线 `reverseDirection = null`，双向线提供可直接传给 `getLineDetail` 的反向 `BusLineDirection`。 |
| `NearbyStop` | `source` 设为本数据源枚举；纯地铁站标 `isSubway = true`（`isPureSubwayStation` 也会识别名称含 `（M）`/`(M)`/`（地铁站）`/`(地铁站)` 的站点）。 |
| `TransitCity` | 城市对象由多源合并生成。若新数据源需要独立城市编码或服务端点字段，需加到 `TransitCity` 并同步更新 `uniqueKey` 和 `matchesCity()`。 |

---

## 4. 关键约束与工程约定

### 4.1 ID 协议与前缀隔离（极易踩坑）
1. 为新数据源定义独立短前缀（如 `xx:`），并在 `core/network/src/commonMain/kotlin/com/trackr/core/network/remote/<name>/XxxIds.kt` 集中收口编码、解码和站名清洗逻辑（参考 `MygolbsIds`、`LenzIds`）。
2. **自包含原则**：`lineId` / `stopId` 尽量自包含查询 `getLineDetail` / `getStopDetail` 所需的全部定位信息（如城市码、方向、服务端点等），因为收藏、提醒、DeepLink、通知恢复只会持久化 `lineId` / `stopId` 和 `cityId`。
3. **必须更新 `ChelaileTransitProvider.ownsId()`**：车来了的 ID 无固定前缀，目前采用反向排除判断：
   ```kotlin
   // ChelaileTransitProvider.kt
   override fun ownsId(id: String): Boolean =
       !MygolbsIds.isMygolbsId(id) && !LenzIds.isLenzId(id) && !XxxIds.isXxxId(id)
   ```
   **漏改此处会导致车来了误认领新数据源的 ID。**

### 4.2 坐标系统一为 WGS-84
- 所有 Provider 方法入参（`wgsLat`/`wgsLng`、`userLat`/`userLng`、`stationLat`/`stationLng`）和返回模型（`BusStop`、`LiveBus`、`NearbyStop`）一律使用 **WGS-84**。
- 若上游接口要求或返回 GCJ-02（高德/火星坐标），必须在 `XxxApiClient` 边界处调用 `com.trackr.core.domain.math.CoordTransform` 显式互转，禁止将 GCJ-02 直接透传到领域模型。

### 4.3 KMP 跨平台（Android + iOS）
- `:core:network` 和 `:core:data` 均为 KMP 模块，`commonMain` **禁止引入 `java.*` 和 `android.*`**。
- 优先继承 `BaseTransitApiClient`，复用以下基础设施：
  - `createTrackrHttpClient()`：统一 Ktor 客户端（Android OkHttp / iOS Darwin，总超时 20 秒，不打 URL 日志）。
  - `executeHttpCall()` / `executeJson()`：自动通过 `NetworkErrors.sanitize()` 剔除异常中的 query 参数，防止泄露 token/key。
  - `com.trackr.core.network.json.JSONObject` / `JSONArray`：跨平台只读 JSON 解析器。
  - `SingleFlightCache`：并发合并与短 TTL 缓存（需注入 `MonotonicClock` 以便单测控制时间）。
  - `ServiceHours` / `TimetableRules`：首末班状态判断与时刻表通用规则。
- 若上游需要平台特定能力（如原生加解密 `ChelaileCipher`、原始 TCP Socket `LenzSocket`），在 `commonMain` 声明 `expect`，并同时提供 `androidMain` 与 `iosMain` 的 `actual` 实现。

### 4.4 网络安全与明文 HTTP 白名单
- 工程默认禁止明文 HTTP（`androidApp/src/main/res/xml/network_security_config.xml` 中 `cleartextTrafficPermitted="false"`）。
- 上游接口应优先使用 HTTPS；若确实只有明文 HTTP，需在 `network_security_config.xml` 仅针对对应域名或 IP 放开白名单，并在 iOS 侧同步检查 ATS（`NSAppTransportSecurity`）配置。

---

## 5. 接入新数据源完整清单

按模块从底层到上层依次完成以下改动：

### Step 1：`:core:model` — 模型与枚举
- [ ] `core/model/src/commonMain/kotlin/com/trackr/core/model/TransitModels.kt`
  - 在 `DataSourceType` 新增枚举项（含 `displayName`）。
  - 在 `EngineMode` 新增枚举项（含 `displayName`、`shortLabel`）。
  - 如需城市级元数据（如城市码、服务器地址、区划码），在 `TransitCity` 新增可空默认值字段，并更新 `uniqueKey` 与 `matchesCity()`（同时检查 `BaseTransitProvider.getSupportedCities()` 的 `areaCode` 省份推断）。
  - 注意：`TransitModels.kt` 中所有模型均带 `@Serializable`，新增字段务必提供默认值以保证旧版序列化数据向后兼容。

### Step 2：`:core:network` — 客户端与 Provider 实现
- [ ] `core/network/src/commonMain/kotlin/com/trackr/core/network/remote/<name>/XxxIds.kt`
  - 定义协议前缀常量、ID 构造与解析函数、判定函数 `isXxxId(id)`。
- [ ] `core/network/src/commonMain/kotlin/com/trackr/core/network/remote/<name>/XxxApiClient.kt`
  - 继承 `BaseTransitApiClient`，封装 HTTP/Socket 请求、坐标系转换、JSON 解析和模型映射。
  - 若需要平台 `expect`/`actual`，在 `androidMain` 与 `iosMain` 同时实现。
- [ ] `core/network/src/commonMain/kotlin/com/trackr/core/network/provider/<name>/XxxTransitProvider.kt`
  - 继承 `BaseTransitProvider`，实现 `sourceType`、`ownsId`、`loadSupportedCities` 及各查询方法，覆写必要的能力声明。
- [ ] `core/network/src/commonMain/kotlin/com/trackr/core/network/provider/chelaile/ChelaileTransitProvider.kt`
  - 在 `ownsId()` 中排除新前缀 `!XxxIds.isXxxId(id)`。

### Step 3：`:core:data` — 协调器与仓库适配
- [ ] `core/data/src/commonMain/kotlin/com/trackr/core/data/provider/TransitProviderCoordinator.kt`
  - `EngineMode.dataSource` 补充新枚举映射。
  - 构造函数增加新 Provider 参数（或统一使用 `Map<DataSourceType, ITransitProvider>` 构造）并放入 `providers`。
  - `getSupportedCities()`：并发拉取新 Provider 的城市列表，按城市名合并已有城市并追加新数据源独有城市。
  - `getStopDetail()`、`getLineDetail()`、`getTimetable()`：补充按显式 `source` 与 `supportsStop`/`supportsLine`（ID 前缀）的优先路由分支。
  - 按需评估：`GEOCODE_ORDER`（是否参与逆地理）、`listAllLines()`、`unreachableCities()`、`getNearbyStops()` 降级策略、`search()` 兜底策略。
- [ ] 核对无需硬编码但需确认的通用点：
  - `LegacyDataSource.resolve()`、`core/data/.../local/Mappers.kt`、`androidApp/.../deeplink/DeepLinkIntents.kt` 均已通过 `entries.firstOrNull { it.name == ... }` 通用解析，新增枚举后自动生效。

### Step 4：`:core:designsystem` — 徽标与视觉资源
- [ ] `core/designsystem/src/commonMain/kotlin/com/trackr/core/designsystem/theme/Color.kt`
  - 定义新数据源徽标的容器色与文字色（如 `DataSourceXxxContainer`、`DataSourceXxxContent`）。
- [ ] `core/designsystem/src/commonMain/composeResources/values/strings.xml`
  - 新增 `data_source_<name>` 字符串资源。
- [ ] `core/designsystem/src/commonMain/kotlin/com/trackr/core/designsystem/component/DataSourceBadge.kt`
  - 在 `DataSourceBadgeStyle` 新增对应样式枚举。

### Step 5：`:feature:*` — 页面与交互适配
- [ ] `feature/bus/src/commonMain/kotlin/com/trackr/feature/bus/home/BusHomeUiState.kt`
  - 在 `filteredCities` 的 `when (engineMode)` 中补充新数据源的城市过滤条件。
- [ ] `feature/bus/src/commonMain/kotlin/com/trackr/feature/bus/home/BusHomeViewModel.kt`
  - 检查首页快捷切换数据源的轮换逻辑（`when (_uiState.value.engineMode)`）及城市切换后自动切源逻辑。
- [ ] `feature/bus/src/commonMain/kotlin/com/trackr/feature/bus/citypicker/CityPickerBottomSheet.kt`
  - 在城市列表中为支持新数据源的城市绘制 `DataSourceBadge`。
- [ ] `feature/line/src/commonMain/kotlin/com/trackr/ui/screens/linedetail/LineDetailViewModel.kt`
  - 检查 `initialSource` 的默认前缀推断，避免未传 `source` 时回落到 `CHELAILE`。
- [ ] `feature/me/src/commonMain/kotlin/com/trackr/ui/screens/me/MeScreen.kt`
  - 「我的 → 数据源」直接使用 `EngineMode.entries`，一般无需改页面，但需同步更新 `MeViewModelTest` 中对 `EngineMode.entries` 的断言。

### Step 6：DI 装配与平台配置（Android + iOS）
- [ ] Android：`androidApp/src/main/java/com/trackr/di/AppModule.kt`
  - 注册 `single { XxxApiClient(get()) }`、`single { XxxTransitProvider(get()) }`，并注入到 `TransitProviderCoordinator`。
- [ ] iOS：`composeApp/src/iosMain/kotlin/com/trackr/app/di/IosAppRuntime.kt`
  - 在 `TransitProviderCoordinator(...)` 构造处同步装配 `XxxTransitProvider(XxxApiClient(http))`。
- [ ] 网络安全（如上游存在明文 HTTP）：
  - `androidApp/src/main/res/xml/network_security_config.xml` 添加最小范围域名/IP 白名单。
  - iOS 对应 `Info.plist` 配置 ATS 例外（如需要）。

---

## 6. 测试与验证要求

所有测试遵循**网络隔离原则**，禁止在单元测试中访问真实上游服务器（详见 [测试策略与指南](testing.md)）：

1. **ID 与客户端测试（`:core:network` `commonTest`）**
   - `XxxIdsTest`：覆盖正常编解码、名称含 `:` 等特殊字符、非法格式返回 `null`。
   - `XxxApiClientTest`：使用 `mockHttpClient { request -> MockReply(json) }`（定义在 `core/network/src/commonTest/.../testing/MockHttp.kt`）验证各接口解析、空响应/错误码脱敏、坐标转换、纯地铁过滤与车牌清洗。
   - `TransitProviderTest`：验证 `ownsId` / `supportsLine` / `supportsStop`（含确认 `ChelaileTransitProvider` 不误认新前缀）以及 `BaseTransitProvider` 省份补全。
2. **协调器测试（`:core:data` `commonTest`）**
   - `TransitProviderCoordinatorTest`：覆盖城市合并与去重、按 `EngineMode` 分发、按 `source`/ID 前缀路由线路详情与时刻表。
   - 核对 `:core:testing` 中 `FakeRealtimeRepository.stopDetailIncludesBuses()` 是否与新数据源能力一致。
3. **DI 与 UI 回归（`:androidApp` / `:feature:*`）**
   - `KoinGraphVerifyTest`：确保 Android Koin 依赖图完整无缺失。
   - `MeViewModelTest`、`BusHomeViewModelTest`、`CityPickerRowsTest`：覆盖新 `EngineMode` 选项与城市筛选。

### 推荐验证命令

```bash
# 1. 网络层与数据层核心单测
./gradlew :core:network:testAndroidHostTest :core:data:testAndroidHostTest

# 2. 全量单元测试（含 DI 校验与 Feature 单测）
./gradlew test

# 3. iOS 侧 KMP 编译与链接检查（防止 commonMain 误用 JVM/Android API 或漏改 IosAppRuntime）
./gradlew :core:network:compileKotlinIosSimulatorArm64 \
          :core:data:compileKotlinIosSimulatorArm64 \
          :composeApp:compileKotlinIosSimulatorArm64
```
