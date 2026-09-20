# Subsonic Player for Android

面向上面自建音乐服务（Subsonic / OpenSubsonic 兼容：Navidrome、Gonic、Jellyfin 等）的**原生 Android 客户端**。

- 纯 **Java + Android Framework**，**零第三方依赖**（没有 androidx、没有 Gradle、没有 ExoPlayer）
- **完全离线构建**：只用本机 Android SDK 命令行工具（aapt2 / javac / d8 / zipalign / apksigner）
- 界面按手机竖屏重做：底部 5 Tab（发现 / 曲库 / 收藏 / 搜索 / 设置）+ 迷你播放条 + 全屏播放页
- 播放走系统 `MediaPlayer` + 前台服务 + `MediaSession`：**后台播放、通知栏/锁屏控制、耳机按键、音频焦点**都支持

---

## 一、快速开始

### 1. 构建 APK

```powershell
cd subsonic-player/android
powershell -ExecutionPolicy Bypass -File build.ps1            # 产出 dist\SubsonicPlayer-0.1.0-release.apk
powershell -ExecutionPolicy Bypass -File build.ps1 -Install   # 构建并安装到已连接设备
powershell -ExecutionPolicy Bypass -File build.ps1 -Clean     # 清理
```

脚本会自动定位 SDK（`ANDROID_HOME` / `ANDROID_SDK_ROOT`，否则 `D:\tools\Android\Sdk`）与 JDK（优先 Android Studio 自带 JBR 17）。
首次构建会自动生成自签名密钥库 `keystore/subsonic-player.jks`（口令 `subsonicplayer`，仅用于侧载）。

> ⚠️ **build.ps1 必须保存为 UTF-8 with BOM**：Windows PowerShell 5.1 会把无 BOM 的中文脚本按 GBK 解析并报「字符串缺少终止符」。用编辑器改动后请确认编码。

### 2. 安装与配置

APK 侧载即可（`minSdk 29`，即 Android 10 及以上）。
首次启动进「设置 → 音乐服务器 → 添加服务器」，填**内网地址 +（可选）外网地址 + 用户名 + 密码**，点「保存并连接」。
密码用 `AndroidKeyStore` 的 AES-GCM 加密后存本地（密钥不出安全硬件；极端设备不支持时退回随机软件密钥），**不落明文、不进代码仓库**。

**从文件导入配置**（换机 / 批量部署）：把配置文件放到
`Android/data/com.lixscn.subsonicplayer/files/server.json`，下次启动自动导入并删除该文件。

```json
{ "name": "我的 NAS", "lanUrl": "http://192.168.1.10:8002",
  "wanUrl": "https://music.example.com", "username": "your-name", "password": "******" }
```

`lanUrl` 填局域网地址（在家走它，最快、不占上行带宽），`wanUrl` 填外网域名或地址（不在家时回退到它）。
App 会先在内网地址上做一次 **3 秒**快速探测，不通就自动切到外网地址，因此外网不必依赖 VPN/Tailscale。
只想用域名的话，把地址填在 `lanUrl`、`wanUrl` 留空即可。

---

## 二、构建链路（为什么不用 Gradle）

本项目开发机**无外网**，`dotnet workload` / Gradle / Maven 依赖都拉不下来，因此走 SDK 自带工具链：

```
aapt2 compile  →  aapt2 link(R.java + 资源 APK)  →  javac  →  jar  →  d8  →  塞入 classes.dex
              →  zipalign  →  apksigner（自签名）
```

| 环节 | 工具 | 说明 |
|------|------|------|
| 资源编译/链接 | `build-tools/34.0.0/aapt2.exe` | `--min-sdk-version 29 --target-sdk-version 34` |
| Java 编译 | JBR 17 `javac` | `-source 8 -target 8`，源码清单走 `@argfile`（**必须无 BOM**，否则 javac 报非法参数） |
| dex | `d8.bat` | 类文件先打成 `classes.jar` 再喂给 d8（d8 不吃裸目录，逐个传文件会超命令行长度上限） |
| 打包 | .NET `ZipArchive` | 把 `classes.dex` 写进 aapt2 产出的 APK |
| 对齐/签名 | `zipalign.exe` / `apksigner.bat` | `apksigner verify` 通过才算成功 |

**没有 layout XML**：所有界面用 Java 程序化建 View —— 这样切换主题只需重建视图，不必依赖资源重载。

---

## 三、工程结构

