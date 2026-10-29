<div align="center">

# 🚌 追客 (Trackr) · BusOnRoad

<p align="center">
  <strong>现代化、灵动优雅的城市公交与地铁实时信息追踪客户端</strong>
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

## 📖 项目简介

**追客 (Trackr / BusOnRoad)** 是一款基于现代 Android 技术栈打造的公交与地铁实时到站查询与动态追踪应用。

通过接入实时公共交通数据源，为你提供极速、精准的车辆运行实况、到站预测与候车辅助，告别盲目等车焦虑，让每一次城市出行都尽在掌握。

---

## ✨ 核心特性

- 📍 **实时动态追踪**：支持实时显示当前线路上所有在线车辆的相对站序、预计到达时间与站点距离。
- 🎨 **Material 3 灵动设计**：全 Compose 原生构建，支持动态取色（Material You）、优雅流畅的动效与表现力极强的路线示意图。
- 🧭 **桌面候车卡 (Widget)**：支持添加桌面微件，无需打开 App 即可直接在主屏幕瞄一眼下一班车的到站动态。
- 🔔 **实时进度通知 (Live Progress)**：支持常驻通知栏动态进度条与到站预警提醒，候车途中做自己的事，不再担心错过车。
- ⚡ **现代架构驱动**：基于 **Jetpack Navigation 3**、**Kotlin Coroutines & Flow**、**DataStore** 构建，响应敏捷、架构清晰。

---

## 🛠️ 技术栈

| 模块 / 框架 | 选用方案 | 徽标 |
| :--- | :--- | :--- |
| **编程语言** | Kotlin (Modern Coroutines & Flow) | <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" /> |
| **UI 框架** | Jetpack Compose + Material 3 | <img src="https://img.shields.io/badge/Jetpack_Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" /> |
| **导航架构** | AndroidX Navigation 3 | <img src="https://img.shields.io/badge/Navigation_3-34A853?style=flat-square&logo=android" /> |
| **网络请求** | OkHttp + Kotlinx Serialization | <img src="https://img.shields.io/badge/OkHttp-008080?style=flat-square" /> |
| **持久化存储** | Jetpack DataStore Preferences | <img src="https://img.shields.io/badge/DataStore-4285F4?style=flat-square&logo=google" /> |
| **构建系统** | Gradle Kotlin DSL + Version Catalogs | <img src="https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white" /> |

---

## 🚀 快速上手与构建

### 前置要求
- **Android Studio** Ladybug (2024.2+) 或更高版本
- **JDK** 21 (配置了 Java 17+ 兼容性)
- Android 设备或模拟器 (系统版本 **Android 8.0 / API 26** 或以上)

### 编译步骤

1. 克隆本仓库：
   ```bash
   git clone https://github.com/5151561/Trackr-.git
   cd busonroad
   ```

2. 构建 Debug APK：
   ```bash
   ./gradlew assembleDebug
   ```

3. 安装至已连接的测试设备：
   ```bash
   ./gradlew installDebug
   ```

---

## 🤝 致谢 (Acknowledgments)

本项目在数据协议分析与实现过程中，参考并受益于以下开源项目，在此表示由衷的感谢：

- [**chelaile-mcp**](https://github.com/PeanutSplash/chelaile-mcp) - 提供车来了相关的协议参考与思路启发。
- [**BusFleetTracker-GuiYang-Public-Transit**](https://github.com/mage802/BusFleetTracker-GuiYang-Public-Transit) - 提供贵阳公交及车队数据分析与追踪经验参考。

---

## 📄 开源协议 (License)

本项目采用 [MIT License](LICENSE) 开源许可协议。
你可以自由地使用、修改和分发本项目代码，但需保留原作者版权声明及许可声明。
