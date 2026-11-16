package com.trackr.core.network.remote.lenz

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 收到最后一帧后，再等这么久没有新数据就认为应答结束 */
private const val IDLE_TIMEOUT_MS = 800

/** 单次应答最多收这么多字节，防止异常服务器把内存吃满 */
private const val MAX_RESPONSE_BYTES = 512 * 1024

internal actual suspend fun lenzSocketSendAndReceive(
    host: String,
    port: Int,
    reqData: ByteArray,
    timeoutMs: Int
): ByteArray? = withContext(Dispatchers.IO) {
    var socket: Socket? = null
    try {
        socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = IDLE_TIMEOUT_MS
        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        out.write(reqData)
        out.flush()

        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (baos.size() < MAX_RESPONSE_BYTES) {
            val read = try {
                inp.read(buffer)
            } catch (_: SocketTimeoutException) {
                // 长应答分片下发；万一没等到收尾帧，读空闲超时也按结束处理
                break
            }
            if (read <= 0) break
            baos.write(buffer, 0, read)
            // 见到收尾分片就不再等服务端关连接，省一次 1 秒的空闲等待
            if (LenzFrames.isResponseComplete(baos.toByteArray())) break
        }
        val result = baos.toByteArray()
        if (result.isNotEmpty()) result else null
    } catch (_: Exception) {
        null
    } finally {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}

internal actual fun lenzDecodeGbk(bytes: ByteArray): String =
    String(bytes, Charset.forName("GBK"))

internal actual fun lenzEncodeGbk(text: String): ByteArray =
    text.toByteArray(Charset.forName("GBK"))

internal actual suspend fun lenzSocketProbe(host: String, port: Int, timeoutMs: Int): Boolean =
    withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }
