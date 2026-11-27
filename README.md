# 骑投 · 小米适配版（QITOU-Mi）

把手机画面和声音投送到 **春风 CFMOTO 450MT 摩托车 MotoPlay 仪表** 的 Android 桥接工具。

本仓库基于 [yuppiesnotzhuhao/QITOU](https://github.com/yuppiesnotzhuhao/QITOU)（AGPL-3.0）二次开发，
将发送端从 OPPO Car+ 扩展为 **百度 CarLife+**，使小米及其他非 OPPO 机型可用。

> 与 OPPO、百度、CFMOTO、Google 无任何隶属或官方合作关系。协议互通实现借鉴
> OpenCfMoto、CarPToCarL、CarVeX 等开源项目。

## 功能概览

- 将 CarLife 画面（H.264）与声音（音乐 / 导航双音量）投送到 450MT 仪表
- 仪表端触控回传手机
- 扫码 / 热点配对
- 不依赖 Root、Shizuku、无障碍、悬浮窗，也不需要 Android Auto / CarPlay
- 输出画布 800×384，底部保留 80 px 仪表安全区

## 与上游版本的区别

上游 QITOU 仅支持 OPPO Car+ 作为发送端，其中协议层（CarLife 车机端 + CFMOTO PXC）
本身与手机品牌无关。本分支只改动发送端适配层：

| 模块 | 本分支改动 |
|---|---|
| 发送端抽象 `sender/`（新增） | 支持百度 CarLife+（`com.baidu.carlife`），自动探测并拉起 |
| `CarPlusBridgeService` | 发送端检测与拉起改走 `SenderRouter` |
| `CarPlusVideoReceiver` / `MiniVideoPipeline` / `MotoWifi` / `MotoLink` / `MotoAudioPlayer` | 不改动 |

媒体链路不变：

```
百度 CarLife+ / OPPO Car+ 发送端
  → 本机 CarLife 车机端口 7240/8240/9240/9241/9340
  → H.264 解码与应用内控制画面
  → 800x384 H.264，底部保留 80 px 仪表安全区
  → CFMOTO EasyConn / PXC
  → 450MT MotoPlay
```

## 环境要求

- Android 手机（Android 10+，小米机型需 HyperOS / MIUI）
- 已安装 **百度 CarLife+** 并登录（小米手机应用商店或官网下载）
- CFMOTO 450MT（MotoPlay，2024 款）
- 权限：附近 Wi-Fi、通知、相机（扫码）、蓝牙连接、定位（Android 12-）

## 安装与使用

1. 安装本应用，授予上述权限
2. **HyperOS 必做**：设置 → 应用 → 骑投 → 省电策略改为"无限制"，并允许自启动，
   否则息屏后投送会中断
3. 打开骑投，扫码或手动配对 450MT 仪表热点
4. 应用会拉起百度 CarLife+，在其界面选择"无线连接"进入车机模式
5. 仪表出现画面后即可使用；音乐 / 导航音量可在应用内分别调节

## 从源码构建

```bash
git clone https://github.com/super-band/QITOU-MI.git
cd QITOU-MI
# Android Studio 打开，Sync 后 Build → Build APK
# 或命令行：
./gradlew assembleRelease
```

## 持续集成

仓库内置 GitHub Actions（`.github/workflows/build.yml`）：

- push / PR 到 `main`：自动跑 lint + 单测，构建 debug / release APK，
  在 Actions 的 Artifacts 中下载；
- 推送 `v*` 标签（如 `v0.10.2`）：额外创建 GitHub Release 并附上 APK。

release 签名（可选）：在仓库 Settings → Secrets and variables → Actions 配置
`QITOU_RELEASE_KEYSTORE_BASE64`（keystore 的 base64）、`QITOU_RELEASE_STORE_PASSWORD`、
`QITOU_RELEASE_KEY_ALIAS`、`QITOU_RELEASE_KEY_PASSWORD` 后，release APK 将自动签名；
未配置则输出未签名 release 与 debug APK。

## 已知限制

- CarLife+ 的无线发现机制能否被本机虚拟车机端直接唤起，仍在验证；
  若卡在连接阶段，请手动在 CarLife+ 内发起无线连接
- `WifiNetworkSpecifier` 连接仪表热点时每次都会弹系统确认框，属系统行为
- CFMOTO 仪表固件升级可能改动 PXC 协议，导致投送失效
- 触控转发在部分 CarLife+ 版本上可能不生效

## License

[AGPL-3.0](LICENSE)

基于 QITOU（Copyright 2026 yuppiesnotzhuhao）修改。分发本软件或其衍生版本时，
须保留本许可与版权声明，并公开完整源码。