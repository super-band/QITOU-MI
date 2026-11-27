package app.qitou.bridge

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VehicleSecretStoreTest {
    @Test
    fun encryptedVehicleRoundTripsWithoutPlaintextSecrets() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MotoVehiclePrefs.clear(context)
        val vehicle = MotoVehicle(
            ssid = "CFMOTO-test",
            password = "test-secret",
            mac = "aa:bb:cc:dd:ee:ff",
            modelId = "test",
        )

        assertTrue(MotoVehiclePrefs.save(context, vehicle))
        val loaded = MotoVehiclePrefs.load(context)
        assertNotNull(loaded)
        assertEquals("CFMOTO-test", loaded!!.ssid)
        assertEquals("test-secret", loaded.password)

        val prefs = context.getSharedPreferences("qitou_vehicle", android.content.Context.MODE_PRIVATE)
        assertNull(prefs.getString("password", null))
        assertNull(prefs.getString("raw", null))
        val cipherText = prefs.getString("password_cipher", null)
        assertNotNull(cipherText)
        assertFalse(cipherText!!.contains("test-secret"))

        MotoVehiclePrefs.clear(context)
    }
}
