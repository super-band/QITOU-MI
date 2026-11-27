package app.qitou.bridge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class QrScanActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val scanner: BarcodeScanner by lazy { BarcodeScanning.getClient() }
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            showCameraPermissionRecovery()
        }
    }
    @Volatile private var busy = false
    @Volatile private var busyAt = 0L
    @Volatile private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        previewView = PreviewView(this)
        status = TextView(this).apply {
            text = "对准仪表上的 MotoPlay 配对二维码；识别不到可点右上角「粘贴文本」"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(24, 28, 24, 28)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(Button(this@QrScanActivity).apply {
                text = "返回"
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(175, 12, 18, 26))
                setOnClickListener { finish() }
            }, FrameLayout.LayoutParams(180, 105, Gravity.TOP or Gravity.START).apply {
                topMargin = (48 * resources.displayMetrics.density).toInt()
            })
            addView(Button(this@QrScanActivity).apply {
                text = "粘贴文本"
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(175, 12, 18, 26))
                setOnClickListener { showPasteDialog() }
            }, FrameLayout.LayoutParams(220, 105, Gravity.TOP or Gravity.END).apply {
                topMargin = (48 * resources.displayMetrics.density).toInt()
            })
            addView(status, FrameLayout.LayoutParams(-1, 160, Gravity.BOTTOM))
        })
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            CarDialogs.builder(this)
                .setTitle("需要相机权限")
                .setMessage("相机只用于扫描仪表上的 MotoPlay 配对二维码。")
                .setNegativeButton("取消") { _, _ -> finish() }
                .setPositiveButton("继续") { _, _ ->
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                .show()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showCameraPermissionRecovery() {
        val permanent = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        CarDialogs.builder(this)
            .setTitle("无法扫描二维码")
            .setMessage("请授予相机权限，或返回应用使用“手动 Wi‑Fi”连接。")
            .setNegativeButton("返回") { _, _ -> finish() }
            .setPositiveButton(if (permanent) "打开应用设置" else "重新授权") { _, _ ->
                if (permanent) {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                    finish()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            .show()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (error: Exception) {
                status.text = "相机启动失败：${error.message ?: error.javaClass.simpleName}"
            }
        }, mainExecutor)
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyze(image: ImageProxy) {
        if (saving) {
            image.close()
            return
        }
        if (busy) {
            // 看门狗：识别器长时间无回调时自动复位，避免相机从此再无响应。
            if (SystemClock.elapsedRealtime() - busyAt <= 5_000L) {
                image.close()
                return
            }
            busy = false
        }
        val media = image.image ?: run {
            image.close()
            return
        }
        busy = true
        busyAt = SystemClock.elapsedRealtime()
        try {
            val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { barcodes -> handleBarcodes(barcodes) }
                .addOnFailureListener {
                    status.text = "识别失败：${it.message ?: "请重新对准二维码"}"
                }
                .addOnCompleteListener {
                    image.close()
                    if (!saving) busy = false
                }
        } catch (error: Exception) {
            runCatching { image.close() }
            busy = false
            status.text = "识别异常：${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun handleBarcodes(barcodes: List<Barcode>) {
        var rejected: String? = null
        for (barcode in barcodes) {
            val raw = (barcode.rawValue ?: barcode.displayValue)?.trim().orEmpty()
            if (raw.isEmpty()) continue
            val vehicle = MotoVehicle.parse(raw)
            if (vehicle == null) {
                rejected = raw
                continue
            }
            acceptVehicle(vehicle)
            return
        }
        if (rejected != null) {
            status.text = "不是 MotoPlay 配对码：${rejected.take(60)}…" +
                "\n请打开仪表上的“手机互联 / MotoPlay”二维码页面。"
        }
    }

    private fun acceptVehicle(vehicle: MotoVehicle) {
        if (saving) return
        saving = true
        status.text = "已识别 ${vehicle.ssid}，正在保存…"
        val appContext = applicationContext
        executor.execute {
            val saved = MotoVehiclePrefs.save(appContext, vehicle)
            runOnUiThread {
                if (saved) {
                    MiniState.update("已读取 ${vehicle.ssid}")
                    setResult(RESULT_OK)
                    finish()
                } else {
                    saving = false
                    busy = false
                    status.text = "无法安全保存车辆信息，请检查系统 KeyStore 后重试。"
                }
            }
        }
    }

    /** 相机识别不到时的兜底：粘贴其他扫码工具读到的二维码文本，走同一套解析与保存流程。 */
    private fun showPasteDialog() {
        val input = EditText(this).apply {
            hint = "粘贴二维码文本内容"
            setSingleLine(false)
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        CarDialogs.builder(this)
            .setTitle("粘贴二维码文本")
            .setMessage("相机识别不到时，可用其他扫码工具读取文本后粘贴到这里。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val vehicle = MotoVehicle.parse(input.text.toString())
                if (vehicle == null) {
                    status.text = "无法识别该文本为配对信息，请检查内容是否完整。"
                } else {
                    acceptVehicle(vehicle)
                }
            }
            .show()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        scanner.close()
        super.onDestroy()
    }

}
