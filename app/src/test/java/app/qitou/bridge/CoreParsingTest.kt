package app.qitou.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreParsingTest {
    @Test
    fun parsesClassicMotoPlayQr() {
        val vehicle = MotoVehicle.parse(
            "http://www.carbit.com.cn/?modelid=450mt&action=9&" +
                "ssid=CFMOTO-4e5bc0&pwd=secret&auth=wpa2-psk&name=MotoPlay",
        )

        assertNotNull(vehicle)
        assertEquals("CFMOTO-4e5bc0", vehicle!!.ssid)
        assertEquals("secret", vehicle.password)
        assertEquals("450mt", vehicle.modelId)
        assertEquals(9, vehicle.action)
    }

    @Test
    fun parsesMotoMoriniHashSeparatedQr() {
        val vehicle = MotoVehicle.parse(
            "http://admin.motomorini.com/app.html?Wifi=ML174167#12345678#dc0d30da1b6c" +
                "&MachineID=dc0d30da1b6c&ProductID=00297",
        )

        assertNotNull(vehicle)
        assertEquals("ML174167", vehicle!!.ssid)
        assertEquals("12345678", vehicle.password)
        assertEquals("00297", vehicle.modelId)
        assertEquals("dc:0d:30:da:1b:6c", vehicle.mac)
    }

    @Test
    fun treatsExplicitEmptyPasswordAsOpenNetwork() {
        val vehicle = MotoVehicle.parse(
            "https://pair.local/?ssid=OpenDash&pwd=",
        )

        assertNotNull(vehicle)
        assertTrue(vehicle!!.isOpenNetwork)
    }

    @Test
    fun defaultsAudioToPhoneAndPersistsUnknownAsPhone() {
        assertEquals(AudioOutputRoute.PHONE, AudioOutputRoute.fromStorage(null))
        assertEquals(AudioOutputRoute.PHONE, AudioOutputRoute.fromStorage("unknown"))
        assertEquals(AudioOutputRoute.BIKE_BLUETOOTH, AudioOutputRoute.fromStorage("bike"))
        assertFalse(
            AudioVolumeSettings().outputRoute == AudioOutputRoute.BIKE_BLUETOOTH,
        )
    }
}
