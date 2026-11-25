# WebRTC 服务部署

当前入口为 `webrtc.mjs`。Node.js 服务只在本机 `127.0.0.1:18128` 监听。Nginx 对外提供 HTTPS 和 WSS，coturn 提供公网媒体转发。

## 服务配置

安装 Node.js 24，进入服务器上的 `server/` 目录并安装依赖。

```sh
npm install --omit=dev
```

`DEVICE_KEY`、`VIEWER_KEY`、`TURN_SECRET` 是三个独立的 64 位十六进制值，可分别用 `openssl rand -hex 32` 生成。示例 systemd 单元从 `/etc/esp32-p4-intercom.env` 和 `/etc/p4-webrtc.env` 读配置。将这些文件放在服务器上，仅授权服务管理员读取。

| 变量 | 用途 |
| --- | --- |
| `DEVICE_KEY` | 固件的设备密钥 |
| `VIEWER_KEY` | H5 观看密钥，供浏览器建立观看会话 |
| `PUBLIC_ORIGIN` | 用户访问 H5 的 HTTPS 源，例如 `https://rc.example.com`，不带结尾斜杠 |
| `TURN_HOST` | 客户端可访问的 TURN 域名或公网地址，不带协议和端口 |
| `TURN_SECRET` | 与 coturn 的 `static-auth-secret` 一致的密钥 |

手动试运行时，在环境变量已设置的终端执行 `node webrtc.mjs`。持续运行可以用 [p4-webrtc.service](p4-webrtc.service)，并按实际安装位置检查 `WorkingDirectory` 和 Node 路径。

## HTTPS 和 TURN

1. 使用 [nginx-webrtc.conf](nginx-webrtc.conf) 中的两个 location 块接入自己的 HTTPS 虚拟主机。公网页面和 WebSocket 信令共用同一个域名。
2. 在 coturn 启用 `use-auth-secret`，将 `static-auth-secret` 设置为 `TURN_SECRET`，监听端口设为 3478。云主机在公网地址映射之后，需要按实际网络设置 coturn 的监听、转发及公网映射地址。
3. 放行 HTTPS 的 TCP 443，以及 TURN 的 UDP 3478。需要 TURN 的 TCP 备用链路时同时放行 TCP 3478。再放行与 coturn `min-port` 和 `max-port` 一致的 UDP 中继端口范围，本项目联调使用 49180–49219。
4. 手机访问 `https://rc.example.com/p4-intercom/#观看密钥`。这里的域名和观看密钥换成自己的配置。点击声音和麦克风按钮分别开启收听与说话。

`relay.mjs` 和 `nginx.conf` 保留了早期网页转发方案。当前固件和 H5 使用 WebRTC 的部署配置。