```
android/
├── build.ps1                     # 离线构建脚本（UTF-8 BOM）
├── keystore/subsonic-player.jks  # 自动生成的自签名密钥
├── app/
│   ├── AndroidManifest.xml
│   ├── res/
│   │   ├── drawable/             # 40+ 矢量图标（tools/gen-icons.ps1 生成）
│   │   ├── values/               # 颜色/主题/字符串
│   │   └── mipmap-anydpi-v26/    # 自适应启动图标
│   └── java/com/lixscn/subsonicplayer/
│       ├── MainActivity.java     # 唯一 Activity：外壳 + 路由 + 迷你播放条 + 底部导航
│       ├── core/
│       │   ├── Item.java         # 统一模型（歌曲/专辑/艺术家/歌单/流派共用一个类型）
│       │   ├── Http.java         # HttpURLConnection 封装（显式 UTF-8、跟随重定向）
│       │   ├── SubsonicClient.java  # Subsonic REST 客户端（org.json 解析）
│       │   ├── Library.java      # 数据门面：后台线程取数 + 主线程回调 + 内存缓存
│       │   ├── Settings.java     # SharedPreferences 持久化（多服务器配置）
│       │   ├── Secret.java       # AndroidKeyStore AES-GCM 密码加解密
│       │   ├── ConfigImport.java # server.json 配置文件导入
│       │   ├── Lyrics.java / LrcParser.java / LyricsProvider.java  # 歌词（服务端 + LRCLIB + 网易云）
│       ├── player/
│       │   ├── Player.java       # 播放引擎：队列/播放模式/进度/位置记忆（进程内单例）
│       │   └── PlaybackService.java  # 前台服务 + MediaSession + 通知栏控制
│       └── ui/
│           ├── Theme.java        # 6 套主题 + 强调色（对齐桌面版 styles.css）
│           ├── Ui.java           # 尺寸/圆角/涟漪/控件工厂
│           ├── Page.java         # 页面基类
│           ├── Pages.java        # 页面工厂（唯一跳转入口，页面之间零耦合）
│           ├── ItemAdapter.java  # 通用列表/网格适配器（ListView/GridView）
│           ├── ItemListPage.java # 通用列表页（分页 + 滚动加载 + 空/错态）
│           ├── CoverLoader.java  # 封面异步加载（内存 + 磁盘两级缓存、去重、防错位）
│           ├── CircleCover.java  # 圆角/圆形 ImageView
│           ├── Menus.java        # 条目操作菜单（收藏/评分/加入歌单/队列）
│           └── pages/            # 各业务页面
└── tools/
    ├── gen-icons.ps1             # 生成矢量图标
    ├── probe.js / run-probe.ps1  # 探测真实服务端 API（凭据经环境变量传入，不打印）
    ├── dev-push-config.ps1       # 把桌面版配置解密后推到设备（联调用，不回显密码）
    └── shape-api.js              # 把响应整理成字段清单
```

---

## 四、已实现功能

| 模块 | 内容 |
|------|------|
| 浏览 | 发现（问候/快捷入口/最近添加/常听/随机推荐/精选歌曲）、专辑网格（5 种排序）、艺术家、全部歌曲（渐进式按专辑展开）、歌单、风格、最近播放、书签续播 |
| 搜索 | 歌曲/专辑/艺术家三段结果、350ms 防抖、最近搜索词 |
| 播放 | 顺序/随机/列表循环/单曲循环、队列编辑（移除/下一首播放/随机/清空）、进度拖动、音量、断点续播、播放位置持久化（本机） |
| 网络 | **切网自动换地址**：WiFi ⇄ 蜂窝切换后重新确认当前地址，**只在真的不可达时才换**（内网 ⇄ 外网、选延迟低的），换完**从断点续播**；流量模式下切到蜂窝会中止后台缓存 |
| **DLNA 推送** | 发现局域网里的 MediaRenderer（SSDP M-SEARCH + 解析设备描述）→ 选设备 → 把**当前曲目推给音箱/功放**，由它自己去 NAS 拉流（不占手机流量）；播放页/迷你条上的播放·暂停·上一首·下一首·进度都作用于音箱，**唱完自动推下一首**；纯 Android Framework，零第三方依赖 |
| 缓存 | 「边播边存」整首存本地（重播零流量）、缓存优先播放、4GB LRU、设置页可看用量/一键清空；已缓存的曲目在**播放页 + 播放队列**显示**「本地」标志**（浏览列表不加，保持干净） |
| 后台 | 前台服务 + MediaSession：通知栏 3 键、锁屏、耳机按键、音频焦点（来电/其他 App 抢占时自动暂停、拔出耳机暂停） |
| 歌词 | 服务端同步歌词（`getLyricsBySongId`）→ 服务端 LRC（`getLyrics`）→ LRCLIB → 网易云兜底；卡拉OK 滚动高亮 |
| 媒体管理 | 收藏（红心）、1–5 星评分、加入歌单/新建歌单/重命名/删除/移除曲目、播放上报（scrobble） |
| 外观 | 6 套主题（深邃黑/月光白/森林绿/午夜蓝/落日橙/玫瑰紫）+ 6 种强调色，设置页即时切换；**没有封面的曲目/专辑/艺术家用「音符占位图」**（不留空白，艺术家的占位跟着圆形头像一起变圆） |

