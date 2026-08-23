using System.Collections.Generic;

namespace SubsonicPlayer.Models;

/// <summary>分享链接。</summary>
public class Share
{
    public string Id { get; set; } = "";
    public string Url { get; set; } = "";
    public string Description { get; set; } = "";
    public string Username { get; set; } = "";
    public int VisitCount { get; set; }
    public List<Song> Songs { get; set; } = new();
}
