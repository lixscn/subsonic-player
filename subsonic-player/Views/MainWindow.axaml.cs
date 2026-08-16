using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using SubsonicPlayer.Services;
using SubsonicPlayer.ViewModels;

namespace SubsonicPlayer.Views;

public partial class MainWindow : Window
{
    private readonly GlobalHotkeyManager _hotkeys = new();

    public MainWindow()
    {
        InitializeComponent();
        Opened += (_, _) => _hotkeys.Register(this);
    }

    private void OnMinimizeClick(object? sender, RoutedEventArgs e)
        => WindowState = WindowState.Minimized;

    private void OnMaximizeClick(object? sender, RoutedEventArgs e)
        => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void OnCloseClick(object? sender, RoutedEventArgs e)
        => Close();

    private void OnTitleBarPressed(object? sender, PointerPressedEventArgs e)
    {
        if (e.GetCurrentPoint(this).Properties.IsLeftButtonPressed)
            BeginMoveDrag(e);
    }

    private void OnProgressReleased(object? sender, PointerReleasedEventArgs e)
    {
        if (sender is Slider slider)
            AppServices.Playback.Seek(slider.Value);
    }

    private void OnSearchKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && DataContext is MainWindowViewModel vm)
        {
            vm.SearchCommand.Execute(null);
            e.Handled = true;
        }
    }

    private void OnTitleBarDoubleTapped(object? sender, TappedEventArgs e)
    {
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
    }

    private void OnKeyDown(object? sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.MediaPlayPause:
                AppServices.Playback.PlayPauseCommand.Execute(null);
                e.Handled = true;
                break;
            case Key.MediaNextTrack:
                AppServices.Playback.NextCommand.Execute(null);
                e.Handled = true;
                break;
            case Key.MediaPreviousTrack:
                AppServices.Playback.PreviousCommand.Execute(null);
                e.Handled = true;
                break;
            case Key.MediaStop:
                AppServices.Playback.PlayPauseCommand.Execute(null);
                e.Handled = true;
                break;
        }
    }
}
