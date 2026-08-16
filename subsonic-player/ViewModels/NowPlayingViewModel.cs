using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public class NowPlayingViewModel : ViewModelBase
{
    public PlaybackService Playback => AppServices.Playback;
}
