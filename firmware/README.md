# ESP32-P4 固件

固件通过 PlatformIO 的 `waiken-p4` 环境构建，使用 ESP-IDF 5.5.1。代码在慧勤智远 ESP32-P4 CB V1.2 和 ESP32-P4 v1.3 上联调。

## 硬件连接

| 模块 | 接线或设置 |
| --- | --- |
| OV5647 | 接 CSI 摄像头排线座。GPIO28 和 GPIO29 用于板上的 I²C 总线 |
| Air780E | 接 USB-A Host。当前配置匹配 USB VID:PID `19d1:0001` 的第 2 号接口；模块使用支持 USB PPP 的 AT 固件和已开通流量的 SIM |
| ES8311 | 板载音频使用 GPIO2、GPIO3、GPIO4、GPIO5、GPIO6，GPIO11 控制功放 |
| 转向舵机 | 信号接 GPIO21（P6 排针第 34 脚）。舵机使用符合规格的电源，与 ESP32 共地 |

GPIO3 已用于板载扬声器的 I²S 数据输出。舵机 PWM 为 50 Hz，中位 1500 µs，当前行程为 1150–1850 µs，松手或超过 450 ms 没有有效转向指令时回中。首次联调时架空车轮，确认实际左右方向、中位和机械限位。

## 配置和构建

将 [config.example.h](src/config.example.h) 复制为同目录的 `config.local.h`，并填写以下两项。

| 配置 | 内容 |
| --- | --- |
| `INTERCOM_HOST` | 对讲服务的 HTTPS 域名，例如 `rc.example.com` |
| `DEVICE_KEY` | 与服务器一致的 64 位十六进制设备密钥 |

在仓库根目录运行以下命令。首次构建需要下载 PlatformIO 平台、ESP-IDF 组件和经过 SHA‑256 校验的 Opus 1.5.2 源码。

```sh
pio run -d firmware -e waiken-p4
```

## 烧录

如果板卡已经使用本项目或原型板的匹配分区布局，可以只写应用固件。以下以 Windows 上的 `COM18` 为例，请换成实际下载串口。

```sh
python -m esptool --chip esp32p4 --port COM18 --baud 460800 write-flash 0xd00000 firmware/.pio/build/waiken-p4/firmware.bin
```

[partitions.csv](partitions.csv) 将应用放在 `0xd00000`，适用于本项目使用的 16 MB Flash 布局。首次完整烧录时先备份原板固件，再核对构建产物中的 `flash_args`、分区表和主板版本。监视串口使用 115200 波特率。

```sh
pio device monitor -d firmware -e waiken-p4 --port COM18
```

正常启动时，串口依次报告 Air780E 枚举和 PPP 分配 IP、系统时间同步、WebRTC 信令连接。在网页建立会话后开始推流和对讲。
