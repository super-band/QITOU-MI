// SPDX-License-Identifier: AGPL-3.0-or-later
package app.qitou.bridge.sender

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 百度 CarLife+（com.baidu.carlife）发送端。品牌无关，小米及其他非 OPPO 机型
 * 均可使用。拉起后由用户在 CarLife+ 界面选择「无线连接」进入车机模式；其
 * CarLife 发送引擎即会在本机 7240/8240/9240/9241/9340 端口暴露标准车机端协议。
 */
object CarLifeSender : Sender {
    override val id = "carlife"
    override val displayName = "百度 CarLife+"
    override val wirelessHint = "请在百度 CarLife+ 中选择「无线连接」"

    private val packages = listOf(
        "com.baidu.carlife",
    )

    override fun isInstalled(context: Context): Boolean =
        packages.any { isSenderPackageInstalled(context, it) }

    override fun launch(context: Context, log: (String) -> Unit): Boolean {
        for (pkg in packages) {
            if (!isSenderPackageInstalled(context, pkg)) continue
            log("[SENDER] installed CarLife+ package: $pkg")

            // 首选启动入口：进入主界面，由用户点「无线连接」。
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                    )
                    context.startActivity(intent)
                    log("[SENDER] launched 百度 CarLife+ ($pkg)，请在其界面选择「无线连接」")
                    return true
                }
            } catch (error: Exception) {
                log("[SENDER] CarLife+ launcher entry rejected: ${error.javaClass.simpleName}")
            }

            val activities = try {
                context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                    .activities
                    ?.toList()
                    .orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            log(
                "[SENDER] CarLife+ activities: " +
                    activities.take(24).joinToString {
                        "${it.name}${if (it.exported) "" else "(private)"}"
                    },
            )
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
                    log("[SENDER] launched CarLife+ activity ${activity.name} (score=$score)")
                    return true
                } catch (error: Exception) {
                    log("[SENDER] CarLife+ activity ${activity.name} failed: ${error.javaClass.simpleName}")
                }
            }
        }
        log("[SENDER] 百度 CarLife+（com.baidu.carlife）未安装或无法拉起")
        return false
    }
}
