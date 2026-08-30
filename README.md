# 小米电视助手 (MiTvAssistant)

一个开源的安卓 App，用于在局域网内控制小米电视：

| 能力 | 说明 | 依赖条件 |
| --- | --- | --- |
| 🎮 **遥控器** | 方向键/OK/主页/返回/菜单/音量/电源/静音，按住连发；支持给常用应用做快捷启动、发送英文文字到输入框 | 默认走电视直控接口(6095)，无需任何设置；不可用时回退 ADB |
| 🈲 **应用限制 / 儿童锁** | 列出电视上的应用，一键禁用/启用；勾选白名单后「一键套用儿童锁」，只保留勾选应用 | 需要在电视上开启 ADB 调试 |
| 🖥️ **远程看屏** | 通过 ADB 截屏按 1~4 帧/秒刷新查看电视画面，可保存截图 | 同上 |

> 三条能力相互独立：即使不开启 ADB，遥控器也可能直接工作；但应用管理与看屏必须开启 ADB。

---

## 一、电视端准备

### A. 开启 ADB 调试（应用管理 / 看屏必需）

1. 电视进入 `设置` → `账号与安全`；
2. 把 `安装未知来源应用` 设为允许（部分应用禁用后的启动入口需要）；
3. 打开 `ADB调试` / 部分机型叫 `网络调试`；
4. 回到手机 App 里连接时，**电视会弹出「允许 USB 调试？」授权框**：
   勾选 `一律允许使用这台计算机进行调试`，然后用遥控器点确定。
   （本项目会在手机本地生成一对 RSA 密钥用于 ADB 认证，与官方 adb 工具机制完全一致。）

不同年份固件路径略有差异。若找不到开关，可在 `设置 → 关于本机 → 系统版本` 上用遥控器连点多次唤出开发者选项后再找 ADB。

### B. 直控接口（老固件自动可用）

