using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Input;
using Avalonia.Interactivity;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.Views;

public partial class MiniPlayerView : Window
{
    public MiniPlayerView()
    {
        InitializeComponent();
    }

    private void OnPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (e.GetCurrentPoint(this).Properties.IsLeftButtonPressed)
            BeginMoveDrag(e);
    }

    private void OnProgressReleased(object? sender, PointerReleasedEventArgs e)
    {
        if (sender is Slider slider)
            AppServices.Playback.Seek(slider.Value);
    }

    /// <summary>展开到主窗口：显示主窗口并关闭迷你播放器。</summary>
    private void OnExpandClick(object? sender, RoutedEventArgs e)
    {
        if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop
            && desktop.MainWindow is { } main)
        {
            main.Show();
            main.WindowState = WindowState.Normal;
            main.Activate();
        }
        Close();
    }

    private void OnCloseClick(object? sender, RoutedEventArgs e) => Close();
}
