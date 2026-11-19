@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.trackr.core.network.remote.lenz

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingGB_18030_2000
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithBytes
import platform.posix.AF_UNSPEC
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_NOSIGPIPE
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockopt
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.socklen_tVar

/** 收到最后一帧后，再等这么久没有新数据就认为应答结束 */
private const val IDLE_TIMEOUT_MS = 800

/** 单次应答最多收这么多字节，防止异常服务器把内存吃满 */
private const val MAX_RESPONSE_BYTES = 512 * 1024

/** GB 18030 严格兼容 GBK，Apple 平台由 CoreFoundation 原生支持 */
private val gbkEncoding: ULong = CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000.convert())

internal actual suspend fun lenzSocketSendAndReceive(
    host: String,
    port: Int,
    reqData: ByteArray,
    timeoutMs: Int
): ByteArray? = withContext(Dispatchers.IO) {
    if (host.isBlank() || port !in 1..65535) return@withContext null
    var fd = -1
    try {
        fd = connectWithTimeout(host, port, timeoutMs)
        if (fd < 0) return@withContext null

        if (!sendAll(fd, reqData, timeoutMs)) return@withContext null

        var acc = ByteArray(4096)
        var total = 0
        val chunk = ByteArray(8192)
        while (total < MAX_RESPONSE_BYTES) {
            currentCoroutineContext().ensureActive()
            val waitMs = if (total == 0) timeoutMs.coerceAtLeast(IDLE_TIMEOUT_MS) else IDLE_TIMEOUT_MS
            val ready = pollSocket(fd, POLLIN.toShort(), waitMs)
            if (ready <= 0) break

            val maxRead = minOf(chunk.size, MAX_RESPONSE_BYTES - total)
            val n = chunk.usePinned { pinned ->
                recv(fd, pinned.addressOf(0), maxRead.convert(), 0).toInt()
            }
            if (n > 0) {
                if (total + n > acc.size) {
                    val nextCap = maxOf(acc.size * 2, total + n).coerceAtMost(MAX_RESPONSE_BYTES)
                    acc = acc.copyOf(nextCap)
                }
                chunk.copyInto(acc, destinationOffset = total, startIndex = 0, endIndex = n)
                total += n
                // 见到收尾分片就不再等服务端关连接，省一次空闲等待
                if (LenzFrames.isResponseComplete(acc.copyOf(total))) break
            } else if (n == 0) {
                break
            } else {
                val err = errno
                if (err == EINTR || err == EAGAIN || err == EWOULDBLOCK) continue
                break
            }
        }
        if (total > 0) acc.copyOf(total) else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    } finally {
        if (fd >= 0) close(fd)
    }
}

internal actual fun lenzDecodeGbk(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val data = bytes.usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), bytes.size.convert())
    }
    return NSString.create(data = data, encoding = gbkEncoding)?.toString() ?: bytes.decodeToString()
}

internal actual fun lenzEncodeGbk(text: String): ByteArray {
    if (text.isEmpty()) return ByteArray(0)
    val nsString = NSString.create(string = text)
    val data = nsString.dataUsingEncoding(gbkEncoding, allowLossyConversion = true)
        ?: return text.encodeToByteArray()
    val len = data.length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), data.bytes, data.length)
    }
    return out
}

internal actual suspend fun lenzSocketProbe(host: String, port: Int, timeoutMs: Int): Boolean =
    withContext(Dispatchers.IO) {
        if (host.isBlank() || port !in 1..65535) return@withContext false
        var fd = -1
        try {
            fd = connectWithTimeout(host, port, timeoutMs)
            fd >= 0
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        } finally {
            if (fd >= 0) close(fd)
        }
    }

private suspend fun connectWithTimeout(host: String, port: Int, timeoutMs: Int): Int = memScoped {
    val hints = alloc<addrinfo>()
    memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
    hints.ai_family = AF_UNSPEC
    hints.ai_socktype = SOCK_STREAM
    hints.ai_protocol = IPPROTO_TCP

    val resPtr = alloc<kotlinx.cinterop.CPointerVar<addrinfo>>()
    if (getaddrinfo(host, port.toString(), hints.ptr, resPtr.ptr) != 0) return -1
    val head = resPtr.value ?: return -1
    try {
        var cur: kotlinx.cinterop.CPointer<addrinfo>? = head
        while (cur != null) {
            currentCoroutineContext().ensureActive()
            val ai = cur.pointed
            val fd = socket(ai.ai_family, ai.ai_socktype, ai.ai_protocol)
            if (fd >= 0) {
                val noSigPipe = alloc<IntVar>().apply { value = 1 }
                setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, noSigPipe.ptr, sizeOf<IntVar>().convert())

                val flags = fcntl(fd, F_GETFL, 0)
                if (flags >= 0) fcntl(fd, F_SETFL, flags or O_NONBLOCK)

                val rc = connect(fd, ai.ai_addr, ai.ai_addrlen)
                if (rc == 0) return fd
                if (errno == EINPROGRESS) {
                    val ready = pollSocket(fd, POLLOUT.toShort(), timeoutMs)
                    if (ready > 0) {
                        val soError = alloc<IntVar>()
                        val len = alloc<socklen_tVar>().apply { value = sizeOf<IntVar>().convert() }
                        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, soError.ptr, len.ptr) == 0 && soError.value == 0) {
                            return fd
                        }
                    }
                }
                close(fd)
            }
            cur = ai.ai_next
        }
        -1
    } finally {
        freeaddrinfo(head)
    }
}

private suspend fun sendAll(fd: Int, data: ByteArray, timeoutMs: Int): Boolean {
    if (data.isEmpty()) return true
    var offset = 0
    return data.usePinned { pinned ->
        while (offset < data.size) {
            currentCoroutineContext().ensureActive()
            val ready = pollSocket(fd, POLLOUT.toShort(), timeoutMs)
            if (ready <= 0) return@usePinned false
            val n = send(fd, pinned.addressOf(offset), (data.size - offset).convert(), 0).toInt()
            if (n > 0) {
                offset += n
            } else if (n < 0) {
                val err = errno
                if (err == EINTR || err == EAGAIN || err == EWOULDBLOCK) continue
                return@usePinned false
            } else {
                return@usePinned false
            }
        }
        true
    }
}

private fun pollSocket(fd: Int, events: Short, timeoutMs: Int): Int = memScoped {
    val pfd = alloc<pollfd>()
    pfd.fd = fd
    pfd.events = events
    pfd.revents = 0
    var rc: Int
    do {
        rc = poll(pfd.ptr, 1u, timeoutMs)
    } while (rc < 0 && errno == EINTR)
    rc
}
