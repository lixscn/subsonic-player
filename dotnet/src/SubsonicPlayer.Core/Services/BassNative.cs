using System;
using System.Runtime.InteropServices;

namespace SubsonicPlayer.Services;

/// <summary>BASS 初始化标志。</summary>
public enum BASSInit : uint
{
    Default = 0,
    Mono = 2,
    Device16bits = 8,
    DeviceLatency = 0x100,
    DeviceCPSpeakers = 0x400,
    DeviceSpeaker = 0x2000,
    Device3D = 0x4000,
}

/// <summary>BASS 采样/流标志。</summary>
[Flags]
public enum BASSFlag : uint
{
    Default = 0,
    SampleFloat = 256,
    SampleMono = 2,
    SampleLoop = 4,
    StreamAutoFree = 0x40000,
    StreamStatus = 0x800000,
    StreamPrescan = 0x20000,
    StreamDownload = 0x400000,
    StreamDecode = 0x200000,
}

/// <summary>BASS_ChannelGetData 标志（FFT）。</summary>
[Flags]
public enum BASSData : uint
{
    FFT512 = 0x80000000,
    FFT1024 = 0x80000001,
    FFT2048 = 0x80000002,
    FFT4096 = 0x80000003,
    FFTIndividual = 0x10,
    FFTNoWindow = 0x20,
    FFTRemoveDC = 0x40,
}

/// <summary>BASS 位置模式。</summary>
public enum BASSMode : uint
{
    PosByte = 0,
    PosMusicOrder = 1,
    PosOgg = 3,
    PosDecode = 0x10000000,
    PosBytes = 0,
}

/// <summary>BASS 通道属性。</summary>
public enum BASSAttribute : uint
{
    Freq = 1,
    Volume = 2,
    Pan = 3,
    EaxMix = 4,
}

/// <summary>BASS 通道活动状态。</summary>
public enum BASSActive : uint
{
    Stopped = 0,
    Playing = 1,
    Stalled = 2,
    Paused = 3,
}

/// <summary>BASS 错误码。</summary>
public enum BASSError : int
{
    Ok = 0,
    Mem = 1,
    FileOpen = 2,
    Driver = 3,
    BufferLost = 4,
    Handle = 5,
    SampleFormat = 6,
    Position = 7,
    Init = 8,
    Start = 9,
    Already = 14,
    NoChannel = 18,
    IllegalParam = 20,
    No3D = 21,
    NoNet = 23,
    NotAvail = 37,
    Decode = 38,
    Dx = 39,
    InitTimeout = 40,
    NoFreq = 41,
    NoPlay = 42,
    Unknown = -1,
}

/// <summary>BASS 全局配置项（用于网络缓冲等）。</summary>
public enum BASSConfig : uint
{
    NetBuffer = 12,            // 网络缓冲，ms（默认约 500）
    NetPrebuf = 14,            // 起播前预缓冲：0=全缓冲 1=按需 -1=缓冲一份
    NetConnectTimeout = 25,    // 连接超时，ms（默认 30s）
    NetReadTimeout = 26,       // 读超时，ms（默认 30s）
}

/// <summary>BASS 原生 P/Invoke（仅基础播放所需函数）。</summary>
internal static class BassNative
{
    private const string Lib = "bass";

    [DllImport(Lib, EntryPoint = "BASS_Init")]
    public static extern bool BASS_Init(int device, int freq, BASSInit flags, IntPtr win, IntPtr clsid);

    [DllImport(Lib, EntryPoint = "BASS_SetConfig")]
    public static extern bool BASS_SetConfig(BASSConfig config, int value);

    [DllImport(Lib, EntryPoint = "BASS_StreamCreateURL", CharSet = CharSet.Ansi)]
    public static extern int BASS_StreamCreateURL(
        [MarshalAs(UnmanagedType.LPStr)] string url, int offset, BASSFlag flags, IntPtr proc, IntPtr user);

    [DllImport(Lib, EntryPoint = "BASS_ChannelPlay")]
    public static extern bool BASS_ChannelPlay(int handle, bool restart);

    [DllImport(Lib, EntryPoint = "BASS_ChannelPause")]
    public static extern bool BASS_ChannelPause(int handle);

    [DllImport(Lib, EntryPoint = "BASS_ChannelStop")]
    public static extern bool BASS_ChannelStop(int handle);

    [DllImport(Lib, EntryPoint = "BASS_ChannelIsActive")]
    public static extern BASSActive BASS_ChannelIsActive(int handle);

    [DllImport(Lib, EntryPoint = "BASS_StreamFree")]
    public static extern bool BASS_StreamFree(int handle);

    [DllImport(Lib, EntryPoint = "BASS_ChannelSetPosition")]
    public static extern bool BASS_ChannelSetPosition(int handle, long pos, BASSMode mode);

    [DllImport(Lib, EntryPoint = "BASS_ChannelGetPosition")]
    public static extern long BASS_ChannelGetPosition(int handle, BASSMode mode);

    [DllImport(Lib, EntryPoint = "BASS_ChannelGetLength")]
    public static extern long BASS_ChannelGetLength(int handle, BASSMode mode);

    [DllImport(Lib, EntryPoint = "BASS_ChannelSetAttribute")]
    public static extern bool BASS_ChannelSetAttribute(int handle, BASSAttribute attrib, float value);

    [DllImport(Lib, EntryPoint = "BASS_ChannelGetAttribute")]
    public static extern bool BASS_ChannelGetAttribute(int handle, BASSAttribute attrib, ref float value);

    [DllImport(Lib, EntryPoint = "BASS_ChannelBytes2Seconds")]
    public static extern double BASS_ChannelBytes2Seconds(int handle, long pos);

    [DllImport(Lib, EntryPoint = "BASS_ChannelSeconds2Bytes")]
    public static extern long BASS_ChannelSeconds2Bytes(int handle, double pos);

    [DllImport(Lib, EntryPoint = "BASS_ChannelSetFX")]
    public static extern int BASS_ChannelSetFX(int handle, BASSFXType type, int priority);

    [DllImport(Lib, EntryPoint = "BASS_ChannelRemoveFX")]
    public static extern bool BASS_ChannelRemoveFX(int handle, int fx);

    [DllImport(Lib, EntryPoint = "BASS_FXSetParameters")]
    public static extern bool BASS_FXSetParameters(int handle, IntPtr par);

    [DllImport(Lib, EntryPoint = "BASS_FXGetParameters")]
    public static extern bool BASS_FXGetParameters(int handle, IntPtr par);

    [DllImport(Lib, EntryPoint = "BASS_ChannelGetData")]
    public static extern int BASS_ChannelGetData(int handle, float[] buffer, uint length);

    [DllImport(Lib, EntryPoint = "BASS_ErrorGetCode")]
    public static extern BASSError BASS_ErrorGetCode();

    [DllImport(Lib, EntryPoint = "BASS_PluginLoad", CharSet = CharSet.Ansi)]
    public static extern int BASS_PluginLoad([MarshalAs(UnmanagedType.LPStr)] string fileName, uint flags);

    [DllImport(Lib, EntryPoint = "BASS_Free")]
    public static extern bool BASS_Free();
}
