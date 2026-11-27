# WebRTC 服务部署

## 用公网 IP 部署

在有**固定公网 IPv4** 的 Linux 云主机安装 Docker Engine 和 Docker Compose 插件。安全组与主机防火墙放行下面的入站端口。

| 入站端口 | 用途 |
| --- | --- |
| TCP 80、443 | 签发和续期 IP 证书、HTTPS 控制页、WSS 信令 |
| UDP 3478、TCP 3478 | STUN/TURN，TCP 可作为浏览器侧备用连接 |
| UDP 49180–49219 | TURN 中继媒体端口 |

把本目录复制到云主机。进入目录运行下面的命令，把示例地址换成云主机的公网 IPv4。

```sh
bash setup.sh 你的公网IPv4
```

脚本生成独立密钥并用一个 Compose 项目启动 Caddy、WebRTC 信令和 coturn。Caddy 自动申请和续期 Let’s Encrypt 公网 IP 证书。首次启动可用 `docker compose logs -f https` 查看签发进度，等 `https://你的公网IPv4/healthz` 返回 `ok` 再连 App。请保留 `.env` 和 Caddy 数据卷，重启可用 `docker compose up -d`。

| 生成的文件 | 用途 |
| --- | --- |
| `setup-output/android-link.txt` | 在 Android App 的「设置」粘贴并保存。也可手动填写公网 IP |
| `setup-output/config.local.h` | 复制到固件的 `firmware/src/config.local.h`，使用 PlatformIO 编译烧录 |

公网访问者可直接打开控制页或在 App 中填写 IP。默认在同一次 ICE 协商中探测直连和 TURN 中继，可在 App 中选择仅直连。每辆小车同一时间允许一个控制会话。

### 添加其他小车

默认的「小车 1」使用原有的 DEVICE_KEY，现有固件可继续使用。为新车分别运行 `openssl rand -hex 32` 生成设备密钥，再在服务器的 `.env` 增加 P4_CARS 配置。把示例中的 key 换成实际生成的值，如需更多车辆，在数组中添加对应条目。

```dotenv
P4_CARS='[{"id":"car-2","name":"小车 2","key":"用新生成的64位十六进制密钥替换"}]'
```

在每辆新车的 `firmware/src/config.local.h` 中填写同一个 INTERCOM_HOST 和各自的 DEVICE_KEY，用 PlatformIO 编译烧录。运行 `docker compose up -d signaling` 应用配置，再从 Android App 控制页选择「切换小车」。H5 和微信小程序继续连接默认小车。

脚本会从 Linux 默认路由读取 TURN 的本机网卡地址。多网卡或云网络使用其他地址时，第二个参数可指定 TURN 绑定的本机 IPv4，例如 `bash setup.sh 你的公网IPv4 10.0.1.5`。公网地址经云平台映射时，TURN 的监听和中继端口需要保持原端口号。

证书签发失败时优先检查公网 IP、入站端口和端口占用。更换公网 IP 时，在 `.env` 中更新 `PUBLIC_IP`，重新运行脚本生成连接地址和固件配置，再更新 App 和固件。设备密钥和 TURN 密钥会保留。

## 现有域名部署

当前入口为 `webrtc.mjs`。Node.js 服务只在本机 `127.0.0.1:18128` 监听。Nginx 对外提供 HTTPS 和 WSS，coturn 提供公网媒体转发。

## 服务配置

安装 Node.js 24，进入服务器上的 `server/` 目录并安装依赖。

```sh
npm install --omit=dev
```

`DEVICE_KEY` 和 `TURN_SECRET` 是两个独立的 64 位十六进制值，可分别用 `openssl rand -hex 32` 生成。示例 systemd 单元从 `/etc/esp32-p4-intercom.env` 和 `/etc/p4-webrtc.env` 读配置。将这些文件放在服务器上，仅授权服务管理员读取。

| 变量 | 用途 |
| --- | --- |
| `DEVICE_KEY` | 固件的设备密钥 |
| `P4_CARS` | 可选的额外小车 JSON 数组，包含独立的 id、name 和 key |
| `PUBLIC_ORIGIN` | 用户访问 H5 的 HTTPS 源，例如 `https://rc.example.com`，不带结尾斜杠 |
| `TURN_HOST` | 客户端可访问的 TURN 域名或公网地址，不带协议和端口 |
| `TURN_SECRET` | 与 coturn 的 `static-auth-secret` 一致的密钥 |

手动试运行时，在环境变量已设置的终端执行 `node webrtc.mjs`。持续运行可以用 [p4-webrtc.service](p4-webrtc.service)，并按实际安装位置检查 `WorkingDirectory` 和 Node 路径。

## HTTPS 和 TURN

1. 使用 [nginx-webrtc.conf](nginx-webrtc.conf) 中的两个 location 块接入自己的 HTTPS 虚拟主机。公网页面和 WebSocket 信令共用同一个域名。
2. 在 coturn 启用 `use-auth-secret`，将 `static-auth-secret` 设置为 `TURN_SECRET`，监听端口设为 3478。云主机在公网地址映射之后，需要按实际网络设置 coturn 的监听、转发及公网映射地址。
3. 放行 HTTPS 的 TCP 443，以及 TURN 的 UDP 3478。需要 TURN 的 TCP 备用链路时同时放行 TCP 3478。再放行与 coturn `min-port` 和 `max-port` 一致的 UDP 中继端口范围，本项目联调使用 49180–49219。
4. 手机访问 `https://rc.example.com/p4-intercom/rtc/`，把示例域名换成自己的服务端地址。点击声音和麦克风按钮分别开启收听与说话。

默认在同一次 ICE 协商中检查直连和 TURN 中继，界面按实际采用的路径标记 P2P 或 TURN。在 Android App「设置」开启「仅 P2P 直连」，或在 H5 地址末尾追加 `#ice=direct`，即可禁止使用 TURN。直连时服务器继续承担网页、信令及 STUN 探测。

`relay.mjs` 和 `nginx.conf` 保留了早期网页转发方案。当前固件和 H5 使用 WebRTC 的部署配置。
