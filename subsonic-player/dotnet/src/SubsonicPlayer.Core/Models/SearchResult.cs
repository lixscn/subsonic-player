using System.Collections.Generic;

namespace SubsonicPlayer.Models;

public class SearchResult
{
    public List<Artist> Artists { get; set; } = new();
    public List<Album> Albums { get; set; } = new();
    public List<Song> Songs { get; set; } = new();
}
