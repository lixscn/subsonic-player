using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;
using SubsonicPlayer.ViewModels;

namespace SubsonicPlayer.Services;

/// <summary>鎾斁妯″紡銆?/summary>
public enum PlayMode
{
    Sequence,
    Shuffle,
    Repeat,
    RepeatOne,
}

/// <summary>鎾斁鏈嶅姟锛氶槦鍒椼€丟apless銆丆rossfade銆丒Q銆乼empo銆丏SP銆侀璋便€?/summary>
public partial class PlaybackService : ObservableObject
{
    public const int EqBandCount = 10;

    private readonly AudioEngine _engine = new();
    private readonly List<Song> _queue = new();

    /// <summary>褰撳墠鎾斁绱㈠紩锛堟挱鏀鹃槦鍒楅€変腑楂樹寒鐢級銆?/summary>
    [ObservableProperty]
    private int _currentIndex = -1;

    /// <summary>鎾斁闃熷垪鐨勫睍绀洪」锛堝皝闈?绾㈠績/鍔犲彿锛屼緵闃熷垪寮圭獥涓庢鍦ㄦ挱鏀鹃〉浣跨敤锛夈€?/summary>
    public ObservableCollection<SongItemViewModel> QueueItems { get; } = new();

    private int _preloadChannel;
    private readonly UiTimer _progressTimer;
    private readonly UiTimer _spectrumTimer;
    private UiTimer? _sleepTimer;
    private string? _submittedSongId;
    private double _resumePositionSeconds;
    private DateTime _lastStateSave = DateTime.MinValue;

    [ObservableProperty]
    private Song? _currentSong;

    [ObservableProperty]
    private byte[]? _currentCover;

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

    /// <summary>浜ゅ弶娣″叆娣″嚭鏃堕暱锛堢锛夛紝0 琛ㄧず鍏抽棴锛堢函 Gapless锛夈€?/summary>
    public double CrossfadeSeconds { get; set; } = 3.0;

    public PlaybackService()
    {
        _progressTimer = new UiTimer(UpdateProgress);
        _progressTimer.Start(200);

        _spectrumTimer = new UiTimer(() => Spectrum = _engine.GetSpectrum(64));
        _spectrumTimer.Start(50);
    }

    partial void OnVolumeChanged(double value) => _engine.Volume = (float)value;
    partial void OnTempoChanged(double value) => _engine.SetTempo((float)value);
    partial void OnPitchChanged(double value) => _engine.SetPitch((float)value);
    partial void OnReverbEnabledChanged(bool value) => _engine.SetReverb(value);
    partial void OnEchoEnabledChanged(bool value) => _engine.SetEcho(value);
    partial void OnChorusEnabledChanged(bool value) => _engine.SetChorus(value);
    partial void OnCompressorEnabledChanged(bool value) => _engine.SetCompressor(value);
    partial void OnIsPlayingChanged(bool value) => AppServices.MediaIntegration.UpdatePlaybackStatus(value);

    partial void OnCurrentIndexChanged(int value) => UpdateQueueSelection();

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

    // ---- 闃熷垪 / 鎾斁 ----

    public void PlayQueue(IEnumerable<Song> songs, int startIndex = 0)
    {
        _queue.Clear();
        _queue.AddRange(songs);
        if (_queue.Count == 0)
        {
            RebuildQueueItems();
            return;
        }

        CacheSongs(_queue.ToList());

        CurrentIndex = Math.Clamp(startIndex, 0, _queue.Count - 1);
        RebuildQueueItems();
        PlayCurrent(_queue[CurrentIndex]);
        PreloadNext();
    }

    /// <summary>浠庨槦鍒楁寚瀹氱储寮曞紑濮嬫挱鏀撅紙闃熷垪椤圭偣鍑昏Е鍙戯紝鐩存帴鎾斁璇ラ」涓嶈蛋棰勫姞杞斤級銆?/summary>
    public void PlayFromIndex(int index)
    {
        if (index < 0 || index >= _queue.Count)
            return;

        CurrentIndex = index;
        PlayCurrent(_queue[index]);
        PreloadNext();
    }

    /// <summary>绉诲姩闃熷垪椤癸紙鎷栨嫿鎺掑簭锛夈€?/summary>
    public void MoveQueueItem(int fromIndex, int toIndex)
    {
        if (fromIndex < 0 || fromIndex >= _queue.Count || toIndex < 0 || toIndex >= _queue.Count || fromIndex == toIndex)
            return;

        var song = _queue[fromIndex];
        _queue.RemoveAt(fromIndex);
        _queue.Insert(toIndex, song);

        if (CurrentIndex == fromIndex)
        {
            CurrentIndex = toIndex;
        }
        else if (fromIndex < CurrentIndex && toIndex >= CurrentIndex)
        {
            CurrentIndex--;
        }
        else if (fromIndex > CurrentIndex && toIndex <= CurrentIndex)
        {
            CurrentIndex++;
        }

        RebuildQueueItems();
    }