### 服务端适配（实测「道理鱼音乐 / Music Tap」，Subsonic 1.16.0）

- nginx 会把 `/rest/xxx` **301 到 `/rest/xxx/`**：客户端启动时探测一次尾斜杠，之后所有请求（含 MediaPlayer 的流地址）都用规范形式，避免每首歌多一次重定向。
- 认证优先走 **token+salt**（`t=MD5(密码+salt)&s=salt`，每次请求换 salt）：密码不会以可还原的形式出现在 URL 里，
  因此反代 nginx 的 access log 或服务端错误日志泄露也拿不到密码；服务端不支持时自动退回 `p=enc:<hex>` 并记住该模式。
- 封面 id 形如 `al-<albumId>`；部分响应 `coverArt` 为空，按该约定兜底。
- 歌曲 JSON 里 `bitrate` 与 `bitRate` **重复键**：`org.json` 容忍重复键（后者覆盖前者），这也是选 JSON 而非 XML 的原因之一。
- `getBookmarks` 返回空响应体 → 做了容错；本机书签独立存 SharedPreferences。
- 服务端**不转码**：`stream` 直接返回原始文件（实测 `maxBitRate` / `format=mp3` 被忽略）。
  可播格式 = BASS 核心自带（**mp3 / m4a(AAC,ALAC) / flac / wav / ogg**）+ add-on 插件（**APE / WavPack / DSD / Opus**）。
- 它对**自己不认识的格式**（`.ape` / `.dsf` …）会返回**空的 `suffix`**，只在 `contentType` 里给对 MIME
  （例：`suffix=""` + `contentType: "audio/ape"`）。所以判断格式一律用 `FormatSupport.suffixOf()`
  （内部已用 `contentType` 兜底），**不要直接读 `item.suffix`**。
- `getScanStatus` 用 `count` 表示歌曲总数（标准字段名是 `songCount`），两个都兼容。

---

## 五、与桌面版的差异（有意为之）

| 能力 | 桌面版 | Android 版 | 原因 |
|------|--------|-----------|------|
| UI | HTML/CSS/JS + CEF | 原生 View | 手机竖屏需要重排，触控尺寸/手势也不一样 |
| 音频链路 | BASS + BASS_FX + BASSMIX | 系统 MediaPlayer | 无外网拿不到 Android 版 BASS；系统解码器已覆盖主流格式 |
| EQ/DSP/频谱 | 10 段 EQ + DSP + 实时频谱 | 未实现 | 依赖 BASS_FX，同上 |
| Gapless / 淡入淡出 | 支持 | 未实现 | 需要双播放器接力，本轮不做（已留 TODO） |
| 全局快捷键 | 支持 | 不适用 | 移动端无此概念，改用通知栏/耳机按键 |
| 下载原文件 | 支持 | 未实现 | 需要 MediaStore 落盘流程 |
| 分享链接 | 支持 | 复制播放链接 | — |

---

## 六、开发备忘

```powershell
# 指定设备安装
adb -s emulator-5554 install -r dist\SubsonicPlayer-0.1.0-release.apk

# 看崩溃/播放状态
adb logcat -d -t 300 | Select-String "FATAL|AndroidRuntime|PlaybackState"

# 探测服务端（会解密桌面版 settings.json 里的 DPAPI 密文，不打印密码）
powershell -File tools\run-probe.ps1        # 结果落在 build\probe\SHAPES.txt

# DLNA 推送联调：在同局域网的 Linux 机器（NAS/树莓派）上跑一个**模拟渲染器**
# （它应答 SSDP、提供 AVTransport/RenderingControl 的 SOAP 端点，并把收到的每个动作写日志）
scp tools\dlna-mock.py NAS:/tmp/ && ssh NAS "python3 /tmp/dlna-mock.py --http-port 8099 --max-dur 20"
#   --max-dur N: 把每首都当成最多 N 秒 → 方便快速验证「唱完自动推下一首」
#   日志: /tmp/dlna-mock.log（能看到 SetAVTransportURI / Play / Pause / Seek 的真实参数）
#   验完记得停: ssh NAS "pkill -f '[d]lna-mock'"（否则它会出现在 App 的设备列表里）

# 给模拟器注入服务器配置并启动
powershell -File tools\dev-push-config.ps1
```

