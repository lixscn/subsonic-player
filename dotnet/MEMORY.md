# subsonic-player 项目记忆

> 项目记忆。完整设计方案见 `PLAN.md`。

## 一句话

面向 NAS 音乐服务的**专业级桌面音乐播放器**。当前自用服务端为「**道理鱼音乐**」（Gonic）。

## 技术栈（已定）

.NET 10 + Avalonia 11.3.20 + Semi.Avalonia 11.3.14 + MVVM（CommunityToolkit.Mvvm）+ SQLite；音频引擎 BASS + BASS_FX；**UI 已迁移为 HTML（Chromium 120 via CefGlue.Avalonia 120.6099.211）**。

## UI 技术（CEF 迁移，重要）

- **为什么降级 Avalonia 12→11.3.20**：CefGlue.Avalonia 120（Chromium 120）依赖 Avalonia 11.0.9 编译，在 Avalonia 12 上运行时报 `TypeLoadException: Could not load type 'Avalonia.Input.GotFocusEventArgs'`（二进制不兼容，编译过但运行崩）。降到 11.3.20 后正常。
- **Avalonia 11 兼容改动**：`TextBox.PlaceholderText`→`Watermark`；`WindowDecorations` 属性（12 新增）需移除。
- **exclr8cef 已弃用**：NuGet 包只有托管 DLL，native 二进制缺失（runtime.*.Exclr8Cef 包在 nuget.org 全部 404、GitHub 无 release），无法落地。
- **HTML UI 架构**：
  - WebAssets/ 目录存放 index.html / styles.css / app.js（Roon 风格深色 UI）
  - 自定义 scheme `app://ui/`（`AppSchemeHandlerFactory` + `FileResourceHandler` 实现 `CefResourceHandler` 新式 Open/Read 异步 API）服务本地文件
  - C#→JS：`CefUiBridge`（`RegisterJavascriptObject` 暴露为 `window.bridge`），`PropertyChanged`/事件订阅后 `ExecuteJavaScript` 推送 `bridgeEvent` CustomEvent
  - JS→C#：`window.bridge.invokeData(method, argsJson)` 统一入口（`CefUiBridge.InvokeData` 内部 `Dispatcher.UIThread.Invoke` 调度，避免 CEF 回调线程问题）；`CefPageDataProvider` 提供页面 DTO
  - 封面用 `IMusicService.GetCoverArtUrl` 返回带认证 URL 给 `<img src>`；播放走 BASS（JS 只发命令，进度由 C# 推送）
- **CEF 缓存**：`%APPDATA%\subsonic-player\cef-cache`，改 HTML/CSS/JS 后若 UI 异常先清缓存
- **CEF 初始化**：Program.cs `AfterSetup` 里 `CefRuntimeLoader.Initialize(settings, cmdArgs, customSchemes)`，需 `WindowlessRenderingEnabled = true`（OSR）

## 已踩大坑（CefGlue + HTML UI）

1. **右侧"边框阴影"其实是 queue-panel 的 box-shadow**：
   - `.queue-panel`（播放队列侧面板）`position:fixed; right:0`，关闭时用 `style.right='-340px'` 移出屏幕，但**其 `box-shadow: -12px 0 40px rgba(0,0,0,0.4)` 仍向左投射**到内容区右缘 → 整个右侧出现渐变阴影。
   - **深色下看不见**（阴影深色融入背景），**浅色下显形**（白色背景上成灰渐变）。
   - **排查方法**：建独立 CEF 测试项目（`dotnet/src/CefShadowTest`），从纯白页逐步加元素二分定位——空白页无阴影、空容器+完整CSS无阴影、完整 index.html 有阴影，逐个清空 侧栏/顶栏/内容/播放栏 后仍存在，**移除 queue-panel 后阴影消失**。
   - **修复**：`.queue-panel` 默认 `visibility:hidden`，JS 打开时设 `visibility:visible`、关闭时 `hidden`。关闭状态完全不渲染（含阴影）。
   - **教训**：`position:fixed/absolute` + `box-shadow` 的元素，即使移出视口阴影仍会投射，需用 `visibility:hidden` 而非仅位移。

