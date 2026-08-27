using System;
using System.IO;
using System.Threading.Tasks;

namespace SubsonicPlayer.Services;

/// <summary>
/// BASS 音频引擎（Mixer 架构）。
/// 解码流（DECODE）→ BASS_FX tempo → 加入 Mixer，实现 Gapless + Crossfade。
/// EQ/DSP 设置在 Mixer 上（全局）；tempo/pitch 设置在单个 channel 上。
/// </summary>
public class AudioEngine
{
    private const int EqBands = 10;
    private static readonly float[] EqFrequencies = { 100, 150, 250, 500, 1000, 2000, 4000, 8000, 12000, 16000 };

    private int _mixer;
    private int _currentChannel;
    private float _volume = 0.8f;

    private readonly int[] _eqFx = new int[EqBands];
    private int _reverbFx;
    private int _echoFx;
    private int _chorusFx;
    private int _compressorFx;

    public bool IsInitialized { get; private set; }

    public bool Initialize()
    {
        if (IsInitialized)
            return true;

        if (!BassNative.BASS_Init(-1, 44100, BASSInit.Default, IntPtr.Zero, IntPtr.Zero))
            return false;

        // 慢网优化：加大网络缓冲/预缓冲/超时，减少 underrun 导致的播放卡顿。
        // NetBuffer 单位 ms；NetPrebuf=-1 表示起播前预缓冲一份缓冲的量。
        BassNative.BASS_SetConfig(BASSConfig.NetBuffer, 3000);          // 3s 网络缓冲
        BassNative.BASS_SetConfig(BASSConfig.NetPrebuf, -1);            // 起播前预缓冲一份缓冲
        BassNative.BASS_SetConfig(BASSConfig.NetConnectTimeout, 15000); // 连接超时 15s
        BassNative.BASS_SetConfig(BASSConfig.NetReadTimeout, 30000);    // 读超时 30s

        LoadPlugins();

        _mixer = BassMixNative.BASS_Mixer_StreamCreate(44100, 2, (uint)(BASSFlag.SampleFloat | (BASSFlag)BASSMixFlag.MixerEnd));
        if (_mixer == 0)
        {
            BassNative.BASS_Free();
            return false;
        }

        IsInitialized = true;
        return true;
    }

    /// <summary>加载解码插件（FLAC/Opus/APE/WavPack/DSD/MIDI），遇对应格式时由 BASS 内核自动调用解码。</summary>
    private static void LoadPlugins()
    {
        // BASS_PluginLoad 按文件名显式加载，需按平台使用对应后缀（.dll / .so / .dylib）；
        // 传绝对路径确保 macOS（默认只搜 bundle Frameworks）与 Linux 都能找到与可执行文件同目录的插件。
        string[] pluginNames = { "bassflac", "bassopus", "bassape", "basswv", "bassdsd", "bassmidi" };
        var baseDir = AppContext.BaseDirectory;
        foreach (var name in pluginNames)
        {
            // 插件缺失时静默跳过，不影响其他格式
            BassNative.BASS_PluginLoad(Path.Combine(baseDir, "lib", name + LibraryExtension), 0);
        }
    }

    /// <summary>当前平台原生库文件后缀（Windows .dll / Linux .so / macOS .dylib）。</summary>
    private static string LibraryExtension =>
        OperatingSystem.IsWindows() ? ".dll" : OperatingSystem.IsMacOS() ? ".dylib" : ".so";

    /// <summary>创建解码流（含 tempo 包装），返回 channel 句柄（0 表示失败）。</summary>
    public int CreateStream(string url)
    {
        if (!Initialize())
            return 0;

        var source = BassNative.BASS_StreamCreateURL(url, 0, BASSFlag.StreamDecode | BASSFlag.SampleFloat, IntPtr.Zero, IntPtr.Zero);
        if (source == 0)
            return 0;

        // tempo 包装（tempo=1.0 默认不改变，支持速度/音调调整）
        // FreSource：释放 tempo 流时自动释放 source 流，避免 URL 流泄漏（内存 + 网络线程）
        var tempo = BassFxNative.BASS_FX_TempoCreate(source, (uint)(BASSFlag.StreamDecode | BASSFlag.SampleFloat) | BassFxNative.FreSource);
        if (tempo == 0)
            return source; // tempo 创建失败时退化为原始流

        return tempo;
    }

