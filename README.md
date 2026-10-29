<div align="center">

# Trackr · BusOnRoad

<p align="center">
  <strong>Android 实时公交与地铁到站追踪应用</strong>
</p>

<p align="center">
  <a href="https://developer.android.com">
    <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
  </a>
  <a href="https://kotlinlang.org">
    <img src="https://img.shields.io/badge/Kotlin-2.0%2B-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  </a>
  <a href="https://developer.android.com/jetpack/compose">
    <img src="https://img.shields.io/badge/Jetpack-Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  </a>
  <a href="https://m3.material.io">
    <img src="https://img.shields.io/badge/Design-Material%203-7C4DFF?style=for-the-badge&logo=materialdesign&logoColor=white" alt="Material 3" />
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-E91E63?style=for-the-badge&logo=opensourceinitiative&logoColor=white" alt="License" />
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Target%20SDK-36-blue?style=flat-square&logo=google" alt="Target SDK" />
  <img src="https://img.shields.io/badge/Min%20SDK-26-orange?style=flat-square&logo=android" alt="Min SDK" />
  <img src="https://img.shields.io/badge/PRs-Welcome-brightgreen.svg?style=flat-square" alt="PRs Welcome" />
  <img src="https://img.shields.io/badge/Status-Active-success?style=flat-square" alt="Status" />
</p>

</div>

---

## 项目简介

Trackr (BusOnRoad) 是一个 Android 实时公交与地铁到站查询应用。通过接入公共交通接口，展示车辆当前站序、预计到达时间与实时位置。

---

## 功能特性

- 实时追踪：展示线路上所有运行车辆的相对站序、预计到达时间与站点距离。
- 路线示意图：基于 Jetpack Compose 构建，支持横向与纵向线路示意及动态取色。
- 桌面微件：在系统主屏幕展示下一班车到站信息。
- 状态栏通知：支持常驻进度通知与到站提醒。
- 架构基础：使用 Navigation 3 路由、Coroutines 与 Flow 异步数据流、DataStore 存储。

---

## 技术栈

| 模块 | 方案 | 徽标 |
| :--- | :--- | :--- |
| 编程语言 | Kotlin (Coroutines & Flow) | <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" /> |
| UI 框架 | Jetpack Compose, Material 3 | <img src="https://img.shields.io/badge/Jetpack_Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" /> |
| 导航组件 | AndroidX Navigation 3 | <img src="https://img.shields.io/badge/Navigation_3-34A853?style=flat-square&logo=android" /> |
| 网络请求 | OkHttp, Kotlinx Serialization | <img src="https://img.shields.io/badge/OkHttp-008080?style=flat-square" /> |
| 本地存储 | Jetpack DataStore Preferences | <img src="https://img.shields.io/badge/DataStore-4285F4?style=flat-square&logo=google" /> |
| 构建系统 | Gradle Kotlin DSL, Version Catalogs | <img src="https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white" /> |

---

## 构建与运行

### 环境要求

- Android Studio Ladybug (2024.2) 或更高版本
- JDK 21 (兼容 Java 17+)
- Android 8.0 (API 26) 及以上设备或模拟器

### 编译步骤

1. 克隆仓库
   ```bash
   git clone https://github.com/5151561/Trackr-.git
   cd busonroad
   ```

2. 构建 Debug APK
   ```bash
   ./gradlew assembleDebug
   ```

3. 安装到已连接设备
   ```bash
   ./gradlew installDebug
   ```

---

## 致谢

本项目在数据协议与接口分析中参考了以下开源项目：

- [chelaile-mcp](https://github.com/PeanutSplash/chelaile-mcp)：车来了数据协议与参数参考。
- [BusFleetTracker-GuiYang-Public-Transit](https://github.com/mage802/BusFleetTracker-GuiYang-Public-Transit)：贵阳公交数据分析与追踪实现参考。

---

## 许可证

本项目采用 [MIT License](LICENSE)。
