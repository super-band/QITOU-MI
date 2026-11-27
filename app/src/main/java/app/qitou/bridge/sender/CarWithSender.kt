// SPDX-License-Identifier: AGPL-3.0-or-later
package app.qitou.bridge.sender

import android.content.Context

/**
 * 小米 CarWith（com.miui.carlink）发送端。CarWith 的「CarLife 组件」在
 * 「CarLife → 无线连接」页面会暴露标准 CarLife 车机端协议（7240/8240/9240/
 * 9241/9340），接收端与其他发送端一致。小米/Redmi 机型（HyperOS）自带
 * CarWith，无需另装百度 CarLife+。
 */
object CarWithSender : Sender {
    override val id = "carwith"
    override val displayName = "小米 CarWith"
    override val wirelessHint = "请在小米 CarWith 中进入「CarLife → 无线连接」"

    private const val PKG = "com.miui.carlink"

    override fun isInstalled(context: Context): Boolean =
        isSenderPackageInstalled(context, PKG)

    override fun launch(context: Context, log: (String) -> Unit): Boolean {
        if (!isSenderPackageInstalled(context, PKG)) {
            log("[SENDER] 小米 CarWith（$PKG）未安装")
            return false
        }
        log("[SENDER] installed CarWith package: $PKG")
        val ok = launchSenderApp(context, PKG, "小米 CarWith", log)
        if (ok) {
            log("[SENDER] 小米 CarWith 已拉起，请在其界面进入「CarLife → 无线连接」")
        } else {
            log("[SENDER] 小米 CarWith（$PKG）无法拉起")
        }
        return ok
    }
}
