# Trackr 更新检测分支 (update)

本分支专门用于提供应用程序的版本更新检测数据。

## 配置文件

- `update.json`（或 `version.json`）

### 字段说明

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `versionCode` | 整数 (Int) | 远程最新内部版本号（对应 Android `versionCode`） |
| `versionName` | 字符串 (String) | 最新展示版本号（如 `0.0.2`） |
| `minSupportedVersionCode` | 整数 (Int) | 最低支持的客户端版本号（低于此版本建议强制更新） |
| `changelog` | 字符串 (String) | 本次版本更新日志说明（支持换行符 `\n`） |
| `downloadUrl` | 字符串 (String) | 最新安装包或发布页下载地址 |
| `publishTime` | 字符串 (String) | 发布日期（如 `2026-10-30`） |
| `forceUpdate` | 布尔 (Boolean) | 是否强制要求更新 |

### 访问链接

- GitHub Raw: `https://raw.githubusercontent.com/5151561/Trackr/update/update.json`
- CDN 镜像: `https://fastly.jsdelivr.net/gh/5151561/Trackr@update/update.json`