小米电视早期固件（米 TV 3 / 3s / 4A 等）在局域网开放了 HTTP 控制端口 **6095**，
本项目参考 [pymitv](https://github.com/simse/pymitv) 实现了同款调用：

```
GET http://<TV_IP>:6095/controller?action=keyevent&keycode=up|down|left|right|enter|back|home|menu|volumeup|volumedown|power
GET http://<TV_IP>:6095/request?action=isalive          # 探活
GET http://<TV_IP>:6095/general?action=getVolum         # 查询音量
```

新固件可能关闭该接口——App 会自动探测并在界面上显示「直控✔/✘」，不影响 ADB 通道功能。

---

## 二、手机端使用

1. **扫描设备**：打开 App 点「扫描局域网设备」。
   发现方式为 SSDP 组播 + mDNS 投屏服务(`_airplay._tcp`)双通道，并对每台候选机器探测 6095 与 5555 端口能力后列表展示。
   扫不到？直接「手动输入 IP」（电视的 IP 在 `设置→关于本机→网络` 中可见）。
2. 点击列表中的设备将其设为当前电视（标记「使用中」）。
3. 进入对应功能页：
   - **遥控器**：按键即时生效，长按方向键/音量键连发；底部可为常用应用添加包名快捷方式；
   - **应用管理**：首次进入会触发电视上的授权弹窗（见上文 A-4）。列表里：
     - 勾选框 = 白名单（儿童锁保留项）
     - 单个按钮 = 禁用/启用该应用
     - 长按条目 = 设置中文备注名
     - 「一键套用儿童锁」= 禁用所有未勾选的第三方应用并启用勾选项；
       「全部恢复启用」= 恢复当前列表中已禁用的应用。
   - **远程看屏**：选择刷新速度后开始预览；「保存截图」写入手机
     `Android/data/com.mitv.assistant/files/Pictures/MiTvScreenshots/`。

被禁用的应用图标会从电视桌面消失、无法拉起，重启电视依然生效；随时可回本 App 恢复。

---

## 三、构建 APK

要求 JDK 17 + Android SDK (API 34) + Gradle 8.4。

```bash
# 方式一：Android Studio 直接 File → Open 本目录，等待 Sync 后 Run

# 方式二：命令行（先确保 local.properties 或 ANDROID_HOME 指向 SDK）
gradle assembleDebug        # 输出 app/build/outputs/apk/debug/app-debug.apk
```

懒人方案：本仓库附带云构建配置 `ci/android.yml`，把它复制为 `.github/workflows/android.yml` 提交推送，
GitHub Actions 即会每次 push 自动云端构建，在 Actions 的 Artifacts 里下载 `MiTvAssistant-debug-apk` 即可，本地什么都不用装。

---

## 四、技术架构

```
app/src/main/java/com/mitv/assistant/
├── App.kt                    # Application，初始化配置与 ADB 密钥
├── data/
│   ├── AppPrefs.kt           # SharedPreferences 封装（选中电视、白名单、密钥…）
│   └── TvDevice.kt           # 设备模型（ip/来源/双通道能力位）
├── net/
│   ├── MiTvHttp.kt           # 小米 6095 HTTP 直控客户端
│   └── TvDiscovery.kt        # SSDP + NsdManager(mDNS) 发现 + 并行能力探测
├── adb/
│   ├── AdbCryptoCompat.kt    # ADB RSA 认证（移植 tananaev/adblib 的公钥结构/签名算法）
│   ├── AdbClient.kt          # ADB over TCP 协议实现（CNXN/AUTH/OPEN/WRTE/CLSE + 流管理）
│   └── TvAdb.kt              # 连接池式管理器（复用长连接、失效重建、端口探测）
├── remote/
│   └── RemoteController.kt   # 门面层：keyevent 双通道择优、monkey 启动应用、screencap 截屏
└── ui/
    ├── MainActivity.kt       # 主控台：扫描/手输 IP/能力展示/功能入口/帮助
    ├── RemoteActivity.kt     # 遥控器（含连发、音量回显、常用应用、发送文字）
    ├── AppsActivity.kt       # 应用管理 + 儿童锁（pm disable-user/enable 批量执行）
    └── CastActivity.kt       # 远程看屏（screencap 轮询刷新 + 保存截图）
```

要点：
- **ADB 公钥格式**严格按 Android adbd 要求生成（524 字节小端 RSAPublicKey 结构 → Base64 → `user@host\0`），
  签名采用手工 PKCS#1 v1.5(SHA-1) padding + RSA NoPadding，兼容所有标准 adbd。
- 看屏截屏优先走 `exec:screencap -p` 二进制安全通道；个别旧 adbd 不支持 `exec:` 时自动降级
  `shell:` 通道并修复 CRLF 污染后再解码 PNG。
- 所有网络操作都在协程 Dispatchers.IO 中执行并带超时保护，超时/异常自动重建连接。

## 五、已知限制（务必阅读）

1. **均限于同一局域网**。跨互联网远程需要在路由器上做端口映射或自建 VPN（如 WireGuard），
   请自行评估安全性。
2. 应用「限制」的本质是 `pm disable-user`（禁用），不是运行时拦截：
   - 无法阻止家长知情的孩子在其他设备上改回来（恢复需本 App/ADB）；
   - 系统桌面等关键组件做了二次确认保护，请谨慎操作受保护组件。
3. 远程看屏是**截图流而非视频流**，单帧延迟取决于电视性能与分辨率（百毫秒级），适合「看看孩子在看什么」，不适合看球赛。若要丝滑镜像可研究 scrcpy 方案的后续扩展。
4. 小米固件碎片化严重：新固件可能关闭 6095 直控接口；极端情况个别固件会限制非官方 `input keyevent`。遇问题先在帮助页确认两条通道的状态标识。
5. 发送文字仅支持 ASCII（中文受 `adb input text` 编码限制）。

## 六、隐私与安全

- App 无任何云端服务：所有数据（选中的电视 IP、白名单、备注、ADB 私钥）都只存在手机本地 SharedPreferences。
- ADB 私钥即你手机的身份凭据，与电脑上的 `~/.android/adbkey` 同级敏感，请不要随意 root/备份分享该目录。
- 电视开启 ADB 后，同网段内任何获得授权配对的设备都能控制电视；不用时建议关掉电视的调试开关。

## 七、致谢 / 参考

- [tananaev/adblib](https://github.com/tananaev/adblib) (MIT) —— ADB 协议与 RSA 认证参考实现
- [simse/pymitv](https://github.com/simse/pymitv) —— 小米电视 6095 局域网接口的字面来源
- AOSP adb protocol 文档

License: MIT
