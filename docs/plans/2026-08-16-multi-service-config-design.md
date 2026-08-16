# 多音乐服务支持配置 — 设计

> 日期：2026-08-16

## 目标

让用户在设置界面管理多个 NAS 音乐服务（服务器），每个服务含名称、类型、内网/外网地址、用户名、密码，并可增删改、切换当前服务。协议先覆盖 Subsonic 兼容族（Subsonic / Navidrome / Jellyfin / Gonic），其余协议（Emby / Plex / AudioStation）后续按 Type 扩展。

## 数据模型与类型

`MusicServiceType` 扩展为 4 个标签（协议行为一致，仅作标签）：

```csharp
public enum MusicServiceType
{
    Subsonic,
    Navidrome,
    Jellyfin,
    Gonic,
}
```

`MusicServiceConfig` 与 `AppSettings` 结构不变（已支持多服务列表 + 当前服务 id）。

## 服务层与切换行为

`AppServices` 新增：

- 事件：`ServicesChanged`（增删改）、`CurrentServiceChanged`（切换）
- `AddService(config)` / `UpdateService(config)` / `RemoveService(id)`（最后一个禁止删，删当前自动切剩余第一个）
- `SwitchTo(id)`：保存当前服务 id → 重建 `Music` → `Playback.StopAndClear()` → `Favorites.Reset()` + `LoadAsync()` → 触发 `CurrentServiceChanged`

`PlaybackService` 新增 `StopAndClear()`：停引擎、清队列、`CurrentSong = null`。

`FavoritesService` 新增 `Reset()`：清 `_loadTask` 缓存与 `_songIds`。

`MusicServiceFactory` 保持不变（4 类型都走 `SubsonicMusicService`）。

## ViewModel 与 UI

- `SettingsViewModel`：`ObservableCollection<MusicServiceConfig>` 列表 + 选中项 + 编辑字段（Name/Type/LanUrl/WanUrl/Username/Password）+ 命令（Add/Remove/SetCurrent/Save）+ `TypeOptions`
- `SettingsView.axaml`：服务列表 + 编辑表单 + 按钮行
- 顶栏：`ComboBox` 服务切换（`MainWindowViewModel` + `MainWindow.axaml`），订阅 `CurrentServiceChanged` 刷新页面
- 首次运行：创建空模板「默认服务器」

## 错误处理

切换/保存失败时静默回退到原状态；删除最后一个服务被禁用。
