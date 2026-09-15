using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net.Http;
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
    private float _streamGain = 1f;   // 当前曲目 ReplayGain 线性倍率（跨曲音量一致）
    private float _normGain = 1f;     // 客户端音量平均倍率（服务器未提供 ReplayGain 时采样当前音量归一）

    // 音量平均：采样每首歌前几秒的平均峰值，把响度拉平到目标峰值的静态增益。
    private bool _normEnabled = true;
    private double _normTargetPeak = 0.45;  // 目标平均峰值（0..1）
    private double _normSum;
    private int _normCount;
    private bool _normDone;
    private const int NormSamples = 20;     // ~4s @ 200ms 进度节拍采样

    private readonly int[] _eqFx = new int[EqBands];
    private int _reverbFx;
    private int _echoFx;
    private int _chorusFx;
    private int _compressorFx;

    /// <summary>URL 流失败降级为「下载到临时文件再播」时产生的临时文件（Stop/Free 时清理）。</summary>
    private readonly ConcurrentBag<string> _tempFiles = new();

    public bool IsInitialized { get; private set; }

    private readonly object _initLock = new();

    public bool Initialize()
    {
        if (IsInitialized)
            return true;

        lock (_initLock)
        {
            if (IsInitialized)
                return true;

            // BASS_Init 失败时可能是“已初始化”(BASS_ERROR_ALREADY)——设备已可用，视为成功。
            // 常见于并发建流（主播放 + 预加载下一首同时进入这里）导致两线程都调 BASS_Init，
            // 后到者拿到 ALREADY。若按失败处理，CreateStream 会返回 0 → “点播放没反应”。
            if (!BassNative.BASS_Init(-1, 44100, BASSInit.Default, IntPtr.Zero, IntPtr.Zero))
            {
                var err = BassNative.BASS_ErrorGetCode();
                if (err != BASSError.Already)
                {
                    PlaybackLog($"BASS_Init failed err={err}");
                    return false;
                }
            }

            // 慢网优化（★ 配置项数值必须对，见 BassNative.BASSConfig 的说明——历史上 14/25/26
            // 全是非法索引，BASS_SetConfig 静默失败，等于什么都没设）：
            //   · NetBuffer 5000ms：5s 网络缓冲，抗 underrun 卡顿；
            //   · NetPrebuf 10%：起播只预缓冲约 0.5s。**这是"点歌半天不响"的主因** ——
            //     默认 80% 配合 3s 缓冲 = 起播前先憋 ≈2.4s 才出声；
            //   · NetTimeout 15s：连接/响应超时。默认只有 5s，服务端转码慢一点就直接
            //     BASS_ERROR_TIMEOUT(40)，于是掉进 DownloadToTemp 下载整个文件（更慢）；
            //   · NetReadTimeout 30s：读超时。
            BassNative.BASS_SetConfig(BASSConfig.NetBuffer, 5000);
            BassNative.BASS_SetConfig(BASSConfig.NetPrebuf, 10);
            BassNative.BASS_SetConfig(BASSConfig.NetReadTimeout, 30000);

            // 以上任一项失败都会让"点歌起播慢"回归，故显式校验一项并记日志（不阻断启动）。
            if (!BassNative.BASS_SetConfig(BASSConfig.NetTimeout, 15000))
                PlaybackLog($"BASS_SetConfig(NetTimeout) 失败 err={BassNative.BASS_ErrorGetCode()}");

            LoadPlugins();

            _mixer = BassMixNative.BASS_Mixer_StreamCreate(44100, 2, (uint)(BASSFlag.SampleFloat | (BASSFlag)BASSMixFlag.MixerEnd));
            if (_mixer == 0)
            {
                PlaybackLog($"BASS mixer create failed err={BassNative.BASS_ErrorGetCode()}");
                BassNative.BASS_Free();
                return false;
            }

            IsInitialized = true;
            return true;
        }
    }

    /// <summary>加载解码插件（FLAC/Opus/APE/WavPack/DSD/MIDI/AAC），遇对应格式时由 BASS 内核自动调用解码。</summary>
    private static void LoadPlugins()
    {
        // BASS_PluginLoad 按文件名显式加载，需按平台使用对应后缀（.dll / .so / .dylib）；
        // 传绝对路径确保 macOS（默认只搜 bundle Frameworks）与 Linux 都能找到与可执行文件同目录的插件。
        // Linux/macOS 的 BASS 插件文件名带 lib 前缀（libbassflac.so），Windows 不带（bassflac.dll），
        // 故先从 {base}\lib\{name}.{ext} 找，找不到再试 lib{name}.{ext}。
        string[] pluginNames = { "bassflac", "bassopus", "bassape", "basswv", "bassdsd", "bassmidi", "bass_aac" };
        var baseDir = AppContext.BaseDirectory;
        foreach (var name in pluginNames)
        {
            var p = Path.Combine(baseDir, "lib", name + LibraryExtension);
            if (!File.Exists(p))
                p = Path.Combine(baseDir, "lib", "lib" + name + LibraryExtension);
            // 插件缺失时静默跳过，不影响其他格式
            BassNative.BASS_PluginLoad(p, 0);
        }
    }

    /// <summary>当前平台原生库文件后缀（Windows .dll / Linux .so / macOS .dylib）。</summary>
    private static string LibraryExtension =>
        OperatingSystem.IsWindows() ? ".dll" : OperatingSystem.IsMacOS() ? ".dylib" : ".so";

    /// <summary>创建解码流（含 tempo 包装），返回 channel 句柄（0 表示失败）。</summary>
    public int CreateStream(string url)
    {
        // 单独计时 Initialize()：它只在本次运行的**第一首歌**跑（BASS_Init 开音频设备 + 载 7 个解码插件），
        // 慢的话会被误算进"建流耗时"。拆开才能判断慢在设备初始化还是等服务端出首字节。
        var swInit = System.Diagnostics.Stopwatch.StartNew();
        if (!Initialize())
        {
            PlaybackLog("CreateStream: Initialize() false");
            return 0;
        }
        var initMs = swInit.ElapsedMilliseconds;

        var sw = System.Diagnostics.Stopwatch.StartNew();
        var source = BassNative.BASS_StreamCreateURL(url, 0, BASSFlag.StreamDecode | BASSFlag.SampleFloat, IntPtr.Zero, IntPtr.Zero);
        if (source == 0)
        {
            var err = BassNative.BASS_ErrorGetCode();
            var urlMs = sw.ElapsedMilliseconds;
            // err 常见两类（名字见 BassNative.BASSError）：
            //   Timeout(40)：连接/起播超时。默认 NET_TIMEOUT 只有 5s —— 已在 Initialize 里改成 15s；
            //   Unstreamable(47)：非 faststart 的 M4A/AAC 等，BASS 网络流读不到文件末尾的 moov 头，
            //     只能降级为「下载到临时文件再播」（本地文件能读到完整结构）。
            // ★ 降级要先把整个文件拉完才出声，是"点歌不能马上播放"另一大来源，故单独记日志 + 计时。
            PlaybackLog($"CreateStream: URL 流失败 err={err}，建流耗时 {urlMs}ms，开始降级下载 url={RedactUrl(url)}");
            var local = DownloadToTemp(url);
            if (local is not null)
            {
                source = BassNative.BASS_StreamCreateFile(false, local, 0, 0, BASSFlag.StreamDecode | BASSFlag.SampleFloat);
                if (source != 0)
                {
                    _tempFiles.Add(local);
                    PlaybackLog($"CreateStream: 降级下载成功 err={err}，下载+建流共 {sw.ElapsedMilliseconds}ms（建流 {urlMs}ms）");
                }
                else
                {
                    try { File.Delete(local); } catch { }
                    AppLog.LastCreateStreamError = $"服务端返回的音频无法解码（err={BassNative.BASS_ErrorGetCode()}）";
                    PlaybackLog($"CreateStream: 临时文件建流也失败 err={BassNative.BASS_ErrorGetCode()} url={RedactUrl(url)}");
                    return 0;
                }
            }
            else
            {
                AppLog.LastCreateStreamError = DescribeError(err);
                PlaybackLog($"CreateStream: BASS_StreamCreateURL failed err={err} url={RedactUrl(url)}");
                return 0;
            }
        }
        else
        {
            AppLog.LastCreateStreamError = "";
            // 成功也记一条：这是「点歌到出声」里最不可控的一段（等服务器出首字节），
            // 必须能看到它到底花了多久，否则无法区分"服务端慢"和"客户端慢"。
            // 注：服务端确认按曲目而异 —— 实测同一台服务器同一端点，
            //     有的歌 110ms 就出首字节，有的 7~8 秒（缺文件则 404，挂载离线则 >30s 悬挂）。
            PlaybackLog($"CreateStream: 建流成功 总 {sw.ElapsedMilliseconds}ms（Initialize {initMs}ms，等服务端首字节 {sw.ElapsedMilliseconds - initMs}ms）url={RedactUrl(url)}");
        }

        // tempo 包装（tempo=1.0 默认不改变，支持速度/音调调整）
        // FreSource：释放 tempo 流时自动释放 source 流，避免 URL 流泄漏（内存 + 网络线程）
        var tempo = BassFxNative.BASS_FX_TempoCreate(source, (uint)(BASSFlag.StreamDecode | BASSFlag.SampleFloat) | BassFxNative.FreSource);
        if (tempo == 0)
            return source; // tempo 创建失败时退化为原始流

        return tempo;
    }

    /// <summary>把 URL（含认证参数）整体下载到临时文件，返回路径；失败返回 null。仅用于「非 faststart 流式解不了」的兜底。</summary>
    private static string? DownloadToTemp(string url)
    {
        try
        {
            if (string.IsNullOrEmpty(url))
                return null;

            var path = Path.Combine(Path.GetTempPath(), "subsonic_" + Guid.NewGuid().ToString("N") + ".aud");
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(120) };
            var sw = System.Diagnostics.Stopwatch.StartNew();
            using var resp = http.GetAsync(url).GetAwaiter().GetResult();
            if (!resp.IsSuccessStatusCode)
            {
                // ★ 以前这里是静默 return null：降级失败时日志只说一句"建流失败"，
                //   看不出到底是服务端给了 4xx/5xx 还是网络断了。排"点了歌不播"必须知道这个状态码。
                PlaybackLog($"DownloadToTemp: HTTP {(int)resp.StatusCode} {resp.StatusCode}（{sw.ElapsedMilliseconds}ms）url={RedactUrl(url)}");
                return null;
            }

            using var fs = File.Create(path);
            resp.Content.CopyToAsync(fs).GetAwaiter().GetResult();
            PlaybackLog($"DownloadToTemp: 下载完成 {sw.ElapsedMilliseconds}ms url={RedactUrl(url)}");
            return path;
        }
        catch (Exception ex)
        {
            PlaybackLog($"DownloadToTemp: 异常 {ex.GetType().Name}: {ex.Message} url={RedactUrl(url)}");
            return null;
        }
    }

    /// <summary>清理降级下载产生的临时文件（Stop / Free 时调用）。</summary>
    private void CleanupTempFiles()
    {
        foreach (var f in _tempFiles)
        {
            try { File.Delete(f); } catch { }
        }
        _tempFiles.Clear();
    }

    /// <summary>播放 channel（加入 mixer 并播放）。</summary>
    public bool PlayChannel(int channel)
    {
        if (_mixer == 0 || channel == 0)
            return false;

        RemoveCurrent();
        _currentChannel = channel;
        BassMixNative.BASS_Mixer_StreamAddChannel(_mixer, channel, (uint)BASSMixFlag.MixerNoRampIn);
        BeginNormalization();
        ApplyChannelVolume();
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
        BeginNormalization();
        ApplyChannelVolume();

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
        BeginNormalization();
        ApplyChannelVolume();
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
        CleanupTempFiles();
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
            // 通道未就绪时无法定位（建流是异步的），调用方需在 PlayChannel 之后补做 —— 见 PlaybackService.PlayCurrent。
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
            ApplyChannelVolume();
        }
    }

    /// <summary>用户音量 × ReplayGain 倍率 × 客户端音量平均倍率（夹到 0..1 防削波）。</summary>
    private float EffectiveVolume() => Math.Clamp(_volume * _streamGain * _normGain, 0f, 1f);

    /// <summary>开启/关闭音量平均（关闭时归一倍率复位为 1）。</summary>
    public void SetVolumeNormalization(bool enabled)
    {
        _normEnabled = enabled;
        if (!enabled)
        {
            _normGain = 1f;
            ApplyChannelVolume();
        }
    }

    public bool VolumeNormalizationEnabled => _normEnabled;

    /// <summary>开始新一首歌的音量平均采样：清空累计、暂用 1 倍，等采样后定增益。</summary>
    public void BeginNormalization()
    {
        _normSum = 0;
        _normCount = 0;
        _normDone = false;
        if (_normEnabled)
        {
            _normGain = 1f;
            ApplyChannelVolume();
        }
    }

    /// <summary>采样当前曲目电平（由进度节拍驱动），采样够后设置归一增益并固定。</summary>
    public void SampleNormalization()
    {
        if (!_normEnabled || _normDone || _mixer == 0 || _currentChannel == 0)
            return;
        if (BassNative.BASS_ChannelIsActive(_mixer) != BASSActive.Playing)
            return;

        var level = BassNative.BASS_ChannelGetLevel(_mixer); // 取 mixer 输出峰值（更可靠地反映实际响度）
        if (level == -1)
            return; // 读取失败

        // ★ 关键：BASS_ChannelGetLevel(_mixer) 量的是**混音输出**，也就是已经乘过用户音量的电平。
        // 直接按它算增益，等于把用户刚拖下去的音量又补回来：
        //   gain = target / (音量 × 源电平)  ⇒  实际输出 = 音量 × gain = target（与音量无关）
        // 采样窗口（前 ~4 秒）里输出被钉在目标峰值上，用户拖音量条就没反应 ——
        // 这正是"推拉声音没马上改变"的原因。所以先除以当前有效音量，把电平还原成**源电平**，
        // 归一化只负责"歌与歌之间的响度差"，不再跟用户音量打架。
        var effective = EffectiveVolume();
        if (effective <= 0.02)
            return; // 音量几乎为 0：除以它会把噪声当信号，跳过本次采样
        var peak = (level & 0xFFFF) / 32768.0 / effective; // LOWORD = 左声道峰值 0..32768 → 0..1（已还原为源电平）
        if (peak <= 0.001)
            return; // 静音或未起播，跳过

        _normSum += peak;
        _normCount++;
        if (_normCount >= NormSamples)
        {
            var avg = _normSum / _normCount;
            if (avg > 0.001)
            {
                var linear = (float)Math.Clamp(_normTargetPeak / avg, 0.5, 2.0); // 限定 ±~6dB，避免过度
                _normGain = linear;
                ApplyChannelVolume();
                PlaybackLog($"Normalize: srcPeak={avg:F3} vol={effective:F2} gain={linear:F2}x");
            }
            _normDone = true;
        }
    }

    private void ApplyChannelVolume()
    {
        if (_currentChannel != 0)
            BassNative.BASS_ChannelSetAttribute(_currentChannel, BASSAttribute.Volume, EffectiveVolume());
    }

    /// <summary>设置当前曲目的 ReplayGain 增益（dB）→ 线性倍率；NaN（未提供）表示不调整。</summary>
    public void SetStreamGain(double gainDb)
    {
        if (double.IsNaN(gainDb))
        {
            _streamGain = 1f;
            return;
        }
        // dB → 线性；限定在 0.25（-12dB）~ 3.0（+9.5dB）内，避免过度放大/压低
        var linear = Math.Pow(10, gainDb / 20.0);
        _streamGain = (float)Math.Clamp(linear, 0.25, 3.0);
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

    /// <summary>播放/建流诊断日志（写到 playback.log，便于排查"按播放键无声音"）。</summary>
    private static void PlaybackLog(string msg) => AppLog.Playback(msg);

    /// <summary>把 BASS 错误码翻成人话，供 UI 提示用户（"歌点了没反应"至少要知道为什么）。</summary>
    private static string DescribeError(BASSError err) => err switch
    {
        BASSError.FileOpen => "服务端没有这个音频文件（HTTP 404 或文件缺失）",
        BASSError.Timeout => "服务端响应超时（音频迟迟没开始传输）",
        BASSError.Unstreamable => "这个文件无法流式播放，且整文件下载也失败",
        BASSError.NoNet => "网络不可达",
        BASSError.Codec => "缺少对应格式的解码器",
        BASSError.FileForm => "不支持的音频格式",
        _ => $"播放失败（BASS 错误码 {(int)err}）",
    };

    /// <summary>脱敏流地址：隐藏账户/密码等敏感参数，只留 host 与资源 id。</summary>
    private static string RedactUrl(string url)
    {
        try
        {
            if (string.IsNullOrEmpty(url)) return url;
            var idx = url.IndexOf('?');
            return idx > 0 ? url.Substring(0, idx) : url;
        }
        catch { return url; }
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
        CleanupTempFiles();
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
