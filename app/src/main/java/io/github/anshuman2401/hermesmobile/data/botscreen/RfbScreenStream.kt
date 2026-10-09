package io.github.anshuman2401.hermesmobile.data.botscreen

import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import java.util.concurrent.TimeUnit

interface ScreenStream {
    fun open(origin: CanonicalOrigin, ticket: String, onFrame: (ScreenFrame) -> Unit, onClosed: () -> Unit)
    fun setInputEnabled(enabled: Boolean)
    fun pointer(x: Int, y: Int, buttons: Int)
    fun key(keysym: Int, down: Boolean)
    fun close()
}

/** Raw RFB only. The bridge's lease is authoritative; [inputEnabled] is an additional local fence. */
class RfbScreenStream(
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
) : ScreenStream {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private var socket: WebSocket? = null
    @Volatile var inputEnabled: Boolean = false
        private set
    @Volatile private var closed = true
    private var protocol: RawRfbProtocol? = null

    override fun open(origin: CanonicalOrigin, ticket: String, onFrame: (ScreenFrame) -> Unit, onClosed: () -> Unit) {
        close()
        closed = false
        val url = origin.resolveWs("/api/display/ws") + "?display_ticket=" +
            java.net.URLEncoder.encode(ticket, "UTF-8")
        // The ticket is used once and never retained in a field or exception message.
        val request = Request.Builder().url(url).build()
        val parser = RawRfbProtocol({ bytes -> socket?.send(ByteString.of(*bytes)) }, onFrame)
        protocol = parser
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (closed || webSocket !== socket) return
                try { parser.feed(bytes.toByteArray()) } catch (_: Exception) { close(); onClosed() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed && webSocket === socket) { close(); onClosed() }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed && webSocket === socket) { close(); onClosed() }
            }
        })
    }

    override fun setInputEnabled(enabled: Boolean) { inputEnabled = enabled && !closed }
    override fun pointer(x: Int, y: Int, buttons: Int) {
        if (inputEnabled) protocol?.pointer(x, y, buttons)
    }
    override fun key(keysym: Int, down: Boolean) {
        if (inputEnabled) protocol?.key(keysym, down)
    }
    override fun close() {
        closed = true
        inputEnabled = false
        protocol = null
        socket?.close(1000, "Viewer closed")
        socket = null
    }
}

