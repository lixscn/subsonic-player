using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public class MiniPlayerViewModel : ViewModelBase
{
    public PlaybackService Playback => AppServices.Playback;
}
