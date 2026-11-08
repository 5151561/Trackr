# iOS 启动工程

`iosApp/Trackr.xcodeproj` 是薄 UIKit 宿主，根控制器来自 `composeApp` 的 `MainViewController()`；应用主体直接使用已有 `TrackrRoot()` 和各 feature 的 CMP 页面，没有另一套 iOS 界面。

## 在模拟器运行

需要 macOS、完整 Xcode（含 iOS Simulator runtime）、JDK，以及项目现有的 Gradle / Android SDK 环境。本工程提供 `iosArm64` 和 `iosSimulatorArm64`，模拟器使用 Apple Silicon。

1. 用 Xcode 打开 `iosApp/Trackr.xcodeproj`。
2. 选择共享的 **Trackr** scheme 和一台 iPhone 模拟器，点击 Run。
3. 首次构建会通过 Run Script 执行 `:composeApp:embedAndSignAppleFrameworkForXcode`，编译共享代码并复制 Compose 资源。无需手动导入 framework。
4. 首次打开会申请使用期间定位权限。可以拒绝并手动选城市、搜索线路；模拟器附近站查询需要设置模拟坐标，例如执行 `xcrun simctl location booted set 31.2304,121.4737`（上海）。新版 Xcode 可在 Device Hub 中查看模拟器。

命令行构建（不需要开发者签名）：

```sh
xcodebuild -project iosApp/Trackr.xcodeproj -scheme Trackr \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData CODE_SIGNING_ALLOWED=NO build
```

安装到已经启动的模拟器：

```sh
xcrun simctl install booted iosApp/DerivedData/Build/Products/Debug-iphonesimulator/Trackr.app
xcrun simctl launch booted com.github.u5151561.trackr
```

真机需在 Xcode 的 Signing & Capabilities 里设置自己的 Team；不要把个人签名配置提交到仓库。当前 deployment target 为 iOS 17.0，低版本兼容性需要另行真机验证。

## 本轮接通的能力

- 共享公交首页、城市选择、搜索、站点和线路详情、收藏管理、我的与主题设置。
- Ktor Darwin 直连已有公交接口；沿用现有 ViewModel、业务规则和页面轮询。
- Core Location 的前台定位、原生权限申请、跳系统设置，以及与共享层一致的单调时钟。
- Room 保存收藏、路线、搜索历史和站表缓存；DataStore 保存设置与高德 Key。
- `trackr://` 链接由共享 `DeepLinkParser` 校验后交给根导航，支持冷启动和运行中打开。
- 路线页复用共享 CMP 页面。配置高德 Web Key 并同意隐私说明后可使用 Web 算路；未配置时遵循现有规则隐藏路线页签。当前不接原生地图 SDK。

首次没有收藏、定位尚未授权时显示现有空态，不注入演示数据。搜索历史、收藏、城市和主题在重启后保留。

## 后续平台能力

此工程先验证 iOS 启动与共享页面。后台追踪、通知、定时提醒、实时活动、小组件、语音播报和乘车码外部跳转尚未接入。

追踪与乘车码操作会说明暂不可用；定时提醒不允许创建。不会创建没有轮询服务维护的追踪会话，也不会显示已成功安排提醒。现有设置页仍会展示部分共享的追踪偏好，这些不代表 iOS 后台能力已经实现。

## 装配和存储

- `composeApp/src/iosMain/kotlin/com/trackr/app/di/IosAppRuntime.kt` 初始化本地数据后提供 Koin 平台模块，再安装 `trackrModules`。
- `MainViewController.kt` 在装配期间展示加载状态，初始化失败可重试。
- `Application Support/trackr.db` 与 `Application Support/datastore/trackr_settings.preferences_pb` 是普通应用数据。
- Key 与追踪文件位于 `Application Support/private/`，整个目录设置不参与 iCloud 备份，避免原子写入替换文件后丢失排除标记。
- iOS 不读取 Android 的 SharedPreferences，也不执行 Android 旧数据导入。

## 验证

```sh
./gradlew test
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 :feature:line:linkDebugTestIosSimulatorArm64
./gradlew :core:data:iosSimulatorArm64Test
```

手动检查：启动 → 城市 / 搜索 → 线路详情 → 收藏 → 返回首页 → 重启检查收藏；我的 → 主题设置 → 明暗切换；拒绝 / 授予定位后检查附近站；未接入功能应显示明确反馈。

### 本轮验证记录（2026-11-08）

- Xcode 27.0 / iOS 27.0 / iPhone 17 模拟器：构建、安装、冷启动通过。
- JVM 回归共 1400 个用例通过；iOS 数据层 104 个用例通过，线路模块 iOS 测试链接通过。所有自动化网络测试使用 Fake / MockEngine。
- 手动使用真实上海 71 路验证搜索、线路详情、选站、收藏、返回首页；打开附近站点详情并返回正常。
- 关闭进程再启动后，收藏、搜索历史和深色主题保留；查询与附近站数据显示正常。
- 追踪按钮显示尚未接入提示，不创建虚假的活动会话。

目前依赖中的 `libicu.icudtl_dat.o` 会提示其构建版本为 iOS Simulator 18.5，高于工程设置的 17.0。iOS 17 的实际兼容性尚未验证，发布前需要补齐对应系统的运行检查。当前运行验证以 iOS 27.0 为准。
