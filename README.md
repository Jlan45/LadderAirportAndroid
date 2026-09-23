# LadderAirport Android

LadderAirport 节点 Agent Android 客户端。

根据 [/home/jlan/android-agent.md](/home/jlan/android-agent.md) 设计规范，本项目将 LadderAirport Agent 运行在普通 Android 设备（手机 / 平板 / 电视盒子）上，使闲置设备作为受 Panel 管控的代理节点接入机群。

---

## 核心架构与设计

- **控制通道**：仅走 **uplink-ws**（WebSocket 实时控制长连接），设备主动向 Panel 建立出站 WebSocket 连接，对齐 push/gRPC 全部管控功能，无需公网 IP 与监听端口。
- **数据通道**：内嵌 **sing-box**（`box.Box`）+ **FRPC 反向隧道**（`frpcbridge`），将入站流量经远端公网 FRPS 穿透回本地 Android 节点。
- **生命周期**：Android 前台 Service（`FOREGROUND_SERVICE` + `specialUse`）常驻运行，结合 `START_STICKY` 与 `WakeLock` 保持长连接；支持网络切网重连与开机自启动。
- **管理 PKI**：本地生成 ECDSA P-256 私钥，向 Panel CA 签发 SPIFFE 身份证书（`spiffe://ladderairport/agent/<node-id>`），私钥保存在应用私有沙箱中。
- **系统指标与网卡**：由 Android Kotlin 宿主环境通过 `Host` 回调注入系统指标（CPU/内存/磁盘/网络速率）和网卡列表。

---

## 构建方式

### 前置条件
- Android Studio 与 Android SDK（API 24+，build-tools 35+ / 36+）
- Android NDK（r27 或 r28）
- Go 1.26+ 与 `gomobile` (`github.com/sagernet/gomobile`)
- Java 17+ / 21

### 一键构建
```bash
# 构建 AAR 动态库并编译生成 APK
make all
```

或者分步构建：
```bash
# 1. 编译 Go Agent AAR
make aar

# 2. 编译 Debug APK
make assemble
# 产物位置：app/build/outputs/apk/debug/app-debug.apk

# 3. 编译 Release APK
make release
# 产物位置：app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## 项目结构

```text
LadderAirportAndroid/
├── Makefile                          # 一键自动化构建脚本
├── settings.gradle                   # Gradle 多模块与依赖仓库设置
├── build.gradle                      # 项目级 Gradle 配置
├── app/
│   ├── build.gradle                  # App 模块配置与依赖
│   ├── libs/
│   │   └── ladderagent.aar           # gomobile 生成的 Go 核心动态库
│   └── src/main/
│       ├── AndroidManifest.xml       # 权限与 Service/Receiver 声明
│       ├── java/io/ladderairport/agent/
│       │   ├── LadderApplication.kt  # 全局初始化与通知渠道
│       │   ├── service/
│       │   │   └── AgentService.kt   # 前台常驻 Service 与 Host 回调实现
│       │   ├── receiver/
│       │   │   └── BootReceiver.kt   # 开机自启广播接收器
│       │   ├── util/
│       │   │   ├── DeviceMetricsHelper.kt   # 系统 CPU/RAM/磁盘/网络采集
│       │   │   ├── NetworkInterfaceHelper.kt# 网卡信息枚举
│       │   │   └── PreferencesHelper.kt     # 配置持久化
│       │   ├── model/
│       │   │   └── AgentStatus.kt    # 节点状态数据模型
│       │   └── ui/
│       │       └── MainActivity.kt   # 交互主界面（配置/一键注册/启停/日志）
│       └── res/                      # 布局、配色、图标资源
```