**踩过的坑（都已在代码/脚本里规避）**

1. `build.ps1` 等 PowerShell 脚本必须 **UTF-8 with BOM**，否则中文注释被 GBK 解析导致语法错误。
2. javac 的 `@sources.txt` 必须**无 BOM**（用 `UTF8Encoding($false)` 写）。
3. d8 不接受裸目录，也不能逐个传上千个 class（命令行超长）→ 先 `jar cf` 再喂。
4. `GridView` **不支持 header/footer**，网格页的「加载中」只能用底部浮层。
5. `AbsListView.getColumnWidth()` 在 `GridView` 上，不在 `AbsListView` 上。
6. 服务器是 HTTP 明文 → manifest 必须 `usesCleartextTraffic="true"`（Android 9+ 默认禁止）。
7. Android 13+ 通知需要运行时权限 `POST_NOTIFICATIONS`：在首次播放时申请。
8. `Select-Object -First N` 会提前终止上游进程（Node 探测脚本被 EPIPE 杀掉过），排查脚本输出别用它。
9. **BASS 的格式 add-on 必须显式 `BASS_PluginLoad`**：把 `libbassape.so` 之类「链」进 `libspbass.so`（DT_NEEDED）**不会**让 BASS 认识该格式。
   漏了这一步的后果极具误导性：mp3 / m4a / flac / wav 是**核心自带**所以一直正常，而 `.ape` / `.dsf` / `.wv` 全部回
   `BASS_ERROR_FILEFORM(41)`，看起来就像「安卓端不支持这些格式」—— 实际是插件没挂上。见 `sp_bass.c` 的 `sp_load_plugins()`。
10. **`BASS_STREAM_BLOCK` 会让网络流永远不能定位**（`BASS_ChannelSetPosition` 恒返回 false）：
   于是「断点续播」实际从 0 开始、播放页**进度条拖动对所有流式曲目都无效**。
   去掉 BLOCK 即可（桌面端从来没用它）；起播快慢靠 `NET_PREBUF` + `PREBUF_WAIT=0`，不靠 BLOCK。
11. **改 `app/jni/sp_bass.c` 后必须重编 native**：`tools\build-native.ps1 -Abis 'arm64-v8a','x86_64'`。
   注意 `-Abis` 是 `[string[]]`，**要用数组写法**（`-Abis arm64-v8a,x86_64` 会被 `powershell -File` 当成两个参数报错）。
12. `build\classes.jar` 偶尔会被别的进程以「只读共享」方式占住（读得到、删不掉、覆盖不了），
   `jar cf` 覆写它就会 `AccessDeniedException` 让构建失败。`build.ps1` 已加兜底：检测到占用就改用 `classes-<时间戳>.jar`。
   （2026-09-17 遇到过，根因未查明，重启后自行消失。）
13. **曲目在本地落盘时（历史 / 队列 state / 书签）必须连 `suffix`、`contentType` 一起存**。
   只存 id/title/artist/album 的话，恢复出来的曲目格式信息全空：缓存文件名退化成 `<id>.bin`、
   `.dsf` 不会走 bassdsd 专用建流函数、「本地」标志也可能算错。写侧见 `MainActivity.recordHistory`、
   `Player.saveState`/`saveBookmark`，读侧见 `Player.restoreLastQueue`、`Pages.songFromJson`。
14. **起播要确保前台服务在跑**：`Player.startCurrent()` 里统一 `startService(PlaybackService)`。
   以前只有 `MainActivity.playNow()` 会调 —— 从**播放队列页**点歌、按耳机键切歌都绕过了它，
   结果是「有声音但没有通知栏/锁屏控制，退到后台还可能被系统回收」。
