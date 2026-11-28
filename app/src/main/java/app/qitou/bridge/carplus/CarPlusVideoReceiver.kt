// SPDX-License-Identifier: AGPL-3.0-or-later
// CarLife HU-side receiver adapted from CarPToCarL (MIT, Copyright (c) 2026 lizhihua).
package app.qitou.bridge.carplus

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.view.Surface
import app.qitou.bridge.MiniState
import app.qitou.bridge.Stage
import app.qitou.bridge.aa.AaLog
import app.qitou.bridge.aa.VideoDecoder
import app.qitou.bridge.sender.SenderRouter
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/**
 * Connects to OPPO Car+'s local CarLife sender and feeds its H.264 stream into the
 * same decoder-to-compositor path previously used by Android Auto.
 */
class CarPlusVideoReceiver(
    private val context: Context,
    private val surface: Surface,
    private val log: (String) -> Unit,
    private val requestedWidth: Int = 1280,
    private val requestedHeight: Int = 720,
    private val requestedFps: Int = 30,
    private val onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> },
    private val onReady: () -> Unit = {},
    private val onSessionEnded: () -> Unit = {},
    private val onAudioPacket: (isTts: Boolean, pcm: ByteArray, sampleRate: Int, channels: Int) -> Unit =
        { _, _, _, _ -> },
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val VERSION_TIMEOUT_MS = 15_000L
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
        private const val MAX_AUDIO_BYTES = 2 * 1024 * 1024
        private const val STARTUP_TIMEOUT_MS = 75_000L
    }

    private val running = AtomicBoolean(false)
    private val sessionDown = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap<Int, Socket>()
    private val writeLock = Any()
    private val touchLock = ReentrantLock()
    private val touchReady = touchLock.newCondition()
    private val touchQueue = ArrayDeque<TouchEvent>()
    @Volatile private var cmdOut: OutputStream? = null
    @Volatile private var touchOut: OutputStream? = null
    @Volatile private var touchWorker: Thread? = null
    @Volatile private var videoDead = false
    @Volatile private var versionMatched = false
    @Volatile private var featureConfigAcked = false
    @Volatile private var starterScheduled = false
    @Volatile private var readyFired = false
    @Volatile private var everConnected = false
    @Volatile private var senderAddr: String? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastFrameNudgeAt = 0L
    @Volatile private var lastTouchProbeAt = 0L
    @Volatile private var touchMissLogged = false
    @Volatile private var startupWarned = false
    @Volatile private var swapRetryDone = false
    private val unknownCommands = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    private val decoder = VideoDecoder().apply {
        fallbackWidth = requestedWidth
        fallbackHeight = requestedHeight
        dimensionsListener = object : app.qitou.bridge.aa.VideoDimensionsListener {
            override fun onVideoDimensionsChanged(width: Int, height: Int) {
                log("[CAR+] stream ${width}x$height")
                if (requestedWidth > requestedHeight && width == requestedHeight && height == requestedWidth) {
                    log(
                        "[CAR+] ⚠ stream is exactly swapped vs the ${requestedWidth}x$requestedHeight request " +
                            "(vendor encoder override?) — re-requesting 98311 with swapped dims",
                    )
                    retrySwappedEncoderInit()
                }
                MiniState.setCar("CarLife 画面 ${width}×$height", Stage.WAIT)
                onVideoSize(width, height)
            }
        }
        onFpsChanged = { fps ->
            if (fps > 0) log("[CAR+] decode fps=$fps")
            if (fps >= 5 && !readyFired) {
                readyFired = true
                log("[CAR+] steady video reached (fps=$fps) — starting bike hand-off")
                MiniState.setCar("CarLife 画面稳定 · $fps fps", Stage.OK)
                onReady()
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        AaLog.sink = log
        decoder.setSurface(surface)
        MiniState.update(fps = 0)
        MiniState.setCar("正在等待 CarLife 画面", Stage.WAIT)
        MiniState.setAudio("等待 9240/9241 音频", Stage.WAIT)
        startTouchWorker()
        thread(name = "carplus-session", isDaemon = true) {
            val startedAt = SystemClock.elapsedRealtime()
            var failures = 0
            while (running.get()) {
                var senderMissing = false
                try {
                    if (connectSession()) {
                        startupWarned = false
                        failures = 0
                        waitForSession()
                    } else {
                        failures++
                        if (SenderRouter.detect(context) == null) {
                            // 发送端根本没装：别再空转探测端口，提示安装后退出
                            log("[CAR+] 发送端未安装，停止重连")
                            senderMissing = true
                            running.set(false)
                        } else if (!startupWarned &&
                            SystemClock.elapsedRealtime() - startedAt > STARTUP_TIMEOUT_MS
                        ) {
                            startupWarned = true
                            log("[CAR+] local CarLife ports did not appear — start 无线连接 in the sender app")
                            MiniState.setCar(
                                "未找到 CarLife 端口，${SenderRouter.wirelessHint(context)}",
                                Stage.ERROR,
                            )
                        }
                    }
                } catch (error: Exception) {
                    if (running.get()) log("[CAR+] bridge retry: ${error.message}")
                } finally {
                    closeSession()
                    MiniState.update(fps = 0)
                    when {
                        senderMissing -> MiniState.setCar(
                            "请安装小米 CarWith（com.miui.carlink）或百度 CarLife+",
                            Stage.ERROR,
                        )
                        running.get() && !everConnected -> MiniState.setCar(
                            "未发现 CarLife 端口，${SenderRouter.wirelessHint(context)}",
                            Stage.ERROR,
                        )
                        running.get() -> MiniState.setCar("CarLife 连接中断，正在重连", Stage.ERROR)
                        else -> MiniState.setCar("已停止", Stage.OFF)
                    }
                }
                if (running.get()) Thread.sleep(if (failures < 3) 1_500L else 5_000L)
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        closeSession()
        try { decoder.stop("CarPlusVideoReceiver.stop") } catch (_: Exception) {}
    }

    fun sendTouch(action: Int, x: Int, y: Int) {
        if (!running.get()) return
        val type = when (action) {
            0 -> CarPlusProtocol.TOUCH_DOWN
            1 -> CarPlusProtocol.TOUCH_UP
            else -> CarPlusProtocol.TOUCH_MOVE
        }
        enqueueTouch(TouchEvent(type, action, x, y))
    }

    /** Composite CarLife action carrying an Android pointer id in the high byte. */
    fun sendTouchComposite(actionCode: Int, x: Int, y: Int) {
        if (!running.get()) return
        enqueueTouch(TouchEvent(CarPlusProtocol.TOUCH_ACTION, actionCode, x, y))
    }

    private fun enqueueTouch(event: TouchEvent) {
        touchLock.withLock {
            if (isMove(event)) {
                val tail = touchQueue.lastOrNull()
                if (tail != null && isMove(tail)) {
                    tail.x = event.x
                    tail.y = event.y
                    return
                }
            }
            if (touchQueue.size >= 12) {
                val move = touchQueue.firstOrNull(::isMove)
                if (move != null) touchQueue.remove(move) else if (touchQueue.isNotEmpty()) {
                    touchQueue.removeFirst()
                }
            }
            touchQueue.addLast(event)
            touchReady.signalAll()
        }
    }

    private fun isMove(event: TouchEvent): Boolean =
        event.serviceType == CarPlusProtocol.TOUCH_MOVE ||
            (event.serviceType == CarPlusProtocol.TOUCH_ACTION &&
                (event.actionCode and 0xff) == 2)

    private fun connectSession(): Boolean {
        closeSession()
        sessionDown.set(false)
        versionMatched = false
        featureConfigAcked = false
        starterScheduled = false
        readyFired = false
        swapRetryDone = false
        unknownCommands.clear()
        videoDead = false
        val addrs = senderAddresses()
        log("[CAR+] probing CarLife sender ports on ${addrs.joinToString("/")} …")
        MiniState.setCar("正在寻找 CarLife 发送端", Stage.WAIT)

        for (port in CarPlusProtocol.PORTS) {
            for (addr in addrs) {
                try {
                    val socket = connectSender(addr, port)
                    sockets[port] = socket
                    if (port == CarPlusProtocol.PORT_CMD) {
                        senderAddr = addr
                        log("[CAR+] CarLife sender reachable at ${addr}:${port}")
                    }
                    break
                } catch (error: Exception) {
                    if (port == CarPlusProtocol.PORT_CMD) {
                        log("[CAR+] CarLife ${addr}:${port} not ready (${error.javaClass.simpleName})")
                    }
                }
            }
        }

        val cmd = sockets[CarPlusProtocol.PORT_CMD] ?: return false
        cmdOut = cmd.getOutputStream()
        touchOut = sockets[CarPlusProtocol.PORT_TOUCH]?.getOutputStream()
        everConnected = true
        log("[CAR+] connected to CarLife sender engine")
        MiniState.setCar("CarLife 发送端已连接，正在握手", Stage.WAIT)

        thread(name = "carplus-cmd", isDaemon = true) {
            readCommands(cmd.getInputStream())
        }
        sockets[CarPlusProtocol.PORT_VIDEO]?.let { video ->
            thread(name = "carplus-video", isDaemon = true) {
                readVideo(video)
            }
        }
        videoDead = sockets[CarPlusProtocol.PORT_VIDEO] == null
        sockets[CarPlusProtocol.PORT_MEDIA]?.let { media ->
            thread(name = "carplus-audio-media", isDaemon = true) { drainAudio(media, "media") }
        }
        sockets[CarPlusProtocol.PORT_TTS]?.let { tts ->
            thread(name = "carplus-audio-tts", isDaemon = true) { drainAudio(tts, "tts") }
        }
        thread(name = "carplus-heartbeat", isDaemon = true) { heartbeatLoop() }
        thread(name = "carplus-video-watch", isDaemon = true) { videoWatchdog() }
        thread(name = "carplus-touch-watch", isDaemon = true) { touchWatchdog() }
        thread(name = "carplus-audio-watch", isDaemon = true) { audioWatchdog() }

        val deadline = SystemClock.elapsedRealtime() + VERSION_TIMEOUT_MS
        while (running.get() && !sessionDown.get() && !versionMatched &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            send(CarPlusProtocol.HU_PROTOCOL_VERSION, CarPlusProtocol.version())
            Thread.sleep(1_200)
        }
        if (!versionMatched) {
            log("[CAR+] version negotiation timed out")
            return false
        }
        log("[CAR+] CarLife protocol 3.0 matched")
        MiniState.setCar("CarLife 协议已匹配", Stage.WAIT)
        return true
    }

    private fun waitForSession() {
        while (running.get() && !sessionDown.get()) {
            Thread.sleep(500)
        }
        log("[CAR+] CarLife session ended")
        onSessionEnded()
    }

    private fun readCommands(input: InputStream) {
        val data = DataInputStream(input)
        try {
            while (running.get() && !sessionDown.get()) {
                val header = ByteArray(8)
                data.readFully(header)
                val length = ((header[0].toInt() and 0xff) shl 8) or (header[1].toInt() and 0xff)
                val type = readU32(header, 4)
                if (length < 0 || length > MAX_FRAME_BYTES) throw IllegalStateException("bad command length $length")
                val payload = ByteArray(length)
                if (length > 0) data.readFully(payload)
                handleCommand(type, payload)
            }
        } catch (error: Exception) {
            if (running.get() && !sessionDown.get()) log("[CAR+] control channel closed: ${error.message}")
        } finally {
            sessionDown.set(true)
        }
    }

    private fun handleCommand(type: Int, payload: ByteArray) {
        when (type) {
            CarPlusProtocol.MD_VERSION_MATCH -> {
                versionMatched = true
                send(CarPlusProtocol.HU_STATISTICS, CarPlusProtocol.statistics())
            }
            CarPlusProtocol.MD_INFO -> {
                log(
                    "[CAR+] phone CarLife sender identified (${payload.size} bytes) " +
                        "raw=${payload.take(96).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }}",
                )
                send(
                    CarPlusProtocol.HU_INFO,
                    CarPlusProtocol.headUnitInfo("${requestedWidth}x$requestedHeight"),
                )
                scheduleFeatureAndEncoder()
            }
            CarPlusProtocol.MD_AUTHEN_RESPONSE -> {
                log("[CAR+] HU authentication challenge answered (${payload.size} bytes)")
                send(CarPlusProtocol.HU_AUTHEN_RESULT, CarPlusProtocol.authenResult(true))
            }
            CarPlusProtocol.MD_AUTHEN_RESULT -> {
                log("[CAR+] HU authentication accepted (${payload.size} bytes)")
                send(CarPlusProtocol.HU_AUTHEN_ACK, ByteArray(0))
            }
            CarPlusProtocol.MD_MODULE_STATUS -> {
                val modules = CarPlusProtocol.parseModuleStatus(payload)
                if (modules.isNotEmpty() && modules.any { it.second == 0 }) {
                    send(CarPlusProtocol.HU_MODULE_CONTROL, CarPlusProtocol.moduleControl(modules.map { it.first }))
                }
            }
            CarPlusProtocol.MD_FEATURE_CONFIG -> {
                log("[CAR+] CarLife host requested feature configuration")
                send(CarPlusProtocol.HU_FEATURE_CONFIG, CarPlusProtocol.featureConfig())
            }
            CarPlusProtocol.MD_FEATURE_CONFIG_DONE -> {
                featureConfigAcked = true
                log("[CAR+] sender accepted feature configuration")
            }
            CarPlusProtocol.MD_VIDEO_ENC_INIT -> {
                val fields = CarPlusProtocol.readVarints(payload)
                val width = fields[1]?.toInt() ?: requestedWidth
                val height = fields[2]?.toInt() ?: requestedHeight
                val fps = fields[3]?.toInt()?.takeIf { it > 0 } ?: requestedFps
                log(
                    "[CAR+] encoder confirmed ${width}x$height@$fps " +
                        "raw=${payload.take(48).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }}",
                )
                send(CarPlusProtocol.VIDEO_ENC_START, ByteArray(0))
            }
            CarPlusProtocol.VIDEO_ENC_DONE -> sendEncoderInit()
            in CarPlusProtocol.MD_IGNORED_IDS -> Unit
            else -> if (unknownCommands.add(type)) {
                log(
                    "[CAR+] unhandled command 0x${type.toString(16)} (${payload.size} bytes) " +
                        "head=${payload.take(16).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }}",
                )
            }
        }
    }

    private fun scheduleFeatureAndEncoder() {
        if (!starterScheduled) {
            starterScheduled = true
            thread(name = "carplus-init", isDaemon = true) {
                Thread.sleep(250)
                if (!running.get() || sessionDown.get()) return@thread
                send(CarPlusProtocol.HU_FEATURE_CONFIG, CarPlusProtocol.featureConfig())
                val deadline = SystemClock.elapsedRealtime() + 3_000
                while (running.get() && !featureConfigAcked && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(50)
                }
                sendEncoderInit()
            }
        }
    }

    private fun sendEncoderInit() {
        if (!running.get() || sessionDown.get()) return
        log(
            "[CAR+] requesting ${requestedWidth}x$requestedHeight@$requestedFps video " +
                "(98311 wire fields ${requestedHeight}x${requestedWidth} — Xiaomi vendor layer reads W/H reversed)",
        )
        send(CarPlusProtocol.HU_VIDEO_ENC_INIT, buildEncoderInfo(swapped = true))
    }

    /**
     * 构造 98311 载荷。[swapped]=true 时按「field1=height, field2=width」反序写入——
     * 小米 CarWith 实测把 98311 的宽高反读（请求 1280x480 → 实流 480x1280、确认回包
     * 也按高宽报告），反序写入让它的虚拟屏/编码器落回 1280x480 横屏。
     */
    private fun buildEncoderInfo(swapped: Boolean): ByteArray {
        val w = if (swapped) requestedHeight else requestedWidth
        val h = if (swapped) requestedWidth else requestedHeight
        return CarPlusProtocol.videoEncoderInfo(
            w,
            h,
            requestedFps,
            needAdapterScreen = true,
            saveArea = null,
            drawUIOutsideSafeArea = false,
        )
    }

    /**
     * 流尺寸与期望宽高完全互换（如期望 1280x480 却收 480x1280）时，用正序字段
     * 重发一次 98311 + 98313（反序补偿已由首次请求覆盖，这里是反向兜底）。
     * 只重试一次，避免与发送端来回拉锯。
     */
    private fun retrySwappedEncoderInit() {
        if (swapRetryDone) return
        if (!running.get() || sessionDown.get()) return
        swapRetryDone = true
        log("[CAR+] retrying 98311 with normal field order (swapped compensation did not take)")
        send(CarPlusProtocol.HU_VIDEO_ENC_INIT, buildEncoderInfo(swapped = false))
        send(CarPlusProtocol.VIDEO_ENC_START, ByteArray(0))
    }

    private fun readVideo(socket: Socket) {
        val data = DataInputStream(socket.getInputStream())
        try {
            while (running.get() && !sessionDown.get()) {
                val header = ByteArray(12)
                data.readFully(header)
                val length = readU32(header, 0)
                if (length < 0 || length > MAX_FRAME_BYTES) {
                    log("[CAR+] invalid video frame length=$length")
                    break
                }
                val payload = ByteArray(length)
                if (length > 0) data.readFully(payload)
                if (length > 0) {
                    lastFrameAt = SystemClock.elapsedRealtime()
                    decoder.decode(payload, 0, payload.size)
                }
            }
        } catch (error: Exception) {
            if (running.get() && !sessionDown.get()) log("[CAR+] video channel closed: ${error.message}")
        } finally {
            if (sockets[CarPlusProtocol.PORT_VIDEO] === socket) videoDead = true
        }
    }

    private fun drainAudio(socket: Socket, label: String) {
        val data = DataInputStream(socket.getInputStream())
        val isTts = label == "tts"
        var sampleRate = if (isTts) 16_000 else 48_000
        var channels = if (isTts) 1 else 2
        try {
            while (running.get() && !sessionDown.get()) {
                val header = ByteArray(12)
                data.readFully(header)
                val length = readU32(header, 0)
                val type = readU32(header, 8)
                if (length < 0 || length > MAX_AUDIO_BYTES) break
                val payload = ByteArray(length)
                if (length > 0) data.readFully(payload)
                if (type == 0x30001 || type == 0x40001) {
                    parseAudioInit(payload)?.let { (rate, channelConfig, bits) ->
                        if (rate in 8_000..96_000) sampleRate = rate
                        channels = if (channelConfig == 1) 1 else 2
                        log("[CAR+] $label PCM ${sampleRate}Hz ${channels}ch ${bits}bit")
                    }
                } else if (type == 0x30006 || type == 0x40003) {
                    if (payload.isNotEmpty()) onAudioPacket(isTts, payload, sampleRate, channels)
                }
            }
        } catch (error: Exception) {
            if (running.get() && !sessionDown.get()) log("[CAR+] $label audio channel closed: ${error.message}")
            if (running.get() && !sessionDown.get()) {
                MiniState.setAudio("$label 音频中断，正在重连", Stage.ERROR)
            }
        } finally {
            val port = if (isTts) CarPlusProtocol.PORT_TTS else CarPlusProtocol.PORT_MEDIA
            sockets.remove(port, socket)
        }
    }

    private fun parseAudioInit(payload: ByteArray): Triple<Int, Int, Int>? {
        if (payload.isEmpty()) return null
        var sampleRate = 0
        var channelConfig = 0
        var sampleFormat = 16
        var index = 0
        while (index < payload.size) {
            val tag = payload[index++].toInt() and 0xff
            if (tag and 7 != 0) return null
            var value = 0
            var shift = 0
            while (index < payload.size) {
                val byte = payload[index++].toInt() and 0xff
                value = value or ((byte and 0x7f) shl shift)
                shift += 7
                if (byte and 0x80 == 0) break
            }
            when (tag ushr 3) {
                1 -> sampleRate = value
                2 -> channelConfig = value
                3 -> sampleFormat = value
            }
        }
        if (sampleRate !in 8_000..96_000) sampleRate = 48_000
        if (channelConfig <= 0) channelConfig = 2
        if (sampleFormat != 8 && sampleFormat != 16) sampleFormat = 16
        return Triple(sampleRate, channelConfig, sampleFormat)
    }

    private fun videoWatchdog() {
        while (running.get() && !sessionDown.get()) {
            Thread.sleep(2_000)
            val now = SystemClock.elapsedRealtime()
            if (versionMatched && lastFrameAt > 0 && now - lastFrameAt > 12_000 &&
                now - lastFrameNudgeAt > 8_000
            ) {
                lastFrameNudgeAt = now
                log("[CAR+] video stalled — re-requesting encoder")
                sendEncoderInit()
            }
            if (!videoDead || sessionDown.get()) continue
            videoDead = false
            val old = sockets.remove(CarPlusProtocol.PORT_VIDEO)
            try { old?.close() } catch (_: Exception) {}
            try {
                val socket = connectAny(CarPlusProtocol.PORT_VIDEO)
                sockets[CarPlusProtocol.PORT_VIDEO] = socket
                thread(name = "carplus-video", isDaemon = true) { readVideo(socket) }
                log("[CAR+] video channel reconnected")
                sendEncoderInit()
            } catch (error: Exception) {
                MiniState.setCar("视频端口重连失败，继续重试", Stage.ERROR)
                videoDead = true
            }
        }
    }

    private fun audioWatchdog() {
        while (running.get() && !sessionDown.get()) {
            ensureAudioChannel(CarPlusProtocol.PORT_MEDIA, "media")
            ensureAudioChannel(CarPlusProtocol.PORT_TTS, "tts")
            Thread.sleep(2_000)
        }
    }

    private fun ensureAudioChannel(port: Int, label: String) {
        val current = sockets[port]
        if (current != null && !current.isClosed) return
        runCatching { current?.close() }
        try {
            val socket = connectAny(port)
            sockets[port] = socket
            thread(name = "carplus-audio-$label", isDaemon = true) {
                drainAudio(socket, label)
            }
            log("[CAR+] $label audio channel connected :$port")
            MiniState.setAudio("$label 音频通道已连接", Stage.WAIT)
        } catch (_: Exception) {
            // 9240/9241 appear with the rest of the receiver; retry without flooding the log.
        }
    }

    /** Car+ may expose 9340 only after its encoder/session becomes ready. */
    private fun touchWatchdog() {
        while (running.get() && !sessionDown.get()) {
            if (touchOut == null) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastTouchProbeAt >= 1_000L) {
                    lastTouchProbeAt = now
                    try {
                        val socket = connectAny(CarPlusProtocol.PORT_TOUCH)
                        sockets[CarPlusProtocol.PORT_TOUCH] = socket
                        touchOut = socket.getOutputStream()
                        touchMissLogged = false
                        log("[CAR+] touch channel ready :${CarPlusProtocol.PORT_TOUCH}")
                    } catch (_: Exception) {
                    }
                }
            }
            Thread.sleep(500)
        }
    }

    private fun startTouchWorker() {
        if (touchWorker?.isAlive == true) return
        touchWorker = thread(name = "carplus-touch-send", isDaemon = true) {
            while (running.get()) {
                var event: TouchEvent? = null
                touchLock.withLock {
                    if (touchQueue.isEmpty()) touchReady.await(300, TimeUnit.MILLISECONDS)
                    if (touchQueue.isNotEmpty()) event = touchQueue.removeFirst()
                }
                val e = event ?: continue
                sendTouchNow(e)
            }
        }
    }

    private fun sendTouchNow(event: TouchEvent) {
        val out = touchOut
        if (out == null) {
            if (!touchMissLogged) {
                touchMissLogged = true
                log("[CAR+] touch channel not open yet — waiting for sender :9340")
            }
            return
        }
        val payload = if (event.serviceType == CarPlusProtocol.TOUCH_ACTION) {
            CarPlusProtocol.touch(event.actionCode, event.x, event.y)
        } else {
            CarPlusProtocol.touchPoint(event.x, event.y)
        }
        try {
            synchronized(writeLock) {
                out.write(CarPlusProtocol.command(event.serviceType, payload))
                out.flush()
            }
        } catch (error: Exception) {
            log("[CAR+] touch write failed: ${error.message}")
            touchOut = null
            sockets.remove(CarPlusProtocol.PORT_TOUCH)?.let { runCatching { it.close() } }
        }
    }

    private fun heartbeatLoop() {
        try {
            while (running.get() && !sessionDown.get()) {
                Thread.sleep(1_000)
                send(CarPlusProtocol.HEARTBEAT, ByteArray(0))
            }
        } catch (_: Exception) {
        }
    }

    private fun send(type: Int, payload: ByteArray): Boolean {
        val output = cmdOut ?: return false
        return try {
            synchronized(writeLock) {
                output.write(CarPlusProtocol.command(type, payload))
                output.flush()
            }
            true
        } catch (error: Exception) {
            if (running.get() && !sessionDown.get()) log("[CAR+] send 0x${type.toString(16)} failed: ${error.message}")
            false
        }
    }

    private fun closeSession() {
        sessionDown.set(true)
        cmdOut = null
        touchOut = null
        touchLock.withLock {
            touchQueue.clear()
            touchReady.signalAll()
        }
        for ((_, socket) in sockets) {
            try { socket.close() } catch (_: Exception) {}
        }
        sockets.clear()
    }

    /**
     * 发送端探测目标：回环 + 本机所有 IPv4。CarWith 等发送端可能只把协议服务绑在
     * Wi-Fi 网卡地址上（真实车机走网络连过来），只探 127.0.0.1 会永远探空。
     * Bike Wi-Fi is bound to the whole process after video starts; probing must
     * happen without that binding, matching the AA receiver workaround.
     */
    private fun senderAddresses(): List<String> {
        val addrs = linkedSetOf("127.0.0.1")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return addrs.toList()
            for (ni in java.util.Collections.list(interfaces)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in java.util.Collections.list(ni.inetAddresses)) {
                    if (ia is Inet4Address) addrs.add(ia.hostAddress)
                }
            }
        } catch (_: Exception) {
        }
        return addrs.toList()
    }

    private fun connectSender(address: String, port: Int): Socket {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val bound = try { cm?.boundNetworkForProcess } catch (_: Exception) { null }
        try {
            if (bound != null) try { cm?.bindProcessToNetwork(null) } catch (_: Exception) {}
            return Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            }
        } finally {
            if (bound != null) try { cm?.bindProcessToNetwork(bound) } catch (_: Exception) {}
        }
    }

    /** 单通道重连：优先用握手成功的那个地址，再退回全量探测。 */
    private fun connectAny(port: Int): Socket {
        val tried = linkedSetOf<String>()
        senderAddr?.let { tried.add(it) }
        tried.addAll(senderAddresses())
        var last: Exception? = null
        for (addr in tried) {
            try {
                return connectSender(addr, port)
            } catch (error: Exception) {
                last = error
            }
        }
        throw last ?: IllegalStateException("no sender address to probe")
    }

    private fun readU32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private data class TouchEvent(
        val serviceType: Int,
        val actionCode: Int,
        var x: Int,
        var y: Int,
    )
}
