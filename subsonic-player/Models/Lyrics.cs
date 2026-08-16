using System.Collections.Generic;

namespace SubsonicPlayer.Models;

/// <summary>歌词（支持纯文本与带时间戳的结构化歌词）。</summary>
public class Lyrics
{
    /// <summary>非结构化纯文本（无时间戳时使用）。</summary>
    public string Text { get; set; } = "";

    /// <summary>结构化歌词行（带起始时间戳）。</summary>
    public List<LyricsLine> Lines { get; set; } = new();

    public string DisplayArtist { get; set; } = "";
    public string DisplayTitle { get; set; } = "";

    public bool IsSynced => Lines.Count > 0;
}

public class LyricsLine
{
    /// <summary>行起始时间（秒）。</summary>
    public double StartSeconds { get; set; }
    public string Text { get; set; } = "";
}
