// SPDX-License-Identifier: AGPL-3.0-or-later
package app.qitou.bridge.sender

import android.content.Context
import app.qitou.bridge.carplus.CarPlusLauncher

/** OPPO Car+ 发送端：沿用上游检测与拉起逻辑，作为 CarLife+ 之外的兼容路径。 */
object CarPlusSender : Sender {
    override val id = "carplus"
    override val displayName = "OPPO Car+"
    override val wirelessHint = "请在 OPPO Car+ 中开启无线连接"

    override fun isInstalled(context: Context): Boolean = CarPlusLauncher.isInstalled(context)

    override fun launch(context: Context, log: (String) -> Unit): Boolean =
        CarPlusLauncher.launch(context, log)
}
