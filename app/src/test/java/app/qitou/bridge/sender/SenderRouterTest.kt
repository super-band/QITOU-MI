package app.qitou.bridge.sender

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * SenderRouter 识别逻辑回归测试。
 *
 * 背景：2026-11-27 之前只认 com.baidu.carlife 与 OPPO Car+，小米 CarWith
 * （com.miui.carlink）被当成「未安装发送端」。本组用例锁定：CarWith 可识别、
 * 优先级正确、缺失时提示包含 CarWith。
 */
class SenderRouterTest {

    /** 构造只认得 [installed] 这几个包的 Context；getPackageInfo 对其余包抛异常。 */
    private fun contextWith(vararg installed: String): Context {
        val pm = Mockito.mock(PackageManager::class.java)
        Mockito.`when`(pm.getPackageInfo(Mockito.anyString(), Mockito.anyInt()))
            .thenAnswer { invocation ->
                val pkg = invocation.arguments[0] as String
                if (installed.contains(pkg)) {
                    val info = Mockito.mock(PackageInfo::class.java)
                    info.packageName = pkg
                    info
                } else {
                    throw RuntimeException("package not found: $pkg")
                }
            }
        val ctx = Mockito.mock(Context::class.java)
        Mockito.`when`(ctx.packageManager).thenReturn(pm)
        return ctx
    }

    @Test
    fun detectsNothingWhenNoSenderInstalled() {
        assertNull(SenderRouter.detect(contextWith()))
    }

    @Test
    fun carWithIsRecognized() {
        val sender = SenderRouter.detect(contextWith("com.miui.carlink"))
        assertNotNull(sender)
        assertEquals("carwith", sender!!.id)
        assertEquals("小米 CarWith", sender.displayName)
        assertTrue(sender.wirelessHint.contains("CarLife"))
    }

    @Test
    fun carWithWinsOverBaiduCarLife() {
        val sender = SenderRouter.detect(contextWith("com.miui.carlink", "com.baidu.carlife"))
        assertNotNull(sender)
        assertEquals("carwith", sender!!.id)
    }

    @Test
    fun baiduCarLifeIsFallback() {
        val sender = SenderRouter.detect(contextWith("com.baidu.carlife"))
        assertNotNull(sender)
        assertEquals("carlife", sender!!.id)
        assertEquals("百度 CarLife+", sender.displayName)
    }

    @Test
    fun wirelessHintGuidesUserForDetectedSender() {
        val hint = SenderRouter.wirelessHint(contextWith("com.miui.carlink"))
        assertTrue(hint.contains("CarLife → 无线连接"))
    }

    @Test
    fun wirelessHintFallbackWhenNothingInstalled() {
        val hint = SenderRouter.wirelessHint(contextWith())
        assertTrue(hint.contains("无线连接"))
    }

    @Test
    fun launchReportsMissingSenderWithCarWithHint() {
        val logs = mutableListOf<String>()
        val ok = SenderRouter.launch(contextWith(), { logs.add(it) }, force = true)
        assertFalse(ok)
        val all = logs.joinToString("\n")
        assertTrue(all.contains("小米 CarWith"))
        assertTrue(all.contains("com.miui.carlink"))
    }
}
