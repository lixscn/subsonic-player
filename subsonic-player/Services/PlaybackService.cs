using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Media;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>播放模式。</summary>
public enum PlayMode
{
    Sequence,
    Shuffle,
    Repeat,
    RepeatOne,
}

/// <summary>播放服务：队列、Gapless、Crossfade、EQ、tempo、DSP、频谱。</summary>
public partial class PlaybackService : ObservableObject
{
    public const int EqBandCount = 10;

    private readonly AudioEngine _engine = new();
    private readonly List<Song> _queue = new();
    private int _currentIndex = -1;
    private int _preloadChannel;
    private readonly DispatcherTimer _progressTimer;
    private readonly DispatcherTimer _spectrumTimer;
    private DispatcherTimer? _sleepTimer;
    private string? _submittedSongId;
    private double _resumePositionSeconds;
    private DateTime _lastStateSave = DateTime.MinValue;

    [ObservableProperty]
    private Song? _currentSong;

    [ObservableProperty]
    private IImage? _currentCover;

    [ObservableProperty]
    private bool _isPlaying;

    [ObservableProperty]
    private double _positionSeconds;

    [ObservableProperty]
    private double _durationSeconds;

    [ObservableProperty]
    private double _volume = 0.8;

    [ObservableProperty]
    private PlayMode _playMode = PlayMode.Sequence;

    [ObservableProperty]
    private double _tempo = 1.0;

    [ObservableProperty]
    private double _pitch;

    [ObservableProperty]
    private bool _reverbEnabled;

    [ObservableProperty]
    private bool _echoEnabled;

    [ObservableProperty]
    private bool _chorusEnabled;

    [ObservableProperty]
    private bool _compressorEnabled;

    [ObservableProperty]
    private double[] _spectrum = Array.Empty<double>();

    /// <summary>交叉淡入淡出时长（秒），0 表示关闭（纯 Gapless）。</summary>
    public double CrossfadeSeconds { get; set; } = 3.0;

    public PlaybackService()
    {
        _progressTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(200) };
        _progressTimer.Tick += (_, _) => UpdateProgress();
        _progressTimer.Start();

