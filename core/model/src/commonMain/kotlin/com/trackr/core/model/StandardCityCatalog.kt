package com.trackr.core.model

/** 标准城市层级 */
enum class StandardCityLevel {
    /** 直辖市、地级市、省直辖县级行政区、港澳台 */
    CITY,
    /** 县、县级市、自治县、旗 */
    COUNTY,
    /** 市辖区 */
    DISTRICT
}

/**
 * Trackr 数据源协议（v1）标准城市条目（GB/T 2260）。
 *
 * 与 `docs/cities-v1.json` 一一对应。数据源在 `/v1/cities` 中只需返回 6 位国标 `adcode`
 * （或 `{ "adcode": "...", "cityId": "..." }`），App 即可按此表补全名称、省份、上级地级市与展示层级。
 */
data class StandardCityEntry(
    val adcode: String,
    val name: String,
    val province: String,
    val parentAdcode: String? = null,
    val parentCity: String? = null,
    val level: StandardCityLevel = StandardCityLevel.CITY,
    val isHot: Boolean = false
) {
    fun toTransitCity(
        cityId: String = adcode,
        supportSubway: Boolean = false
    ): TransitCity = TransitCity(
        cityId = cityId,
        cityName = name,
        supportSubway = supportSubway,
        isHot = isHot,
        mygolbsCityCode = adcode,
        province = province,
        parentCity = parentCity
    )
}

/**
 * Trackr 协议内置标准城市表（GB/T 2260）。
 *
 * - 收录全国 3,190 个省/市/县/区标准行政区划，纯离线、零网络请求。
 * - 数据源返回 `adcode` 时用 [findByAdcode] / [normalize] 统一补全省份、上级地级市与名称。
 * - 未配置数据源时用 [fallbackCities] 提供全国地级市 + 县/县级市兜底列表（不含普通主城区市辖区，避免列表过长）。
 */
object StandardCityCatalog {

    /** 直辖市省级码到常用市级码的映射，兼容数据源传 "310000" 或 "310100" */
    private val MUNICIPALITY_ALIASES: Map<String, String> = mapOf(
        "110000" to "110100",
        "120000" to "120100",
        "310000" to "310100",
        "500000" to "500100"
    )

    private data class ParsedCatalog(
        val all: List<StandardCityEntry>,
        val byAdcode: Map<String, StandardCityEntry>,
        val fallbackCities: List<TransitCity>
    )

    private val catalog: ParsedCatalog by lazy { parse() }

    /** 全部标准行政区划条目（含地级市、县/县级市、市辖区） */
    val all: List<StandardCityEntry>
        get() = catalog.all

    /** 按 6 位 GB/T 2260 `adcode` 查标准条目；兼容直辖市 `xx0000` 写法 */
    fun findByAdcode(adcode: String?): StandardCityEntry? {
        val raw = adcode?.trim().orEmpty()
        if (raw.length != 6) return null
        val normalized = MUNICIPALITY_ALIASES[raw] ?: raw
        return catalog.byAdcode[normalized]
    }

    /** 根据 6 位县级 `adcode` 查上级地级市名称；非县级或未收录返回 null */
    fun parentCityNameOf(adcode: String?): String? {
        findByAdcode(adcode)?.parentCity?.let { return it }
        val prefCode = ProvinceCityCatalog.prefectureCodeOf(adcode) ?: return null
        return findByAdcode(prefCode)?.name
    }

    /**
     * 未接入数据源时的全国兜底城市列表：包含全部直辖市、地级市、省直辖县、县/县级市/旗与港澳台，
     * 按「省份 → 地级市 → 县级」层级组织（不含普通市辖区）。
     */
    fun fallbackCities(): List<TransitCity> = catalog.fallbackCities

    /**
     * 用标准城市表补全一条 [TransitCity] 的省份、上级地级市与热门标记。
     * 若 `mygolbsCityCode` 不在标准表中则原样返回。
     */
    fun normalize(city: TransitCity): TransitCity {
        val entry = findByAdcode(city.mygolbsCityCode) ?: return city
        val effectiveName = city.cityName.ifBlank { entry.name }
        val effectiveProvince = city.province.takeIf { it.isNotBlank() && it != "其他" } ?: entry.province
        val effectiveParent = city.parentCity?.takeIf { it.isNotBlank() }
            ?: entry.parentCity?.takeIf {
                ProvinceCityCatalog.cleanCityName(it) != ProvinceCityCatalog.cleanCityName(effectiveName)
            }
        return city.copy(
            cityName = effectiveName,
            province = effectiveProvince,
            parentCity = effectiveParent,
            isHot = city.isHot || entry.isHot
        )
    }

    private fun parse(): ParsedCatalog {
        val rawRows = ArrayList<Array<String>>(3200)
        val nameByAdcode = HashMap<String, String>(4096)
        val provinces = StandardCityData.PROVINCES

        for (chunk in StandardCityData.CHUNKS) {
            for (line in chunk.lineSequence()) {
                if (line.isEmpty()) continue
                val parts = line.split('|')
                if (parts.size < 6) continue
                rawRows.add(parts.toTypedArray())
                nameByAdcode[parts[0]] = parts[1]
            }
        }

        val all = ArrayList<StandardCityEntry>(rawRows.size)
        val byAdcode = HashMap<String, StandardCityEntry>(rawRows.size * 2)
        val fallback = ArrayList<TransitCity>(2300)

        for (parts in rawRows) {
            val adcode = parts[0]
            val name = parts[1]
            val province = provinces.getOrElse(parts[2].toInt()) { "其他" }
            val parentAdcode = parts[3].takeIf { it.isNotEmpty() }
            val parentCity = parentAdcode?.let { nameByAdcode[it] }
            val level = when (parts[4]) {
                "C" -> StandardCityLevel.CITY
                "X" -> StandardCityLevel.COUNTY
                else -> StandardCityLevel.DISTRICT
            }
            val isHot = parts[5] == "1"
            val entry = StandardCityEntry(
                adcode = adcode,
                name = name,
                province = province,
                parentAdcode = parentAdcode,
                parentCity = parentCity,
                level = level,
                isHot = isHot
            )
            all.add(entry)
            byAdcode[adcode] = entry
            if (level != StandardCityLevel.DISTRICT) {
                fallback.add(entry.toTransitCity(cityId = ""))
            }
        }

        return ParsedCatalog(all = all, byAdcode = byAdcode, fallbackCities = fallback)
    }
}
