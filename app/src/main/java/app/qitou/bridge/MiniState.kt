package app.qitou.bridge

import android.content.Context
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class Stage {
    OFF,
    WAIT,
    OK,
    ERROR,
}

/**
 * Shared, process-local state for the foreground service and both control activities.
 * UI reads snapshots; protocol code only has to publish short status changes.
 */
object MiniState {
    private const val MAX_LOG_LINES = 900
    private const val MAX_LOG_BYTES = 1_500_000L

    @Volatile var running: Boolean = false
    @Volatile var serviceActive: Boolean = false
    @Volatile var status: String = "未启动"
    @Volatile var framesPerSecond: Int = 0
    @Volatile var lastLog: String = ""
    @Volatile var vehicleName: String = ""
    @Volatile var wifiText: String = "未连接"
    @Volatile var carText: String = "未启动"
    @Volatile var pxcText: String = "未启动"
    @Volatile var audioText: String = "未连接"
    @Volatile var bluetoothText: String = "未连接"
    @Volatile var wifiStage: Stage = Stage.OFF
    @Volatile var carStage: Stage = Stage.OFF
    @Volatile var pxcStage: Stage = Stage.OFF
    @Volatile var audioStage: Stage = Stage.OFF
    @Volatile var bluetoothStage: Stage = Stage.OFF
    @Volatile var listener: (() -> Unit)? = null

    private val logs = ArrayDeque<String>(MAX_LOG_LINES)
    private val logLock = Any()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    private val logExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "qitou-log-writer").apply { isDaemon = true }
    }
    @Volatile private var appContext: Context? = null
    @Volatile private var logFile: File? = null

    fun attach(context: Context) {
        val app = context.applicationContext
        if (appContext === app && logFile != null) return
        synchronized(logLock) {
            appContext = app
            logFile = File(app.filesDir, "qitou.log")
            if ((logFile?.length() ?: 0L) > MAX_LOG_BYTES) {
                runCatching { logFile?.delete() }
            }
        }
    }

    @Synchronized
    fun update(status: String? = null, fps: Int? = null) {
        if (status != null) this.status = status
        if (fps != null) framesPerSecond = fps
        listener?.invoke()
    }

    fun setVehicle(name: String) {
        vehicleName = name
        listener?.invoke()
    }

    fun setWifi(text: String, stage: Stage) {
        wifiText = text
        wifiStage = stage
        listener?.invoke()
    }

    fun setCar(text: String, stage: Stage) {
        carText = text
        carStage = stage
        listener?.invoke()
    }

    fun setPxc(text: String, stage: Stage) {
        pxcText = text
        pxcStage = stage
        listener?.invoke()
    }

    fun setAudio(text: String, stage: Stage) {
        audioText = text
        audioStage = stage
        listener?.invoke()
    }

    fun setBluetooth(text: String, stage: Stage) {
        bluetoothText = text
        bluetoothStage = stage
        listener?.invoke()
    }

    fun resetLinkStates() {
        wifiText = "未连接"
        wifiStage = Stage.OFF
        carText = "未启动"
        carStage = Stage.OFF
        pxcText = "未启动"
        pxcStage = Stage.OFF
        audioText = "未连接"
        audioStage = Stage.OFF
        bluetoothText = "未连接"
        bluetoothStage = Stage.OFF
        listener?.invoke()
    }

    fun log(line: String) {
        val safe = redact(line)
        lastLog = safe
        val stamped = "${timeFormat.format(LocalTime.now())}  $safe"
        android.util.Log.i("QITOU", safe)
        synchronized(logLock) {
            if (logs.size >= MAX_LOG_LINES) logs.removeFirst()
            logs.addLast(stamped)
        }
        logExecutor.execute {
            val file = logFile ?: return@execute
            runCatching {
                if (file.length() > MAX_LOG_BYTES) file.writeText("")
                file.appendText(stamped + "\n")
            }
        }
    }

    fun recentLogs(): List<String> = synchronized(logLock) { logs.toList() }

    /**
     * Writes a redacted support log into the app cache and returns it for FileProvider sharing.
     */
    fun exportLog(context: Context): File {
        attach(context)
        runCatching { logExecutor.submit {}.get(2, TimeUnit.SECONDS) }
        val shareDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val out = File(shareDir, "QITOU-support.log")
        val header = buildString {
            appendLine("${Brand.CHINESE_NAME} ${Brand.LATIN_NAME} diagnostic log")
            appendLine("time=${Date()}")
            appendLine("android=${android.os.Build.VERSION.RELEASE}")
            appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("vehicle=${vehicleName.ifBlank { "未选择" }}")
            appendLine("wifi=${wifiText}")
            appendLine("carlife=${carText}")
            appendLine("pxc=${pxcText}")
            appendLine("audio=${audioText}")
            appendLine("bluetooth=${bluetoothText}")
            appendLine("fps=$framesPerSecond")
            appendLine("status=$status")
            appendLine("----------------------------------------")
        }
        val persisted = synchronized(logLock) {
            runCatching {
                logFile?.takeIf { it.exists() }?.readText()?.takeLast(600_000)
            }.getOrNull()
        }
        val current = persisted?.takeIf { it.isNotBlank() }
            ?: recentLogs().joinToString("\n", postfix = "\n")
        out.writeText(redact(header + current))
        return out
    }

    private fun redact(input: String): String {
        var text = input
        text = text.replace(
            Regex("""(?i)(pwd|password|passphrase|psk)\s*[=:]\s*([^&#\s]+)"""),
            "$1=***",
        )
        text = text.replace(
            Regex("""(?i)(Wifi=)[^&#\s]+#([^&#\s]+)#"""),
            "$1***#***#",
        )
        return text
    }
}
