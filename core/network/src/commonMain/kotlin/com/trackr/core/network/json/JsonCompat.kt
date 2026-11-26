package com.trackr.core.network.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * 只读 JSON，接口和 org.json 的 JSONObject / JSONArray 一样（只做了解析代码用到的那部分），底下是 kotlinx.serialization。
 *
 * 为什么不直接改用 kotlinx 的 JsonElement：保留和 org.json 一致的宽松取值规则
 * （optInt 能读字符串 "12"、optString 能读数字、缺字段给默认值），解析代码只换 import，行为不变。
 *
 * 和 org.json 20240303 对齐的取值规则：
 * - optString：缺字段或 JSON null 给默认值；数字、布尔原样转成字符串；对象 / 数组给紧凑 JSON 文本。
 * - optInt / optLong / optDouble：数字直接取；字符串以数字或 '-' 开头且能解析时按数字取；其余给默认值。
 *   小数转整数时截断（同 Number.intValue）。
 * - optBoolean：布尔直接取；字符串 "true" / "false"（不分大小写）也认。
 * - opt：字符串给 String，布尔给 Boolean，整数给 Int / Long，小数给 toString 保留原文的 Number，对象 / 数组给本文件的包装类，
 *   JSON null 给 [JSONObject.NULL]，缺字段给 null。
 *
 * 和 org.json 不同的地方：重复的 key 以最后一个为准（org.json 会抛异常）；文本末尾多出的内容会报错（org.json 忽略）；
 * 指数写法的数字 optString 原样给出（`1e3`，org.json 给 `1E+3`）；只读，没有 put。
 */

/** 解析失败或取值类型不对时抛出（和 org.json 一样是 RuntimeException） */
class JSONException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private val lenientJson = Json { isLenient = true }

private fun parseElement(text: String): JsonElement = try {
    lenientJson.parseToJsonElement(text)
} catch (e: Exception) {
    throw JSONException("Malformed JSON: ${e.message}", e)
}

/**
 * org.json 的字符串转数字识别：只认以数字或 '-' 开头的串，且不认前导零（"007"、"-01" 不算数字）
 */
private fun looksNumeric(s: String): Boolean {
    val c = s.firstOrNull() ?: return false
    if (c !in '0'..'9' && c != '-') return false
    val digits = if (c == '-') s.drop(1) else s
    return !(digits.length > 1 && digits[0] == '0' && digits[1].isDigit())
}

private fun JsonPrimitive.numberOrNull(): Double? {
    if (this is JsonNull) return null
    if (isString && !looksNumeric(content)) return null
    if (!isString && (content == "true" || content == "false")) return null
    return content.toDoubleOrNull()
}

private fun JsonPrimitive.longOrNull(): Long? {
    if (this is JsonNull) return null
    if (isString && !looksNumeric(content)) return null
    if (!isString && (content == "true" || content == "false")) return null
    content.toLongOrNull()?.let { return it }
    return content.toDoubleOrNull()?.toLong()
}

private fun JsonElement?.asOptString(fallback: String): String = when (this) {
    null, JsonNull -> fallback
    is JsonPrimitive -> content
    else -> toString()
}

private fun JsonElement?.asOptLong(fallback: Long): Long =
    (this as? JsonPrimitive)?.longOrNull() ?: fallback

private fun JsonElement?.asOptInt(fallback: Int): Int =
    (this as? JsonPrimitive)?.longOrNull()?.toInt() ?: fallback

private fun JsonElement?.asOptDouble(fallback: Double): Double =
    (this as? JsonPrimitive)?.numberOrNull() ?: fallback

private fun JsonElement?.asOptBoolean(fallback: Boolean): Boolean {
    val p = this as? JsonPrimitive ?: return fallback
    if (p is JsonNull) return fallback
    return when {
        p.content.equals("true", ignoreCase = true) -> true
        p.content.equals("false", ignoreCase = true) -> false
        else -> fallback
    }
}

private fun JsonElement?.asOpt(): Any? = when (this) {
    null -> null
    JsonNull -> JSONObject.NULL
    is JsonObject -> JSONObject(this)
    is JsonArray -> JSONArray(this)
    is JsonPrimitive -> when {
        isString -> content
        content == "true" -> true
        content == "false" -> false
        else -> content.toIntOrNull() ?: content.toLongOrNull()
            ?: content.toDoubleOrNull()?.let { JsonDecimal(content, it) } ?: content
    }
}

/** 小数：toString 给原文（同 org.json 的 BigDecimal，如 `31.20`），取值按 double */
private class JsonDecimal(private val literal: String, private val value: Double) : Number() {
    override fun toDouble(): Double = value
    override fun toFloat(): Float = value.toFloat()
    override fun toLong(): Long = value.toLong()
    override fun toInt(): Int = value.toInt()
    override fun toShort(): Short = value.toInt().toShort()
    override fun toByte(): Byte = value.toInt().toByte()
    override fun toString(): String = literal
    override fun equals(other: Any?): Boolean = other is JsonDecimal && other.literal == literal
    override fun hashCode(): Int = literal.hashCode()
}