    /// <summary>播放 channel（加入 mixer 并播放）。</summary>
    public bool PlayChannel(int channel)
    {
        if (_mixer == 0 || channel == 0)
            return false;

        RemoveCurrent();
        _currentChannel = channel;
        BassMixNative.BASS_Mixer_StreamAddChannel(_mixer, channel, (uint)BASSMixFlag.MixerNoRampIn);
        BassNative.BASS_ChannelSetAttribute(channel, BASSAttribute.Volume, _volume);
        return BassNative.BASS_ChannelPlay(_mixer, false);
    }

    /// <summary>Gapless 切换到新 channel（mixer 不中断）。</summary>
    public bool SwitchTo(int newChannel)
    {
        if (_mixer == 0 || newChannel == 0)
            return false;

        RemoveCurrent();
        _currentChannel = newChannel;
        BassMixNative.BASS_Mixer_StreamAddChannel(_mixer, newChannel, (uint)BASSMixFlag.MixerNoRampIn);
        BassNative.BASS_ChannelSetAttribute(newChannel, BASSAttribute.Volume, _volume);

        // mixer 因 MixerEnd 已停止时（兜底切歌场景），需重新启动
        if (BassNative.BASS_ChannelIsActive(_mixer) == BASSActive.Stopped)
            BassNative.BASS_ChannelPlay(_mixer, false);
        return true;
    }

    /// <summary>Crossfade：新 channel 淡入，旧 channel 淡出。</summary>
    public void CrossfadeTo(int newChannel, double seconds)
    {
        if (_mixer == 0 || newChannel == 0)
            return;

        var old = _currentChannel;

        // 新 channel 加入 mixer，设最终音量，envelope 相对淡入 0 → 1
        BassMixNative.BASS_Mixer_StreamAddChannel(_mixer, newChannel, (uint)BASSMixFlag.MixerNoRampIn);
        BassNative.BASS_ChannelSetAttribute(newChannel, BASSAttribute.Volume, _volume);
        var fadeInBytes = BassNative.BASS_ChannelSeconds2Bytes(newChannel, seconds);
        BassMixNative.BASS_Mixer_ChannelSetEnvelope(newChannel, new[]
        {
            new BASS_MIXER_ENV_POS { pos = 0, value = 0f },
            new BASS_MIXER_ENV_POS { pos = fadeInBytes, value = 1f },
        }, 2);

        _currentChannel = newChannel;

        // 旧 channel envelope 相对淡出 1 → 0，淡出结束后移除，确保旧歌停止
        if (old != 0 && old != newChannel)
        {
            var fadeOutBytes = BassNative.BASS_ChannelSeconds2Bytes(old, seconds);
            BassMixNative.BASS_Mixer_ChannelSetEnvelope(old, new[]
            {
                new BASS_MIXER_ENV_POS { pos = 0, value = 1f },
                new BASS_MIXER_ENV_POS { pos = fadeOutBytes, value = 0f },
            }, 2);

            _ = RemoveChannelAfterAsync(old, seconds);
        }

        // mixer 因 MixerEnd 已停止时，重新启动
        if (BassNative.BASS_ChannelIsActive(_mixer) == BASSActive.Stopped)
            BassNative.BASS_ChannelPlay(_mixer, false);
    }

    private async Task RemoveChannelAfterAsync(int channel, double seconds)
    {
        try
        {
            await Task.Delay((int)Math.Ceiling(seconds * 1000) + 100);
            BassMixNative.BASS_Mixer_ChannelRemove(channel);
            BassNative.BASS_StreamFree(channel);
        }
        catch
        {
            // 移除失败忽略
        }
    }

    public bool Play() => _mixer != 0 && BassNative.BASS_ChannelPlay(_mixer, false);
    public bool Pause() => _mixer != 0 && BassNative.BASS_ChannelPause(_mixer);

    public void Stop()
    {
        if (_mixer != 0)
            BassNative.BASS_ChannelStop(_mixer);
        RemoveCurrent();
    }

    public bool IsPlaying => _mixer != 0 && BassNative.BASS_ChannelIsActive(_mixer) == BASSActive.Playing;

