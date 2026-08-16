using System.Collections.Generic;
using CommunityToolkit.Mvvm.ComponentModel;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class EqPanelViewModel : ViewModelBase
{
    public PlaybackService Playback => AppServices.Playback;

    [ObservableProperty] private double _eq1;
    [ObservableProperty] private double _eq2;
    [ObservableProperty] private double _eq3;
    [ObservableProperty] private double _eq4;
    [ObservableProperty] private double _eq5;
    [ObservableProperty] private double _eq6;
    [ObservableProperty] private double _eq7;
    [ObservableProperty] private double _eq8;
    [ObservableProperty] private double _eq9;
    [ObservableProperty] private double _eq10;

    [ObservableProperty] private string _selectedPreset = "自定义";

    partial void OnEq1Changed(double value) => Playback.SetEqGain(0, (float)value);
    partial void OnEq2Changed(double value) => Playback.SetEqGain(1, (float)value);
    partial void OnEq3Changed(double value) => Playback.SetEqGain(2, (float)value);
    partial void OnEq4Changed(double value) => Playback.SetEqGain(3, (float)value);
    partial void OnEq5Changed(double value) => Playback.SetEqGain(4, (float)value);
    partial void OnEq6Changed(double value) => Playback.SetEqGain(5, (float)value);
    partial void OnEq7Changed(double value) => Playback.SetEqGain(6, (float)value);
    partial void OnEq8Changed(double value) => Playback.SetEqGain(7, (float)value);
    partial void OnEq9Changed(double value) => Playback.SetEqGain(8, (float)value);
    partial void OnEq10Changed(double value) => Playback.SetEqGain(9, (float)value);

    partial void OnSelectedPresetChanged(string value) => ApplyPreset(value);

    public static IReadOnlyList<string> PresetNames { get; } =
        new List<string> { "自定义", "摇滚", "流行", "古典", "人声", "重低音" };

    private void ApplyPreset(string name)
    {
        float[] gains = name switch
        {
            "摇滚" => new float[] { 5, 3, 0, -2, -1, 2, 4, 5, 4, 3 },
            "流行" => new float[] { -1, 1, 3, 4, 3, 0, -1, -1, 0, 1 },
            "古典" => new float[] { 4, 3, 2, 0, -1, -1, 0, 2, 3, 4 },
            "人声" => new float[] { -2, -1, 0, 2, 4, 4, 3, 1, 0, -1 },
            "重低音" => new float[] { 6, 5, 4, 2, 0, 0, 0, 0, 0, 0 },
            _ => new float[10],
        };

        Eq1 = gains[0];
        Eq2 = gains[1];
        Eq3 = gains[2];
        Eq4 = gains[3];
        Eq5 = gains[4];
        Eq6 = gains[5];
        Eq7 = gains[6];
        Eq8 = gains[7];
        Eq9 = gains[8];
        Eq10 = gains[9];
    }
}
