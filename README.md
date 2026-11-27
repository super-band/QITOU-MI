# 骑投 QITOU

骑投 QITOU is an Android-only bridge from OPPO Car+ to a CFMOTO 450MT MotoPlay
dashboard. It does not use Android Auto, Google Maps, CarPlay, Shizuku, root, an
accessibility service, or an overlay window.

```text
OPPO Car+ / CarLife sender
  -> local CarLife head-unit ports 7240/8240/9240/9241/9340
  -> H.264 decode and in-app controller Surface
  -> 800x384 H.264, 80 px dashboard-safe bottom inset
  -> CFMOTO EasyConn/PXC
  -> 450MT MotoPlay
```

## Implemented

- One-page CarWithin-style UI with a built-in phone control Surface.
- Touch and two-finger composite touch forwarding to OPPO Car+.
- Immersive landscape full-screen controller inside the APK; no floating overlay.
- MotoPlay QR scanning with automatic Wi-Fi join.
- Manual SSID/password entry and open-network support.
- Automatic Wi-Fi recovery after a dashboard sleep or reboot.
- Car+ video reconnection and encoder re-initialization.
- PXC listener, probe, heartbeat, and reconnect supervisor.
- Media and navigation PCM playback from CarLife ports 9240/9241.
- Explicit **phone** or **bike Bluetooth A2DP** output selection. Phone is the default
  and follows Android's current media route, including personal Bluetooth headsets;
  bike mode never silently falls back to the phone.
- Independent in-app music/navigation volume sliders, plus navigation auto-ducking.
- A2DP route preference and a paired-CFMOTO reconnection keeper.
- Partial wake lock and low-latency Wi-Fi lock for real screen-off streaming.
- Per-stage vehicle, Wi-Fi, Car+, MotoPlay, audio, and Bluetooth status.
- Redacted support-log export through the Android share sheet.
- Foreground notification with a stop action.
- "Open OPPO Car+" is enabled only while 骑投 is connected and running; it is a
  recovery entry for when the Car+ Activity was closed.
- Built-in About page with the runtime version, open-source credits, the complete
  AGPL-3.0-only license text, and third-party notices.

## Credits and license

Thanks to the [CarWithin project](https://github.com/xmz28/CarWithin) and its
author [xmz28](https://github.com/xmz28). CarWithin's iOS + CarPlay/CarLife
integration work provided useful product-design and protocol-research
inspiration for 骑投 QITOU.

骑投 QITOU also contains or adapts work from OpenCfMoto, CarPToCarL, CarVeX, and
other open-source projects. See `NOTICE` and `app/src/main/assets/licenses/` for
the complete notices.

骑投 QITOU is distributed under the GNU Affero General Public License v3.0 only
(`AGPL-3.0-only`). The complete license is bundled in the APK and available from
the in-app About page. Redistributed modified versions must preserve attribution
and provide the Corresponding Source as required by AGPL.

## First run

1. On the dashboard, open the MotoPlay / phone-link QR page.
2. Open 骑投 and tap **扫码并启动**.
3. Approve camera, nearby Wi-Fi, Bluetooth, and notification access.
4. 骑投 joins the dashboard Wi-Fi and opens OPPO Car+.
5. In OPPO Car+, choose **CarLife 无线连接** and tap **连接**.
6. Keep the dashboard on its MotoPlay page.
7. Use **手机控制画面** or **全屏控制** on the phone.
8. Allow battery-optimization exclusion from **息屏保活** if ColorOS stops the
   bridge after the screen turns off.

The first MotoPlay connection may take a few seconds while Car+ starts its local
CarLife sender. A wrong or non-pairing QR is reported without trying to save it.

## Build

Release signing credentials are intentionally not stored in the repository. Put the
keystore outside the checkout and create
`%USERPROFILE%\.android\qitou-release.properties`:

```properties
storeFile=C:/Users/you/.android/qitou-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=qitou
keyPassword=YOUR_KEY_PASSWORD
```

The same values can be supplied through `QITOU_RELEASE_STORE_FILE`,
`QITOU_RELEASE_STORE_PASSWORD`, `QITOU_RELEASE_KEY_ALIAS`, and
`QITOU_RELEASE_KEY_PASSWORD` in CI.

```powershell
$env:JAVA_HOME='C:\path\to\jdk-17'
$env:ANDROID_HOME='C:\path\to\android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :app:lintRelease :app:testReleaseUnitTest :app:assembleRelease --no-daemon
```

Output: `app/build/outputs/apk/release/app-release.apk`.

## Safety and limitations

- Dashboard interaction is intentionally phone-only; 骑投 does not depend on
  a motorcycle touchscreen or handlebar buttons.
- CFMOTO PXC is reverse-engineered. A future dashboard firmware update can require
  protocol changes.
- Map SDK keys are not stored by 骑投. OPPO Car+ and the installed map app
  own their map configuration.
- The app cannot bypass ColorOS's first-time Wi-Fi-confirmation UI. Once the network
  has been approved, automatic reconnection is attempted in the background.
- Logs redact Wi-Fi passwords and pairing fragments, but review any support log
  before sharing it.