2. **CefGlue.Avalonia 的 OSR 尺寸问题（未彻底解决）**：
   - `AvaloniaControl.OnLayoutUpdated` 用 `Bounds.Width/Height`（逻辑像素）传 CEF，**不乘 RenderScaling**，高 DPI（150%）下 OSR 视口比窗口窄，右侧可能有残留——经排查该残留与 queue-panel 阴影叠加，queue-panel 修复后主问题消失。

3. **图标用 SVG symbol sprite（`<svg width=0><defs><symbol>`）替代 unicode/emoji**：unicode 图标（▶/⏮/⏭/🔁 等）在 CEF 字体下渲染闪烁/缺字方块；SVG `<use href="#i-xxx">` 稳定且可切换。

## 服务器连接

- **地址分内网/外网，连接时内网优先、不可达回退外网**（已实现于 `SubsonicClient.ConnectAsync`）
- **服务器地址/用户名/密码均可配置**（持久化到 settings，支持多服务器切换，不硬编码）
- 服务器地址与凭据由用户在设置页填写，不提交到仓库

## 多服务支持目标

- 目标协议：Subsonic（原生）、Navidrome、Jellyfin、Emby、Plex、AudioStation（群晖）等 NAS 音乐服务
- 现状：Subsonic 协议族（Subsonic / Navidrome / Jellyfin 兼容模式 / Gonic）+ Emby + Plex 均已实现；AudioStation 规划中
- Emby/Plex 走 `MusicServiceBase` 派生客户端（`EmbyMusicService` JSON / `PlexMusicService` XML）
- 认证：Emby 用用户名密码 AuthenticateByName 或 API Key；Plex 用 `X-Plex-Token`（存 ApiKey 字段）
- 扩展点：`MusicServiceFactory.Create` 按 `MusicServiceConfig.Type` 分支创建不同 `IMusicService` 实现

## 服务器实现（重要，已验证）

- 服务端是 **Gonic**（Go 实现，非原生 Subsonic/Navidrome），API 兼容但有脏数据：
  - 认证用 **`p` 参数（明文密码）**，**不支持** token（`MD5(password+salt)` 会报 error 41）
  - `album.artist` 返回 Go 内存地址（如 `0xc001b0a7c0`）、`year`/`track`/`path`/`size` 返回 `<nil>` 或地址
  - `getArtists` 响应含非法 XML 控制字符（如 0x19），解析前需正则清理
  - `song.artist`/`song.artistId`/`id`/`title`/`duration`/`coverArt` 字段正常
  - id 前缀：`alb_`（专辑）、`trk_`（歌曲）、`art_`（艺术家）；coverArt 值形如 `album:alb_xxx`
- 曲库规模：约 2174 艺术家 / 28 个字母索引

## 关键点

- 播放走 BASS Mixer + DECODE 流，实现 Gapless + Crossfade
- 音效：10 段 EQ + 预设、交叉淡入淡出、无缝、ReplayGain、DSP（混响/回声/合唱/立体声扩展/压缩器）、频谱可视化
- 分 P1~P4 里程碑实现（见 PLAN.md）

## UI 布局规范（重要，多次返工）

- **所有列表/网格行的 Grid 列宽必须用固定宽度**（如 `40,36,180,48,32`），**禁止用 `*` 自适应列宽**
- 用 `*` 会让标题列伸缩，导致右侧的时长/红心/加号等列随标题长度浮动、**收缩错位、不整齐**（用户多次反馈此问题）
- 参考：歌曲列表行、播放队列弹窗、正在播放页队列等，均用固定列宽
- 若容器宽度有限（如 320 面板），适当缩小固定列宽（如标题列 160）而不是改用 `*`

## 来源项目

本播放器服务的音乐库/Subsonic 服务托管在自建的 NAS 上。
