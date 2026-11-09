package com.omama.stationalarm.network

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Minimal loopback HTTP/1.1 server for tests (okhttp mockwebserver is not on the test classpath and no
 * dependency may be added). Every response is `Connection: close`, so each call is a fresh connection.
 */
internal class TinyHttpServer : Closeable {

    class Req(val requestLine: String, val path: String, val rawQuery: String, val headers: Map<String, String>) {
        /** Decoded query parameters in wire order (duplicates kept). */
        val params: List<Pair<String, String>> = rawQuery.split("&").filter { it.isNotEmpty() }.map {
            val i = it.indexOf('=')
            val k = if (i < 0) it else it.substring(0, i)
            val v = if (i < 0) "" else it.substring(i + 1)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
        val paramNames: Set<String> get() = params.map { it.first }.toSet()
        fun param(name: String): String? = params.firstOrNull { it.first == name }?.second
    }

    class Reply(
        val status: Int = 200,
        val body: String = """{"results":[]}""",
        val contentType: String = "application/json",
        /** Accept the connection, read the request, then hang up without answering. */
        val hangUp: Boolean = false,
        /** Declare the full Content-Length but send only this many body bytes, then hang up. */
        val truncateBodyTo: Int? = null,
    )

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val requests = CopyOnWriteArrayList<Req>()

    @Volatile
    var reply: (Req) -> Reply = { Reply() }

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "TinyHttpServer-accept") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                thread(isDaemon = true, name = "TinyHttpServer-conn") { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = input.readLine() ?: return
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val target = requestLine.split(" ").getOrElse(1) { "/" }
                val q = target.indexOf('?')
                val req = Req(
                    requestLine,
                    if (q < 0) target else target.substring(0, q),
                    if (q < 0) "" else target.substring(q + 1),
                    headers,
                )
                requests += req
                val r = reply(req)
                if (r.hangUp) return
                val bytes = r.body.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 ${r.status} X\r\nContent-Type: ${r.contentType}; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                val out = s.getOutputStream()
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.write(bytes, 0, r.truncateBodyTo?.coerceAtMost(bytes.size) ?: bytes.size)
                out.flush()
            } catch (_: IOException) {
                // the client may have given up (timeout / cancellation): nothing to do
            }
        }
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) { }
    }
}
