package com.hifiprobe

import java.io.InputStream
import java.io.OutputStream

/**
 * 极简 HTTP 的公共零件 —— 读请求、发响应。
 *
 * ★ 抽出来的理由：现在有两个服务要用它（无线传输管库、调试端口只读）。
 *   `readLine` 这种"逐字节读到 \n"的东西抄两遍倒还好，但响应头的格式
 *   （Content-Length 算错、忘了那对 \r\n）错一处就是很难查的怪问题 ——
 *   浏览器会一直转圈而不是报错。这种东西只该有一份。
 *
 * 不引第三方 HTTP 框架的理由见 [WirelessServer] 的说明：我们要的东西太少，
 * 而且上传要直接流式落盘（几百 MB 的 DSD），那部分无论如何都得自己写。
 */
object Http {

    /** 读一行（以 \n 结尾，去掉 \r）。流结束且一个字都没读到时返回 null */
    fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    /** 读请求头。键统一转小写 —— HTTP 头大小写不敏感，混用会漏匹配 */
    fun readHeaders(input: InputStream): MutableMap<String, String> {
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] =
                line.substring(i + 1).trim()
        }
        return headers
    }

    fun respondBytes(
        out: OutputStream,
        code: Int,
        type: String,
        body: ByteArray,
        extra: String = ""
    ) {
        out.write(
            ("HTTP/1.1 $code\r\nContent-Type: $type\r\n" +
                    "Content-Length: ${body.size}\r\n$extra\r\n").toByteArray(Charsets.UTF_8)
        )
        out.write(body)
        out.flush()
    }

    fun respondText(
        out: OutputStream,
        code: Int,
        type: String,
        body: String,
        extra: String = ""
    ) = respondBytes(out, code, type, body.toByteArray(Charsets.UTF_8), extra)

    /**
     * 解析查询串。
     *
     * 自己拆而不引 `Uri`：这里拿到的是原始请求行，`Uri.parse` 对畸形输入
     * 的行为不好预期，而我们要的只是"几个 key=value"，几行就够了。
     */
    fun query(path: String): Map<String, String> {
        val q = path.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            out[k] = runCatching {
                java.net.URLDecoder.decode(v, "UTF-8")
            }.getOrDefault(v)
        }
        return out
    }
}
