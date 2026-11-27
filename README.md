# ESP32-P4 4G 遥控车

基于莽牛 99S RC 遥控车改造的 4G 远程遥控小车。ESP32-P4 通过合宙 Air780E 联网，手机可通过原生 Android App 或 H5 查看实时画面、双向对讲和控制转向。

| 功能 | 当前实现 |
| --- | --- |
| 4G 联网 | Air780E 接开发板 USB-A，固件通过 USB PPP 拨号 |
| 图传 | OV5647 CSI 以 1280×960 采集，ESP32-P4 输出 16:9 H.264。高清 1280×720、2.4 Mbps，标清 640×360、1.1 Mbps，两档目标 20 fps |
| 双向对讲 | 16 kHz 单声道 Opus，板端拾音增益和全双工回声消除 |
| 转向 | GPIO21 驱动舵机。松手或指令超时后舵机回中 |
| 油门 | 左摇杆支持前进、倒车和松手归零的界面预览，驱动电机尚未接入 |
| 灯光 | Android 可开关前灯，倒车和转向指令联动对应灯光 |
| 全屏 | 视频铺满横屏，摇杆、声音和麦克风控件覆盖在画面上 |

## 构建和部署

无域名部署使用 [Docker Compose](server/README.md)，在 Linux 云主机运行 `bash setup.sh 你的公网IPv4`。脚本输出 Android 连接地址与车端 `config.local.h`。Android App 源码和使用方法见 [android/](android/README.md)。

1. 按 [固件说明](firmware/README.md) 接好摄像头、Air780E 和转向舵机。
2. 按 [服务器说明](server/README.md) 部署 WebRTC 信令、HTTPS 和 TURN，并生成设备密钥与 TURN 密钥。
3. 将部署脚本生成的 `config.local.h` 复制至 `firmware/src/`，或根据 `config.example.h` 手动填写公网 IPv4 或域名以及设备密钥。
4. 用 PlatformIO 构建并烧录固件，在 Android App 输入公网 IP，或在手机浏览器打开 H5。

```sh
pio run -d firmware -e waiken-p4
```

板端和控制端通过 WebSocket 完成信令及转向指令转发。H.264 视频和双向 Opus 音频走 WebRTC，公网中继由 TURN 提供。同一服务器可登记多辆小车，Android App 的「切换小车」每次只连接一辆车，每辆车允许一个控制会话。

## 代码目录

| 目录 | 内容 |
| --- | --- |
| [android/](android/) | 原生 Android 控制端，支持全屏双摇杆、灯光控制和多车切换 |
| [firmware/](firmware/) | PlatformIO 工程，包括 USB PPP、摄像头、双向音频、转向舵机和车灯 |
| [server/](server/) | Docker Compose 公网 IP 部署、WebRTC 信令、H5 和域名部署示例 |
| [miniprogram/](miniprogram/) | 微信小程序入口，通过 `web-view` 复用 H5 控制页 |

本地设备配置由各自的部署者管理。仓库提供配置示例。
