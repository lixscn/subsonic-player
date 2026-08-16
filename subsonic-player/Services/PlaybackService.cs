using System;
using System.Collections.Generic;
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
        _progressTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
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

    private void PlayCurrent(Song song)
    {
        var music = AppServices.Music;
        if (music is null)
            return;

        var channel = _engine.CreateStream(music.GetStreamUrl(song.Id));
        if (channel == 0)
            return;

        _engine.PlayChannel(channel);
        ApplyState(song);
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
            PlayCurrent(song);
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
        _ = LoadCoverAsync(song);
        _ = ScrobbleAsync(song.Id, false);
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

        var pos = _engine.PositionSeconds;
        var dur = CurrentSong.Duration;
        PositionSeconds = pos;

        if (dur > 0 && pos >= dur - 0.3)
            AdvanceTo((_currentIndex + 1) % _queue.Count, true);
    }
}
