// SPDX-License-Identifier: AGPL-3.0-or-later
package app.qitou.bridge.sender

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * A phone-side CarLife sender app. 百度 CarLife+、小米 CarWith（CarLife 组件）
 * and OPPO Car+ expose the standard CarLife head-unit ports on loopback once
 * their wireless connection page is active, so the receiver side is identical
 * for every sender.
 */
interface Sender {
    /** Stable id used in logs. */
    val id: String

    /** User-facing app name. */
    val displayName: String

    /** How to turn on wireless car mode inside this sender app. */
    val wirelessHint: String

    /** True when at least one candidate package is installed and visible. */
    fun isInstalled(context: Context): Boolean

    /** Brings the sender app up so the user can start its wireless CarLife mode. */
    fun launch(context: Context, log: (String) -> Unit): Boolean
}

/** True when the package is installed and visible to this app. */
internal fun isSenderPackageInstalled(context: Context, pkg: String): Boolean = try {
    context.packageManager.getPackageInfo(pkg, 0)
    true
} catch (_: Exception) {
    false
}

/**
 * 拉起指定包的入口界面：先试 launcher Activity，失败时按导出 Activity
 * 名称打分（wireless/wlan/wifi/connect/carlife…），挑最接近「无线连接」
 * 的一项。供各 Sender 的 launch() 共用。
 */
internal fun launchSenderApp(
    context: Context,
    pkg: String,
    label: String,
    log: (String) -> Unit,
): Boolean {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            )
            context.startActivity(intent)
            log("[SENDER] launched $label ($pkg)")
            return true
        }
    } catch (error: Exception) {
        log("[SENDER] $label launcher entry rejected: ${error.javaClass.simpleName}")
    }
    val activities = try {
        context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            .activities
            ?.toList()
            .orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
    val candidates = activities
        .filter { it.exported }
        .map { info ->
            val name = info.name.lowercase()
            var score = 0
            if ("wireless" in name || "wlan" in name || "wifi" in name) score += 100
            if ("connect" in name) score += 80
            if ("carlife" in name) score += 60
            if ("guide" in name) score += 30
            if ("main" in name || "home" in name) score += 20
            info to score
        }
        .sortedByDescending { it.second }
    for ((activity, score) in candidates) {
        if (score <= 0) continue
        try {
            context.startActivity(
                Intent().apply {
                    component = ComponentName(pkg, activity.name)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                    )
                },
            )
            log("[SENDER] launched $label activity ${activity.name} (score=$score)")
            return true
        } catch (error: Exception) {
            log("[SENDER] $label activity ${activity.name} failed: ${error.javaClass.simpleName}")
        }
    }
    return false
}
