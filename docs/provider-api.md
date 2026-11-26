# Trackr 数据源协议（v1）

Trackr **不内置、不提供任何第三方实时公交数据源**，也不包含针对任何特定第三方服务的接入代码。

用户在 App 的「我的 → 数据源」里填写一个**自己部署**的服务地址（可选访问令牌），App 只按本文档定义的
HTTP + JSON 协议与该服务通信。服务背后的数据从哪里来、是否获得授权，由部署者自行负责。

```text
Trackr App ──(本协议：HTTPS + JSON)──▶ 你部署的数据源服务 ──▶ 你有权使用的数据
```

---

## 1. 通用约定

| 项目 | 约定 |
| :--- | :--- |
| 传输 | 只支持 **HTTPS**（Android 网络安全配置、iOS ATS 均不放行明文 HTTP） |
| 地址 | 用户填写的 `baseUrl`，App 在其后拼接 `/v1/<接口>`，如 `https://example.com/trackr/v1/search` |
| 鉴权 | 填了令牌时，每个请求带 `Authorization: Bearer <token>`；没填则不带 |
| 编码 | 请求、响应均为 UTF-8 JSON；`POST` 请求带 `Content-Type: application/json` |
| 成功 | HTTP `2xx` + 响应体为对应 JSON；其他状态码一律视为失败，界面显示错误 |
| 坐标 | **全部使用 WGS-84**（请求里的 `lat`/`lng`、响应里的站点和车辆坐标） |
| 未知字段 | App 忽略响应里不认识的字段；可省略的字段省略即取默认值 |
| 超时 | 单次请求总超时 20 秒 |

---

## 2. 接口一览

| 方法 | 路径 | 请求体 | 响应 | 必需 |
| :--- | :--- | :--- | :--- | :--- |
| GET | `/v1/manifest` | — | `Manifest` | 否（不实现时按默认能力） |
| GET | `/v1/cities` | — | `TransitCity[]` | 是 |
| POST | `/v1/search` | `{ city, keyword, userLat?, userLng? }` | `TransitSearchResult` | 是 |
| POST | `/v1/line` | `{ city, lineId, targetOrder?, stationId?, userLat?, userLng?, lineNameHint, directionHint }` | `BusLineDetail` | 是 |
| POST | `/v1/stop` | `{ city, stopId?, stopName?, physicalStId?, namesakeStId?, stationLat?, stationLng?, userLat?, userLng? }` | `NearbyStop[]` | 是 |
| POST | `/v1/nearby` | `{ city, lat, lng, limit }` | `NearbyStop[]` | 能力 `nearby` |
| POST | `/v1/geocode` | `{ lat, lng }` | `{ city?, district? }` | 能力 `geocode` |
| POST | `/v1/timetable` | `{ city, line, at? }` | `LineTimetable` | 能力 `timetable` |
| POST | `/v1/lines` | `{ city }` | `BusLineSummary[]` | 能力 `listAllLines` |
| POST | `/v1/reachable` | `{ city }` | `{ reachable }` | 能力 `cityReachability` |

请求体里的 `city` 就是 `/v1/cities` 返回的城市对象原样回传，服务可以在城市对象里放自己需要的标识（`cityId`、`adcode`）。

### 2.1 `GET /v1/manifest`

```json
{
  "protocolVersion": 1,
  "name": "我的数据源",
  "capabilities": {
    "nearby": true,
    "geocode": true,
    "timetable": true,
    "stopDetailIncludesBuses": true,
    "listAllLines": false,
    "cityReachability": false
  }
}
```

| 能力 | 默认 | 含义 |
| :--- | :--- | :--- |
| `nearby` | `true` | 支持 `/v1/nearby`（首页附近站）；为 `false` 时也不会调用 `/v1/geocode` |
| `geocode` | `true` | 支持 `/v1/geocode`（定位所在城市 / 区县） |
| `timetable` | `true` | 支持 `/v1/timetable`；为 `false` 时时刻表显示「当前数据源未提供时刻表」 |
| `stopDetailIncludesBuses` | `true` | `/v1/stop` 返回的 `approachingBuses` 带车辆在全线的站序；为 `false` 时「一起等」会改为逐条查 `/v1/line` |
| `listAllLines` | `false` | 支持 `/v1/lines`，首页直接铺出本城全部线路（适合线路少的县级市） |
| `cityReachability` | `false` | 支持 `/v1/reachable`，城市选择器会把不可达的城市标「离线」 |

App 在每个服务地址第一次使用时读取一次 manifest 并缓存，换地址后重新读取。

### 2.2 `POST /v1/geocode`

```json
// 请求
{ "lat": 31.2304, "lng": 121.4737 }
// 响应
{ "city": "上海", "district": "黄浦区" }
```