class JSONObject internal constructor(internal val element: JsonObject) {

    /** 解析 JSON 文本；不是对象时抛 [JSONException] */
    constructor(text: String) : this(
        parseElement(text) as? JsonObject ?: throw JSONException("A JSONObject text must begin with '{'")
    )

    /** 空对象 */
    constructor() : this(JsonObject(emptyMap()))

    fun has(key: String): Boolean = element.containsKey(key)

    fun isNull(key: String): Boolean = element[key].let { it == null || it is JsonNull }

    fun length(): Int = element.size

    fun keys(): Iterator<String> = element.keys.iterator()

    fun opt(key: String): Any? = element[key].asOpt()

    fun optString(key: String): String = optString(key, "")

    fun optString(key: String, fallback: String): String = element[key].asOptString(fallback)

    fun optInt(key: String): Int = optInt(key, 0)

    fun optInt(key: String, fallback: Int): Int = element[key].asOptInt(fallback)

    fun optLong(key: String): Long = optLong(key, 0L)

    fun optLong(key: String, fallback: Long): Long = element[key].asOptLong(fallback)

    fun optDouble(key: String): Double = optDouble(key, Double.NaN)

    fun optDouble(key: String, fallback: Double): Double = element[key].asOptDouble(fallback)

    fun optBoolean(key: String): Boolean = optBoolean(key, false)

    fun optBoolean(key: String, fallback: Boolean): Boolean = element[key].asOptBoolean(fallback)

    fun optJSONObject(key: String): JSONObject? = (element[key] as? JsonObject)?.let(::JSONObject)

    fun optJSONArray(key: String): JSONArray? = (element[key] as? JsonArray)?.let(::JSONArray)

    fun getJSONObject(key: String): JSONObject =
        optJSONObject(key) ?: throw JSONException("JSONObject[\"$key\"] is not a JSONObject.")

    fun getJSONArray(key: String): JSONArray =
        optJSONArray(key) ?: throw JSONException("JSONObject[\"$key\"] is not a JSONArray.")

    /** 和 org.json 一样只认字符串值 */
    fun getString(key: String): String {
        val p = element[key] as? JsonPrimitive
        if (p == null || p is JsonNull || !p.isString) throw JSONException("JSONObject[\"$key\"] is not a string.")
        return p.content
    }

    fun getInt(key: String): Int {
        val p = element[key] as? JsonPrimitive ?: throw JSONException("JSONObject[\"$key\"] not found.")
        return p.longOrNull()?.toInt() ?: throw JSONException("JSONObject[\"$key\"] is not an int.")
    }

    override fun toString(): String = element.toString()

    override fun equals(other: Any?): Boolean = other is JSONObject && other.element == element

    override fun hashCode(): Int = element.hashCode()

    companion object {
        /** JSON null 的占位值（同 org.json 的 JSONObject.NULL） */
        val NULL: Any = object {
            override fun toString(): String = "null"
        }
    }
}

class JSONArray internal constructor(internal val element: JsonArray) {

    /** 解析 JSON 文本；不是数组时抛 [JSONException] */
    constructor(text: String) : this(
        parseElement(text) as? JsonArray ?: throw JSONException("A JSONArray text must start with '['")
    )

    /** 空数组 */
    constructor() : this(JsonArray(emptyList()))

    fun length(): Int = element.size

    fun opt(index: Int): Any? = element.getOrNull(index).asOpt()

    fun optString(index: Int): String = optString(index, "")

    fun optString(index: Int, fallback: String): String = element.getOrNull(index).asOptString(fallback)

    fun optInt(index: Int): Int = optInt(index, 0)

    fun optInt(index: Int, fallback: Int): Int = element.getOrNull(index).asOptInt(fallback)

    fun optDouble(index: Int, fallback: Double): Double = element.getOrNull(index).asOptDouble(fallback)

    fun optJSONObject(index: Int): JSONObject? = (element.getOrNull(index) as? JsonObject)?.let(::JSONObject)

    fun optJSONArray(index: Int): JSONArray? = (element.getOrNull(index) as? JsonArray)?.let(::JSONArray)

    fun getJSONObject(index: Int): JSONObject =
        optJSONObject(index) ?: throw JSONException("JSONArray[$index] is not a JSONObject.")

    override fun toString(): String = element.toString()

    override fun equals(other: Any?): Boolean = other is JSONArray && other.element == element

    override fun hashCode(): Int = element.hashCode()
}
