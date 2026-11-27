// SPDX-License-Identifier: AGPL-3.0-or-later
package app.qitou.bridge.sender

import android.content.Context
import android.os.SystemClock

/**
 * 发送端路由器：自动探测已安装的 CarLife 发送端并拉起。
 *
 * 优先级：小米 CarWith（本分支目标机型自带，CarLife 组件）优先，
 * 百度 CarLife+（品牌无关）次之，OPPO Car+ 作为兜底兼容路径。
 */
object SenderRouter {
    private const val LAUNCH_COOLDOWN_MS = 5_000L

    private val senders: List<Sender> = listOf(
        CarWithSender,
        CarLifeSender,
        CarPlusSender,
    )

    @Volatile private var lastLaunchAt = 0L

    /** 返回优先级最高且已安装的发送端；均未安装时返回 null。 */
    fun detect(context: Context): Sender? = senders.firstOrNull { it.isInstalled(context) }

    /** 用于界面文案的发送端名称；未检测到时返回「发送端」。 */
    fun displayName(context: Context): String = detect(context)?.displayName ?: "发送端"

    /** 指导用户在发送端里开启无线车机模式的操作提示。 */
    fun wirelessHint(context: Context): String =
        detect(context)?.wirelessHint ?: "请在发送端中开启「无线连接」"

    /**
     * 探测并拉起发送端；全部未安装或拉起失败时返回 false。
     *
     * `force = false` 时带冷却时间，供前台服务自动补拉使用，
     * 避免与用户手动触发的拉起重复；用户显式操作应传 `force = true`。
     */
    fun launch(context: Context, log: (String) -> Unit, force: Boolean = true): Boolean {
        if (!force) {
            val last = lastLaunchAt
            if (last != 0L && SystemClock.elapsedRealtime() - last < LAUNCH_COOLDOWN_MS) {
                log("[SENDER] 发送端刚已拉起，跳过重复启动")
                return true
            }
        }
        val installed = senders.filter { it.isInstalled(context) }
        if (installed.isEmpty()) {
            log("[SENDER] 未安装发送端：请安装小米 CarWith（com.miui.carlink）或百度 CarLife+（com.baidu.carlife）")
            return false
        }
        if (installed.size > 1) {
            log("[SENDER] 已安装多个发送端：" + installed.joinToString { it.displayName })
        }
        val sender = installed.first()
        log("[SENDER] 选用发送端：${sender.displayName}")
        val ok = sender.launch(context, log)
        if (ok) lastLaunchAt = SystemClock.elapsedRealtime()
        return ok
    }
}
