package com.trackr.data.remote.chelaile

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 车来了通信层加解密与验签工具
 * 包含 MD5 参数签名与微信小程序/H5 接口双密钥 AES/ECB 解密
 */
object ChelaileCrypto {
    const val SIGN_SALT = "qwihrnbtmj"
    const val AES_KEY_WX = "FF32AE65FBFD19414EAAFF6291A54B42"
    const val AES_KEY_H5 = "422556651C7F7B2B5C266EED06068230"

    /**
     * 对请求参数计算 MD5 校验和：
     * 将各键值对拼接为 `"key"="value"&... + SIGN_SALT` 后计算小写 32 位 MD5 hex
     */
    fun cryptoSign(params: Map<String, String>): String {
        val str = params.entries.joinToString("&") { "\"${it.key}\"=\"${it.value}\"" } + SIGN_SALT
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(str.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun decodeBase64(input: String): ByteArray {
        val clean = input.trim()
        return try {
            Base64.getDecoder().decode(clean)
        } catch (e: Throwable) {
            android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
        }
    }

    private fun decryptWithKey(cipherBytes: ByteArray, aesKey: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(aesKey.toByteArray(Charsets.UTF_8), "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec)
        val decryptedBytes = cipher.doFinal(cipherBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    /**
     * 对 Base64 编码的密文进行 AES/ECB/PKCS5Padding 解密。
     * 默认优先尝试微信小程序接口密钥 [AES_KEY_WX]，解密失败时自动尝试 H5 接口备用密钥 [AES_KEY_H5]。
     */
    fun decryptResult(cipherTextBase64: String, aesKey: String = AES_KEY_WX): String {
        val cipherBytes = decodeBase64(cipherTextBase64)
        return try {
            decryptWithKey(cipherBytes, aesKey)
        } catch (e: Exception) {
            if (aesKey != AES_KEY_H5) {
                decryptWithKey(cipherBytes, AES_KEY_H5)
            } else {
                throw e
            }
        }
    }

    /**
     * 定位响应体中的 JSON 主体（剥离前缀 `**YGKJ` 与后缀 `YGKJ##`），
     * 解析 `jsonr.data`，并在检测到 `encryptResult` 时自动调用 [decryptResult] 解密为明文 [JSONObject]。
     */
    fun extractEnvelopeDataJson(raw: String): JSONObject {
        val jsonStart = raw.indexOf('{')
        if (jsonStart < 0) {
            throw IllegalArgumentException("Upstream response is not JSON or contains no '{'")
        }
        var depth = 0
        var jsonEnd = -1
        for (i in jsonStart until raw.length) {
            when (raw[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        jsonEnd = i + 1
                        break
                    }
                }
            }
        }
        val jsonStr = if (jsonEnd > jsonStart) {
            raw.substring(jsonStart, jsonEnd)
        } else {
            val lastClose = raw.lastIndexOf('}')
            if (lastClose > jsonStart) raw.substring(jsonStart, lastClose + 1)
            else raw.substring(jsonStart)
        }

        val envelope = JSONObject(jsonStr)
        val jsonr = envelope.optJSONObject("jsonr")

        val dataAny = jsonr?.opt("data") ?: envelope.opt("data")
        if (dataAny is String && dataAny.isNotBlank()) {
            return try {
                val decrypted = decryptResult(dataAny)
                JSONObject(decrypted)
            } catch (_: Exception) {
                JSONObject(dataAny)
            }
        }

        val dataObj = (dataAny as? JSONObject) ?: jsonr ?: envelope
        val encryptResult = dataObj.optString("encryptResult")
        if (encryptResult.isNotBlank()) {
            val decryptedStr = decryptResult(encryptResult)
            return JSONObject(decryptedStr)
        }

        return dataObj
    }
}
