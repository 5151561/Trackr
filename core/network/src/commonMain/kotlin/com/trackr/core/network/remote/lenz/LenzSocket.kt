package com.trackr.core.network.remote.lenz

/**
 * 朗斯掌上出行底层 TCP Socket 通信平台桥接接口
 */
internal expect suspend fun lenzSocketSendAndReceive(
    host: String,
    port: Int,
    reqData: ByteArray,
    timeoutMs: Int = 4000
): ByteArray?

/** 朗斯应答载荷是 GBK 编码，需要按 GBK 解码才能拿到正确的中文站名/线路名 */
internal expect fun lenzDecodeGbk(bytes: ByteArray): String

/** 请求载荷也必须是 GBK：服务器按 GBK 读，UTF-8 的中文关键词会被直接丢弃 */
internal expect fun lenzEncodeGbk(text: String): ByteArray

/** 探测城市服务器端口能不能连上（只建连接、不发数据） */
internal expect suspend fun lenzSocketProbe(host: String, port: Int, timeoutMs: Int = 2500): Boolean
