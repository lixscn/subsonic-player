using System;
using System.Runtime.InteropServices;

namespace SubsonicPlayer.Services;

/// <summary>BASSmix 混合器标志。</summary>
[Flags]
public enum BASSMixFlag : uint
{
    Default = 0,
    MixerEnd = 0x10000,
    MixerNonStop = 0x20000,
    MixerResume = 0x1000,
    MixerLimit = 0x1000,
    MixerNoRampIn = 0x800000,
}

/// <summary>BASSmix 音量包络节点（crossfade 用）。</summary>
[StructLayout(LayoutKind.Sequential)]
public struct BASS_MIXER_ENV_POS
{
    public long pos;
    public float value;
}

/// <summary>BASSmix 原生 P/Invoke。</summary>
internal static class BassMixNative
{
    private const string Lib = "bassmix";

    [DllImport(Lib, EntryPoint = "BASS_Mixer_StreamCreate")]
    public static extern int BASS_Mixer_StreamCreate(int freq, int chans, uint flags);

    [DllImport(Lib, EntryPoint = "BASS_Mixer_StreamAddChannel")]
    public static extern bool BASS_Mixer_StreamAddChannel(int handle, int channel, uint flags);

    [DllImport(Lib, EntryPoint = "BASS_Mixer_StreamAddChannelEx")]
    public static extern bool BASS_Mixer_StreamAddChannelEx(int handle, int channel, uint flags, long start, long length);

    [DllImport(Lib, EntryPoint = "BASS_Mixer_ChannelRemove")]
    public static extern bool BASS_Mixer_ChannelRemove(int channel);

    [DllImport(Lib, EntryPoint = "BASS_Mixer_ChannelGetPosition")]
    public static extern long BASS_Mixer_ChannelGetPosition(int channel, uint mode);

    [DllImport(Lib, EntryPoint = "BASS_Mixer_ChannelSetPosition")]
    public static extern bool BASS_Mixer_ChannelSetPosition(int channel, long pos, uint mode);

    [DllImport(Lib, EntryPoint = "BASS_Mixer_ChannelSetEnvelope")]
    public static extern bool BASS_Mixer_ChannelSetEnvelope(int handle, BASS_MIXER_ENV_POS[] nodes, int count);

    [DllImport(Lib, EntryPoint = "BASS_Mixer_ChannelGetLevel")]
    public static extern int BASS_Mixer_ChannelGetLevel(int channel);
}