15. **心跳（ticker）不能用 `isPlaying()` 当续命条件**：`isPlaying()` = `nativeState() == 1`，
   而 BASS 在曲尾 `STOPPED(0)` / 卡顿 `STALLED(2)` 时都返回非 1 → 心跳当场永久停摆，
   **既不会「播完切下一首」，断流重连也永远等不到**（重连逻辑本身就在心跳里）。
   现在只要还有活的流就继续跑，并先 `removeCallbacks` 再 `postDelayed` 保证只有一份排队；
   曲尾判定窗口给 3 秒（窗口太窄时「曲尾」和「断流」两条分支都不进 → 卡死）。
   实测（2026-09-18 早上蓝牙车里）：08:54 卡住后 15 分钟不切歌，直到点亮屏幕触发 `resume()` 才补上。
   ⚠️ **状态码别记错**：`0=STOPPED 1=PLAYING 2=STALLED 3=PAUSED`。2026-09-20 专家发现代码里
   一直把 `3` 当「卡顿」，于是**把暂停当卡顿、真卡顿永远匹配不到**，断流重连形同虚设；
   现在统一走 `BassNative.nativeIsStalled()`，别再在 Java 里手写数字。
16. **播放期间要持有 `PARTIAL_WAKE_LOCK`**（清单里声明了 WAKE_LOCK 权限但以前从没用过）：
   屏幕关闭时保证心跳按时跑；暂停 / 停止 / 彻底放不了时释放。
   音频焦点也要区分「永久失去」和「临时失去」—— 后者（导航播报、来电、车机切源）焦点回来要自动续播。


## 八、耗电模型与「不要做」清单（2026-09-20，三路专家 + 真机 batterystats）

**实测模型**（`dumpsys batterystats --charged`，本 App 一个充电周期 **216 mAh**）：

```
mobile_radio = 203 mAh (38m14s)   ← 94%，且射频时长 ≈ 播放时长(44m18s)：就是在边听边拉流
cpu          = 7.96 mAh (32m26s)  ← 单核口径 73%，折算整机约 9%（别当成 CPU 占用率）
wakelock     = 4.53 mAh (36m30s)
audio        = 0      (44m18s)
```

**结论**：耗电几乎全是「**蜂窝射频 × 高码率原始文件**」。服务端不转码，而库内**真实**码率
650~1200kbps（服务端标签会骗人：标 128kbps 的歌实测 918kbps）。App 层能抠的只有 ~3~5%。

**唯一的大杠杆**：让同一批字节**别在蜂窝上搬第二遍** ——
设置 → 存储 → **缓存整个队列**（或让「边播边存」在 WiFi 下跑）。命中缓存的曲目射频 ≈ 0，
专家估算一次 44 分钟车程可从 203mAh 降到 5~15mAh。

**量化方法**：`tools/measure-power.ps1 -Snapshot before|after|diff`
（同时给出 mAh / 射频秒数 / 字节数 / 起播次数）。对照时必须同曲目、同使用方式，
并以 **radio 的 duration** 为主要指标 —— 只看 mAh 会被充电周期和链路抖动淹没。

**不要做**（专家明确否掉的，别再走回头路）：
1. 不要加 `BASS_STREAM_BLOCK`：seek 永久失效；而且长度未知或 >2GB 时 BASS 会**自动**加，
   要用 `BASS_ChannelFlags` 检测（检测到就退化成「下载后本地播」）。
2. 不要为了省那 4.5mAh 撤掉 `PARTIAL_WAKE_LOCK`：换来的是「不会自动切歌 / 断流不重连」。
3. 不要降 `BASS_CONFIG_UPDATEPERIOD`（100ms 已是省电端）、不要 `UPDATETHREADS>1`、
   更不要设 0（那样得自己周期 `BASS_Update`，心跳一停就静音）。
4. `DEV_BUFFER` 不要 ≥50ms（会关掉 AAudio fastpath，反而更费电）；`DEV_PERIOD/DEV_BUFFER`
   必须成对调，且**放在 `BASS_Init` 之前**（放后面静默失效）。
5. **不要把后台缓存限速压得太低**：射频按**开机时间**计费，慢速下载 = 射频窗口更长（以前理解反了）。
6. 不要降 `NET_READTIMEOUT`：慢链路会被误杀 → 整条流重建，比等 BASS 自愈更费电。
7. 不要设 `BASS_ATTRIB_NET_RESUME`：与 `PREBUF` 打架（填到 1% 就恢复 → 立刻又 stall），
   还会让 BASS 自带的 Range 重连失效。
8. 不要删掉 500ms 心跳（曲尾判定/断流检测都挂在它上面），只能**分区降频**
   （息屏中段 1500ms；临近曲尾 8 秒内、缓冲中仍 500ms）。
9. 不要降低网络探测/切地址频率：实测 21 次切换里有 6 次是「切回内网」（1900ms → 50ms），那是保可靠性。
10. 不要用「整首下完再播」替代流播（首播要等几分钟，并破坏断点续播体验）。