`city` 用于自动切换城市，需能与 `/v1/cities` 里的 `cityName` 对上（`"上海"` 与 `"上海市"` 视为相同）。

---

## 3. 数据模型

字段与 App 内部模型（`core/model/.../TransitModels.kt`）一一对应，省略的字段取下表默认值。

### `TransitCity`

| 字段 | 类型 | 默认 | 说明 |
| :--- | :--- | :--- | :--- |
| `cityId` | string | **必填** | 你的服务自己的城市 ID，可为空串 |
| `cityName` | string | **必填** | 城市名，如 `"上海"` |
| `pinyin` | string | `""` | 用于城市搜索 |
| `province` | string | `""` | 省份；为空时 App 按城市名 / 区划码推断 |
| `adcode` | string? | `null` | 6 位国标行政区划码（GB/T 2260）。用于推断省份、县级市归属 |
| `parentCity` | string? | `null` | 县级城市的上级地级市，如 `"北流"` → `"玉林"` |
| `supportSubway` / `isHot` | bool | `false` | 展示用 |

### `BusLineDirection`（线路的一个方向）

| 字段 | 类型 | 默认 | 说明 |
| :--- | :--- | :--- | :--- |
| `lineId` | string | **必填** | 全局唯一、可直接传回 `/v1/line` 的方向 ID。收藏、提醒、通知恢复只会保存它和城市 |
| `name` | string | **必填** | 线路名，如 `"49路"` |
| `startStop` / `endStop` | string | **必填** | 起点站 / 终点站 |
| `lineNo` | string | `""` | |
| `direction` | int | `0` | `0` 正向、`1` 反向 |
| `firstTime` / `lastTime` | string | `""` | 首末班，`"HH:mm"` |
| `price` | string | `""` | |

### `BusLineSummary`（搜索结果里的一条线路）

`{ name, lineNo, isSubway = false, directions: BusLineDirection[] }`。地铁线标 `isSubway = true`（App 会过滤掉）。

### `BusStop`

| 字段 | 类型 | 默认 | 说明 |
| :--- | :--- | :--- | :--- |
| `stopId` | string | **必填** | |
| `name` | string | **必填** | |
| `order` | int | **必填** | **严格 1-based** 站序 |
| `wgsLat` / `wgsLng` | double | `0.0` | 无坐标填 `0.0` |
| `physicalStId` / `namesakeStId` | string? | `null` | 物理站台 ID / 同名站 ID，原样回传给 `/v1/stop` |
| `statusNote` | string? | `null` | 如「临时不停靠」 |

### `LiveBus`（在途车辆）

| 字段 | 类型 | 默认 | 说明 |
| :--- | :--- | :--- | :--- |
| `busId` | string | **必填** | 同一趟行程内保持稳定（用于「追这辆车」） |
| `plateOrFleetNo` | string | **必填** | 车牌或自编号；机器哈希会被 App 过滤不显示 |
| `order` | int | **必填** | 正在驶向（或已停靠）的 1-based 站序 |
| `isArrived` | bool | `false` | 已停靠在 `order` 站 |
| `segmentProgress` | float | `1.0` | `order-1 → order` 区间进度 `[0, 1]` |
| `wgsLat` / `wgsLng` | double | `0.0` | |
| `etaSeconds` | int | `-1` | 到目标站剩余秒数，`<= 0` 表示无预测 |
| `distanceToTargetMeters` | int? | `null` | |
| `expectedArrivalTimeText` | string? | `null` | 如 `"21:03"` |
| `capacityLevel` | int | `0` | `0` 舒适、`1` 适中、`2` 拥挤、`-1` 未知 |

### `BusLineDetail`（`/v1/line` 响应）

| 字段 | 类型 | 默认 | 说明 |
| :--- | :--- | :--- | :--- |
| `line` | BusLineDirection | **必填** | 当前方向 |
| `reverseDirection` | BusLineDirection? | `null` | 反向；单程线为 `null` |
| `stops` | BusStop[] | **必填** | 全线站点 |
| `buses` | LiveBus[] | **必填** | 在途车辆 |
| `operatingState` | string | `"RUNNING"` | `RUNNING` / `WAITING` / `SUSPENDED` / `ENDED` / `NO_REALTIME` |
| `statusDescription` | string | `""` | |
| `nextDepartureFromStart` | string? | `null` | 首站下一班，`"HH:mm"` |
| `targetOrder` | int | `0` | 请求里的候车站对应站序 |

### `NearbyStop`（`/v1/nearby`、`/v1/stop` 响应元素；同名站的多个站台各算一个）

| 字段 | 类型 | 默认 | 说明 |
| :--- | :--- | :--- | :--- |
| `stopId` / `name` | string | **必填** | |
| `distanceMeters` | int? | `null` | 离用户距离 |
| `wgsLat` / `wgsLng` | double | `0.0` | |
| `physicalStId` / `namesakeStId` | string? | `null` | |
| `isSubway` | bool | `false` | 纯地铁站（App 会过滤） |
| `lines` | NearbyStopLineStatus[] | `[]` | 途经线路实时状态 |

