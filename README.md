# Subsonic Player

面向 NAS 音乐服务的**专业级桌面音乐播放器**。

## 支持的音乐服务

| 服务 | 协议 | 支持状态 |
|------|------|----------|
| Subsonic | 原生 Subsonic / OpenSubsonic | ✅ 已实现 |
| Navidrome | 兼容 Subsonic API | ✅ 可用 |
| Jellyfin | Subsonic 兼容模式 | ✅ 可用 |
| Gonic | 兼容 Subsonic API | ✅ 已适配 |
| Emby | Emby 原生 API | ✅ 已实现 |
| Plex | Plex API | ✅ 已实现 |
| AudioStation（群晖） | Synology DMS API | 🔜 规划中 |

> 当前自用的服务端是「**道理鱼音乐**」（Gonic 实现，Go 编写）。其 API 存在若干非标准脏数据，客户端已做适配处理，详见 `MEMORY.md`。

## 技术栈

- .NET 10
- Avalonia 12.0.3 + Semi.Avalonia
- MVVM（CommunityToolkit.Mvvm）
- SQLite（Microsoft.Data.Sqlite，曲库缓存 / 播放历史 / 歌词 / 播放状态）
- 音频引擎：BASS + BASS_FX

## 功能概览

- **曲库浏览**：艺术家 / 专辑 / 歌曲 / 搜索 / 发现（最新·最高评分·最常播放·随机）/ 最近播放 / 书签
- **播放**：流式播放、队列管理、四种播放模式、Gapless 无缝、交叉淡入淡出、播放位置记忆（本地 SQLite 恢复）
- **歌词**：服务端歌词 + 网络兜底搜索（LRCLIB），同步歌词逐行滚动，SQLite 离线缓存
- **音效**：10 段 EQ + 预设（导入/导出）、频谱可视化、DSP（混响/回声/合唱/压缩器）、速度/音调
- **媒体管理**：歌单 CRUD、收藏、评分、分享链接、下载原文件、播放历史、书签、播放队列云端同步
- **系统集成**：系统托盘、迷你播放器（与大窗口互转）、全局快捷键、SMTC 任务栏媒体控制、睡眠定时器、深浅色主题
- **多服务**：Subsonic / Navidrome / Jellyfin / Gonic / Emby / Plex，统一 `IMusicService` 抽象

> 项目仍处于迭代中，里程碑规划见 `PLAN.md`，当前状态与设计决策见 `MEMORY.md`。

## 构建

```bash
dotnet build subsonic-player/SubsonicPlayer.csproj
```

## 服务器连接配置

服务器地址、用户名、密码**不硬编码**，也不提交到仓库。首次启动应用后，在「设置 → 服务器连接」页填写即可，配置会持久化到本机数据目录（`%APPDATA%\subsonic-player\settings.json`，已被 `.gitignore` 忽略）。

> 兼容 Subsonic / OpenSubsonic 协议（Gonic / Navidrome / Jellyfin 等）。本项目的源服务端为 Gonic，存在若干非标准脏数据，客户端已做适配处理，详见 `MEMORY.md`。
>
> Emby / Plex 通过 `MusicServiceFactory` 的 `Type` 分支走各自原生协议客户端（`EmbyMusicService` / `PlexMusicService`），复用同一套 `IMusicService` 抽象与 UI。认证：Emby 用用户名密码或 API Key、Plex 用 `X-Plex-Token`。AudioStation 规划中。
