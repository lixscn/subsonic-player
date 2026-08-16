using System.Collections.Generic;

namespace SubsonicPlayer.Models;

public class Playlist
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Owner { get; set; } = "";
    public string CoverArtId { get; set; } = "";
    public int SongCount { get; set; }
    public int Duration { get; set; }
    public List<Song> Songs { get; set; } = new();
}
