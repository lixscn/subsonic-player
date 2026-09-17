# AGENTS.md — Android 端开发约定

Subsonic Player 的**原生 Android 客户端**（`subsonic-player/android/`）。桌面版见 `../dotnet/`，架构与协议差异见 `README.md`。

## 这是什么

纯 **Java + Android Framework** 的 App，**零第三方依赖**（无 androidx、无 Kotlin、无 Gradle）。
开发机**无外网**，所以走本机 Android SDK 命令行工具链构建，**不要**试图引入 Gradle / androidx / Media3 / Glide 等依赖（拉不下来）。

## 常用命令

```powershell
cd subsonic-player/android

# 构建（产出 dist\SubsonicPlayer-<版本>-release.apk）
powershell -ExecutionPolicy Bypass -File build.ps1
powershell -ExecutionPolicy Bypass -File build.ps1 -Install   # 构建 + 安装 + 启动
powershell -ExecutionPolicy Bypass -File build.ps1 -Clean

# 启动模拟器（AVD 名 sp_test，android-34 x86_64）
D:\tools\Android\Sdk\emulator\emulator.exe -avd sp_test -no-window -no-audio -gpu swiftshader_indirect

# 设备/日志
D:\tools\Android\Sdk\platform-tools\adb.exe devices
D:\tools\Android\Sdk\platform-tools\adb.exe logcat -d -t 300 | Select-String "FATAL|AndroidRuntime"

# 给设备注入服务器配置（解密桌面版 settings.json 的 DPAPI 密文，不回显密码）
powershell -File tools\dev-push-config.ps1

# 探测真实服务端 API（字段结构、二进制端点行为）
powershell -File tools\run-probe.ps1      # 报告落在 build\probe\SHAPES.txt

# 重新生成矢量图标
powershell -File tools\gen-icons.ps1
```

## 铁律

1. **`build.ps1` 与 `tools\*.ps1` 必须 UTF-8 with BOM**。Windows PowerShell 5.1 会把无 BOM 的中文脚本按 GBK 解析并报「字符串缺少终止符」。改完脚本务必确认首字节是 `EF BB BF`。
2. **javac 的 `@sources.txt` 必须无 BOM**（脚本里用 `UTF8Encoding($false)` 写），否则 javac 报「非法参数」。
3. **禁止 Java 9+ 语法与 API**：不能用 `var`、`List.of`、`Map.of`、`String.isBlank`、`String.repeat`、records、switch 表达式、`HexFormat`。目标是 `-source 8 -target 8` + d8（minApi 29）。lambda 与 try-with-resources 可以用。
4. **禁止 androidx / 任何第三方库**。可用的只有 Android Framework：列表用 `ListView`/`GridView`（**没有 RecyclerView**）、通知用 `Notification.MediaStyle`（**没有 MediaSessionCompat**）、JSON 用 `org.json`、弹窗用 `android.app.AlertDialog`。
5. **没有 layout XML**，界面全部程序化建 View。主题色一律走 `Ui.colors(act)`，**不要硬编码颜色**；切换主题靠 `MainActivity.applyTheme()` 重建外壳与当前页。
6. **页面之间零耦合**：新增页面必须在 `ui/Pages.java` 里加工厂方法，由 `MainActivity` 的导航方法（`push/pop/openXxx/playXxx`）跳转，页面类之间不互相 `new`。
7. **注册了 `Player.Listener` 的页面必须在 `onDestroy()` 里 `removeListener`**，否则页面销毁后回调仍会打到旧视图上。
8. **网络请求失败必须可重试、不能白屏不能崩**；服务端脏数据（空 `coverArt`、`getBookmarks` 返回空体、`bitrate`/`bitRate` 重复键）都要容错。
9. **服务器凭据不硬编码、不打印、不进日志**：`Settings` 只存 `AndroidKeyStore` 加密后的密文（`Secret`），内存中的明文不落盘。
10. **`Select-Object -First N` 会提前终止上游进程**（探测脚本被 EPIPE 杀过），跑 Node/长输出脚本时改用 `-Last N` 或重定向到文件。

## 关键目录

| 路径 | 说明 |
|------|------|
| `app/java/com/lixscn/subsonicplayer/MainActivity.java` | 唯一 Activity：外壳（顶栏/内容/迷你播放条/底部导航）+ 页面路由 |
| `core/SubsonicClient.java` | Subsonic REST 客户端（尾斜杠探测、`p=enc:` 认证、JSON 解析） |
| `core/Library.java` | 数据门面：后台取数 + 主线程回调 + 内存 TTL 缓存 + 「全部歌曲」渐进加载 |
| `core/ConfigImport.java` | `server.json` 导入（换机/批量部署/自动化联调） |
| `player/Player.java` | 播放引擎（队列/模式/进度/位置记忆） |
| `player/PlaybackService.java` | 前台服务 + MediaSession + 通知栏控制 |
| `ui/Ui.java` `ui/Theme.java` | 控件工厂 / 6 套主题配色 |
| `ui/ItemListPage.java` `ui/Pages.java` | 通用列表页 / 页面工厂（跳转入口） |
| `ui/pages/*.java` | 各业务页面 |

## 已知限制（有意为之，别当 bug 修）

- 无 BASS 音效链路：EQ / DSP / 实时频谱 / Gapless / 淡入淡出 均未实现。
- 服务端**不转码**（实测 `maxBitRate` / `format=mp3` 被忽略），`stream` 直接给原始文件。
  可播格式 = **BASS 核心自带**（mp3 / m4a(AAC,ALAC) / flac / wav / ogg）+ **add-on 插件**（APE / WavPack / DSD / Opus）。
  ⚠️ 插件必须显式 `BASS_PluginLoad` 才生效（见 `sp_bass.c` 的 `sp_load_plugins()`）——
  2026-09-17 之前漏了这一步，导致 .ape/.dsf/.wv 一律 `BASS_ERROR_FILEFORM(41)` 被当成「坏文件」，
  还被误记成「安卓端就是不支持」。
- 下载原文件、分享链接（仅复制播放链接）未实现。
- 「全部歌曲」没有服务端端点，是按专辑分页展开的渐进式加载，首次进入需要滚一会儿才完整。
- 「最近播放」是本机记录（`sp_history`），不是服务端 scrobble 历史。
- **服务端脏数据**：它对不认识的格式（APE/DSD 等）返回**空 `suffix`**，只在 `contentType` 里给对 MIME。
  所以判断格式一律走 `FormatSupport.suffixOf()`（内部已用 `contentType` 兜底），别直接读 `item.suffix`。