    /// <summary>mixer 是否已停止（歌曲结束 / 流中断导致，区别于暂停 Paused 与缓冲 Stalled）。</summary>
    public bool IsStopped => _mixer != 0 && BassNative.BASS_ChannelIsActive(_mixer) == BASSActive.Stopped;

    public bool HasStream => _currentChannel != 0;

    public double PositionSeconds
    {
        get
        {
            if (_currentChannel == 0)
                return 0;
            var bytes = BassMixNative.BASS_Mixer_ChannelGetPosition(_currentChannel, 0);
            return BassNative.BASS_ChannelBytes2Seconds(_currentChannel, bytes);
        }
        set
        {
            if (_currentChannel == 0)
                return;
            var bytes = BassNative.BASS_ChannelSeconds2Bytes(_currentChannel, Math.Max(0, value));
            BassMixNative.BASS_Mixer_ChannelSetPosition(_currentChannel, bytes, 0);
        }
    }

    public float Volume
    {
        get => _volume;
        set
        {
            _volume = Math.Clamp(value, 0f, 1f);
            if (_currentChannel != 0)
                BassNative.BASS_ChannelSetAttribute(_currentChannel, BASSAttribute.Volume, _volume);
        }
    }

    // ---- EQ ----

    public static float GetEqFrequency(int band)
        => band >= 0 && band < EqBands ? EqFrequencies[band] : 0;

    public void SetEqGain(int band, float gain)
    {
        if (_mixer == 0 || band < 0 || band >= EqBands)
        {
            EqLog($"skipped mixer={_mixer} band={band}");
            return;
        }

        if (_eqFx[band] == 0)
        {
            _eqFx[band] = BassNative.BASS_ChannelSetFX(_mixer, BASSFXType.Dx8ParamEQ, 0);
            if (_eqFx[band] == 0)
            {
                EqLog($"ChannelSetFX failed band={band} err={BassNative.BASS_ErrorGetCode()}");
                return;
            }
        }

        var par = new BASS_DX8_PARAMEQ
        {
            fCenter = EqFrequencies[band],
            fBandwidth = 12f,   // 带宽（半音）：1 octave = 12 semitones，保证增益可闻
            fGain = gain,
        };
        var ok = BassFxNative.SetParameters(_eqFx[band], ref par);
        EqLog($"band={band} gain={gain} fx={_eqFx[band]} ok={ok} err={BassNative.BASS_ErrorGetCode()}");
    }

