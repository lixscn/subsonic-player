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
    NoFx = 34,
    NotAvail = 37,
    Decode = 38,
    Dx = 39,
    /// <summary>连接/起播超时（BASS_ERROR_TIMEOUT）。慢服务器最常见：默认 NET_TIMEOUT 只有 5s。</summary>
    Timeout = 40,
    FileForm = 41,
    Speaker = 42,
    Version = 43,
    Codec = 44,
    Ended = 45,
    Busy = 46,
    /// <summary>文件不可流式播放（BASS_ERROR_UNSTREAMABLE）：服务端返回体没有可用的长度/结构。
    /// 本工程正是靠它决定"降级下载整个文件再播"。</summary>
    Unstreamable = 47,
    Protocol = 48,
    Denied = 49,
    Freeing = 50,
    Cancel = 51,
    Unknown = -1,
}

/// <summary>
/// BASS 全局配置项（★ 数值必须与 bass.h 完全一致）。
///
/// 历史坑：这里曾写成 NetPrebuf=14 / NetConnectTimeout=25 / NetReadTimeout=26 —— 这三个**都不是**
/// 合法配置项（14/25/26 在 bass.h 里根本不存在，BASS 也没有独立的 "connect timeout"）。
/// 于是 BASS_SetConfig 静默失败（返回值被忽略），整套"慢网优化"从未生效：
///   · NetBuffer 生效了（12 正确）→ 3000ms；
///   · NetPrebuf 没生效 → 仍是默认 80%，即起播前要预缓冲 ≈2.4s 才出声；
///   · NetTimeout 没生效 → 仍是默认 5s，慢服务器上 BASS_StreamCreateURL 直接超时(40)，
///     掉进"下载整个文件再播"的兜底 → 点歌要等好几秒到几十秒才响。
/// </summary>
public enum BASSConfig : uint
{
    /// <summary>连接/响应超时，ms（BASS 只有这一个超时项，默认 5000）。</summary>
    NetTimeout = 11,
    /// <summary>网络缓冲长度，ms（默认 5000）。</summary>
    NetBuffer = 12,
    /// <summary>起播前预缓冲比例，0-100（相对 NetBuffer，默认 80）。越小起播越快。</summary>
    NetPrebuf = 15,
    /// <summary>读超时，ms（默认与 NetTimeout 相关）。</summary>
    NetReadTimeout = 37,
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

    [DllImport(Lib, EntryPoint = "BASS_StreamCreateFile", CharSet = CharSet.Ansi)]
    public static extern int BASS_StreamCreateFile(
        bool mem, [MarshalAs(UnmanagedType.LPStr)] string file, long offset, long length, BASSFlag flags);

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

    [DllImport(Lib, EntryPoint = "BASS_ChannelGetLevel")]
    public static extern int BASS_ChannelGetLevel(int handle);

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
