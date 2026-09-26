# LadderAirport Android

Android 上的 LadderAirport 节点。手机、平板或电视盒子主动连到 Panel，作为 uplink 节点加入机群。不需要公网 IP，也不监听入站端口。

需要 Panel **v0.15.7** 或更新版本。注册走 `POST /api/v1/agent/enroll`，只交换长期控制令牌，不申请管理面证书。

## 运行方式

- 控制面是 Agent 主动建立的 WebSocket 长连接，能力与 Panel 拨号 gRPC 对齐。连接断开时回退到 HTTP 上报和拉配置。
- 数据面在进程里跑 sing-box，并通过 FRP 把入站流量从公网 FRPS 转到这台设备。
- 前台服务保持进程，支持开机自启。
- 安装包只包含 `arm64-v8a`。32 位 ARM 和 x86 模拟器不能安装这份包。

在 Panel 里把节点建成 **uplink**。添加成功后点「扫码配对」，用本应用的相机扫描二维码，填入 Panel 地址、节点 ID 和控制令牌，再点「一键注册」。

## 本地构建

`app/libs/ladderagent.aar` 由本机 `make aar` 生成，不提交。打 APK 前需要 Android SDK（compileSdk 35）、JDK 17、Go 1.26+、`gomobile` 和 Android NDK r28。`core/go.mod` 的 `replace` 指向本机的 [LadderAirport](https://github.com/LadderAirport/LadderAirport) 检出，路径不对时 `make aar` 会失败。

```bash
make aar
make assemble
# app/build/outputs/apk/debug/app-debug.apk

make release
# app/build/outputs/apk/release/app-release-unsigned.apk
```

`make test` 跑 Android 单元测试。CI 会检出 [LadderAirport](https://github.com/LadderAirport/LadderAirport) 主仓库，用 `with_quic,with_utls` 编 arm64 AAR，再编 debug APK 并上传产物。不把 AAR 提交回仓库。

## 目录

```text
app/                  Kotlin 界面与前台服务
core/mobile/          gomobile 绑定：注册、uplink HTTP、WebSocket
```
