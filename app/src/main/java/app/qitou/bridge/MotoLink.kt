package app.qitou.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Narrow EasyConn/PXC implementation for the 450MT MotoPlay path. */
class MotoLink(
    private val context: Context,
    private val pipeline: MiniVideoPipeline,
    private val log: (String) -> Unit,
) {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val clients = java.util.Collections.synchronizedList(ArrayList<Socket>())
    private val servers = ArrayList<ServerSocket>()
    private val heartbeats = java.util.Collections.synchronizedList(ArrayList<Thread>())
    private val live = AtomicInteger(0)
    private val phoneUuid = UUID.randomUUID().toString()
    private val cycleLock = Any()
    private val rearming = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var network: Network? = null
    @Volatile private var localIp: Inet4Address? = null
    @Volatile private var bikeAddrs: List<Inet4Address> = emptyList()
    @Volatile private var lastKnownBike: Inet4Address? = null
    @Volatile private var lastScanAt = 0L
    @Volatile private var captureSeen = false
    @Volatile private var frames = 0
    @Volatile private var lastBikeAt = 0L
    @Volatile private var lastActivityAt = 0L
    @Volatile private var lastPullAt = 0L
    @Volatile private var lastProbeAt = 0L

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "moto-link-start", isDaemon = true) {
            try {
                if (!bindActiveBikeNetwork()) {
                    MiniState.update("等待车机 Wi‑Fi 恢复")
                    MiniState.setPxc("等待车机 Wi-Fi", Stage.WAIT)
                } else {
                    log("[PXC] phone=${localIp?.hostAddress} bike candidates=${bikeAddrs.joinToString { it.hostAddress ?: "?" }}")
                    listen()
                    probe()
                }
                supervisor()
            } catch (error: Exception) {
                log("[PXC] start failed: ${error.javaClass.simpleName}: ${error.message}")
                MiniState.update("PXC 启动失败")
                MiniState.setPxc("PXC 启动失败", Stage.ERROR)
                synchronized(cycleLock) { closeBikeConnections() }
                supervisor()
            }
        }
    }

    private fun bindActiveBikeNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = MotoWifi.network ?: cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        val props = cm.getLinkProperties(net) ?: return false
        val ip = props.linkAddresses
            .map(LinkAddress::getAddress)
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress } ?: return false
        network = net
        localIp = ip
        bikeAddrs = resolveBikeAddresses(props, ip)
        return true
    }

    /**
     * 车机地址候选：已验证过的地址、默认路由网关、DHCP 网关/服务器、网段 .1/.254。
     * 车机 SoftAP 的默认路由网关常为 0.0.0.0（on-link），不能直接拿来连。
     */
    private fun resolveBikeAddresses(props: android.net.LinkProperties, ip: Inet4Address): List<Inet4Address> {
        val out = linkedSetOf<Inet4Address>()
        lastKnownBike?.let { out += it }
        props.routes.firstOrNull { it.isDefaultRoute }?.gateway?.let { gw ->
            if (gw is Inet4Address && !gw.isAnyLocalAddress && !gw.isLoopbackAddress) out += gw
        }
        runCatching {
            val dhcp = context.getSystemService(WifiManager::class.java)?.dhcpInfo
            dhcp?.gateway?.toInet4()?.let { out += it }
            dhcp?.serverAddress?.toInet4()?.let { out += it }
        }
        val b = ip.address
        for (host in intArrayOf(1, 254)) {
            runCatching {
                out += InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], host.toByte())) as Inet4Address
            }
        }
        return out.filter { it != ip }
    }

    /** DhcpInfo 的 int 是小端序地址。 */
    private fun Int.toInet4(): Inet4Address? {
        if (this == 0 || this == -1) return null
        return runCatching {
            InetAddress.getByAddress(
                byteArrayOf(
                    (this and 0xff).toByte(),
                    ((this shr 8) and 0xff).toByte(),
                    ((this shr 16) and 0xff).toByte(),
                    ((this shr 24) and 0xff).toByte(),
                ),
            ) as Inet4Address
        }.getOrNull()
    }

    private fun listen() {
        val ip = localIp ?: return
        for (port in LISTEN_PORTS) {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(ip, port), 50)
            }
            servers += server
            thread(name = "pw-accept-$port", isDaemon = true) {
                while (running.get()) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    clients += socket
                    live.incrementAndGet()
                    lastBikeAt = SystemClock.elapsedRealtime()
                    lastActivityAt = lastBikeAt
                    log("[PXC] bike connected :$port")
                    MiniState.setPxc("车机已连接 :$port", Stage.OK)
                    thread(name = "pw-conn-$port", isDaemon = true) {
                        try {
                            if (port == PORT_CTRL) controlLoop(socket)
                            else mediaLoop(socket)
                        } finally {
                            clients.remove(socket)
                            live.decrementAndGet()
                            runCatching { socket.close() }
                            if (running.get() && live.get() == 0) {
                                MiniState.setPxc("车机连接断开，准备重连", Stage.ERROR)
                            }
                        }
                    }
                }
            }
        }
        log("[PXC] listening ${LISTEN_PORTS.joinToString()} on ${ip.hostAddress}")
        MiniState.setPxc("等待车机连接", Stage.WAIT)
    }

    private fun probe() {
        val net = network ?: return
        lastProbeAt = SystemClock.elapsedRealtime()
        for (bike in bikeAddrs) {
            if (!running.get()) return
            if (tryProbe(net, bike)) {
                lastKnownBike = bike
                log("[PXC] probe accepted (${bike.hostAddress})")
                MiniState.update("已发现车机，等待 PXC 握手")
                MiniState.setPxc("已发现车机，等待握手", Stage.WAIT)
                return
            }
        }
        // 候选都失败时扫一遍网段找 10930（车机地址可能不在常规项里）。
        val now = SystemClock.elapsedRealtime()
        if (lastKnownBike == null && now - lastScanAt > 25_000L) {
            lastScanAt = now
            val scanned = scanSubnet(net)
            if (scanned != null && running.get() && tryProbe(net, scanned)) {
                lastKnownBike = scanned
                log("[PXC] probe accepted (${scanned.hostAddress}, subnet scan)")
                MiniState.update("已发现车机，等待 PXC 握手")
                MiniState.setPxc("已发现车机，等待握手", Stage.WAIT)
                return
            }
        }
        log("[PXC] 未发现 MotoPlay（已试 ${bikeAddrs.joinToString { it.hostAddress ?: "?" }}）")
        MiniState.update("未发现 MotoPlay 服务，请保持仪表手机互联页面")
        MiniState.setPxc("未发现 MotoPlay，自动重试", Stage.ERROR)
    }

    private fun tryProbe(net: Network, bike: Inet4Address): Boolean = try {
        val socket = net.socketFactory.createSocket()
        socket.connect(InetSocketAddress(bike, PORT_PROBE), 1_200)
        socket.soTimeout = 3_500
        val json = """{"phoneType":"Android","packageName":"com.cfmoto.cfmotointernational"}"""
        PxcFrame(PxcFrame.CMD_MDNS_RESPOND, json.toByteArray()).write(socket.getOutputStream())
        val ack = PxcFrame.read(socket.getInputStream())
        socket.close()
        ack?.cmd == PxcFrame.CMD_MDNS_RESPOND_ACK
    } catch (error: Exception) {
        log("[PXC] probe ${bike.hostAddress}:$PORT_PROBE ${error.javaClass.simpleName}")
        false
    }

    /** 并发扫网段的 PORT_PROBE 端口，返回第一个应答的地址。 */
    private fun scanSubnet(net: Network): Inet4Address? {
        val base = localIp?.address ?: return null
        val self = localIp
        val found = ConcurrentLinkedQueue<Inet4Address>()
        val pool = Executors.newFixedThreadPool(32)
        try {
            val tasks = (1..254).map { host ->
                pool.submit {
                    if (found.isNotEmpty() || !running.get()) return@submit
                    val candidate = runCatching {
                        InetAddress.getByAddress(byteArrayOf(base[0], base[1], base[2], host.toByte())) as Inet4Address
                    }.getOrNull() ?: return@submit
                    if (candidate == self) return@submit
                    try {
                        net.socketFactory.createSocket().use { s ->
                            s.connect(InetSocketAddress(candidate, PORT_PROBE), 300)
                        }
                        found += candidate
                    } catch (_: Exception) {
                    }
                }
            }
            tasks.forEach { runCatching { it.get(4, TimeUnit.SECONDS) } }
        } finally {
            pool.shutdownNow()
        }
        return found.firstOrNull()
    }

    private fun controlLoop(socket: Socket) {
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        while (running.get()) {
            val frame = PxcFrame.read(input) ?: break
            lastActivityAt = SystemClock.elapsedRealtime()
            when (frame.cmd) {
                PxcFrame.CMD_CHANNEL_CAR_CTRL,
                PxcFrame.CMD_CHANNEL_CAR_DATA -> {
                    PxcFrame(frame.cmd + 1, ByteArray(0)).write(out)
                    startHeartbeat(socket)
                }
                PxcFrame.CMD_CLIENT_INFO -> replyClientInfo(frame, out)
                PxcFrame.CMD_CHECK_SN -> replyCheckSn(frame, out)
                PxcFrame.CMD_QUERY_SPEED -> PxcFrame(PxcFrame.CMD_QUERY_SPEED_RLY, ByteArray(0)).write(out)
                PxcFrame.CMD_HEARTBEAT -> PxcFrame(PxcFrame.CMD_HEARTBEAT_ACK, ByteArray(0)).write(out)
                PxcFrame.CMD_HU_TIME_SYNC -> {
                    PxcFrame(PxcFrame.CMD_HU_TIME_SYNC_ACK, TimeAck.ack(frame.payload)).write(out)
                }
                PxcFrame.CMD_HU_QUERY_TIME -> {
                    PxcFrame(PxcFrame.CMD_HU_QUERY_TIME_ACK, ByteArray(0)).write(out)
                }
            }
        }
    }

    private fun replyClientInfo(frame: PxcFrame, out: OutputStream) {
        val bike = runCatching { JSONObject(String(frame.payload, Charsets.UTF_8)) }.getOrNull()
        val huid = bike?.optString("HUID")?.ifBlank { null }
        val reply = JSONObject().apply {
            put("pxcVersion", "1.0.2")
            put("phoneUUID", phoneUuid)
            put("phoneBrand", Build.BRAND)
            put("phoneModel", Build.MODEL)
            put("phoneOsVersion", Build.VERSION.SDK_INT.toString())
            put("phoneOs", "Android")
            put("package", "com.cfmoto.cfmotointernational")
            put("versionCode", 126)
            put("token", 0)
            put("pubkey", RsaKeys.publicKeyBase64)
            put("encryptedHUID", huid?.let(RsaKeys::signHuid).orEmpty())
            put("bluetoothName", "QITOU")
            put("supportH264IFrame", true)
            put("supportFunction", 0)
            put("supportSyncCorrectTime", false)
            put("appVersionFingerPrint", "qitou-mvp")
        }
        PxcFrame(PxcFrame.CMD_CLIENT_INFO_RLY, reply.toString().toByteArray()).write(out)
        log("[PXC] CLIENT_INFO → 450MT profile")
    }

    private fun replyCheckSn(frame: PxcFrame, out: OutputStream) {
        val sn = runCatching { JSONObject(String(frame.payload, Charsets.UTF_8)).optString("sn") }
            .getOrDefault("")
        PxcFrame(PxcFrame.CMD_CHECK_SN_ACK, ByteArray(0)).write(out)
        val result = JSONObject().apply {
            put("isOk", true)
            put("errCode", 0)
            put("errMsg", "")
            put("id", sn)
            put("client_set", "easy_conn")
        }
        PxcFrame(PxcFrame.CMD_CHECK_SN_RESULT, result.toString().toByteArray()).write(out)
        log("[PXC] CHECK_SN accepted")
    }

    private fun startHeartbeat(socket: Socket) {
        val out = socket.getOutputStream()
        val t = thread(name = "pw-heartbeat", isDaemon = true) {
            while (running.get() && !socket.isClosed) {
                Thread.sleep(2_000)
                try {
                    PxcFrame(PxcFrame.CMD_HEARTBEAT, ByteArray(0)).write(out)
                } catch (_: Exception) {
                    break
                }
            }
        }
        heartbeats += t
    }

    private fun mediaLoop(socket: Socket) {
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val header = ByteArray(8)
        while (running.get()) {
            if (!PxcFrame.readFully(input, header, 8)) break
            lastActivityAt = SystemClock.elapsedRealtime()
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.getShort(0).toInt()
            val len = b.getShort(2).toInt() and 0xffff
            val body = ByteArray(len)
            if (len > 0 && !PxcFrame.readFully(input, body, len)) break
            handleMedia(cmd, body, out)
        }
    }

    private fun handleMedia(cmd: Int, body: ByteArray, out: OutputStream) {
        when (cmd) {
            16 -> {
                val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val width = if (body.size >= 2) b.getShort(0).toInt() and 0xffff else 800
                val height = if (body.size >= 4) b.getShort(2).toInt() and 0xffff else 384
                captureSeen = true
                pipeline.configureBikeCanvas(
                    800,
                    384,
                    ScreenFit.FIT,
                    CanvasInsets(bottom = 80),
                )
                val reply = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                reply.putInt(0, 2)
                reply.putShort(4, width.toShort())
                reply.putShort(6, height.toShort())
                reply.put(8, if (body.size >= 30) body[29] else 0)
                req(out, 17, reply.array())
                log("[PXC] capture ${width}x$height")
            }
            48 -> req(out, 49, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0, 3).putInt(4, 1).array())
            64 -> req(out, 65, null)
            96 -> req(out, 97, """{"state":0}""".toByteArray())
            112 -> {
                pipeline.onBikeDataStart()
                req(out, 113, null)
                MiniState.update("车机已请求画面")
                MiniState.setPxc("车机已请求画面", Stage.OK)
            }
            114 -> {
                lastPullAt = SystemClock.elapsedRealtime()
                val frame = pipeline.pollFrame(1_500)
                if (frame != null) {
                    val size = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(0, frame.size).array()
                    synchronized(out) {
                        out.write(size)
                        out.write(frame)
                        out.flush()
                    }
                    frames++
                    MiniState.update("正在投送 · ${frames} 帧")
                    MiniState.setPxc("投送中 · ${frames} 帧", Stage.OK)
                }
            }
        }
    }

    /**
     * The bike may reboot, the SoftAP may disappear, or a half-open socket can stop pulling
     * frames. Rebuild the complete PXC endpoint instead of leaving a false "connected" state.
     */
    private fun supervisor() {
        while (running.get()) {
            Thread.sleep(2_000)
            if (!running.get()) return
            val now = SystemClock.elapsedRealtime()
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val active = MotoWifi.network
            val activeCaps = active?.let(cm::getNetworkCapabilities)
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                MiniState.setPxc("等待车机 Wi-Fi", Stage.WAIT)
                continue
            }
            val props = active?.let(cm::getLinkProperties)
            val currentIp = props?.linkAddresses
                ?.map(LinkAddress::getAddress)
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
            if (currentIp == null) {
                rearm("Wi-Fi lost", 2_000L)
                continue
            }
            if (localIp == null || currentIp.hostAddress != localIp?.hostAddress) {
                rearm("Wi-Fi address changed", 500L)
                continue
            }
            val listenerClosed = synchronized(cycleLock) {
                servers.isEmpty() || servers.any { it.isClosed }
            }
            if (listenerClosed) {
                rearm("listener closed", 1_000L)
                continue
            }
            if (live.get() == 0 && lastBikeAt > 0 && now - lastBikeAt > 5_000) {
                rearm("bike disconnected", 1_500L)
                continue
            }
            if (captureSeen && frames > 0 && live.get() > 0 &&
                lastActivityAt > 0 && now - lastActivityAt > 20_000
            ) {
                rearm("bike traffic stalled", 1_000L)
                continue
            }
            if (live.get() == 0 && now - lastProbeAt > 10_000) {
                thread(name = "pxc-reprobe", isDaemon = true) { probe() }
            }
            if (captureSeen && frames > 0 && lastPullAt > 0 && now - lastPullAt > 12_000) {
                rearm("frame pull stalled", 1_000L)
            }
        }
    }

    private fun rearm(reason: String, delayMs: Long) {
        if (!running.get() || !rearming.compareAndSet(false, true)) return
        thread(name = "pxc-rearm", isDaemon = true) {
            try {
                log("[PXC] re-arm: $reason")
                MiniState.setPxc("车机链路重连：$reason", Stage.WAIT)
                synchronized(cycleLock) {
                    closeBikeConnections()
                    captureSeen = false
                    frames = 0
                    lastPullAt = 0
                    Thread.sleep(delayMs)
                    if (!running.get()) return@synchronized
                    if (!bindActiveBikeNetwork()) {
                        MiniState.setPxc("等待车机 Wi-Fi", Stage.WAIT)
                        return@synchronized
                    }
                    listen()
                    probe()
                }
            } catch (error: Exception) {
                log("[PXC] re-arm failed: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                rearming.set(false)
            }
        }
    }

    private fun closeBikeConnections() {
        synchronized(clients) {
            clients.toList().forEach { runCatching { it.close() } }
            clients.clear()
        }
        servers.forEach { runCatching { it.close() } }
        servers.clear()
        synchronized(heartbeats) {
            heartbeats.forEach(Thread::interrupt)
            heartbeats.clear()
        }
        live.set(0)
        lastBikeAt = 0
        lastActivityAt = 0
    }

    private fun req(out: OutputStream, cmd: Int, body: ByteArray?) {
        val len = body?.size ?: 0
        val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        h.putShort(0, cmd.toShort())
        h.putShort(2, len.toShort())
        h.putInt(4, 0)
        synchronized(out) {
            out.write(h.array())
            if (body != null && body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }

    fun stop() {
        running.set(false)
        synchronized(cycleLock) {
            closeBikeConnections()
        }
        MiniState.setPxc("已停止", Stage.OFF)
    }

    companion object {
        const val PORT_DATA = 10920
        const val PORT_MEDIA = 10921
        const val PORT_CTRL = 10922
        const val PORT_PROBE = 10930
        private val LISTEN_PORTS = intArrayOf(PORT_DATA, PORT_MEDIA, PORT_CTRL)
    }
}
