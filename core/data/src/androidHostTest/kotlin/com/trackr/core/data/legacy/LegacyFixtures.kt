package com.trackr.core.data.legacy

import android.content.Context
import android.content.SharedPreferences

/**
 * 旧版 SharedPreferences 的样例数据，格式和真机上 shared_prefs 目录里那几个 xml 一样（字段、嵌套、转义）。
 * 内容是编的，不含用户的真实数据。
 */
object LegacyFixtures {

    const val CITY_JSON =
        """{"cityId":"051","cityName":"莆田","pinyin":"PuTian","supportSubway":false,"isHot":false,"mygolbsCityCode":"0","province":"福建"}"""

    /** 两条收藏的车，顺序要保住（界面顺序） */
    const val FAVORITES_JSON =
        """[{"id":"051_100_7","cityId":"051","cityName":"莆田","lineId":"100","lineName":"59","lineNo":"r02678","direction":1,"startStop":"公交南站","endStop":"大学城","targetStopId":"0594-1","targetStopName":"某中学（学园校区）","targetOrder":7,"totalStops":36,"wgsLat":25.42,"wgsLng":119.0,"source":"LEGACY_SOURCE","mygolbsCityCode":"","addedAtMillis":1793444022365},""" +
            """{"id":"mg_200_3","cityId":"","cityName":"莆田","lineId":"mg:200","lineName":"夜班1路","direction":0,"startStop":"A","endStop":"B","targetStopId":"x","targetStopName":"X站","targetOrder":3,"source":"LEGACY_SOURCE_B","mygolbsCityCode":"0","addedAtMillis":1793000000000,"someFutureField":42}]"""

    const val SEARCH_HISTORY_JSON = """["59","7路","万达"]"""

    /** 三条路线：直达、带轨迹点的一次换乘、customGroupId 指向不存在的分组 */
    const val GROUPS_JSON =
        """[{"id":"route-1","name":"甲站(公交站) ➔ 乙广场 (7路 直达)","category":"COMMUTE","legs":[{"legIndex":0,"fromStopName":"甲站","fromStopId":"","toStopName":"乙站","toStopId":"","fromStopLat":25.422112,"fromStopLng":119.011119,"toStopLat":25.418058,"toStopLng":118.994386,"candidateLines":[{"cityId":"051","cityName":"莆田","lineId":"594166803842","lineName":"7路","source":"LEGACY_SOURCE","direction":0,"fromStopOrder":21,"toStopOrder":25,"startStopName":"甲站","endStopName":"乙站（广场）","mygolbsCityCode":"0"}],"walkingDistanceMeters":104,"ridePath":[],"walkPathBefore":[]}],"isTemporary":false,"sortOrder":1,"customGroupId":"commute","createdAt":1793411491753,"updatedAt":1793411517420},""" +
            """{"id":"route-2","name":"我的位置 ➔ 丙广场 (23路夜班 ➔ 331路 · 1次换乘)","category":"CUSTOM","legs":[{"legIndex":0,"fromStopName":"丁站","toStopName":"中转","candidateLines":[{"cityId":"051","lineId":"23","lineName":"23路夜班","direction":1,"fromStopOrder":2,"toStopOrder":9},{"cityId":"051","lineId":"23b","lineName":"23路"}],"walkingDistanceMeters":300,"ridePath":[{"lat":25.1,"lng":119.1},{"lat":25.2,"lng":119.2}],"walkPathBefore":[{"lat":25.0,"lng":119.0}]},{"legIndex":1,"fromStopName":"中转","toStopName":"丙广场","candidateLines":[{"cityId":"051","lineId":"331","lineName":"331路","source":"LEGACY_SOURCE_B","mygolbsCityCode":"0"}],"walkingDistanceMeters":50}],"sortOrder":3,"customGroupId":"home","createdAt":1,"updatedAt":2},""" +
            """{"id":"route-3","name":"孤儿","legs":[],"sortOrder":2,"customGroupId":"deleted-group","createdAt":5,"updatedAt":6}]"""

    const val FAVORITE_GROUPS_JSON =
        """[{"id":"commute","name":"上班通勤","sortOrder":0,"isDefault":true},{"id":"home","name":"回家线路","sortOrder":1,"isDefault":true},{"id":"gym","name":"健身","sortOrder":2,"isDefault":false}]"""

    const val REMINDERS_JSON =
        """[{"id":"rem-1","enabled":true,"hour":7,"minute":30,"daysOfWeek":[1,2,3,4,5],"cityId":"051","cityName":"莆田","lineId":"100","lineName":"59","direction":1,"endStopName":"大学城","targetStopId":"s1","targetStopName":"某中学","targetOrder":7,"totalStops":36,"dataSourceType":"LEGACY_SOURCE","autoStartLiveTracking":true},""" +
            """{"id":"rem-2","enabled":false,"hour":18,"minute":5,"daysOfWeek":[],"cityId":"","cityName":"莆田","lineId":"mg:200","lineName":"夜班1路","direction":0,"endStopName":"B","targetStopId":"x","targetStopName":"X站","targetOrder":3,"dataSourceType":"LEGACY_SOURCE_B","mygolbsCityCode":"0"}]"""

    fun prefs(context: Context, name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** 写一套完整的旧数据（0a4fdab 之后的布局：Key 在 trackr_amap_keys） */
    fun writeAll(context: Context) {
        prefs(context, LegacyPrefs.TRANSIT).edit()
            .putString("selected_city_json", CITY_JSON)
            .putString("engine_mode", "LEGACY_SOURCE_B")
            .putString("aggregated_source_priority", "LEGACY_SOURCE_B_FIRST")
            .putString("preferred_transit_code_provider", "alipay")
            .putInt("nearby_stops_count", 6)
            .putBoolean("is_live_notification_enabled", false)
            .putInt("favorites_group_migration_version", 2)
            .putString("favorites_json", FAVORITES_JSON)
            .putString("search_history_json", SEARCH_HISTORY_JSON)
            .commit()
        prefs(context, LegacyPrefs.ROUTE_GROUPS).edit()
            .putString("groups_list_json", GROUPS_JSON)
            .putString("favorite_groups_list_json", FAVORITE_GROUPS_JSON)
            .commit()
        prefs(context, LegacyPrefs.REMINDERS).edit()
            .putString("reminders_list_json", REMINDERS_JSON)
            .commit()
        prefs(context, LegacyPrefs.REMINDER_SCHEDULE_STATE).edit()
            .putLong("next_trigger_rem-1", 1_800_000_000_000L)
            .putLong("next_trigger_gone", 1L)
            .commit()
        prefs(context, LegacyPrefs.AMAP_KEYS).edit()
            .putString("custom_amap_android_key", "android-key-123")
            .putString("custom_amap_web_key", "web-key-456")
            .putBoolean("migrated_from_trackr_prefs", true)
            .commit()
    }

    /** 五个旧文件当前的全部内容（用来核对迁移前后一字不差） */
    fun snapshot(context: Context): Map<String, Map<String, Any?>> =
        LegacyPrefs.ALL_FILES.associateWith { prefs(context, it).all.toMap() }

    fun clearAll(context: Context) {
        LegacyPrefs.ALL_FILES.forEach { prefs(context, it).edit().clear().commit() }
    }
}
