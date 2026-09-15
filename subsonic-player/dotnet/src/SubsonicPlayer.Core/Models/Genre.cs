namespace SubsonicPlayer.Models;

/// <summary>
/// 音乐流派/风格（OpenSubsonic getGenres）。服务端不支持时列表为空。
/// </summary>
public class Genre
{
    public string Name { get; set; } = "";
    public int SongCount { get; set; }
    public int AlbumCount { get; set; }
}
