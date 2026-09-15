# CURRENT_STATE.md — 接班说明（start here）

> 给下一个会话/代理的交接说明。详细技术记忆与踩坑见 `dotnet/MEMORY.md`（尾部有 2026-09-15 会话的完整记录）。
> 仓库布局已从 `dotnet/` 迁到 **`subsonic-player/dotnet/`**（CI 已同步），本文件内路径均为新布局。

## 这是什么

**Subsonic Player**（`github.com/lixscn/subsonic-player`，工作区 `D:\work_space\DeepSeekHarness\music-play`），.NET 10 + Avalonia 11.3.20 + CefGlue.Avalonia 120（Chromium 120, OSR 离屏渲染）。UI 为 HTML（`WebAssets/`，内嵌为 `AppUI.*` 程序集资源，经自定义 `app://` scheme 服务）。音频 BASS。

- `subsonic-player/dotnet/` 桌面端 ｜ `subsonic-player/android/` 原生 Android 端 ｜ `subsonic-player/website/` 官网

## 当前可运行状态（已达成）

- **单文件版**：`D:\tools\SubsonicPlayer-single\SubsonicPlayer.exe`（~253MB，应用托管代码 + .NET 运行时）。
  CEF 原生 + `lib\bass*` **全外置**（CEF 不能打包进 exe）。
- 发布：`cd subsonic-player/dotnet && powershell -ExecutionPolicy Bypass -File publish-singlefile.ps1`
  （**必须在 dotnet 目录下执行**；且需 `AVALONIA_TELEMETRY_OPTOUT=1`，否则构建报错）。
  发布后**清 `%APPDATA%\subsonic-player\cef-cache`**，否则加载的是旧前端。
- **CI 绿**：Windows 产单文件；Linux/macOS 非单文件。

## 2026-09-15 会话：桌面端四连修（已发布并实测）

| 问题 | 根因 | 结果 |
|---|---|---|
| 关闭关不掉 / 关时崩溃 / 托盘图标丢失 | `_spectrumTimer` 从未赋值 ⇒ `Shutdown()` 必抛 NRE ⇒ 打断关机流程；旧 `Exit()` 先 Dispose 托盘 | 已修：幂等 + 逐步兜底 + 托盘延后移除 + 强退兜底 |
| 点歌不能马上播放 | BASS 配置项索引全错（14/25/26 非法，静默失效）⇒ 预缓冲 2.4s + 5s 超时降级整文件下载 | 已修：正确索引 + 预缓冲 10% + 超时 15s |
| 一直显示「正在重连」/窗口卡住 | `ConnectAsync` 探测失败会把 `_server` 永久切到不可达的 WAN；3s 超时短于真实响应 | 已修：成功才提交地址 + 退避 + 每请求快照 |
| 库浏览慢（首屏 5s） | 服务端 music-tag-web **单线程串行**，总耗时 = 请求条数 × 78ms；客户端三处「几十个请求办一件事」 | 首屏 **~5s → ~1.5s**（歌曲页 4816→790ms、发现页 2602→781ms） |
| 点歌后界面无反馈 / 失败无声 | 缺即时反馈与错误提示 | 已加：起播中转圈 + 播放失败 toast |

**实测**：点歌 → 出声 ~1s；`getCoverArt`/封面、关闭/托盘、分页器「上一页」均正常。

## 服务端侧（重要背景）

- 服务端 = **music-tag-web**（`xhongc/music_tag_web`）@ `<NAS_LAN_IP>:8002`，挂载 `<NAS 音乐库目录> → /app/media`。
- 它**单线程串行**处理请求：并发 N 个时每个耗时 ≈ N×78ms ⇒ 优化只能靠「减请求数 + 加缓存」。
- **转码已关闭**（用户 2026-09-15 停掉），现返回原始格式与大小，首字节 135~523ms。
  m4a 仍可能 `err=Unstreamable`（非 faststart），客户端有「下载整文件再播」兜底（LAN 上 100~240ms）。
- 曲库：100GB / 15032 文件 / 6350 音频 / 6252 条库内曲目；**<80kbps 占 72% 曲目但只占 9.6GB**。
- **`music/attachments/` 是它的封面库，不是垃圾目录**（删了 `getCoverArt` 立刻 404）。

## 待办（用户要求"都做"，尚未实现）

1. **全屏播放器页**（HTML UI 新增沉浸式 Now Playing 视图）
2. **智能歌单编辑器（Navidrome）**（协议专属规则编辑）
3. **furigana/romaji 显示**

## 建议的下一步

- 先把 1（全屏播放器页）做掉：改动集中在 `WebAssets` + 少量桥方法，用户可感知度高。
- 继续压客户端请求条数的空间还在：`GetPagerTotals` 的歌曲总数（约 6s，已 quiet 且缓存 30min）可改为
  「随歌曲累加器增量得出」；`getAlbumsPage` 仍要 2 个请求（专辑列表 + 艺术家索引）。

## 关键目录

- `subsonic-player/dotnet/src/SubsonicPlayer.Cef/WebAssets/` —— HTML 主界面（index.html / styles.css / app.js / mini.html）。
- `subsonic-player/dotnet/src/SubsonicPlayer.Cef/Services/` —— `AppSchemeHandler.cs`（app:// + 内嵌资源）、
  `CefUiBridge.cs`（C#↔JS 桥）、`CefPageDataProvider.cs`（页面数据，含 search3 快路径）。
- `subsonic-player/dotnet/src/SubsonicPlayer.Core/Services/` —— AudioEngine / PlaybackService / SubsonicClient /
  RecommendationService / LimitedParallel / AppLog / ImageLoader。
- `subsonic-player/dotnet/MEMORY.md` —— 技术栈与全部踩坑（**本会话记录在文件尾部**）。
- `subsonic-player/dotnet/publish-singlefile.ps1` —— 单文件发布（含子进程依赖补齐 + locales 扁平化）。
