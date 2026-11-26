package com.trackr.core.network.remote.common

import kotlinx.coroutines.CancellationException

/**
 * 和 runCatching 一样，但协程取消照常往上抛，不包成失败结果（重构计划 §2.10「取消不当成失败」）。
 */
inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (ce: CancellationException) {
    throw ce
} catch (e: Throwable) {
    Result.failure(e)
}

/**
 * 和 `java.net.URLEncoder.encode(value, "UTF-8")` 完全一致的表单编码：
 * 字母数字和 `.-*_` 原样保留，空格变 `+`，其余按 UTF-8 字节写成大写的 `%XX`。
 */
fun formUrlEncode(value: String): String {
    val sb = StringBuilder(value.length + 16)
    for (b in value.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        when {
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '.'.code || c == '-'.code || c == '*'.code || c == '_'.code -> sb.append(c.toChar())
            c == ' '.code -> sb.append('+')
            else -> {
                sb.append('%')
                sb.append(HEX_UPPER[c shr 4])
                sb.append(HEX_UPPER[c and 0x0F])
            }
        }
    }
    return sb.toString()
}

private const val HEX_UPPER = "0123456789ABCDEF"
private const val HEX_LOWER = "0123456789abcdef"

/** 字节转小写十六进制（同 `"%02x".format(b)` 逐个拼接） */
fun ByteArray.toLowerHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_LOWER[v shr 4])
        sb.append(HEX_LOWER[v and 0x0F])
    }
    return sb.toString()
}

/**
 * 定点格式化，小数点永远是 `.`，结果和 JVM 的 `String.format(Locale.ROOT, "%.${digits}f", value)` 一致：
 * 先取 double 的最短十进制表示，再按「四舍五入（HALF_UP）」截到 [digits] 位。
 * commonMain 没有 `String.format`（重构计划 §2.10）。
 */
fun formatFixed(value: Double, digits: Int): String {
    require(digits >= 0) { "digits 不能为负" }
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
    val (intDigits, fracDigits) = decimalDigits(if (negative) -value else value)
    // 逐位做 HALF_UP 进位
    val all = (intDigits + fracDigits.padEnd(digits + 1, '0')).toCharArray()
    val keep = intDigits.length + digits
    val roundUp = all[keep] >= '5'
    val kept = all.copyOfRange(0, keep)
    var carry = roundUp
    var i = kept.size - 1
    while (carry && i >= 0) {
        if (kept[i] == '9') {
            kept[i] = '0'
            i--
        } else {
            kept[i] = kept[i] + 1
            carry = false
        }
    }
    var digitsStr = kept.concatToString()
    var intLen = intDigits.length
    if (carry) {
        digitsStr = "1$digitsStr"
        intLen++
    }
    val intPart = digitsStr.substring(0, intLen).trimStart('0').ifEmpty { "0" }
    val fracPart = digitsStr.substring(intLen)
    val body = if (digits == 0) intPart else "$intPart.$fracPart"
    return if (negative) "-$body" else body
}

/** 非负有限 double 的最短十进制表示，拆成（整数位, 小数位），不带指数 */
private fun decimalDigits(value: Double): Pair<String, String> {
    val s = value.toString()
    val ePos = s.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (ePos >= 0) s.substring(0, ePos) else s
    val exponent = if (ePos >= 0) s.substring(ePos + 1).toInt() else 0
    val dot = mantissa.indexOf('.')
    val rawInt = if (dot >= 0) mantissa.substring(0, dot) else mantissa
    val rawFrac = if (dot >= 0) mantissa.substring(dot + 1) else ""
    val digits = rawInt + rawFrac
    val pointPos = rawInt.length + exponent
    return when {
        pointPos <= 0 -> "0" to ("0".repeat(-pointPos) + digits)
        pointPos >= digits.length -> (digits + "0".repeat(pointPos - digits.length)) to ""
        else -> digits.substring(0, pointPos) to digits.substring(pointPos)
    }
}
