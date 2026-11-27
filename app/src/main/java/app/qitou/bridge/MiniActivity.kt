package app.qitou.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.core.content.FileProvider
import app.qitou.bridge.sender.SenderRouter

/** MIUI/米家风格的单页投送控制器。 */
class MiniActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var fps: TextView
    private lateinit var action: Button
    private lateinit var scanButton: Button
    private lateinit var fullscreenButton: Button
    private lateinit var carPlusButton: Button
    private lateinit var mediaVolumeText: TextView
    private lateinit var navigationVolumeText: TextView
    private lateinit var mediaVolumeSeek: SeekBar
    private lateinit var navigationVolumeSeek: SeekBar
    private lateinit var duckSwitch: Switch
    private lateinit var phoneRouteButton: Button
    private lateinit var bikeRouteButton: Button
    private lateinit var preview: TextureView
    private var previewSurface: Surface? = null
    private lateinit var previewHint: TextView
    private lateinit var rowVehicle: TextView
    private lateinit var rowWifi: TextView
    private lateinit var rowCar: TextView
    private lateinit var rowPxc: TextView
    private lateinit var rowAudio: TextView
    private lateinit var rowBluetooth: TextView
    private var attached = false
    private var lastX = 0
    private var lastY = 0
    private var permissionPurpose = PermissionPurpose.START_BRIDGE
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (permissionPurpose == PermissionPurpose.FIRST_LAUNCH) {
            showInitialPermissionResult(result)
            return@registerForActivityResult
        }
        val required = result.filterKeys {
            it != Manifest.permission.POST_NOTIFICATIONS &&
                it != Manifest.permission.BLUETOOTH_CONNECT
        }
        when {
            required.all { it.value } -> {
                if (result[Manifest.permission.BLUETOOTH_CONNECT] == false) {
                    AudioVolumeStore.setOutputRoute(this, AudioOutputRoute.PHONE)
                    renderAudioRouteButtons()
                    Toast.makeText(
                        this,
                        "蓝牙未授权，已继续使用手机音频",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                startWifiThenBridge()
            }
            else -> showPermissionRecovery(required.filterValues { !it }.keys.toTypedArray())
        }
    }
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            selectBikeAudioRoute()
        } else {
            AudioVolumeStore.setOutputRoute(this, AudioOutputRoute.PHONE)
            renderAudioRouteButtons()
            showBluetoothPermissionRecovery()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarAppTheme.prepare(this)
        MiniState.attach(applicationContext)
        AudioVolumeStore.attach(applicationContext)
        setContentView(buildUi())
        MiniState.listener = { runOnUiThread(::render) }
        val vehicle = MotoVehiclePrefs.load(this)
        if (vehicle != null) MiniState.setVehicle(vehicle.displayName)
        render()
        window.decorView.post(::maybeShowInitialPermissionPrompt)
    }

    override fun onResume() {
        super.onResume()
        MiniState.listener = { runOnUiThread(::render) }
        carPlusButton.text = "重新启动${SenderRouter.displayName(this)}"
        attachPreview()
        render()
    }

    override fun onPause() {
        MiniState.listener = null
        detachPreview()
    }

    override fun onDestroy() {
        MiniState.listener = null
        super.onDestroy()
    }

    @Deprecated("Activity result for the embedded QR scanner")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_QR && resultCode == RESULT_OK) startWifiThenBridge()
    }

    private fun buildUi(): View {
        val background = gradient()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
            setBackground(background)
        }

        // ── 顶部品牌栏 ──────────────────────────────────────────
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MiniActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                contentDescription = Brand.FULL_NAME
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(LinearLayout(this@MiniActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, 0, 0)
                addView(LinearLayout(this@MiniActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MiniActivity).apply {
                        text = Brand.CHINESE_NAME
                        setTextColor(color(R.color.text_heading))
                        textSize = 21f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(this@MiniActivity).apply {
                        text = Brand.VARIANT_NAME
                        setTextColor(color(R.color.chip_text))
                        textSize = 10f
                        setPadding(dp(9), dp(2), dp(9), dp(2))
                        setBackground(rounded(color(R.color.chip_surface), 999))
                    }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                })
                addView(TextView(this@MiniActivity).apply {
                    text = "${Brand.LATIN_NAME} · 450MT 投送助手"
                    setTextColor(color(R.color.text_muted))
                    textSize = 11f
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@MiniActivity).apply {
                text = "关于"
                textSize = 12f
                isAllCaps = false
                setPadding(dp(16), 0, dp(16), 0)
                setTextColor(color(R.color.button_secondary_text))
                setBackground(rounded(color(R.color.button_secondary_surface), 999))
                setOnClickListener {
                    startActivity(Intent(this@MiniActivity, AboutActivity::class.java))
                }
            })
        }, LinearLayout.LayoutParams(-1, -2))

        // ── 主状态卡片 ──────────────────────────────────────────
        val heroCard = card()
        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this)
        titleRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        status = TextView(this).apply {
            setTextColor(color(R.color.text_heading))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        titleRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(10)
        })
        heroCard.addView(titleRow)
        fps = TextView(this).apply {
            setTextColor(color(R.color.text_muted))
            textSize = 12f
            setPadding(dp(20), dp(5), 0, 0)
        }
        heroCard.addView(fps)
        action = Button(this).apply {
            textSize = 17f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.accent_on_primary))
            setBackground(rounded(color(R.color.accent_primary), 14))
            setOnClickListener {
                if (MiniState.serviceActive) {
                    CarPlusBridgeService.stop(this@MiniActivity)
                    render()
                } else {
                    requestNotificationThenStart()
                }
            }
        }
        heroCard.addView(action, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        root.addView(heroCard, linearParams(bottom = 12))

        // ── 实时画面 ────────────────────────────────────────────
        val previewCard = card()
        val previewTitleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        previewTitleRow.addView(TextView(this).apply {
            text = "手机控制画面"
            setTextColor(color(R.color.text_heading))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fullscreenButton = Button(this).apply {
            text = "全屏"
            textSize = 12f
            isAllCaps = false
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(color(R.color.button_secondary_text))
            setBackground(rounded(color(R.color.button_secondary_surface), 999))
            setOnClickListener {
                if (MiniRuntime.pipeline == null || !MiniState.running) {
                    Toast.makeText(this@MiniActivity, "请先启动投送", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(Intent(this@MiniActivity, ControlActivity::class.java))
                }
            }
        }
        previewTitleRow.addView(fullscreenButton)
        previewCard.addView(previewTitleRow)
        previewCard.addView(TextView(this).apply {
            text = "触摸画面即可直接操控车机界面"
            setTextColor(color(R.color.text_muted))
            textSize = 12f
            setPadding(0, dp(3), 0, dp(10))
        })

        val previewFrame = FrameLayout(this).apply {
            setBackground(
                rounded(
                    color(R.color.surface_preview),
                    14,
                    stroke = color(R.color.stroke_preview),
                ),
            )
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val previewClip = FrameLayout(this).apply {
            setBackground(rounded(android.graphics.Color.BLACK, 10))
            clipToOutline = true
        }
        preview = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    surfaceTexture.setDefaultBufferSize(1280, 720)
                    attachPreview()
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) = Unit

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    detachPreview()
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
            setOnTouchListener { view, event -> handlePreviewTouch(view, event) }
        }
        previewClip.addView(preview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        previewFrame.addView(previewClip, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        previewHint = TextView(this).apply {
            text = "连接后画面显示在这里"
            setTextColor(color(R.color.text_on_video))
            textSize = 15f
            gravity = Gravity.CENTER
        }
        previewFrame.addView(previewHint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        previewCard.addView(previewFrame, LinearLayout.LayoutParams(-1, dp(180)))
        root.addView(previewCard, linearParams(bottom = 12))

        // ── 连接状态 ────────────────────────────────────────────
        val statusCard = card()
        statusCard.addView(sectionTitle("连接状态"))
        statusCard.addView(TextView(this).apply {
            text = "实时链路各环节状态"
            setTextColor(color(R.color.text_muted))
            textSize = 11f
            setPadding(0, dp(2), 0, dp(6))
        })
        rowVehicle = addStatusRow(statusCard, "车辆", "未选择")
        rowWifi = addStatusRow(statusCard, "Wi-Fi", "未连接")
        rowCar = addStatusRow(statusCard, "CarLife 画面", "未启动")
        rowPxc = addStatusRow(statusCard, "MotoPlay", "未启动")
        rowAudio = addStatusRow(statusCard, "音频", "未连接")
        rowBluetooth = addStatusRow(statusCard, "蓝牙 A2DP", "未连接")
        root.addView(statusCard, linearParams(bottom = 12))

        // ── 发送端 ──────────────────────────────────────────────
        val senderCard = card()
        senderCard.addView(sectionTitle("发送端"))
        senderCard.addView(TextView(this).apply {
            text = "自动探测并拉起${SenderRouter.displayName(this@MiniActivity)}，" +
                "在其界面选择「无线连接」即可进入车机模式。"
            setTextColor(color(R.color.text_body))
            textSize = 12f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(3), 0, dp(10))
        })
        carPlusButton = Button(this).apply {
            text = "重新启动${SenderRouter.displayName(this@MiniActivity)}"
            textSize = 14f
            isAllCaps = false
            setTextColor(color(R.color.button_secondary_text))
            setBackground(rounded(color(R.color.button_secondary_surface), 12))
            setOnClickListener {
                SenderRouter.launch(this@MiniActivity, MiniState::log)
            }
        }
        senderCard.addView(carPlusButton, LinearLayout.LayoutParams(-1, dp(46)))
        senderCard.addView(TextView(this).apply {
            text = "仅在投送过程中发送端被关闭时使用"
            setTextColor(color(R.color.text_muted))
            textSize = 11f
            setPadding(dp(4), dp(5), 0, 0)
        })
        root.addView(senderCard, linearParams(bottom = 12))

        // ── 声音与输出 ──────────────────────────────────────────
        val audioCard = card()
        audioCard.addView(sectionTitle("声音与输出"))
        audioCard.addView(TextView(this).apply {
            text = "只调整${Brand.CHINESE_NAME}接收到的 CarLife 音频；摩托车或耳机总音量仍由 A2DP 控制。"
            setTextColor(color(R.color.text_muted))
            textSize = 11f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(2), 0, dp(8))
        })
        val accentTint = ColorStateList.valueOf(color(R.color.accent_primary))
        mediaVolumeText = TextView(this).apply {
            text = "${AudioVolumeStore.current.mediaPercent}%"
            setTextColor(color(R.color.text_heading))
            textSize = 12f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        audioCard.addView(volumeRow("音乐", mediaVolumeText) {
            mediaVolumeSeek = SeekBar(this).apply {
                max = 100
                progress = AudioVolumeStore.current.mediaPercent
                progressTintList = accentTint
                thumbTintList = accentTint
                setOnSeekBarChangeListener(simpleSeekListener { value ->
                    AudioVolumeStore.setMedia(this@MiniActivity, value)
                    mediaVolumeText.text = "$value%"
                })
            }
            mediaVolumeSeek
        })
        navigationVolumeText = TextView(this).apply {
            text = "${AudioVolumeStore.current.navigationPercent}%"
            setTextColor(color(R.color.text_heading))
            textSize = 12f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        audioCard.addView(volumeRow("导航", navigationVolumeText) {
            navigationVolumeSeek = SeekBar(this).apply {
                max = 100
                progress = AudioVolumeStore.current.navigationPercent
                progressTintList = accentTint
                thumbTintList = accentTint
                setOnSeekBarChangeListener(simpleSeekListener { value ->
                    AudioVolumeStore.setNavigation(this@MiniActivity, value)
                    navigationVolumeText.text = "$value%"
                })
            }
            navigationVolumeSeek
        })
        duckSwitch = Switch(this).apply {
            text = "导航播报时自动压低音乐"
            setTextColor(color(R.color.text_body))
            textSize = 13f
            isChecked = AudioVolumeStore.current.duckMediaDuringNavigation
            setPadding(0, dp(6), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                AudioVolumeStore.setAutoDuck(this@MiniActivity, checked)
            }
        }
        audioCard.addView(duckSwitch)
        audioCard.addView(TextView(this).apply {
            text = "输出设备"
            setTextColor(color(R.color.text_label))
            textSize = 12f
            setPadding(0, dp(12), 0, dp(6))
        })
        audioCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            phoneRouteButton = routeButton("手机") {
                AudioVolumeStore.setOutputRoute(
                    this@MiniActivity,
                    AudioOutputRoute.PHONE,
                )
                if (!MiniState.serviceActive) {
                    MiniState.setAudio("手机 · 投送未启动", Stage.OFF)
                }
                renderAudioRouteButtons()
            }
            bikeRouteButton = routeButton("车机蓝牙") {
                requestBikeAudioRoute()
            }
            addView(phoneRouteButton, weighted())
            addView(bikeRouteButton, weighted(left = 10))
        }, LinearLayout.LayoutParams(-1, dp(42)))
        root.addView(audioCard, linearParams(bottom = 12))

        // ── 工具 ────────────────────────────────────────────────
        val toolsCard = card()
        toolsCard.addView(sectionTitle("工具"))
        scanButton = secondaryButton("扫描新车辆 / 重新配对") {
            if (MiniState.serviceActive) CarPlusBridgeService.stop(this@MiniActivity)
            startActivityForResult(Intent(this@MiniActivity, QrScanActivity::class.java), REQUEST_QR)
        }
        toolsCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(scanButton, weighted())
            addView(secondaryButton("手动 Wi-Fi") { showManualPairing() }, weighted(left = 10))
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        toolsCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondaryButton("蓝牙设置") {
                SystemLinks.openBluetoothSettings(this@MiniActivity)
            }, weighted())
            addView(secondaryButton("息屏保活") {
                SystemLinks.openBatterySettings(this@MiniActivity)
            }, weighted(left = 10))
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        toolsCard.addView(secondaryButton("导出诊断日志") { shareLogs() },
            LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        root.addView(toolsCard, linearParams(bottom = 12))

        // ── 快速上手 ────────────────────────────────────────────
        val guideCard = card()
        guideCard.addView(sectionTitle("快速上手"))
        guideCard.addView(TextView(this).apply {
            text = "1. 点「扫码添加并投送」，或以后点「连接已保存车辆」\n" +
                "2. 在${SenderRouter.displayName(this@MiniActivity)}中选择「无线连接」进入车机模式\n" +
                "3. 保持仪表在手机互联页面，断线后会自动重连\n" +
                "4. 在手机控制画面或全屏控制中直接触控操作"
            setTextColor(color(R.color.text_body))
            textSize = 13f
            setLineSpacing(dp(4).toFloat(), 1.15f)
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(guideCard, linearParams(bottom = 12))

        val scroll = ScrollView(this).apply {
            setBackground(background)
            isFillViewport = true
            addView(root)
        }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                root.setPadding(dp(16), bars.top + dp(12), dp(16), bars.bottom + dp(24))
            } else {
                @Suppress("DEPRECATION")
                root.setPadding(
                    dp(16),
                    insets.systemWindowInsetTop + dp(12),
                    dp(16),
                    insets.systemWindowInsetBottom + dp(24),
                )
            }
            insets
        }
        return scroll
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        setBackground(rounded(color(R.color.surface_card), 18))
        elevation = dp(2).toFloat()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.text_heading))
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun volumeRow(label: String, value: TextView, seek: () -> SeekBar): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(color(R.color.text_body))
            textSize = 13f
        }, LinearLayout.LayoutParams(dp(48), -2))
        row.addView(seek(), LinearLayout.LayoutParams(0, dp(40), 1f))
        row.addView(value, LinearLayout.LayoutParams(dp(46), -2))
        return row
    }

    private fun requestNotificationThenStart() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            AudioVolumeStore.current.outputRoute == AudioOutputRoute.BIKE_BLUETOOTH &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT in 29..32) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.ACCESS_COARSE_LOCATION
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissionsWithRationale(
                needed.toTypedArray(),
                PermissionPurpose.START_BRIDGE,
            )
            return
        }
        startWifiThenBridge()
    }

    private fun maybeShowInitialPermissionPrompt() {
        val prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INITIAL_PERMISSIONS_SHOWN, false)) return
        val includeBluetooth =
            AudioVolumeStore.current.outputRoute == AudioOutputRoute.BIKE_BLUETOOTH
        val missing = SystemLinks.missingRuntimePermissions(
            this,
            includeBluetooth = includeBluetooth,
        )
        if (missing.isEmpty()) {
            prefs.edit().putBoolean(KEY_INITIAL_PERMISSIONS_SHOWN, true).apply()
            return
        }
        CarDialogs.builder(this)
            .setTitle("允许${Brand.CHINESE_NAME}连接车机")
            .setMessage(
                "${Brand.CHINESE_NAME}需要以下权限：\n\n" +
                    "• 附近 Wi‑Fi：连接仪表 MotoPlay 热点\n" +
                    "• 蓝牙：选择车机 A2DP 声音时再授权\n" +
                    "• 通知：显示投送前台状态\n" +
                    "• 相机：扫描仪表配对二维码\n\n" +
                    "拒绝 Wi‑Fi 将无法完成投送；蓝牙和相机可以稍后授权。",
            )
            .setNegativeButton("稍后") { _, _ ->
                prefs.edit().putBoolean(KEY_INITIAL_PERMISSIONS_SHOWN, true).apply()
            }
            .setPositiveButton("继续授权") { _, _ ->
                prefs.edit().putBoolean(KEY_INITIAL_PERMISSIONS_SHOWN, true).apply()
                requestPermissionsWithRationale(
                    missing,
                    PermissionPurpose.FIRST_LAUNCH,
                )
            }
            .show()
    }

    private fun requestPermissionsWithRationale(
        permissions: Array<String>,
        purpose: PermissionPurpose,
    ) {
        permissionPurpose = purpose
        val requiresExplanation = permissions.any(::shouldShowRequestPermissionRationale)
        if (!requiresExplanation) {
            permissionLauncher.launch(permissions)
            return
        }
        CarDialogs.builder(this)
            .setTitle("需要设备访问权限")
            .setMessage(
                "${Brand.CHINESE_NAME}需要附近 Wi‑Fi 和蓝牙权限来连接车机。" +
                    "通知权限可让投送状态保持可见。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ -> permissionLauncher.launch(permissions) }
            .show()
    }

    private fun showInitialPermissionResult(result: Map<String, Boolean>) {
        val connectivityDenied = result.filterKeys {
            it == Manifest.permission.NEARBY_WIFI_DEVICES ||
                it == Manifest.permission.ACCESS_FINE_LOCATION ||
                it == Manifest.permission.ACCESS_COARSE_LOCATION
        }.filterValues { !it }.keys
        val bluetoothDenied = result[Manifest.permission.BLUETOOTH_CONNECT] == false
        val cameraDenied = result[Manifest.permission.CAMERA] == false
        when {
            connectivityDenied.isNotEmpty() -> showPermissionRecovery(connectivityDenied.toTypedArray())
            bluetoothDenied || cameraDenied -> {
                if (bluetoothDenied) {
                    AudioVolumeStore.setOutputRoute(this, AudioOutputRoute.PHONE)
                }
                val message = when {
                    bluetoothDenied && cameraDenied ->
                        "蓝牙和相机未授权；本次使用手机音频，可稍后扫码时授权相机。"
                    bluetoothDenied ->
                        "蓝牙未授权，本次使用手机音频；需要车机声音时可重新授权。"
                    else ->
                        "相机权限未授权，可稍后扫码时授权或使用“手动 Wi‑Fi”。"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPermissionRecovery(denied: Array<String>) {
        val permanent = denied.none(::shouldShowRequestPermissionRationale)
        val dialog = CarDialogs.builder(this)
            .setTitle("权限不足")
            .setMessage("无法连接车机，请授予 Wi‑Fi 和蓝牙权限。")
            .setNegativeButton("关闭", null)
        if (permanent) {
            dialog.setPositiveButton("打开应用设置") { _, _ ->
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }
        } else {
            dialog.setPositiveButton("重新授权") { _, _ ->
                requestPermissionsWithRationale(denied, PermissionPurpose.START_BRIDGE)
            }
        }
        dialog.show()
    }

    private fun startWifiThenBridge() {
        val vehicle = MotoVehiclePrefs.load(this)
        if (vehicle == null) {
            startActivityForResult(Intent(this, QrScanActivity::class.java), REQUEST_QR)
            return
        }
        MiniState.setVehicle(vehicle.displayName)
        MotoWifi.connect(
            context = this,
            vehicle = vehicle,
            onReady = { launchBridge() },
            onError = { error ->
                MiniState.update(error)
            },
        )
    }

    private fun requestBikeAudioRoute() {
        if (Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            selectBikeAudioRoute()
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
            CarDialogs.builder(this)
                .setTitle("需要车机蓝牙权限")
                .setMessage("授权后才能访问已配对的 CFMOTO，并选择车机 A2DP 声音。")
                .setNegativeButton("使用手机音频", null)
                .setPositiveButton("继续授权") { _, _ ->
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
                .show()
        } else {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun selectBikeAudioRoute() {
        AudioVolumeStore.setOutputRoute(this, AudioOutputRoute.BIKE_BLUETOOTH)
        if (!MiniState.serviceActive) {
            MiniState.setAudio("车机蓝牙 · 需要 A2DP", Stage.WAIT)
        }
        renderAudioRouteButtons()
    }

    private fun showBluetoothPermissionRecovery() {
        val permanent =
            !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)
        CarDialogs.builder(this)
            .setTitle("车机蓝牙不可用")
            .setMessage("已继续使用手机音频。若需要摩托车蓝牙声音，请授予蓝牙权限。")
            .setNegativeButton("继续使用手机", null)
            .setPositiveButton(if (permanent) "打开应用设置" else "重新授权") { _, _ ->
                if (permanent) {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                } else {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            .show()
    }

    private fun launchBridge() {
        CarPlusBridgeService.start(this)
        SenderRouter.launch(this, MiniState::log)
        render()
    }

    private fun showManualPairing() {
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(8), padding, 0)
        }
        val help = TextView(this).apply {
            text = "填写仪表显示的 Wi‑Fi 名称，例如 CFMOTO-4e5bc0；开放网络可留空密码。"
            setTextColor(color(R.color.dialog_message))
            textSize = 13f
            setLineSpacing(0f, 1.12f)
        }
        val ssid = EditText(this).apply {
            hint = "车机 Wi‑Fi 名称"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(color(R.color.dialog_input_text))
            setHintTextColor(color(R.color.dialog_input_hint))
            setBackgroundResource(R.drawable.bg_qitou_dialog_input)
            setPadding(dp(14), 0, dp(14), 0)
        }
        val password = EditText(this).apply {
            hint = "Wi‑Fi 密码（可选）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(color(R.color.dialog_input_text))
            setHintTextColor(color(R.color.dialog_input_hint))
            setBackgroundResource(R.drawable.bg_qitou_dialog_input)
            setPadding(dp(14), 0, dp(14), 0)
        }
        content.addView(help, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })
        content.addView(ssid, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(password, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(10)
        })
        CarDialogs.builder(this)
            .setTitle("手动连接车机 Wi‑Fi")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("连接") { _, _ ->
                val vehicle = MotoVehicle.manual(
                    ssid.text?.toString().orEmpty(),
                    password.text?.toString().orEmpty(),
                )
                if (vehicle == null) {
                    Toast.makeText(this, "请输入 Wi‑Fi 名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val appContext = applicationContext
                Thread {
                    val saved = MotoVehiclePrefs.save(appContext, vehicle)
                    runOnUiThread {
                        if (!saved) {
                            Toast.makeText(
                                this@MiniActivity,
                                "无法安全保存车辆信息",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@runOnUiThread
                        }
                        MiniState.setVehicle(vehicle.displayName)
                        if (MiniState.serviceActive) CarPlusBridgeService.stop(this@MiniActivity)
                        startWifiThenBridge()
                    }
                }.apply {
                    name = "vehicle-prefs-save"
                    isDaemon = true
                    start()
                }
            }
            .show()
    }

    private fun shareLogs() {
        val appContext = applicationContext
        Thread {
            val file = MiniState.exportLog(appContext)
            val uri = FileProvider.getUriForFile(
                appContext,
                "${packageName}.files",
                file,
            )
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "${Brand.CHINESE_NAME}日志")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    startActivity(Intent.createChooser(intent, "导出${Brand.CHINESE_NAME}日志"))
                }.onFailure {
                    Toast.makeText(this, "无法打开分享面板：${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply {
            name = "support-log-export"
            isDaemon = true
            start()
        }
    }

    private fun attachPreview() {
        val pipe = MiniRuntime.pipeline ?: return
        if (attached) return
        val texture = preview.surfaceTexture ?: return
        val surface = Surface(texture)
        previewSurface = surface
        pipe.attachPreview(surface, 1280, 720)
        attached = true
        previewHint.visibility = View.GONE
    }

    private fun detachPreview() {
        if (!attached) return
        runCatching { MiniRuntime.pipeline?.clearPreview() }
        runCatching { previewSurface?.release() }
        previewSurface = null
        attached = false
    }

    private fun handlePreviewTouch(view: View, event: MotionEvent): Boolean {
        val pointer = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            event.actionIndex
        } else {
            event.findPointerIndex(event.getPointerId(0)).coerceAtLeast(0)
        }
        val mapped = mapPoint(event.getX(pointer), event.getY(pointer), view.width, view.height)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (mapped != null) {
                lastX = mapped.first
                lastY = mapped.second
                MiniRuntime.receiver?.sendTouch(0, lastX, lastY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (mapped != null) {
                MiniRuntime.receiver?.sendTouchComposite(
                    MotionEvent.ACTION_POINTER_DOWN or (event.actionIndex shl 8),
                    mapped.first,
                    mapped.second,
                )
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount <= 1) {
                    if (mapped != null) {
                        lastX = mapped.first
                        lastY = mapped.second
                        MiniRuntime.receiver?.sendTouch(2, lastX, lastY)
                    }
                } else {
                    for (index in 0 until event.pointerCount) {
                        val point = mapPoint(event.getX(index), event.getY(index), view.width, view.height)
                            ?: continue
                        MiniRuntime.receiver?.sendTouchComposite(
                            MotionEvent.ACTION_MOVE or (index shl 8),
                            point.first,
                            point.second,
                        )
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (mapped != null) {
                MiniRuntime.receiver?.sendTouchComposite(
                    MotionEvent.ACTION_POINTER_UP or (event.actionIndex shl 8),
                    mapped.first,
                    mapped.second,
                )
            }
            MotionEvent.ACTION_UP -> MiniRuntime.receiver?.sendTouch(1, lastX, lastY)
            MotionEvent.ACTION_CANCEL -> MiniRuntime.receiver?.sendTouch(1, lastX, lastY)
        }
        return true
    }

    private fun mapPoint(x: Float, y: Float, w: Int, h: Int): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        val sourceAspect = 1280f / 480f
        val viewAspect = w.toFloat() / h
        val drawW: Float
        val drawH: Float
        if (sourceAspect > viewAspect) {
            drawW = w.toFloat()
            drawH = drawW / sourceAspect
        } else {
            drawH = h.toFloat()
            drawW = drawH * sourceAspect
        }
        val left = (w - drawW) / 2f
        val top = (h - drawH) / 2f
        if (x < left || x >= left + drawW || y < top || y >= top + drawH) return null
        return (((x - left) * 1280 / drawW).toInt().coerceIn(0, 1279)) to
            (((y - top) * 480 / drawH).toInt().coerceIn(0, 479))
    }

    private fun render() {
        if (!::status.isInitialized) return
        status.text = MiniState.status
        fps.text = if (MiniState.framesPerSecond > 0) {
            "${MiniState.framesPerSecond} fps · 1280×480 控制源 · 800×384 车机输出"
        } else {
            "等待 CarLife 画面"
        }
        statusDot.background = oval(
            if (MiniState.serviceActive) color(R.color.success) else color(R.color.status_off),
        )
        val vehicle = MotoVehiclePrefs.load(this)
        rowVehicle.text = vehicle?.displayName ?: MiniState.vehicleName.ifBlank { "未选择" }
        rowWifi.text = MiniState.wifiText
        rowCar.text = MiniState.carText
        rowPxc.text = MiniState.pxcText
        rowAudio.text = MiniState.audioText
        rowBluetooth.text = MiniState.bluetoothText
        rowWifi.setTextColor(stageColor(MiniState.wifiStage))
        rowCar.setTextColor(stageColor(MiniState.carStage))
        rowPxc.setTextColor(stageColor(MiniState.pxcStage))
        rowAudio.setTextColor(stageColor(MiniState.audioStage))
        rowBluetooth.setTextColor(stageColor(MiniState.bluetoothStage))
        action.text = if (MiniState.serviceActive) "停止投送" else {
            if (vehicle == null) "扫码添加并投送" else "连接已保存车辆"
        }
        action.setTextColor(
            if (MiniState.serviceActive) {
                color(R.color.text_on_stopped)
            } else {
                color(R.color.accent_on_primary)
            },
        )
        action.setBackground(
            rounded(
                if (MiniState.serviceActive) {
                    color(R.color.action_stopped)
                } else {
                    color(R.color.accent_primary)
                },
                14,
            ),
        )
        fullscreenButton.isEnabled = MiniState.running
        fullscreenButton.alpha = if (MiniState.running) 1f else 0.62f
        val carPlusAvailable = MiniState.serviceActive && MiniState.wifiStage == Stage.OK
        carPlusButton.isEnabled = carPlusAvailable
        carPlusButton.alpha = if (carPlusAvailable) 1f else 0.48f
        if (::phoneRouteButton.isInitialized) renderAudioRouteButtons()
        previewHint.visibility = if (attached && MiniState.framesPerSecond > 0) View.GONE else View.VISIBLE
        if (MiniState.running) attachPreview()
    }

    private fun addStatusRow(parent: LinearLayout, label: String, initial: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(color(R.color.text_label))
            textSize = 13f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(this).apply {
            text = initial
            setTextColor(color(R.color.text_body))
            textSize = 13f
            gravity = Gravity.END
        }
        row.addView(value, LinearLayout.LayoutParams(0, -2, 1.3f))
        parent.addView(row)
        return value
    }

    private fun secondaryButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(color(R.color.button_secondary_text))
        setBackground(rounded(color(R.color.button_secondary_surface), 12))
        setOnClickListener { click() }
    }

    private fun routeButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun renderAudioRouteButtons() {
        if (!::phoneRouteButton.isInitialized || !::bikeRouteButton.isInitialized) return
        val phoneSelected = AudioVolumeStore.current.outputRoute == AudioOutputRoute.PHONE
        styleRouteButton(phoneRouteButton, phoneSelected)
        styleRouteButton(bikeRouteButton, !phoneSelected)
    }

    private fun styleRouteButton(button: Button, selected: Boolean) {
        button.setTextColor(
            if (selected) {
                color(R.color.accent_on_primary)
            } else {
                color(R.color.button_secondary_text)
            },
        )
        button.setBackground(
            rounded(
                if (selected) {
                    color(R.color.accent_primary)
                } else {
                    color(R.color.button_secondary_surface)
                },
                12,
                if (selected) null else color(R.color.button_secondary_stroke),
            ),
        )
    }

    private fun simpleSeekListener(onChanged: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChanged(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun weighted(left: Int = 0) = LinearLayout.LayoutParams(0, -1, 1f).apply {
        leftMargin = dp(left)
    }

    private fun stageColor(stage: Stage): Int = when (stage) {
        Stage.OK -> color(R.color.success)
        Stage.WAIT -> color(R.color.warning)
        Stage.ERROR -> color(R.color.error)
        Stage.OFF -> color(R.color.status_off)
    }

    private fun linearParams(
        width: Int = LinearLayout.LayoutParams.MATCH_PARENT,
        height: Int = -2,
        bottom: Int = 0,
    ) = LinearLayout.LayoutParams(
        width,
        if (height > 0) dp(height) else height,
    ).apply { bottomMargin = dp(bottom) }

    private fun gradient() = CarAppTheme.canvas(this)

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = if (radius >= 999) {
            dp(24).toFloat()
        } else {
            dp(radius).toFloat()
        }
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun color(@ColorRes resource: Int): Int = CarAppTheme.color(this, resource)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        private const val REQUEST_QR = 303
        private const val APP_PREFS = "qitou_app"
        private const val KEY_INITIAL_PERMISSIONS_SHOWN = "initial_permissions_shown"
    }

    private enum class PermissionPurpose {
        FIRST_LAUNCH,
        START_BRIDGE,
    }
}
