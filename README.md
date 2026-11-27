# 骑投 QITOU

骑投（QITOU）是一个 Android 手机端桥接工具，用于将 OPPO Car+ / CarLife
画面和声音投送到 CFMOTO 450MT 的 MotoPlay 仪表。

项目不依赖 Android Auto、Google 地图、CarPlay、Shizuku、Root、无障碍服务
或悬浮窗。

最新安装包请前往
[GitHub Releases](https://github.com/yuppiesnotzhuhao/QITOU/releases/latest)
下载。

## 工作链路

```text
OPPO Car+ / CarLife 发送端
  -> 本机 CarLife 车机端口 7240/8240/9240/9241/9340
  -> H.264 解码与应用内控制画面
  -> 800x384 H.264，底部保留 80 px 仪表安全区
  -> CFMOTO EasyConn / PXC
  -> 450MT MotoPlay
```

## 已实现功能

- 内置手机控制画面的单页界面，不依赖悬浮窗。
- 支持单指和双指触控转发到 OPPO Car+。
- 应用内横屏全屏控制。
- MotoPlay 二维码扫描与自动连接 Wi-Fi。
- 支持手动输入 SSID、密码和开放网络。
- 仪表休眠或重启后自动恢复 Wi-Fi。
- Car+ 视频断线重连与编码器重新初始化。
- PXC 监听、探测、心跳和自动重连。
- 播放 CarLife 端口 9240/9241 的音乐和导航 PCM 音频。
- 支持 **手机** 和 **车机蓝牙 A2DP** 两种声音输出：
  - 默认使用“手机”，跟随 Android 当前媒体输出，可能是有线耳机、蓝牙耳机或扬声器。
  - “车机蓝牙”只选择已连接的 CFMOTO A2DP，未连接时不会静默回退到手机。
- 音乐、导航独立音量控制，以及导航播报时自动压低音乐。
- 车机 A2DP 路由偏好和已配对 CFMOTO 自动重连。
- 使用 Partial WakeLock 和低延迟 Wi-Fi Lock 维持息屏投送。
- 分别显示车辆、Wi-Fi、Car+、MotoPlay、音频和蓝牙状态。
- 通过 Android 分享面板导出已脱敏的支持日志。
- 带“停止”操作的前台服务通知。
- “重新启动 OPPO Car+”仅在投送运行期间可用，用于恢复被关闭的 Car+ 页面。
- 内置关于页面，包含版本、开源致谢、完整 AGPL-3.0-only 协议和第三方声明。
- 支持 Android 17 深色和浅色主题。

## 首次使用

1. 在摩托车仪表上打开 MotoPlay / 手机互联二维码页面。
2. 打开骑投，点击 **扫码添加并投送**。
3. 按系统提示授权附近设备、通知和相机权限。
4. 骑投连接仪表 Wi-Fi 后会自动打开 OPPO Car+。
5. 在 OPPO Car+ 中选择 **CarLife 无线连接** 并点击 **连接**。
6. 保持仪表停留在 MotoPlay 页面。
7. 在手机上使用 **手机控制画面** 或 **全屏控制** 操作 Car+。
8. 如果 ColorOS 在息屏后停止连接，请在 **息屏保活** 中打开系统电池设置。

车机蓝牙权限只会在选择“车机蓝牙”时申请；如果不授权，应用会继续使用手机
音频，不会阻止投送。

首次连接 MotoPlay 可能需要等待数秒，因为 Car+ 需要先启动本地 CarLife
发送端。无法识别的二维码不会保存。

## 构建

Release 签名材料不会提交到仓库。请把 keystore 放在仓库目录之外，并创建
`%USERPROFILE%\.android\qitou-release.properties`：

```properties
storeFile=C:/Users/you/.android/qitou-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=qitou
keyPassword=YOUR_KEY_PASSWORD
```

CI 也可以使用以下环境变量：

- `QITOU_RELEASE_STORE_FILE`
- `QITOU_RELEASE_STORE_PASSWORD`
- `QITOU_RELEASE_KEY_ALIAS`
- `QITOU_RELEASE_KEY_PASSWORD`

```powershell
$env:JAVA_HOME='C:\path\to\jdk-17'
$env:ANDROID_HOME='C:\path\to\android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :app:lintRelease :app:testReleaseUnitTest :app:assembleRelease --no-daemon
```

输出文件：

`app/build/outputs/apk/release/app-release.apk`

## 注意事项与限制

- 仪表交互仅通过手机完成，不依赖摩托车触摸屏或车把按键。
- CFMOTO PXC 为互操作性实现；后续仪表固件更新可能需要修改协议。
- 骑投不保存高德或百度地图 SDK Key，地图配置由 OPPO Car+ 和已安装的地图应用管理。
- 应用无法绕过 ColorOS 首次连接 Wi-Fi 时的确认界面。网络首次批准后，后续重连会在后台自动进行。
- 日志会脱敏 Wi-Fi 密码和配对信息，但分享前仍建议检查内容。
- 车机蓝牙和本地网络行为仍可能受 ColorOS 或手机厂商策略影响。

## 致谢

感谢 [CarWithin 项目](https://github.com/xmz28/CarWithin) 及作者
[xmz28](https://github.com/xmz28)。CarWithin 的 iOS + CarPlay/CarLife
互联方案为骑投的产品设计和协议研究提供了启发。

骑投还包含或改编了 OpenCfMoto、CarPToCarL、CarVeX 等开源项目。完整声明请查看
`NOTICE` 和 `app/src/main/assets/licenses/`。

## 开源许可证

骑投以 GNU Affero General Public License v3.0 only
（`AGPL-3.0-only`）开源。

完整协议已包含在 APK 和应用内关于页面中。分发修改版时，必须保留版权和许可声明，
并按照 AGPL 提供 Corresponding Source。