    /// <summary>鎸夋瓕鏇?id 鏌ユ壘闃熷垪绱㈠紩锛堟嫋鎷芥帓搴忕敤锛屾湭鎵惧埌杩斿洖 -1锛夈€?/summary>
    public int QueueIndexOf(string songId)
        => _queue.FindIndex(s => s.Id == songId);

    /// <summary>閲嶅缓闃熷垪灞曠ず椤癸紙闃熷垪鍐呭鍙樺寲鍚庤皟鐢級銆?/summary>
    private void RebuildQueueItems()
    {
        QueueItems.Clear();
        for (var i = 0; i < _queue.Count; i++)
        {
            var item = new SongItemViewModel(_queue[i])
            {
                Index = i + 1,
                IsCurrent = i == CurrentIndex,
                PlayFromQueue = PlayFromIndex,
            };
            QueueItems.Add(item);
            var music = AppServices.Music;
            if (music is not null)
                item.LoadCover(music);
        }
    }

    /// <summary>鍚屾闃熷垪閫変腑楂樹寒锛堝綋鍓嶆挱鏀鹃」锛夈€?/summary>
    private void UpdateQueueSelection()
    {
        for (var i = 0; i < QueueItems.Count; i++)
            QueueItems[i].IsCurrent = i == CurrentIndex;
    }

    /// <summary>鎻掑叆鍒板綋鍓嶆洸鐩悗闈紙涓嬩竴棣栨挱鏀撅級銆?/summary>
    public void PlayNext(Song song)
    {
        if (CurrentIndex < 0 || _queue.Count == 0)
        {
            PlayQueue(new[] { song }, 0);
            return;
        }

        _queue.Insert(CurrentIndex + 1, song);
        CacheSongs(new[] { song });
        RebuildQueueItems();
        if (CurrentIndex + 1 == _queue.Count - 1)
            PreloadNext();
    }

