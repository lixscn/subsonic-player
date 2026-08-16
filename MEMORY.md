# subsonic-player 项目记忆

> 项目记忆。完整设计方案见 `PLAN.md`。

## 一句话

面向 NAS 音乐服务的**专业级桌面音乐播放器**。当前自用服务端为「**道理鱼音乐**」（Gonic）。

## 技术栈（已定）

.NET 10 + Avalonia 12.0.3 + Semi.Avalonia + MVVM（CommunityToolkit.Mvvm）+ SQLite；音频引擎 BASS + BASS_FX。

## 服务器连接

- **地址分内网/外网，连接时内网优先、不可达回退外网**（已实现于 `SubsonicClient.ConnectAsync`）
- **服务器地址/用户名/密码均可配置**（持久化到 settings，支持多服务器切换，不硬编码）
- 服务器地址与凭据由用户在设置页填写，不提交到仓库

## 多服务支持目标

- 目标协议：Subsonic（原生）、Navidrome、Jellyfin、Emby、Plex、AudioStation（群晖）等 NAS 音乐服务
- 现状：Subsonic 协议族（Subsonic / Navidrome / Jellyfin 兼容模式 / Gonic）已覆盖；Emby / Plex / AudioStation 需各自实现协议客户端
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

## 来源项目

本播放器服务的音乐库/Subsonic 服务托管在自建的 NAS 上。
