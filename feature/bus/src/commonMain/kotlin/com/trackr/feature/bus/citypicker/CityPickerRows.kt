package com.trackr.feature.bus.citypicker

import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity

/**
 * 城市选择器右侧列表的一行：省 → 地级市 → 县级市/县 三级。
 *
 * 支持县级城市（通过 6 位国标行政区划码或 `parentCity` 归属到上级地级市）。
 * 这里把所有县级条目统一挂到所属地级市下面，地级市本身不在列表里时补一个不可点的分组标题，保证层级不乱。
 */
sealed interface CityPickerRow {
    val key: String

    /** 地级市本级（可选中）；[countyCount] > 0 时下面会跟县级条目 */
    data class City(val city: TransitCity, val countyCount: Int = 0) : CityPickerRow {
        override val key: String get() = "city_${city.uniqueKey}"
    }

    /** 县级城市（可选中，缩进一级显示） */
    data class County(val city: TransitCity) : CityPickerRow {
        override val key: String get() = "county_${city.uniqueKey}"
    }

    /** 上级地级市本身不在城市列表里时的分组标题（不可点） */
    data class PrefectureHeader(val name: String, val province: String) : CityPickerRow {
        override val key: String get() = "header_$name"
    }
}

/** 县级城市：有上级地级市且与自身不同名 */
fun TransitCity.isCountyLevel(): Boolean = isCountyLevel

/** 展示用的「所属地级市」；地级市本级返回空串 */
fun TransitCity.prefectureTrail(): String = if (isCountyLevel) effectivePrefecture.orEmpty() else ""

/**
 * 把一个省下的城市摊平成带层级的行。
 *
 * 顺序：按传入顺序遍历城市——遇到县先跳过，等父级出现时紧跟其后再补上；
 * 父级不在列表里的县，在末尾按地级市名归组，前面加一个分组标题。
 */
fun buildCityPickerRows(cities: List<TransitCity>): List<CityPickerRow> {
    val countiesByPrefKey = linkedMapOf<String, MutableList<TransitCity>>()
    val prefDisplayNameByKey = HashMap<String, String>()
    val countyKeys = HashSet<String>()

    for (city in cities) {
        if (!city.isCountyLevel) continue
        val prefRaw = city.effectivePrefecture.orEmpty()
        val prefKey = ProvinceCityCatalog.cleanCityName(prefRaw)
        if (prefKey.isBlank()) continue
        countiesByPrefKey.getOrPut(prefKey) { mutableListOf() }.add(city)
        if (prefKey !in prefDisplayNameByKey) prefDisplayNameByKey[prefKey] = prefRaw
        countyKeys.add(city.uniqueKey)
    }

    val rows = ArrayList<CityPickerRow>(cities.size)
    val handledPrefKeys = HashSet<String>()

    for (city in cities) {
        if (city.uniqueKey in countyKeys) continue
        val cityKey = ProvinceCityCatalog.cleanCityName(city.cityName)
        val counties = countiesByPrefKey[cityKey].orEmpty()
        rows.add(CityPickerRow.City(city, counties.size))
        if (counties.isNotEmpty() && handledPrefKeys.add(cityKey)) {
            counties.forEach { rows.add(CityPickerRow.County(it)) }
        }
    }

    for ((prefKey, counties) in countiesByPrefKey) {
        if (prefKey in handledPrefKeys) continue
        val displayPref = prefDisplayNameByKey[prefKey] ?: prefKey
        val province = counties.firstOrNull()?.province.orEmpty()
        rows.add(CityPickerRow.PrefectureHeader(displayPref, province))
        counties.forEach { rows.add(CityPickerRow.County(it)) }
    }

    return rows
}
