@file:OptIn(ExperimentalForeignApi::class)

package com.trackr.core.network.remote.lenz

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import platform.posix.AF_INET
import platform.posix.INADDR_LOOPBACK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LenzSocketIosTest {

    private fun openLoopbackServer(): Pair<Int, Int> = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(fd >= 0, "创建本地监听 socket 失败")
        val reuse = alloc<IntVar>().apply { value = 1 }
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, sizeOf<IntVar>().convert())

        val addr = alloc<sockaddr_in>()
        memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
        addr.sin_len = sizeOf<sockaddr_in>().convert()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = 0u
        addr.sin_addr.s_addr = htonlLocal(INADDR_LOOPBACK)

        assertEquals(0, bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()))
        assertEquals(0, listen(fd, 4))

        val bound = alloc<sockaddr_in>()
        val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
        assertEquals(0, getsockname(fd, bound.ptr.reinterpret<sockaddr>(), len.ptr))
        fd to ntohsLocal(bound.sin_port)
    }

    private fun htonlLocal(v: UInt): UInt =
        ((v and 0x000000FFu) shl 24) or
            ((v and 0x0000FF00u) shl 8) or
            ((v and 0x00FF0000u) shr 8) or
            ((v and 0xFF000000u) shr 24)

    private fun ntohsLocal(v: UShort): Int {
        val i = v.toInt() and 0xFFFF
        return ((i and 0xFF) shl 8) or ((i shr 8) and 0xFF)
    }

    private fun buildFrame(cmd: Int, payload: ByteArray, sliceCount: Int, sliceIndex: Int): ByteArray {
        val multi = sliceCount > 1
        val s3 = payload.size or (if (multi) 1 shl 10 else 0)
        val header = byteArrayOf(
            0x02,
            ((s3 shr 8) and 0xFF).toByte(),
            (s3 and 0xFF).toByte(),
            cmd.toByte(),
            0x00,
            0x00
        )
        val ext = if (multi) {
            byteArrayOf(
                ((sliceCount shr 8) and 0xFF).toByte(),
                (sliceCount and 0xFF).toByte(),
                ((sliceIndex shr 8) and 0xFF).toByte(),
                (sliceIndex and 0xFF).toByte()
            )
        } else {
            ByteArray(0)
        }
        return header + ext + payload + byteArrayOf(0x00, 0xFE.toByte(), 0x03)
    }

    @Test
    fun probeReturnsTrueWhenListeningAndFalseWhenClosed() = runBlocking {
        val (serverFd, port) = openLoopbackServer()
        try {
            assertTrue(lenzSocketProbe("127.0.0.1", port, timeoutMs = 1500))
        } finally {
            close(serverFd)
        }
        assertFalse(lenzSocketProbe("127.0.0.1", port, timeoutMs = 1500))
    }

    @Test
    fun sendAndReceiveHandlesGbkMultiFrameResponse() = runBlocking {
        val (serverFd, port) = openLoopbackServer()
        val responseJson = """{"serial":1,"Routes":[{"rid":707,"upDown":0,"rname":"范县至王楼镇"}]}"""
        val responseBytes = lenzEncodeGbk(responseJson)
        val cut = responseBytes.size / 2
        val part1 = buildFrame(83, responseBytes.copyOfRange(0, cut), sliceCount = 2, sliceIndex = 1)
        val part2 = buildFrame(83, responseBytes.copyOfRange(cut, responseBytes.size), sliceCount = 2, sliceIndex = 2)

        val serverJob = async(Dispatchers.IO) {
            val clientFd = accept(serverFd, null, null)
            assertTrue(clientFd >= 0)
            try {
                val reqBuf = ByteArray(1024)
                val read = reqBuf.usePinned { pinned ->
                    recv(clientFd, pinned.addressOf(0), reqBuf.size.convert(), 0).toInt()
                }
                assertTrue(read > 0)
                val received = reqBuf.copyOf(read)
                val reqDocs = LenzApiClient.extractJsonDocuments(received)
                assertEquals(1, reqDocs.size)
                assertTrue(reqDocs[0].contains("范县"))

                part1.usePinned { pinned ->
                    send(clientFd, pinned.addressOf(0), part1.size.convert(), 0)
                }
                part2.usePinned { pinned ->
                    send(clientFd, pinned.addressOf(0), part2.size.convert(), 0)
                }
            } finally {
                close(clientFd)
                close(serverFd)
            }
        }

        val request = LenzApiClient.packFrame(83, """{"type":1,"content":"范县"}""")
        val rawResp = lenzSocketSendAndReceive("127.0.0.1", port, request, timeoutMs = 2000)
        serverJob.await()

        assertNotNull(rawResp)
        val docs = LenzApiClient.extractJsonDocuments(rawResp)
        assertEquals(1, docs.size)
        assertEquals(responseJson, docs[0])
    }
}
