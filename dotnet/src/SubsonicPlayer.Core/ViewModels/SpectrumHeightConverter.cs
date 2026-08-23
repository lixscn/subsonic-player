using System;
using System.Globalization;
using Avalonia.Data.Converters;

namespace SubsonicPlayer.ViewModels;

/// <summary>频谱值（float 0..1）→ 柱高（double 像素）。</summary>
public class SpectrumHeightConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var scale = parameter is string s && double.TryParse(s, out var d) ? d : 150.0;
        return value switch
        {
            float f => f * scale,
            double dd => dd * scale,
            _ => 0.0,
        };
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}