    /// <summary>杩藉姞鍒版挱鏀鹃槦鍒楁湯灏俱€?/summary>
    public void AddToQueue(Song song)
    {
        if (CurrentIndex < 0 || _queue.Count == 0)
        {
            PlayQueue(new[] { song }, 0);
            return;
        }

        _queue.Add(song);
        CacheSongs(new[] { song });
        RebuildQueueItems();
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
                CurrentIndex = 0;
                PlayCurrent(_queue[0]);
                PreloadNext();
            }
            return;
        }

        // 鎭㈠鍦烘櫙锛欳urrentSong 宸茶缃絾灏氭湭鍒涘缓娴?鈫?浠庤褰曚綅缃紑濮嬫挱鏀?
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
            PlayMode.RepeatOne => CurrentIndex,
            _ => (CurrentIndex + 1) % _queue.Count,
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
            PlayMode.RepeatOne => CurrentIndex,
            _ => (CurrentIndex - 1 + _queue.Count) % _queue.Count,
        };
        AdvanceTo(prevIndex, false);
    }

    /// <summary>鍒囨崲鎾斁妯″紡锛氶『搴?鈫?闅忔満 鈫?寰幆 鈫?鍗曟洸寰幆銆?/summary>
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

    /// <summary>褰撳墠鎾斁妯″紡鐨勫浘鏍囥€?/summary>
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

    /// <summary>褰撳墠鎾斁妯″紡鐨勫悕绉般€?/summary>
    public string PlayModeName => PlayMode switch
    {
        PlayMode.Shuffle => "闅忔満鎾斁",
        PlayMode.Repeat => "鍒楄〃寰幆",
        PlayMode.RepeatOne => "鍗曟洸寰幆",
        _ => "椤哄簭鎾斁",
    };

    /// <summary>褰撳墠鎾斁闃熷垪銆?/summary>
    public IReadOnlyList<Song> Queue => _queue;

    /// <summary>褰撳墠姝屾洸鏍囬锛堟竻鐞?Gonic 涓嶈鑼冪殑銆? 鑹烘湳瀹躲€嶅悗缂€锛夈€?/summary>
    public string CurrentTitle => CurrentSong is null ? "鏈湪鎾斁" : CleanTitle(CurrentSong);

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

    /// <summary>鍋滄鎾斁骞舵竻绌洪槦鍒楋紙鍒囨崲鏈嶅姟鍣ㄧ瓑鍦烘櫙锛岄伩鍏嶆棫鏈嶅姟鐨勬祦娈嬬暀锛夈€?/summary>
    public void StopAndClear()
    {
        FreePreload();
        _engine.Stop();
        _queue.Clear();
        CurrentIndex = -1;
        CurrentSong = null;
        CurrentCover = null;
        PositionSeconds = 0;
        DurationSeconds = 0;
        IsPlaying = false;
        _resumePositionSeconds = 0;
        AppServices.MediaIntegration.UpdatePlaybackStatus(false);
        RebuildQueueItems();
        ClearLocalState();
    }

    /// <summary>搴旂敤閫€鍑烘椂閲婃斁闊抽寮曟搸涓庡畾鏃跺櫒锛堥伩鍏?BASS 绾跨▼娈嬬暀瀵艰嚧杩涚▼涓嶉€€锛夈€?/summary>
    public void Shutdown()
    {
        _progressTimer.Stop();
        _spectrumTimer.Stop();
        _sleepTimer?.Stop();
        FreePreload();
        _engine.Stop();
        _engine.Free();
    }

    /// <summary>淇濆瓨褰撳墠闃熷垪鍒颁簯绔紙OpenSubsonic锛屽け璐ラ潤榛橈級銆?/summary>
    public async Task SaveQueueToCloudAsync()
    {
        var music = AppServices.Music;
        if (music is null || _queue.Count == 0)
            return;

        try
        {
            var ids = _queue.Select(s => s.Id).ToList();
            var current = CurrentIndex >= 0 ? _queue[CurrentIndex].Id : null;
            var pos = (long)(PositionSeconds * 1000);
            await music.SavePlayQueueAsync(ids, current, pos);
        }
        catch
        {
            // 鏈嶅姟绔笉鏀寔鏃跺拷鐣?
        }
    }

    /// <summary>浠庝簯绔仮澶嶆挱鏀鹃槦鍒楋紙涓嶆敮鎸?鏃犻槦鍒楄繑鍥?false锛夈€?/summary>
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

    /// <summary>鎶婂綋鍓嶆瓕鏇蹭笌浣嶇疆淇濆瓨涓轰功绛俱€?/summary>
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

    /// <summary>浠庝功绛剧画鎾紙鎾斁鍏舵瓕鏇插苟浠庤褰曚綅缃户缁級銆?/summary>
    public void PlayBookmark(Bookmark bookmark)
    {
        if (bookmark.Songs.Count == 0)
            return;

        PlayQueue(bookmark.Songs, 0);
        var seconds = bookmark.Position / 1000.0;
        if (seconds > 0)
            Seek(seconds);
    }

    /// <summary>鐫＄湢瀹氭椂鍣紙鍒嗛挓锛? 琛ㄧず鍏抽棴锛夈€?/summary>
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
        _sleepTimer = new UiTimer(() =>
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
        });
        _sleepTimer.Start(1000);
    }

    private bool PlayCurrent(Song song)
    {
        var music = AppServices.Music;
        if (music is null)
            return false;

        var url = music.GetStreamUrl(song.Id);
        // 网络/建流放到后台线程，避免阻塞 UI/调用线程（首播、慢网下极易卡 UI）；
        // 完成后经 UI 调度器回到主线程执行播放与状态更新，保证可观察属性的 UI 线程语义。
        _ = Task.Run(() =>
        {
            var channel = _engine.CreateStream(url);
            AppServices.UiDispatcher.Post(() =>
            {
                if (channel == 0)
                {
                    IsPlaying = false;
                    return;
                }

                _engine.PlayChannel(channel);
                ApplyState(song);
            });
        });
        return true;
    }

    /// <summary>鍒囨崲鍒颁笅涓€鏇诧紙鏈夐鍔犺浇鍒?Gapless/Crossfade锛屽惁鍒欑洿鎺ユ挱鏀撅級銆?/summary>
    private void AdvanceTo(int nextIndex, bool crossfade)
    {
        var song = _queue[nextIndex];
        CurrentIndex = nextIndex;

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
            // 鍒涘缓娴佸け璐ユ椂鏍囪鍋滄锛岄伩鍏?IsStopped 鍏滃簳鍙嶅瑙﹀彂瀵艰嚧鏃犻檺鎹㈡瓕
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
        AppServices.MediaIntegration.UpdateTrack(CleanTitle(song), song.Artist);
        SaveState();
    }

    /// <summary>鏈湴鍘嗗彶锛氬悗鍙扮紦瀛樺厓鏁版嵁 + 鍐欏叆鎾斁璁板綍锛堜笉闃诲鎾斁锛夈€?/summary>
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
                // 鏈湴鍘嗗彶澶辫触涓嶅奖鍝嶆挱鏀?
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
            // 鍥炰紶澶辫触涓嶅奖鍝嶆挱鏀?
        }
    }

    private void PreloadNext()
    {
        FreePreload();
        if (_queue.Count <= 1)
            return;

        var nextIndex = (CurrentIndex + 1) % _queue.Count;
        var music = AppServices.Music;
        if (music is null)
            return;

        // 网络/建流放后台，避免阻塞 UI；完成后回填预载句柄（AdvanceTo 读取，若未就绪则退化为即时建流）
        var url = music.GetStreamUrl(_queue[nextIndex].Id);
        _ = Task.Run(() =>
        {
            var ch = _engine.CreateStream(url);
            if (ch != 0)
                _preloadChannel = ch;
        });
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
        AppServices.MediaIntegration.UpdateCover(CurrentCover);
    }

    private void UpdateProgress()
    {
        if (CurrentSong is null || !_engine.HasStream)
            return;

        // mixer 鎰忓鍋滄锛圛sPlaying 浣嗗凡 Stopped锛夆啋 鍏滃簳鍒囦笅涓€棣栵紝閬垮厤鍗℃
        if (IsPlaying && _engine.IsStopped)
        {
            AdvanceTo((CurrentIndex + 1) % _queue.Count, false);
            return;
        }

        var pos = _engine.PositionSeconds;
        var dur = CurrentSong.Duration;
        PositionSeconds = pos;

        // 瀹氭湡淇濆瓨鎾斁浣嶇疆鍒版湰鍦帮紙姣?5 绉掞級
        if ((DateTime.UtcNow - _lastStateSave).TotalSeconds >= 5)
        {
            _lastStateSave = DateTime.UtcNow;
            SaveState();
        }

        // 鎾斁杩囧崐鏃跺洖浼?scrobble submission锛堝彧鎻愪氦涓€娆★級
        if (dur > 0 && pos >= dur / 2.0 && _submittedSongId != CurrentSong.Id)
        {
            _submittedSongId = CurrentSong.Id;
            _ = ScrobbleAsync(CurrentSong.Id, true);
        }

        if (dur > 0 && pos >= dur - 0.3)
            AdvanceTo((CurrentIndex + 1) % _queue.Count, true);
    }

    /// <summary>鍚庡彴鎵归噺缂撳瓨姝屾洸鍏冩暟鎹紙渚涙湰鍦版仮澶嶉槦鍒椾娇鐢紝鍗曚换鍔￠伩鍏嶅苟鍙戝啓锛夈€?/summary>
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
                // 缂撳瓨澶辫触涓嶅奖鍝嶆挱鏀?
            }
        });
    }

    /// <summary>淇濆瓨鎾斁鐘舵€侊紙闃熷垪 + 褰撳墠绱㈠紩 + 浣嶇疆锛夊埌鏈湴 SQLite銆?/summary>
    private void SaveState()
    {
        if (_queue.Count == 0)
            return;

        var ids = _queue.Select(s => s.Id).ToList();
        var index = CurrentIndex;
        var position = PositionSeconds;

        _ = Task.Run(() =>
        {
            try
            {
                AppServices.Library.SavePlaybackState(ids, index, position);
            }
            catch
            {
                // 淇濆瓨澶辫触涓嶅奖鍝嶆挱鏀?
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
                // 娓呯┖澶辫触蹇界暐
            }
        });
    }

    /// <summary>浠庢湰鍦?SQLite 鎭㈠涓婃鎾斁鐘舵€侊紙闃熷垪 + 褰撳墠姝屾洸 + 浣嶇疆锛屼笉鑷姩鎾斁锛夈€?/summary>
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
            CurrentIndex = Math.Clamp(state.Value.CurrentIndex, 0, songs.Count - 1);
            RebuildQueueItems();

            var current = _queue[CurrentIndex];
            CurrentSong = current;
            DurationSeconds = current.Duration;
            PositionSeconds = state.Value.PositionSeconds;
            _resumePositionSeconds = state.Value.PositionSeconds;
            IsPlaying = false;

            _ = LoadCoverAsync(current);
            AppServices.MediaIntegration.UpdateTrack(CleanTitle(current), current.Artist);
        }
        catch
        {
            // 鎭㈠澶辫触蹇界暐
        }
    }
}
