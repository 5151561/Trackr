package com.trackr.core.network.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** org.json 兼容层的取值规则（和 org.json 20240303 对齐，JVM 上的逐个对比见 androidHostTest 的 JsonCompatParityTest） */
class JsonCompatTest {

    private val obj = JSONObject(
        """{"s":"abc","n":12,"d":31.20,"neg":-3.7,"ns":"12","nd":"12.9","b":true,"bs":"TRUE",
            "nul":null,"arr":[1,"x",{"k":"v"}],"o":{"a":1},"empty":"","big":12345678901}"""
    )

    @Test
    fun optString_followsOrgJsonRules() {
        assertEquals("abc", obj.optString("s"))
        assertEquals("12", obj.optString("n"))
        assertEquals("31.20", obj.optString("d"))
        assertEquals("true", obj.optString("b"))
        assertEquals("", obj.optString("nul"))
        assertEquals("fallback", obj.optString("missing", "fallback"))
        assertEquals("fallback", obj.optString("nul", "fallback"))
        assertEquals("""{"a":1}""", obj.optString("o"))
        assertEquals("""[1,"x",{"k":"v"}]""", obj.optString("arr"))
    }

    @Test
    fun optNumbers_acceptNumericStrings() {
        assertEquals(12, obj.optInt("n"))
        assertEquals(12, obj.optInt("ns"))
        assertEquals(12, obj.optInt("nd"))
        assertEquals(31, obj.optInt("d"))
        assertEquals(-3, obj.optInt("neg"))
        assertEquals(-1, obj.optInt("s", -1))
        assertEquals(-1, obj.optInt("b", -1))
        assertEquals(-1, obj.optInt("nul", -1))
        assertEquals(-1, obj.optInt("empty", -1))
        assertEquals(31.2, obj.optDouble("d", 0.0))
        assertEquals(12.9, obj.optDouble("nd", 0.0))
        assertEquals(0.5, obj.optDouble("missing", 0.5))
        assertTrue(obj.optDouble("s").isNaN())
        assertEquals(12345678901L, obj.optLong("big"))
    }

    @Test
    fun optBoolean_acceptsBooleanStrings() {
        assertTrue(obj.optBoolean("b"))
        assertTrue(obj.optBoolean("bs"))
        assertFalse(obj.optBoolean("s"))
        assertTrue(obj.optBoolean("missing", true))
    }

    @Test
    fun opt_returnsTypedValues() {
        assertEquals("abc", obj.opt("s"))
        assertEquals(12, obj.opt("n"))
        assertEquals(true, obj.opt("b"))
        assertSame(JSONObject.NULL, obj.opt("nul"))
        assertNull(obj.opt("missing"))
        assertTrue(obj.opt("arr") is JSONArray)
        assertTrue(obj.opt("o") is JSONObject)
        assertTrue(obj.has("nul"))
        assertTrue(obj.isNull("nul"))
        assertTrue(obj.isNull("missing"))
        assertFalse(obj.has("missing"))
    }

    @Test
    fun objectsAndArrays() {
        assertNull(obj.optJSONObject("s"))
        assertNull(obj.optJSONArray("o"))
        val arr = obj.getJSONArray("arr")
        assertEquals(3, arr.length())
        assertEquals("1", arr.optString(0))
        assertEquals("x", arr.optString(1))
        assertEquals("", arr.optString(9))
        assertNull(arr.optJSONObject(1))
        assertEquals("v", arr.getJSONObject(2).optString("k"))
        assertFailsWith<JSONException> { arr.getJSONObject(0) }
        assertEquals(1, obj.getJSONObject("o").getInt("a"))
        assertFailsWith<JSONException> { obj.getString("n") }
        assertEquals(0, JSONObject().length())
        assertEquals(0, JSONArray().length())
    }

    @Test
    fun constructors_rejectWrongTopLevelType() {
        assertFailsWith<JSONException> { JSONObject("[1]") }
        assertFailsWith<JSONException> { JSONArray("{}") }
        assertFailsWith<JSONException> { JSONObject("not json") }
        assertEquals("v", JSONObject("""  {"k":"v"}  """).optString("k"))
    }
}
