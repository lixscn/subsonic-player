using Avalonia;
using Avalonia.Media;
using Avalonia.Styling;

namespace SubsonicPlayer.Services;

/// <summary>主题切换（跨平台，操作 Application 资源字典）。</summary>
public static class ThemeManager
{
    private static readonly (string Key, string Dark, string Light)[] ThemeColors =
    {
        ("BgAppBrush", "#0A0A0C", "#F5F5F7"),
        ("BgSurfaceBrush", "#121216", "#FFFFFF"),
        ("BgCardBrush", "#1A1A1F", "#ECECF0"),
        ("BgHoverBrush", "#222228", "#E0E0E6"),
        ("BorderBrush", "#2A2A33", "#D5D5DC"),
        ("TextPrimaryBrush", "#F5F5F7", "#1A1A1F"),
        ("TextSecondaryBrush", "#9C9CA6", "#6B6B76"),
        ("TextMutedBrush", "#6B6B76", "#A1A1AA"),
        ("OverlayBrush", "#7A0A0A0C", "#7AF5F5F7"),
        ("ShadowBrush", "#40000000", "#1A000000"),
    };

    /// <summary>切换深浅色主题。</summary>
    public static void ApplyTheme(bool dark)
    {
        if (Application.Current is not { } app)
            return;

        foreach (var (key, darkColor, lightColor) in ThemeColors)
        {
            var color = Color.Parse(dark ? darkColor : lightColor);
            if (app.Resources.TryGetResource(key, null, out var existing) && existing is SolidColorBrush brush)
                brush.Color = color;
            else
                app.Resources[key] = new SolidColorBrush(color);
        }

        app.RequestedThemeVariant = dark ? ThemeVariant.Dark : ThemeVariant.Light;
    }
}