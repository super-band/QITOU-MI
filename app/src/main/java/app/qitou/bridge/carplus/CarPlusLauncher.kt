// SPDX-License-Identifier: AGPL-3.0-or-later
package app.qitou.bridge.carplus

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager

/** Starts ColorOS Car+ and leaves the wireless CarLife choice to the system UI. */
object CarPlusLauncher {
    private val packages = listOf(
        "com.oplus.ocar",
        "com.coloros.ocar",
        "com.oppo.ocar",
        "com.oplus.car",
    )

    /** True when any known Car+ host package is installed and visible. */
    fun isInstalled(context: Context): Boolean = packages.any { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun launch(context: Context, log: (String) -> Unit): Boolean {
        var installedAny = false
        for (pkg in packages) {
            val installed = try {
                context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                true
            } catch (_: Exception) {
                false
            }
            if (!installed) continue
            installedAny = true
            log("[CAR+] installed host package: $pkg")

            if (pkg == "com.oplus.ocar") {
                val known = listOf(
                    "com.oplus.ocar.CarlifeActivity",
                    "com.oplus.ocar.settings.connect.ConnectTypeRouteActivity",
                    "com.oplus.ocar.carmode.CarModeActivity",
                )
                for (activity in known) {
                    try {
                        context.startActivity(
                            Intent().apply {
                                component = ComponentName(pkg, activity)
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                                )
                            },
                        )
                        log("[CAR+] launched known Car+ pairing entry: $activity")
                        return true
                    } catch (error: Exception) {
                        log("[CAR+] known Car+ entry $activity rejected: ${error.javaClass.simpleName}")
                    }
                }
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
                    if ("carlife" in name) score += 100
                    if ("pair" in name) score += 80
                    if ("connect" in name) score += 60
                    if ("wireless" in name || "wlan" in name) score += 50
                    if ("guide" in name) score += 30
                    if ("main" in name || "home" in name) score += 20
                    info to score
                }
                .sortedByDescending { it.second }
            log(
                "[CAR+] activities: " +
                    activities.take(24).joinToString {
                        "${it.name}${if (it.exported) "" else "(private)"}"
                    },
            )

            try {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                log("[CAR+] launched OPPO Car+ ($pkg)")
                return true
            } catch (_: Exception) {
            }

            for ((activity, score) in candidates) {
                if (score <= 0) continue
                try {
                    val intent = Intent().apply {
                        component = ComponentName(pkg, activity.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    }
                    context.startActivity(intent)
                    log("[CAR+] launched Car+ activity ${activity.name} (score=$score)")
                    return true
                } catch (error: Exception) {
                    log("[CAR+] activity ${activity.name} failed: ${error.javaClass.simpleName}")
                }
            }
        }
        if (installedAny) {
            log("[CAR+] OPPO Car+ has no launcher entry; using its CarLife cast service directly")
        } else {
            log("[CAR+] OPPO Car+ package not visible/installed")
        }
        return false
    }
}
