using System;
using System.Collections.Generic;

namespace SubsonicPlayer.Models;

/// <summary>播放书签（记住播放位置）。</summary>
public class Bookmark
{
    /// <summary>播放位置（毫秒）。</summary>
    public long Position { get; set; }
    public string Username { get; set; } = "";
    public string Comment { get; set; } = "";
    public DateTime? Created { get; set; }
    public DateTime? Changed { get; set; }
    public List<Song> Songs { get; set; } = new();
}