    private static void EqLog(string msg)
    {
        try
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "subsonic-player");
            Directory.CreateDirectory(dir);
            File.AppendAllText(Path.Combine(dir, "eq.log"), $"[{DateTime.Now:HH:mm:ss}] {msg}{Environment.NewLine}");
        }
        catch { }
    }

    public float GetEqGain(int band)
    {
        if (band < 0 || band >= EqBands || _eqFx[band] == 0)
            return 0;

        var par = new BASS_DX8_PARAMEQ();
        var ptr = System.Runtime.InteropServices.Marshal.AllocHGlobal(System.Runtime.InteropServices.Marshal.SizeOf<BASS_DX8_PARAMEQ>());
        try
        {
            System.Runtime.InteropServices.Marshal.StructureToPtr(par, ptr, false);
            if (BassNative.BASS_FXGetParameters(_eqFx[band], ptr))
            {
                par = System.Runtime.InteropServices.Marshal.PtrToStructure<BASS_DX8_PARAMEQ>(ptr);
                return par.fGain;
            }
            return 0;
        }
        finally
        {
            System.Runtime.InteropServices.Marshal.FreeHGlobal(ptr);
        }
    }

    // ---- 频谱 ----

    /// <summary>返回 FFT 幅度谱（前 size 个频点，归一化 0..1）。</summary>
    public double[] GetSpectrum(int size = 256)
    {
        if (_mixer == 0)
            return Array.Empty<double>();

        var buffer = new float[1024]; // FFT1024 = 512 个复数 bin
        var got = BassNative.BASS_ChannelGetData(_mixer, buffer, (uint)BASSData.FFT1024);
        if (got < 0)
            return new double[size];

        var bins = got / 2;
        var result = new double[size];
        var max = 0.0;
        for (var i = 0; i < size && i < bins; i++)
        {
            var re = buffer[i * 2];
            var im = buffer[i * 2 + 1];
            result[i] = Math.Sqrt(re * re + im * im);
            if (result[i] > max)
                max = result[i];
        }

        if (max > 0)
            for (var i = 0; i < size; i++)
                result[i] /= max;

        return result;
    }

    // ---- tempo / pitch ----

    public bool SetTempo(float tempo)
    {
        if (_currentChannel == 0)
            return false;
        return BassNative.BASS_ChannelSetAttribute(_currentChannel, (BASSAttribute)BASSFXAttribute.Tempo, tempo);
    }

    public bool SetPitch(float pitchSemitones)
    {
        if (_currentChannel == 0)
            return false;
        return BassNative.BASS_ChannelSetAttribute(_currentChannel, (BASSAttribute)BASSFXAttribute.TempoPitch, pitchSemitones);
    }

    // ---- DSP ----

    public void SetReverb(bool enabled)
    {
        if (enabled && _reverbFx == 0)
        {
            _reverbFx = BassNative.BASS_ChannelSetFX(_mixer, BASSFXType.BfxFreeverb, 0);
            if (_reverbFx == 0)
                return;
            var par = new BASS_BFX_FREEVERB { fDryMix = 0.7f, fWetMix = 0.5f, fRoomSize = 0.7f, fDamp = 0.5f, fWidth = 1.0f, lMode = 0 };
            BassFxNative.SetParameters(_reverbFx, ref par);
        }
        else if (!enabled && _reverbFx != 0)
        {
            BassNative.BASS_ChannelRemoveFX(_mixer, _reverbFx);
            _reverbFx = 0;
        }
    }

    public void SetEcho(bool enabled)
    {
        if (enabled && _echoFx == 0)
        {
            _echoFx = BassNative.BASS_ChannelSetFX(_mixer, BASSFXType.BfxEcho, 0);
            if (_echoFx == 0)
                return;
            var par = new BASS_BFX_ECHO { fLevel = 0.5f, lDelay = 300 };
            BassFxNative.SetParameters(_echoFx, ref par);
        }
        else if (!enabled && _echoFx != 0)
        {
            BassNative.BASS_ChannelRemoveFX(_mixer, _echoFx);
            _echoFx = 0;
        }
    }

    public void SetChorus(bool enabled)
    {
        if (enabled && _chorusFx == 0)
        {
            _chorusFx = BassNative.BASS_ChannelSetFX(_mixer, BASSFXType.BfxChorus, 0);
            if (_chorusFx == 0)
                return;
            var par = new BASS_BFX_CHORUS { fDryMix = 0.7f, fWetMix = 0.4f, fFeedback = 0.1f, fMinSweep = 0.1f, fMaxSweep = 0.3f, fRate = 1.0f, lChannel = 0 };
            BassFxNative.SetParameters(_chorusFx, ref par);
        }
        else if (!enabled && _chorusFx != 0)
        {
            BassNative.BASS_ChannelRemoveFX(_mixer, _chorusFx);
            _chorusFx = 0;
        }
    }

    public void SetCompressor(bool enabled)
    {
        if (enabled && _compressorFx == 0)
        {
            _compressorFx = BassNative.BASS_ChannelSetFX(_mixer, BASSFXType.BfxCompressor, 0);
            if (_compressorFx == 0)
                return;
            var par = new BASS_BFX_COMPRESSOR2 { fGain = 1.0f, fThreshold = -20.0f, fRatio = 4.0f, fAttack = 0.01f, fRelease = 0.2f, lChannel = 0 };
            BassFxNative.SetParameters(_compressorFx, ref par);
        }
        else if (!enabled && _compressorFx != 0)
        {
            BassNative.BASS_ChannelRemoveFX(_mixer, _compressorFx);
            _compressorFx = 0;
        }
    }

    private void RemoveCurrent()
    {
        if (_currentChannel != 0)
        {
            BassMixNative.BASS_Mixer_ChannelRemove(_currentChannel);
            BassNative.BASS_StreamFree(_currentChannel);
            _currentChannel = 0;
        }
    }

    public void Free()
    {
        RemoveCurrent();
        if (_mixer != 0)
        {
            BassNative.BASS_StreamFree(_mixer);
            _mixer = 0;
        }
        if (IsInitialized)
        {
            BassNative.BASS_Free();
            IsInitialized = false;
        }
    }
}
