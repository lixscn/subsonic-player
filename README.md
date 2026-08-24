<div align="center">

# Subsonic Player

**面向 NAS 自托管音乐服务的专业级桌面播放器**

Roon 风格 HTML 界面 · 完整音效链路（EQ/DSP/频谱）· Gapless + 淡入淡出 · 无缝播放 · 多协议（Subsonic/Emby/Plex/AudioStation）

[![MIT License](https://img.shields.io/badge/License-MIT-blue.svg)](dotnet/LICENSE)
[![.NET](https://img.shields.io/badge/.NET-10-blueviolet)](https://dotnet.microsoft.com/)
[![Build](https://github.com/lixscn/subsonic-player/actions/workflows/build.yml/badge.svg)](https://github.com/lixscn/subsonic-player/actions)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20macOS%20%7C%20Linux-informational)]()

</div>

---

> 为自建 NAS 上的 Subsonic / 兼容服务（Navidrome、Jellyfin、Gonic）提供专业级桌面体验：曲库浏览、智能推荐、音效、歌词、歌单管理一应俱全。UI 用 **HTML/CSS/JS** 自绘（Roon 风格），经 **CefGlue OSR（Chromium）** 渲染；核心逻辑与 UI 框架解耦，可跨平台。

## ✨ 功能一览

**曲库浏览 · 发现**
- 艺术家 / 专辑 / 歌曲 三级浏览，封面墙
- 全局搜索（歌曲 / 专辑 / 艺术家）
- 发现页：**随机推荐 + 智能推荐**（基于收藏偏好 + 流派亲和 + 加权评分）
- 最近添加 / 常听 / 高分、按流派 / 年代浏览
- 最近播放、书签续播

**播放 · 音效**
- HTTP 流式播放，队列管理，四种播放模式（顺序 / 随机 / 循环 / 单曲）
- **Gapless 无缝播放 + 交叉淡入淡出**（0–15s 可调，BASS Mixer 架构）
- 播放位置记忆（SQLite 持久化，重启续播）
- **10 段图形 EQ** + 预设（摇滚 / 流行 / 古典 / 人声 / 重低音），支持导入/导出
- **实时频谱** / DSP（混响 / 回声 / 合唱 / 压缩器）/ 回放速度与音调（BASS_FX tempo/pitch）
- 动态封面底图（模糊封面 + 深色遮罩，Spotify 风格）

**歌词**
- 服务端歌词（Subsonic `getLyrics` / Emby `Lyrics`）
- 网络兜底：LRCLIB + **网易云**（并行，含 Referer）
- **卡拉 OK 式**滚动（当前行放大高亮 + 前后行渐隐）
- SQLite 离线缓存，二次秒开

**媒体管理 · 系统集成**
- 歌单 CRUD、添加到歌单、收藏（红心）、评分（1–5 星）
- 分享链接、下载原文件、播放历史 + scrobble、播放队列云端同步
- 系统托盘、**迷你播放器浮窗**、SMTC 任务栏媒体控制、全局快捷键
- 深色 / 浅色主题、睡眠定时器、网络质量设置

## 📚 支持的音乐服务

| 服务 | 协议 | 说明 |
|------|------|------|
| Subsonic / OpenSubsonic | 原生 | `SubsonicClient`（salt+MD5 / 明文 `p`，按服务端能力适配） |
| Navidrome / Jellyfin / Gonic | Subsonic 兼容 | 同一客户端 |
| Emby | 原生 JSON REST | `EmbyMusicService` |
| Plex | 原生 XML | `PlexMusicService` |
| AudioStation（群晖 DSM） | 原生 | `AudioStationMusicService`（`SYNO.AudioStation.*`） |

> 自用服务端为「道理鱼音乐」（Gonic），其 API 含非标准脏数据，客户端已做适配（详见 `dotnet/MEMORY.md`）。
>
> **关于 Navidrome / Jellyfin / Gonic**：三者均实现 Subsonic 兼容 API，故**共用 `SubsonicMusicService`**（`SubsonicClient`），无需独立协议客户端；在「设置 → 音乐服务器 → 类型」里选对应类型即可。

## 🧱 技术栈

| 层 | 技术 |
|----|------|
| 运行时 | .NET 10 |
| UI 宿主 | Avalonia 11.3.20 + Semi.Avalonia 11.3.14 |
| **UI 渲染** | **HTML/CSS/JS**（原生，无框架）+ CefGlue.Avalonia 120（Chromium OSR 离屏渲染） |
| 音频引擎 | **BASS + BASS_FX + BASSMIX**（Gapless / Crossfade / EQ / DSP / tempo） |
| MVVM / 数据 | CommunityToolkit.Mvvm 8.4.1 |
| 存储 | Microsoft.Data.Sqlite 10.0.9（曲库缓存 / 历史 / 歌词 / 播放状态） |
| 歌词兜底 | LRCLIB + 网易云 |

## 🏗️ 架构

```
dotnet/
├── src/
│   ├── SubsonicPlayer.Core/     # 共享逻辑（与 UI 框架解耦，无 Avalonia）
│   │   ├── Models/              # 数据实体（Song / Album / Artist / Playlist / Lyrics…）
│   │   └── Services/            # 音频引擎、PlaybackService、协议客户端（Subsonic/Emby/Plex/AudioStation）、
│   │                            #   歌词、智能推荐、SQLite 缓存、Settings、AppServices、平台抽象
│   ├── SubsonicPlayer.Cef/      # ★ 主力。HTML UI（WebAssets/）+ CefGlue.Avalonia OSR
│   │   ├── WebAssets/           # index.html / styles.css / app.js / mini.html
│   │   └── Services/            # CefUiBridge / CefPageDataProvider / SMTC / 迷你播放器 / 平台注入
│   ├── SubsonicPlayer.Mobile/   # 移动端（P6 规划，占位）
│   └── (SubsonicPlayer.Desktop 已移除：UI 统一为 CEF/HTML)
└── publish.ps1                  # 全平台发布脚本
```

- **UI 层**（Cef）读取 `AppServices`，通过 `CefUiBridge`（C#→JS）与 `CefPageDataProvider`（JS→C#，`Bridge.invokeData`）双向通信；状态由 C# 经 `bridgeEvent` 推送。
- **Core 层**不依赖 Avalonia（封面用 `byte[]`、计时器走 `IActionDispatcher` 抽象），移动端 / 其它 UI 可直接复用。

## 🚀 快速开始

依赖：**.NET 10 SDK**（Windows 本地构建）。

```powershell
cd dotnet

# 构建（Windows）
dotnet build src/SubsonicPlayer.Cef/SubsonicPlayer.Cef.csproj -f net10.0-windows10.0.19041.0

# 运行
Start-Process "src/SubsonicPlayer.Cef/bin/Debug/net10.0-windows10.0.19041.0/SubsonicPlayer.exe"
```

**首次使用**：启动后在「设置 → 音乐服务器」填写服务器地址（内网/外网）、用户名、密码即可。凭据加密存储（Windows DPAPI / 其他平台 AES-GCM），**不硬编码、不提交仓库**。

**跨平台发布**（自包含，无需装 .NET）：

```powershell
cd dotnet
powershell -ExecutionPolicy Bypass -File publish.ps1 -Platform win-x64,linux-x64,osx-x64,osx-arm64
```

## 🤖 CI 与发布

每次 `push` / `pull_request`，GitHub Actions 在 **Windows / Ubuntu / macOS 真机** 编译并发布三平台；打 `v*` tag 时自动创建 GitHub Release 并挂载三平台 zip。

```bash
git tag v1.1.0 && git push origin v1.1.0   # 自动发版
```

## 📁 项目文档

- `dotnet/PLAN.md` — 设计方案与里程碑（P1–P6）
- `dotnet/MEMORY.md` — 项目记忆 / 技术栈 / 踩坑 / 跨平台 / 调试技巧
- `AGENTS.md` — 开发约定（构建命令、铁律、跨平台、调试）

## 📄 许可

[`dotnet/LICENSE`](dotnet/LICENSE) · MIT © 2026 [lixscn](https://github.com/lixscn)。音频引擎 BASS 系列为 Un4seen 产品，非商业使用免费，商用需另行授权。
