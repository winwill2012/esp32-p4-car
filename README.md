# ESP32-P4 4G 遥控车

基于莽牛 99S RC 遥控车改造的 4G 远程遥控小车。ESP32-P4 通过合宙 Air780E 联网，手机打开 H5 即可看实时画面、双向对讲和控制转向。

| 功能 | 当前实现 |
| --- | --- |
| 4G 联网 | Air780E 接开发板 USB-A，固件通过 USB PPP 拨号 |
| 图传 | OV5647 CSI 摄像头，ESP32-P4 硬件 H.264 编码。配置为 800×800、目标 20 fps、目标码率 2.2 Mbps |
| 双向对讲 | 16 kHz 单声道 Opus，板端拾音增益和全双工回声消除 |
| 转向 | GPIO21 驱动舵机，H5 圆形摇杆。松手或指令超时后舵机回中 |
| 油门 | 全屏左侧为前进、倒车、松手归零的界面预览，驱动电机尚未接入 |
| 全屏 | 保留摇杆、声音和麦克风控件。H5 普通视图和全屏均以上下翻转方向显示视频 |

## 构建和部署

1. 按 [固件说明](firmware/README.md) 接好摄像头、Air780E 和转向舵机。
2. 按 [服务器说明](server/README.md) 部署 WebRTC 信令、HTTPS 和 TURN，并生成设备与观看密钥。
3. 将 `firmware/src/config.example.h` 复制为 `firmware/src/config.local.h`，填入域名和设备密钥。
4. 用 PlatformIO 构建并烧录固件，在手机上用配置的域名和观看密钥打开 H5。

```sh
pio run -d firmware -e waiken-p4
```

板端和网页通过 WebSocket 完成信令以及转向指令转发。H.264 视频和双向 Opus 音频走 WebRTC，公网中继由 TURN 提供。当前服务同时允许一台设备和一个观看会话。

## 代码目录

| 目录 | 内容 |
| --- | --- |
| [firmware/](firmware/) | PlatformIO 工程，包括 USB PPP、摄像头、双向音频和转向舵机 |
| [server/](server/) | Node.js 信令、H5 以及 Nginx 和 systemd 部署示例 |

本地设备配置和观看密钥由各自的部署者管理。仓库提供配置示例。