/** Incremental RFB 3.8 decoder, bounded before allocation; requests 32-bit little-endian raw pixels. */
internal class RawRfbProtocol(
    private val send: (ByteArray) -> Unit,
    private val frame: (ScreenFrame) -> Unit
) {
    private enum class Stage { VERSION, SECURITY_TYPES, SECURITY_LIST, SECURITY_RESULT, SERVER_INIT, SERVER_NAME,
        MESSAGE, UPDATE_HEADER, RECT_HEADER, RECT_RAW, CUT_TEXT_HEADER, CUT_TEXT_BODY, COLOR_MAP_HEADER, COLOR_MAP_BODY }
    private val input = Buffer()
    private var stage = Stage.VERSION
    private var securityCount = 0
    private var serverNameBytes = 0
    private var remainingRects = 0
    private var rectX = 0
    private var rectY = 0
    private var rectW = 0
    private var rectH = 0
    private var width = 0
    private var height = 0
    private var pixels = IntArray(0)
    private var skipBytes = 0
    private var resize = false
    private var lastFrameAtNanos = 0L

    @Synchronized fun feed(bytes: ByteArray) {
        require(bytes.size <= MAX_BUFFER && input.size + bytes.size <= MAX_BUFFER) { "RFB data too large" }
        input.write(bytes)
        while (step()) Unit
    }

    private fun step(): Boolean = when (stage) {
        Stage.VERSION -> {
            if (input.size < 12) false else {
                val version = input.readUtf8(12)
                require(version.startsWith("RFB 003.") && version.endsWith("\n")) { "Unsupported RFB version" }
                send("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
                stage = Stage.SECURITY_TYPES; true
            }
        }
        Stage.SECURITY_TYPES -> {
            if (input.size < 1) false else {
                securityCount = u8()
                require(securityCount in 1..32) { "RFB security unavailable" }
                stage = Stage.SECURITY_LIST; true
            }
        }
        Stage.SECURITY_LIST -> {
            if (input.size < securityCount) false else {
                val types = input.readByteArray(securityCount.toLong())
                require(types.any { it.toInt() == 1 }) { "RFB security mismatch" }
                send(byteArrayOf(1))
                stage = Stage.SECURITY_RESULT; true
            }
        }
        Stage.SECURITY_RESULT -> {
            if (input.size < 4) false else {
                require(input.readInt() == 0) { "RFB authentication refused" }
                send(byteArrayOf(1)) // shared desktop
                stage = Stage.SERVER_INIT; true
            }
        }
        Stage.SERVER_INIT -> {
            if (input.size < 24) false else {
                width = u16(); height = u16()
                validateDimensions(width, height)
                input.skip(16) // server pixel format; client sets its own
                serverNameBytes = input.readInt()
                require(serverNameBytes in 0..4096) { "RFB name too large" }
                pixels = IntArray(width * height)
                stage = Stage.SERVER_NAME; true
            }
        }
        Stage.SERVER_NAME -> {
            if (input.size < serverNameBytes) false else {
                input.skip(serverNameBytes.toLong())
                send(setPixelFormat())
                send(setEncodings())
                send(updateRequest(false))
                stage = Stage.MESSAGE; true
            }
        }
        Stage.MESSAGE -> {
            if (input.size < 1) false else {
                stage = when (u8()) {
                    0 -> Stage.UPDATE_HEADER
                    1 -> Stage.COLOR_MAP_HEADER
                    2 -> Stage.MESSAGE // bell
                    3 -> Stage.CUT_TEXT_HEADER
                    else -> error("Unknown RFB message")
                }; true
            }
        }
        Stage.UPDATE_HEADER -> {
            if (input.size < 3) false else {
                input.skip(1); remainingRects = u16()
                require(remainingRects <= 4096) { "Too many RFB rectangles" }
                resize = false
                if (remainingRects == 0) finishUpdate() else stage = Stage.RECT_HEADER
                true
            }
        }
        Stage.RECT_HEADER -> {
            if (input.size < 12) false else {
                rectX = u16(); rectY = u16(); rectW = u16(); rectH = u16()
                val encoding = input.readInt()
                when (encoding) {
                    0 -> {
                        require(rectW > 0 && rectH > 0 && rectX + rectW <= width && rectY + rectH <= height)
                        stage = Stage.RECT_RAW
                    }
                    -223 -> {
                        validateDimensions(rectW, rectH)
                        width = rectW; height = rectH; pixels = IntArray(width * height); resize = true
                        nextRect()
                    }
                    else -> error("Unsupported RFB encoding")
                }; true
            }
        }
        Stage.RECT_RAW -> {
            val length = rectW.toLong() * rectH * 4
            require(length <= MAX_BUFFER) { "RFB rectangle too large" }
            if (input.size < length) false else {
                val raw = input.readByteArray(length)
                var at = 0
                for (row in rectY until rectY + rectH) {
                    var destination = row * width + rectX
                    repeat(rectW) {
                        val b = raw[at++].toInt() and 255
                        val g = raw[at++].toInt() and 255
                        val r = raw[at++].toInt() and 255
                        at++
                        pixels[destination++] = -0x1000000 or (r shl 16) or (g shl 8) or b
                    }
                }
                nextRect(); true
            }
        }
        Stage.CUT_TEXT_HEADER -> {
            if (input.size < 7) false else {
                input.skip(3); skipBytes = input.readInt()
                require(skipBytes in 0..262144) { "RFB clipboard too large" }
                stage = Stage.CUT_TEXT_BODY; true
            }
        }
        Stage.CUT_TEXT_BODY -> {
            if (input.size < skipBytes) false else { input.skip(skipBytes.toLong()); stage = Stage.MESSAGE; true }
        }
        Stage.COLOR_MAP_HEADER -> {
            if (input.size < 5) false else {
                input.skip(3); skipBytes = u16() * 6
                require(skipBytes <= 65536) { "RFB color map too large" }
                stage = Stage.COLOR_MAP_BODY; true
            }
        }
        Stage.COLOR_MAP_BODY -> {
            if (input.size < skipBytes) false else { input.skip(skipBytes.toLong()); stage = Stage.MESSAGE; true }
        }
    }

    private fun nextRect() {
        remainingRects--
        if (remainingRects == 0) finishUpdate() else stage = Stage.RECT_HEADER
    }
    private fun finishUpdate() {
        val now = System.nanoTime()
        if (lastFrameAtNanos == 0L || now - lastFrameAtNanos >= 100_000_000L || resize) {
            frame(ScreenFrame(width, height, pixels.copyOf()))
            lastFrameAtNanos = now
        }
        send(updateRequest(!resize))
        stage = Stage.MESSAGE
    }
    private fun u8(): Int = input.readByte().toInt() and 255
    private fun u16(): Int = input.readShort().toInt() and 65535
    private fun validateDimensions(w: Int, h: Int) {
        require(w in 1..2560 && h in 1..1600 && w.toLong() * h <= 4_096_000) { "RFB screen too large" }
    }
    private fun setPixelFormat() = byteArrayOf(
        0, 0, 0, 0, 32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0
    )
    private fun setEncodings() = byteArrayOf(
        2, 0, 0, 2, 0, 0, 0, 0, -1, -1, -1, 33 // Raw and DesktopSize (-223)
    )
    private fun updateRequest(incremental: Boolean): ByteArray = byteArrayOf(
        3, if (incremental) 1 else 0, 0, 0, 0, 0,
        (width ushr 8).toByte(), width.toByte(), (height ushr 8).toByte(), height.toByte()
    )

    fun pointer(x: Int, y: Int, buttons: Int) {
        if (width == 0 || height == 0) return
        val px = x.coerceIn(0, width - 1); val py = y.coerceIn(0, height - 1)
        send(byteArrayOf(5, buttons.toByte(), (px ushr 8).toByte(), px.toByte(), (py ushr 8).toByte(), py.toByte()))
    }
    fun key(keysym: Int, down: Boolean) {
        send(byteArrayOf(4, if (down) 1 else 0, 0, 0,
            (keysym ushr 24).toByte(), (keysym ushr 16).toByte(), (keysym ushr 8).toByte(), keysym.toByte()))
    }
    private companion object { const val MAX_BUFFER = 20L * 1024 * 1024 }
}
