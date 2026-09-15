using System.Collections.Generic;

namespace SubsonicPlayer.Models;

public class Album
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Title { get; set; } = "";
    public string Artist { get; set; } = "";
    /// <summary>艺术家的 id（Subsonic 的 getAlbum / getArtist / getAlbumList2 响应里带 artistId）。
    /// 有它，专辑页与专辑卡上的歌手名才能点进该艺术家的单曲列表；别的服务不填就是空串，
    /// 前端会退回纯文本，而不是给一个点了没反应的死链接。</summary>
    public string ArtistId { get; set; } = "";
    public string CoverArtId { get; set; } = "";
    public int SongCount { get; set; }
    public int Duration { get; set; }
    public int Year { get; set; }
    public string Genre { get; set; } = "";
    public List<Song> Songs { get; set; } = new();
}
