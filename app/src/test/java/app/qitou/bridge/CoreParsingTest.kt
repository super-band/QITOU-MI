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
    fun parsesCfmotoDownloadUrlQr() {
        val vehicle = MotoVehicle.parse(
            "https://c.cfmoto.com/app/download.html?modelid=37416&sn=gYTG&action=9&" +
                "ssid=CFMOTO-18ccd1&pwd=20d3024968&auth=wpa2-psk&" +
                "mac=6C:F5:4A:14:8E:B5&name=CFMOTO-18ccd1",
        )

        assertNotNull(vehicle)
        assertEquals("CFMOTO-18ccd1", vehicle!!.ssid)
        assertEquals("20d3024968", vehicle.password)
        assertEquals("6c:f5:4a:14:8e:b5", vehicle.mac)
        assertEquals("37416", vehicle.modelId)
        assertEquals("CFMOTO-18ccd1", vehicle.name)
        assertEquals(9, vehicle.action)
        assertEquals("gYTG", vehicle.sn)
    }

    @Test
    fun parsesCfmotoQrWithPercentEncodedMac() {
        // 仪表二维码原始内容（冒号以 %3A 编码，未经过扫码工具解码）
        val vehicle = MotoVehicle.parse(
            "https://c.cfmoto.com/app/download.html?modelid=37416&sn=gYTG&action=9&" +
                "ssid=CFMOTO-18ccd1&pwd=20d3024968&auth=wpa2-psk&" +
                "mac=6C%3AF5%3A4A%3A14%3A8E%3AB5&name=CFMOTO-18ccd1",
        )

        assertNotNull(vehicle)
        assertEquals("CFMOTO-18ccd1", vehicle!!.ssid)
        assertEquals("20d3024968", vehicle.password)
        assertEquals("6c:f5:4a:14:8e:b5", vehicle.mac)
        assertEquals("gYTG", vehicle.sn)
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
