using System.Collections.Generic;

namespace SubsonicPlayer.Models;

public class Album
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Title { get; set; } = "";
    public string Artist { get; set; } = "";
    public string CoverArtId { get; set; } = "";
    public int SongCount { get; set; }
    public int Duration { get; set; }
    public int Year { get; set; }
    public string Genre { get; set; } = "";
    public List<Song> Songs { get; set; } = new();
}
