package com.trackr.core.network.json

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 兼容层和真正的 org.json（20240303，原来 Android 上用的同一套规则）逐个字段对比：
 * 同一段 JSON，每个 key 分别用 optString / optInt / optLong / optDouble / optBoolean 取值，结果必须一样（R17）。
 * 已知差异：指数写法的数字（`1e3`，org.json 给 `1E+3`）和 `-0`（org.json 给 `-0.0`）的 optString 不同，三个数据源都不发这种数字。
 */
class JsonCompatParityTest {

    private val samples = listOf(
        """{"s":"abc","n":12,"d":31.20,"neg":-3.7,"ns":"12","nd":"12.9","b":true,"bs":"TRUE","fs":"False",
            "nul":null,"arr":[1,"x"],"o":{"a":1},"empty":"","sp":" 12","plus":"+5","z":0,
            "big":12345678901,"hex":"0x1A","dots":"1.2.3","lead0":"007","zh":"中文","dash":"-","negs":"-8"}""",
        """{"status":"1","status2":1,"lat":"31.231","lon":121.476,"dis":200,"value":-1,"travelTime":"",
            "state":"1","order":"3","capacity":null,"distanceToDest":"50"}"""
    )

    @Test
    fun everyAccessorMatchesOrgJson() {
        for (text in samples) {
            val ours = JSONObject(text)
            val theirs = org.json.JSONObject(text)
            for (key in theirs.keys().asSequence().toList() + "missing") {
                assertEquals(theirs.optString(key), ours.optString(key), "optString($key) in $text")
                assertEquals(theirs.optString(key, "fb"), ours.optString(key, "fb"), "optString($key, fb)")
                assertEquals(theirs.optInt(key, -99), ours.optInt(key, -99), "optInt($key)")
                assertEquals(theirs.optLong(key, -99L), ours.optLong(key, -99L), "optLong($key)")
                assertEquals(theirs.optDouble(key, -9.5), ours.optDouble(key, -9.5), "optDouble($key)")
                assertEquals(theirs.optBoolean(key, false), ours.optBoolean(key, false), "optBoolean($key, false)")
                assertEquals(theirs.optBoolean(key, true), ours.optBoolean(key, true), "optBoolean($key, true)")
                assertEquals(theirs.has(key), ours.has(key), "has($key)")
                assertEquals(theirs.isNull(key), ours.isNull(key), "isNull($key)")
                assertEquals(theirs.optJSONObject(key) != null, ours.optJSONObject(key) != null, "optJSONObject($key)")
                assertEquals(theirs.optJSONArray(key) != null, ours.optJSONArray(key) != null, "optJSONArray($key)")
                // 解析代码只用 opt(...) 的 toString 或 is String 判断
                assertEquals(theirs.opt(key)?.toString(), ours.opt(key)?.toString(), "opt($key).toString")
                assertEquals(theirs.opt(key) is String, ours.opt(key) is String, "opt($key) is String")
            }
        }
    }

    @Test
    fun arrayAccessorsMatchOrgJson() {
        val text = """[1,"x",{"k":"v"},null,"12",2.5,true]"""
        val ours = JSONArray(text)
        val theirs = org.json.JSONArray(text)
        assertEquals(theirs.length(), ours.length())
        for (i in -1..theirs.length()) {
            assertEquals(theirs.optString(i), ours.optString(i), "optString($i)")
            assertEquals(theirs.optInt(i, -99), ours.optInt(i, -99), "optInt($i)")
            assertEquals(theirs.optDouble(i, -9.5), ours.optDouble(i, -9.5), "optDouble($i)")
            assertEquals(theirs.optJSONObject(i) != null, ours.optJSONObject(i) != null, "optJSONObject($i)")
        }
    }
}
