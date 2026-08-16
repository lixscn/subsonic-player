# Subsonic Player

面向 NAS 音乐服务的**专业级桌面音乐播放器**。

## 支持的音乐服务

| 服务 | 协议 | 支持状态 |
|------|------|----------|
| Subsonic | 原生 Subsonic / OpenSubsonic | ✅ 已实现 |
| Navidrome | 兼容 Subsonic API | ✅ 可用 |
| Jellyfin | Subsonic 兼容模式 | ✅ 可用（原生 API 规划中） |
| Gonic | 兼容 Subsonic API | ✅ 已适配 |
| Emby | Emby 原生 API | 🔜 规划中 |
| Plex | Plex API | 🔜 规划中 |
| AudioStation（群晖） | Synology DMS API | 🔜 规划中 |

> 当前自用的服务端是「**道理鱼音乐**」（Gonic 实现，Go 编写）。其 API 存在若干非标准脏数据，客户端已做适配处理，详见 `MEMORY.md`。

## 技术栈

- .NET 10
- Avalonia 12.0.3 + Semi.Avalonia
- MVVM（CommunityToolkit.Mvvm）
- 音频引擎：BASS + BASS_FX

## 功能概览

- 曲库浏览（艺术家 / 专辑 / 歌曲 / 搜索 / 发现）
- 播放（流式播放、队列、播放列表、收藏）
- 音效（EQ、频谱、交叉淡入淡出、DSP —— 部分在 P2 迭代中）
- 系统托盘 / 迷你播放器 / 全局快捷键

> 项目仍处于迭代中，里程碑规划见 `PLAN.md`，当前状态与设计决策见 `MEMORY.md`。

## 构建

```bash
dotnet build subsonic-player/SubsonicPlayer.csproj
```

## 服务器连接配置

服务器地址、用户名、密码**不硬编码**，也不提交到仓库。首次启动应用后，在「设置 → 服务器连接」页填写即可，配置会持久化到本机数据目录（`%APPDATA%\subsonic-player\settings.json`，已被 `.gitignore` 忽略）。

> 兼容 Subsonic / OpenSubsonic 协议（Gonic / Navidrome / Jellyfin 等）。本项目的源服务端为 Gonic，存在若干非标准脏数据，客户端已做适配处理，详见 `MEMORY.md`。
>
> 其他协议（Emby / Plex / AudioStation）通过 `MusicServiceFactory` 的 `Type` 分支扩展接入，尚未实现。