`NearbyStopLineStatus`：`{ line: BusLineDirection, targetOrder, targetStopId, targetStopName, statusText = "", preArrivalTime?, approachingBuses: LiveBus[] = [] }`。

### `TransitSearchResult`（`/v1/search` 响应）

`{ keyword, lines: BusLineSummary[] = [], stops: NearbyStop[] = [] }`。不支持搜站点时 `stops` 返回空数组。

### `LineTimetable`（`/v1/timetable` 响应）

`{ lineId, lineName, nextDeparture?, nextDepartureSource?, todayDepartures = [], yesterdayDepartures = [], segments = [], depDesc = "" }`。
只提供首末班时如实返回已有字段并在 `depDesc` 说明，不要编造中间班次。

---

## 4. 最小示例与本地 Mock 参考

```bash
curl -H "Authorization: Bearer $TOKEN" https://example.com/trackr/v1/cities
# [{"cityId":"sh","cityName":"上海","adcode":"310100","province":"直辖市"}]

curl -X POST -H "Content-Type: application/json" \
     -d '{"city":{"cityId":"sh","cityName":"上海"},"keyword":"49"}' \
     https://example.com/trackr/v1/search
# {"keyword":"49","lines":[{"name":"49路","lineNo":"49","directions":[
#   {"lineId":"sh-49-0","name":"49路","startStop":"A站","endStop":"B站","direction":0}]}]}
```

### 4.1 最小静态 Mock 服务（Python 3）

以下示例返回纯静态示例数据，用于本地调试或作为接入公开开放数据（如 GTFS / GTFS-RT、各地政府数据开放平台或企业班车自有数据）的起点（生产环境需经由反向代理提供 HTTPS）：

```python
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

CITIES = [{"cityId": "demo", "cityName": "示例市", "adcode": "310100", "province": "直辖市"}]
LINE = {"lineId": "demo-1-0", "name": "1路", "lineNo": "1", "startStop": "北站", "endStop": "南站", "direction": 0}
STOPS = [
    {"stopId": "s1", "name": "北站", "order": 1, "wgsLat": 31.2304, "wgsLng": 121.4737},
    {"stopId": "s2", "name": "中心广场", "order": 2, "wgsLat": 31.2250, "wgsLng": 121.4737},
    {"stopId": "s3", "name": "南站", "order": 3, "wgsLat": 31.2200, "wgsLng": 121.4737},
]

class Handler(BaseHTTPRequestHandler):
    def _send(self, data):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.endswith("/v1/manifest"):
            self._send({"protocolVersion": 1, "name": "Demo Mock", "capabilities": {"nearby": False, "geocode": False, "timetable": False}})
        elif self.path.endswith("/v1/cities"):
            self._send(CITIES)
        else:
            self.send_error(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        req = json.loads(self.rfile.read(length) or b"{}")
        if self.path.endswith("/v1/search"):
            self._send({"keyword": req.get("keyword", ""), "lines": [{"name": "1路", "lineNo": "1", "directions": [LINE]}], "stops": []})
        elif self.path.endswith("/v1/line"):
            self._send({"line": LINE, "stops": STOPS, "buses": [{"busId": "bus-1", "plateOrFleetNo": "A001", "order": 2, "etaSeconds": 180}]})
        elif self.path.endswith("/v1/stop"):
            self._send([{"stopId": "s2", "name": "中心广场", "wgsLat": 31.2250, "wgsLng": 121.4737, "lines": []}])
        else:
            self.send_error(404)

if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 8080), Handler).serve_forever()
```

### 4.2 合规说明

本协议仅用于对接用户自有数据、公开标准数据（如 GTFS / GTFS-RT）或已获合法授权的接口服务。**严禁**将本协议用于未经授权抓取、代理或绕过任何受保护的商业公交服务。

---

## 5. 代码位置

| 内容 | 位置 |
| :--- | :--- |
| 协议客户端 | `core/network/src/commonMain/kotlin/com/trackr/core/network/provider/custom/CustomTransitProvider.kt` |
| Provider 契约 | `core/network/src/commonMain/kotlin/com/trackr/core/network/provider/ITransitProvider.kt` |
| 协调器（城市补全、搜索过滤） | `core/data/src/commonMain/kotlin/com/trackr/core/data/provider/TransitProviderCoordinator.kt` |
| 配置模型 | `CustomSourceConfig`（`core/model/.../TransitModels.kt`） |
| 设置页 | `feature/me/src/commonMain/kotlin/com/trackr/ui/screens/me/DataSourceDialog.kt` |
