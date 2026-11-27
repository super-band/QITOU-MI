# 构建与签名

## 环境要求

- JDK 17
- Android SDK 36
- 可访问 Google Maven 和 Maven Central

## 构建

```powershell
$env:JAVA_HOME='C:\path\to\jdk-17'
$env:ANDROID_HOME='C:\path\to\android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :app:lintRelease :app:testReleaseUnitTest :app:assembleRelease --no-daemon
```

APK 输出：

`app/build/outputs/apk/release/app-release.apk`

## Release 签名

签名材料不能提交到仓库。请把 keystore 放在仓库目录之外，并创建
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

请安全备份 release keystore 和密码。丢失后将无法发布与现有版本签名兼容的更新。