        _spectrumTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(50) };
        _spectrumTimer.Tick += (_, _) => Spectrum = _engine.GetSpectrum(64);
        _spectrumTimer.Start();
    }

    partial void OnVolumeChanged(double value) => _engine.Volume = (float)value;
    partial void OnTempoChanged(double value) => _engine.SetTempo((float)value);
    partial void OnPitchChanged(double value) => _engine.SetPitch((float)value);
    partial void OnReverbEnabledChanged(bool value) => _engine.SetReverb(value);
    partial void OnEchoEnabledChanged(bool value) => _engine.SetEcho(value);
    partial void OnChorusEnabledChanged(bool value) => _engine.SetChorus(value);
    partial void OnCompressorEnabledChanged(bool value) => _engine.SetCompressor(value);
    partial void OnIsPlayingChanged(bool value) => AppServices.Smtc.UpdatePlaybackStatus(value);

    // ---- EQ ----

    public void SetEqGain(int band, float gain) => _engine.SetEqGain(band, gain);
    public float GetEqGain(int band) => _engine.GetEqGain(band);
    public static float GetEqFrequency(int band) => AudioEngine.GetEqFrequency(band);

    public string PositionText => Format(PositionSeconds);
    public string DurationText => Format(DurationSeconds);

    partial void OnPositionSecondsChanged(double value) => OnPropertyChanged(nameof(PositionText));
    partial void OnDurationSecondsChanged(double value) => OnPropertyChanged(nameof(DurationText));

    private static string Format(double seconds)
    {
        var t = TimeSpan.FromSeconds(seconds);
        return t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");
    }

    // ---- 队列 / 播放 ----

    public void PlayQueue(IEnumerable<Song> songs, int startIndex = 0)
    {
        _queue.Clear();
        _queue.AddRange(songs);
        if (_queue.Count == 0)
            return;

        CacheSongs(_queue.ToList());

        _currentIndex = Math.Clamp(startIndex, 0, _queue.Count - 1);
        PlayCurrent(_queue[_currentIndex]);
        PreloadNext();
    }

    /// <summary>插入到当前曲目后面（下一首播放）。</summary>
    public void PlayNext(Song song)
    {
        if (_currentIndex < 0 || _queue.Count == 0)
        {
            PlayQueue(new[] { song }, 0);
            return;
        }

        _queue.Insert(_currentIndex + 1, song);
        CacheSongs(new[] { song });
        if (_currentIndex + 1 == _queue.Count - 1)
            PreloadNext();
    }

    /// <summary>追加到播放队列末尾。</summary>
    public void AddToQueue(Song song)
    {
        if (_currentIndex < 0 || _queue.Count == 0)
        {
            PlayQueue(new[] { song }, 0);
            return;
        }

        _queue.Add(song);
        CacheSongs(new[] { song });
        if (_queue.Count == 2)
            PreloadNext();
    }

    [RelayCommand]
    private void PlayPause()
    {
        if (CurrentSong is null)
        {
            if (_queue.Count > 0)
            {
                _currentIndex = 0;
                PlayCurrent(_queue[0]);
                PreloadNext();
            }
            return;
        }

        // 恢复场景：CurrentSong 已设置但尚未创建流 → 从记录位置开始播放
        if (!_engine.HasStream)
        {
            if (PlayCurrent(CurrentSong))
            {
                if (_resumePositionSeconds > 0)
                {
                    Seek(_resumePositionSeconds);
                    _resumePositionSeconds = 0;
                }
                PreloadNext();
            }
            return;
        }

        if (_engine.IsPlaying)
        {
            _engine.Pause();
            IsPlaying = false;
        }
        else
        {
            _engine.Play();
            IsPlaying = true;
        }
    }

    [RelayCommand]
    private void Next()
    {
        if (_queue.Count == 0)
            return;

        var nextIndex = PlayMode switch
        {
            PlayMode.Shuffle => Random.Shared.Next(_queue.Count),
            PlayMode.RepeatOne => _currentIndex,
            _ => (_currentIndex + 1) % _queue.Count,
        };
        AdvanceTo(nextIndex, false);
    }

    [RelayCommand]
    private void Previous()
    {
        if (_queue.Count == 0)
            return;

        var prevIndex = PlayMode switch
        {
            PlayMode.Shuffle => Random.Shared.Next(_queue.Count),
            PlayMode.RepeatOne => _currentIndex,
            _ => (_currentIndex - 1 + _queue.Count) % _queue.Count,
        };
        AdvanceTo(prevIndex, false);
    }

    /// <summary>切换播放模式：顺序 → 随机 → 循环 → 单曲循环。</summary>
    [RelayCommand]
    private void TogglePlayMode()
    {
        PlayMode = PlayMode switch
        {
            PlayMode.Sequence => PlayMode.Shuffle,
            PlayMode.Shuffle => PlayMode.Repeat,
            PlayMode.Repeat => PlayMode.RepeatOne,
            PlayMode.RepeatOne => PlayMode.Sequence,
            _ => PlayMode.Sequence,
        };
    }

    /// <summary>当前播放模式的图标。</summary>
    public string PlayModeIcon => PlayMode switch
    {
        PlayMode.Shuffle => "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z",
        PlayMode.Repeat => "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z",
        PlayMode.RepeatOne => "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z",
        _ => "M6 18l8.5-6L6 6v12z",
    };

    partial void OnPlayModeChanged(PlayMode value)
    {
        OnPropertyChanged(nameof(PlayModeIcon));
        OnPropertyChanged(nameof(PlayModeName));
    }

    /// <summary>当前播放模式的名称。</summary>
    public string PlayModeName => PlayMode switch
    {
        PlayMode.Shuffle => "随机播放",
        PlayMode.Repeat => "列表循环",
        PlayMode.RepeatOne => "单曲循环",
        _ => "顺序播放",
    };

    /// <summary>当前播放队列。</summary>
    public IReadOnlyList<Song> Queue => _queue;

    /// <summary>当前歌曲标题（清理 Gonic 不规范的「- 艺术家」后缀）。</summary>
    public string CurrentTitle => CurrentSong is null ? "未在播放" : CleanTitle(CurrentSong);

    partial void OnCurrentSongChanged(Song? value) => OnPropertyChanged(nameof(CurrentTitle));

    private static string CleanTitle(Song song)
    {
        var t = song.Title ?? "";
        var a = song.Artist ?? "";
        if (a.Length == 0)
            return t;

        var suffix = " - " + a;
        if (t.EndsWith(suffix, StringComparison.Ordinal))
            return t[..^suffix.Length].Trim();

        var prefix = a + " - ";
        if (t.StartsWith(prefix, StringComparison.Ordinal))
            return t[prefix.Length..].Trim();

        return t;
    }

    public void Seek(double seconds)
    {
        _engine.PositionSeconds = seconds;
        PositionSeconds = seconds;
    }

    /// <summary>停止播放并清空队列（切换服务器等场景，避免旧服务的流残留）。</summary>
    public void StopAndClear()
    {
        FreePreload();
        _engine.Stop();
        _queue.Clear();
        _currentIndex = -1;
        CurrentSong = null;
        CurrentCover = null;
        PositionSeconds = 0;
        DurationSeconds = 0;
        IsPlaying = false;
        _resumePositionSeconds = 0;
        AppServices.Smtc.UpdatePlaybackStatus(false);
        ClearLocalState();
    }

    /// <summary>应用退出时释放音频引擎与定时器（避免 BASS 线程残留导致进程不退）。</summary>
    public void Shutdown()
    {
        _progressTimer.Stop();
        _spectrumTimer.Stop();
        _sleepTimer?.Stop();
        FreePreload();
        _engine.Stop();
        _engine.Free();
    }

    /// <summary>保存当前队列到云端（OpenSubsonic，失败静默）。</summary>
    public async Task SaveQueueToCloudAsync()
    {
        var music = AppServices.Music;
        if (music is null || _queue.Count == 0)
            return;

        try
        {
            var ids = _queue.Select(s => s.Id).ToList();
            var current = _currentIndex >= 0 ? _queue[_currentIndex].Id : null;
            var pos = (long)(PositionSeconds * 1000);
            await music.SavePlayQueueAsync(ids, current, pos);
        }
        catch
        {
            // 服务端不支持时忽略
        }
    }

    /// <summary>从云端恢复播放队列（不支持/无队列返回 false）。</summary>
    public async Task<bool> RestoreQueueFromCloudAsync()
    {
        var music = AppServices.Music;
        if (music is null)
            return false;

        try
        {
            var songs = await music.GetPlayQueueAsync();
            if (songs is null || songs.Count == 0)
                return false;

            PlayQueue(songs, 0);
            return true;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>把当前歌曲与位置保存为书签。</summary>
    public async Task<bool> BookmarkCurrentAsync()
    {
        var music = AppServices.Music;
        if (music is null || CurrentSong is null)
            return false;

        try
        {
            var pos = (long)(PositionSeconds * 1000);
            return await music.CreateBookmarkAsync(CurrentSong.Id, pos);
        }
        catch
        {
            return false;
        }
    }

    /// <summary>从书签续播（播放其歌曲并从记录位置继续）。</summary>
    public void PlayBookmark(Bookmark bookmark)
    {
        if (bookmark.Songs.Count == 0)
            return;

        PlayQueue(bookmark.Songs, 0);
        var seconds = bookmark.Position / 1000.0;
        if (seconds > 0)
            Seek(seconds);
    }

    /// <summary>睡眠定时器（分钟，0 表示关闭）。</summary>
    [RelayCommand]
    private void SetSleepTimer(string? minutesText)
    {
        if (!int.TryParse(minutesText, out var minutes))
            return;

        _sleepTimer?.Stop();
        _sleepTimer = null;

        if (minutes <= 0)
            return;

        var endTime = DateTime.Now.AddMinutes(minutes);
        _sleepTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _sleepTimer.Tick += (_, _) =>
        {
            if (DateTime.Now < endTime)
                return;

            _sleepTimer!.Stop();
            _sleepTimer = null;
            if (_engine.IsPlaying)
            {
                _engine.Pause();
                IsPlaying = false;
            }
        };
        _sleepTimer.Start();
    }

    private bool PlayCurrent(Song song)
    {
        var music = AppServices.Music;
        if (music is null)
            return false;

        var channel = _engine.CreateStream(music.GetStreamUrl(song.Id));
        if (channel == 0)
            return false;

        _engine.PlayChannel(channel);
        ApplyState(song);
        return true;
    }

    /// <summary>切换到下一曲（有预加载则 Gapless/Crossfade，否则直接播放）。</summary>
    private void AdvanceTo(int nextIndex, bool crossfade)
    {
        var song = _queue[nextIndex];
        _currentIndex = nextIndex;

        if (_preloadChannel != 0)
        {
            if (crossfade && CrossfadeSeconds > 0)
                _engine.CrossfadeTo(_preloadChannel, CrossfadeSeconds);
            else
                _engine.SwitchTo(_preloadChannel);
            _preloadChannel = 0;
        }
        else
        {
            // 创建流失败时标记停止，避免 IsStopped 兜底反复触发导致无限换歌
            if (!PlayCurrent(song))
            {
                IsPlaying = false;
                return;
            }
            PreloadNext();
            return;
        }

        ApplyState(song);
        PreloadNext();
    }

    private void ApplyState(Song song)
    {
        CurrentSong = song;
        DurationSeconds = song.Duration;
        PositionSeconds = 0;
        IsPlaying = true;
        _submittedSongId = null;
        _ = LoadCoverAsync(song);
        _ = ScrobbleAsync(song.Id, false);
        RecordLocalHistory(song);
        _ = SaveQueueToCloudAsync();
        AppServices.Smtc.UpdateTrack(CleanTitle(song), song.Artist);
        SaveState();
    }

    /// <summary>本地历史：后台缓存元数据 + 写入播放记录（不阻塞播放）。</summary>
    private void RecordLocalHistory(Song song)
    {
        _ = Task.Run(() =>
        {
            try
            {
                AppServices.Library.UpsertSong(song);
                AppServices.Library.RecordPlay(song.Id);
            }
            catch
            {
                // 本地历史失败不影响播放
            }
        });
    }

    private async Task ScrobbleAsync(string songId, bool submission)
    {
        var music = AppServices.Music;
        if (music is null)
            return;
        try
        {
            await music.ScrobbleAsync(songId, submission);
        }
        catch
        {
            // 回传失败不影响播放
        }
    }

    private void PreloadNext()
    {
        FreePreload();
        if (_queue.Count <= 1)
            return;

        var nextIndex = (_currentIndex + 1) % _queue.Count;
        var music = AppServices.Music;
        if (music is null)
            return;

        _preloadChannel = _engine.CreateStream(music.GetStreamUrl(_queue[nextIndex].Id));
    }

    private void FreePreload()
    {
        if (_preloadChannel != 0)
        {
            BassNative.BASS_StreamFree(_preloadChannel);
            _preloadChannel = 0;
        }
    }

    private async Task LoadCoverAsync(Song song)
    {
        var music = AppServices.Music;
        if (music is null)
            return;
        CurrentCover = await ImageLoader.LoadAsync(music.GetCoverArtUrl(song.CoverArtId, 200));
    }

    private void UpdateProgress()
    {
        if (CurrentSong is null || !_engine.HasStream)
            return;

        // mixer 意外停止（IsPlaying 但已 Stopped）→ 兜底切下一首，避免卡死
        if (IsPlaying && _engine.IsStopped)
        {
            AdvanceTo((_currentIndex + 1) % _queue.Count, false);
            return;
        }

        var pos = _engine.PositionSeconds;
        var dur = CurrentSong.Duration;
        PositionSeconds = pos;

        // 定期保存播放位置到本地（每 5 秒）
        if ((DateTime.UtcNow - _lastStateSave).TotalSeconds >= 5)
        {
            _lastStateSave = DateTime.UtcNow;
            SaveState();
        }

        // 播放过半时回传 scrobble submission（只提交一次）
        if (dur > 0 && pos >= dur / 2.0 && _submittedSongId != CurrentSong.Id)
        {
            _submittedSongId = CurrentSong.Id;
            _ = ScrobbleAsync(CurrentSong.Id, true);
        }

        if (dur > 0 && pos >= dur - 0.3)
            AdvanceTo((_currentIndex + 1) % _queue.Count, true);
    }

    /// <summary>后台批量缓存歌曲元数据（供本地恢复队列使用，单任务避免并发写）。</summary>
    private void CacheSongs(IReadOnlyList<Song> songs)
    {
        if (songs.Count == 0)
            return;

        _ = Task.Run(() =>
        {
            try
            {
                AppServices.Library.BatchUpsertSongs(songs);
            }
            catch
            {
                // 缓存失败不影响播放
            }
        });
    }

    /// <summary>保存播放状态（队列 + 当前索引 + 位置）到本地 SQLite。</summary>
    private void SaveState()
    {
        if (_queue.Count == 0)
            return;

        var ids = _queue.Select(s => s.Id).ToList();
        var index = _currentIndex;
        var position = PositionSeconds;

        _ = Task.Run(() =>
        {
            try
            {
                AppServices.Library.SavePlaybackState(ids, index, position);
            }
            catch
            {
                // 保存失败不影响播放
            }
        });
    }

    private void ClearLocalState()
    {
        _ = Task.Run(() =>
        {
            try
            {
                AppServices.Library.ClearPlaybackState();
            }
            catch
            {
                // 清空失败忽略
            }
        });
    }

    /// <summary>从本地 SQLite 恢复上次播放状态（队列 + 当前歌曲 + 位置，不自动播放）。</summary>
    public void RestoreLastSession()
    {
        try
        {
            var state = AppServices.Library.LoadPlaybackState();
            if (state is null || state.Value.SongIds.Count == 0)
                return;

            var songs = new List<Song>();
            foreach (var id in state.Value.SongIds)
            {
                var song = AppServices.Library.GetSong(id);
                if (song is not null)
                    songs.Add(song);
            }

            if (songs.Count == 0)
                return;

            _queue.Clear();
            _queue.AddRange(songs);
            _currentIndex = Math.Clamp(state.Value.CurrentIndex, 0, songs.Count - 1);

            var current = _queue[_currentIndex];
            CurrentSong = current;
            DurationSeconds = current.Duration;
            PositionSeconds = state.Value.PositionSeconds;
            _resumePositionSeconds = state.Value.PositionSeconds;
            IsPlaying = false;

            _ = LoadCoverAsync(current);
            AppServices.Smtc.UpdateTrack(CleanTitle(current), current.Artist);
        }
        catch
        {
            // 恢复失败忽略
        }
    }
}
