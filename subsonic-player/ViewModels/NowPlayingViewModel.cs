using System;
using System.ComponentModel;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using SubsonicPlayer.Models;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class NowPlayingViewModel : ViewModelBase, IDisposable
{
    public PlaybackService Playback => AppServices.Playback;

    [ObservableProperty]
    private Lyrics? _lyrics;

    /// <summary>非同步歌词纯文本。</summary>
    [ObservableProperty]
    private string _lyricsText = "";

    /// <summary>同步歌词当前行索引（-1 表示未定位）。</summary>
    [ObservableProperty]
    private int _currentLineIndex = -1;

    [ObservableProperty]
    private string _lyricsStatus = "";

    [ObservableProperty]
    private bool _hasLyricsStatus;

    partial void OnLyricsStatusChanged(string value) => HasLyricsStatus = !string.IsNullOrEmpty(value);

    public NowPlayingViewModel()
    {
        Playback.PropertyChanged += OnPlaybackChanged;
        _ = LoadLyricsAsync(Playback.CurrentSong);
    }

    /// <summary>取消订阅，避免页面反复进出导致事件处理器累积。</summary>
    public void Dispose()
    {
        Playback.PropertyChanged -= OnPlaybackChanged;
    }

    private void OnPlaybackChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PlaybackService.CurrentSong))
            _ = LoadLyricsAsync(Playback.CurrentSong);
        else if (e.PropertyName == nameof(PlaybackService.PositionSeconds))
            UpdateCurrentLine(Playback.PositionSeconds);
    }

    private async Task LoadLyricsAsync(Song? song)
    {
        Lyrics = null;
        LyricsText = "";
        CurrentLineIndex = -1;
        LyricsStatus = "";

        if (song is null)
            return;

        var music = AppServices.Music;
        if (music is null)
            return;

        Lyrics? lyrics = null;
        try
        {
            lyrics = await music.GetLyricsAsync(song.Artist, song.Title, song.Id);
        }
        catch
        {
            // 服务端歌词接口失败（如 Gonic 不支持 getLyrics 端点），视为无歌词，走网络兜底
            lyrics = null;
        }

        // 服务端无歌词 → 网络兜底搜索
        if (lyrics is null)
        {
            LyricsStatus = "正在搜索歌词...";
            try
            {
                lyrics = await LyricsSearchService.SearchAsync(song.Artist, song.Title, song.Duration);
            }
            catch
            {
                lyrics = null;
            }
        }

        // 已切歌则丢弃结果，避免旧歌词覆盖新歌
        if (Playback.CurrentSong?.Id != song.Id)
            return;

        Lyrics = lyrics;
        if (lyrics is null)
        {
            LyricsStatus = "未找到歌词";
        }
        else if (lyrics.IsSynced)
        {
            UpdateCurrentLine(Playback.PositionSeconds);
        }
        else
        {
            LyricsText = lyrics.Text;
        }
    }

    private void UpdateCurrentLine(double position)
    {
        if (Lyrics is not { IsSynced: true })
            return;

        var idx = -1;
        for (var i = 0; i < Lyrics.Lines.Count; i++)
        {
            if (Lyrics.Lines[i].StartSeconds <= position)
                idx = i;
            else
                break;
        }
        CurrentLineIndex = idx;
    }
}
