// SPDX-License-Identifier: AGPL-3.0-or-later
package app.qitou.bridge.sender

import android.content.Context

/**
 * A phone-side CarLife sender app. Both 百度 CarLife+ and OPPO Car+ expose the
 * standard CarLife head-unit ports on loopback once their wireless connection
 * page is active, so the receiver side is identical for every sender.
 */
interface Sender {
    /** Stable id used in logs. */
    val id: String

    /** User-facing app name. */
    val displayName: String

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
