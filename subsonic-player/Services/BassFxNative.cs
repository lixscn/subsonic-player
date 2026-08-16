using System;
using System.Runtime.InteropServices;

namespace SubsonicPlayer.Services;

/// <summary>BASS_FX 效果类型。</summary>
public enum BASSFXType : uint
{
    Dx8Chorus = 0,
    Dx8Distortion = 1,
    Dx8Echo = 2,
    Dx8Flanger = 3,
    Dx8Compressor = 4,
    Dx8Gargle = 5,
    Dx8I3DL2Reverb = 6,
    Dx8ParamEQ = 7,
    Dx8Reverb = 8,

    BfxChorus = 0x10003,
    BfxCompressor = 0x1000B,
    BfxEcho = 0x10002,
    BfxFreeverb = 0x10013,
    BfxBqf = 0x10000,
}

/// <summary>10 段 EQ（BASS_DX8_PARAMEQ）。</summary>
[StructLayout(LayoutKind.Sequential)]
public struct BASS_DX8_PARAMEQ
{
    public float fCenter;
    public float fBandwidth;
    public float fGain;
}

/// <summary>混响（BASS_BFX_FREEVERB）。</summary>
[StructLayout(LayoutKind.Sequential)]
public struct BASS_BFX_FREEVERB
{
    public float fDryMix;
    public float fWetMix;
    public float fRoomSize;
    public float fDamp;
    public float fWidth;
    public int lMode;
}

/// <summary>回声（BASS_BFX_ECHO）。</summary>
[StructLayout(LayoutKind.Sequential)]
public struct BASS_BFX_ECHO
{
    public float fLevel;
    public int lDelay;
}

/// <summary>合唱（BASS_BFX_CHORUS）。</summary>
[StructLayout(LayoutKind.Sequential)]
public struct BASS_BFX_CHORUS
{
    public float fDryMix;
    public float fWetMix;
    public float fFeedback;
    public float fMinSweep;
    public float fMaxSweep;
    public float fRate;
    public int lChannel;
}

/// <summary>压缩器（BASS_BFX_COMPRESSOR2）。</summary>
[StructLayout(LayoutKind.Sequential)]
public struct BASS_BFX_COMPRESSOR2
{
    public float fGain;
    public float fThreshold;
    public float fRatio;
    public float fAttack;
    public float fRelease;
    public int lChannel;
}

/// <summary>BASS_FX tempo 属性。</summary>
public enum BASSFXAttribute : uint
{
    Tempo = 0x10000,
    TempoPitch = 0x10001,
    TempoFreq = 0x10002,
}

/// <summary>BASS_FX 原生 P/Invoke（bass_fx.dll 特有的 tempo/BPM/reverse）。</summary>
internal static class BassFxNative
{
    private const string Lib = "bass_fx";

    /// <summary>BASS_FX_FREESOURCE：释放 tempo 流时自动释放 source 流（否则 source 泄漏）。</summary>
    public const uint FreSource = 0x10000;

    [DllImport(Lib, EntryPoint = "BASS_FX_GetVersion")]
    public static extern uint BASS_FX_GetVersion();

    [DllImport(Lib, EntryPoint = "BASS_FX_TempoCreate")]
    public static extern int BASS_FX_TempoCreate(int channel, uint flags);

    public static bool SetParameters<T>(int fxHandle, ref T par) where T : struct
    {
        var ptr = Marshal.AllocHGlobal(Marshal.SizeOf<T>());
        try
        {
            Marshal.StructureToPtr(par, ptr, false);
            return BassNative.BASS_FXSetParameters(fxHandle, ptr);
        }
        finally
        {
            Marshal.FreeHGlobal(ptr);
        }
    }
}
