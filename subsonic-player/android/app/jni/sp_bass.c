/*
 * sp_bass.c —— BASS 的 JNI 包装层（Android）
 *
 * 为什么需要它：BASS 是 C 库（libbass.so），Android 端没有官方 Java 绑定，
 * 所以这里只暴露 App 真正需要的十来个函数，不做任何业务逻辑。
 *
 * 编译（等 NDK 就位后）：
 *   tools\build-native.ps1
 *
 * 设计要点：
 *  1) DSD 走 BASS_DSD_StreamCreateURL 直接流播（bassdsd 新版提供 URL 接口），
 *     失败时可退回「下载到本地再播」（BASS_DSD_StreamCreateFile）。
 *  2) 统一用「秒」为单位跨 JNI 传参，避免 Java 侧再关心 BASS_POS_BYTE 换算。
 *  3) 所有函数都返回状态码/0，不抛异常；错误码通过 sp_bass_error() 取。
 */
#include <jni.h>
#include <string.h>
#include "bass.h"

/* 加了这些 add-on 才有对应格式（缺失时对应函数指针为 0，运行时降级） */
#ifdef SP_HAVE_DSD
#include "bassdsd.h"
#endif
#ifdef SP_HAVE_FLAC
#include "bassflac.h"
#endif
#ifdef SP_HAVE_APE
#include "bassape.h"
#endif
#ifdef SP_HAVE_WV
#include "basswv.h"
#endif
#ifdef SP_HAVE_OPUS
#include "bassopus.h"
#endif

#define JNI_FN(name) Java_com_lixscn_subsonicplayer_player_bass_BassNative_##name

/* 流标志：BLOCK 让网络流边下边播；STATUS 才会回调进度；FLOAT 提高精度 */
#define SP_STREAM_FLAGS (BASS_STREAM_BLOCK | BASS_STREAM_STATUS | BASS_SAMPLE_FLOAT)

/* BASS 自带网络模块**不含 HTTPS**（未加载 SSL 插件时建 https 流会返回 BASS_ERROR_SSL=10）。
   bass_ssl 是纯插件：加载即生效，不需要调用任何函数，所以这里用 BASS_PluginLoad 挂上。 */
static HPLUGIN sp_ssl_plugin = 0;

static void sp_load_ssl(void) {
    if (sp_ssl_plugin) return;
    /* Android 的 dlopen 会在应用 nativeLibraryDir 里找带 soname 的库 */
    sp_ssl_plugin = BASS_PluginLoad("libbass_ssl.so", 0);
    if (!sp_ssl_plugin) sp_ssl_plugin = BASS_PluginLoad("bass_ssl", 0);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSslLoaded)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return sp_ssl_plugin ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeInit)(JNIEnv *env, jclass clazz, jint device, jint freq) {
    (void) env; (void) clazz;
    /* -1 = 默认设备；BASS_DEVICE_LATENCY 让 BASS 自己算缓冲，移动端更稳 */
    if (!BASS_Init(device, (DWORD) freq, BASS_DEVICE_LATENCY, NULL, NULL)) {
        DWORD err = BASS_ErrorGetCode();
        /* 已经初始化过（重复 init）不算失败 */
        if (err != BASS_ERROR_ALREADY) return JNI_FALSE;
    }
    sp_load_ssl();                                     /* HTTPS 支持（流地址是 https://） */
    BASS_SetConfig(BASS_CONFIG_NET_TIMEOUT, 15000);   /* 网络流超时 */
    /* 网络缓冲与预缓冲（单位要说清楚，别像我上次把百分比当毫秒）：
       BASS_CONFIG_NET_BUFFER    = 缓冲长度，单位**毫秒**（默认 5000）
       BASS_CONFIG_NET_PREBUF    = 开播前先填到缓冲的**百分比**（0-100）
       BASS_CONFIG_NET_READTIMEOUT = 多久收不到数据判定连接已断，单位**毫秒**（默认 5000）
       高码率 WAV 一首可达 120MB（约 530 KB/s），缓冲给足才不卡。 */
    /* 缓冲策略（高码率 WAV 一首可达 120MB / 约 530KB/s，公网回源抖动大）：
       - BUFFER 放到 60 秒：起播后 BASS 会持续保持这么长的"余量"，抗抖主要靠它
         （代价仅内存：最坏情况约 30MB，普通码率曲目可忽略）
       - PREBUF 保持小值 + PREBUF_WAIT=0：起播要快，不为了填满缓冲让人干等 */
    BASS_SetConfig(BASS_CONFIG_NET_BUFFER, 60000);        /* 60 秒网络缓冲 */
    BASS_SetConfig(BASS_CONFIG_NET_PREBUF, 10);           /* 起播目标 10%，且不阻塞 */
    BASS_SetConfig(BASS_CONFIG_NET_PREBUF_WAIT, 0);
    BASS_SetConfig(BASS_CONFIG_NET_READTIMEOUT, 30000);   /* 30 秒收不到数据才判死 */
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI_FN(nativeFree)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    BASS_Free();
}

JNIEXPORT void JNICALL JNI_FN(nativeSetNetTimeout)(JNIEnv *env, jclass clazz, jint ms) {
    (void) env; (void) clazz;
    BASS_SetConfig(BASS_CONFIG_NET_TIMEOUT, (DWORD) ms);
}

/* 从 URL 建流（http/https 均可，鉴权参数直接带在 query 里） */
/* 断流自动续传：网络抖动导致 BASS 内部断流时，让它自己重连并接着下，
   而不是等 Java 侧发现 STOPPED 再整条重建（那样起播/定位都会重来一遍）。 */
static void sp_enable_resume(HSTREAM h) {
    if (!h) return;
#ifdef BASS_ATTRIB_NET_RESUME
    BASS_ChannelSetAttribute((HCHANNEL) h, BASS_ATTRIB_NET_RESUME, 1);
#endif
}

JNIEXPORT jlong JNICALL JNI_FN(nativeStreamCreateUrl)(JNIEnv *env, jclass clazz, jstring url) {
    (void) clazz;
    if (url == NULL) return 0;
    const char *u = (*env)->GetStringUTFChars(env, url, NULL);
    if (u == NULL) return 0;
    HSTREAM h = BASS_StreamCreateURL(u, 0, SP_STREAM_FLAGS, NULL, NULL);
    (*env)->ReleaseStringUTFChars(env, url, u);
    sp_enable_resume(h);
    return (jlong) h;
}

/* 从本地文件建流（含 WAV 24/32bit、FLAC、APE、WavPack、Opus 等） */
JNIEXPORT jlong JNICALL JNI_FN(nativeStreamCreateFile)(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    if (path == NULL) return 0;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p == NULL) return 0;
    HSTREAM h = BASS_StreamCreateFile(FALSE, p, 0, 0, SP_STREAM_FLAGS);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) h;
}

/* DSD（.dsf/.dff）只能从本地文件建流：bassdsd 没有 URL 版接口 */
JNIEXPORT jlong JNICALL JNI_FN(nativeDsdCreateFile)(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
#ifdef SP_HAVE_DSD
    if (path == NULL) return 0;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p == NULL) return 0;
    HSTREAM h = BASS_DSD_StreamCreateFile(FALSE, p, 0, 0, SP_STREAM_FLAGS, 0);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) h;
#else
    (void) env; (void) path;
    return 0;   /* 编译时没带 bassdsd */
#endif
}

/* DSD 的 URL 版：bassdsd 确实提供 BASS_DSD_StreamCreateURL（旧版没有，新版有），
   所以 .dsf 也能边下边播，不需要先整文件下载。 */
JNIEXPORT jlong JNICALL JNI_FN(nativeDsdStreamCreateUrl)(JNIEnv *env, jclass clazz, jstring url) {
    (void) clazz;
#ifdef SP_HAVE_DSD
    if (url == NULL) return 0;
    const char *u = (*env)->GetStringUTFChars(env, url, NULL);
    if (u == NULL) return 0;
    HSTREAM h = BASS_DSD_StreamCreateURL(u, 0, SP_STREAM_FLAGS, NULL, NULL, 0);
    (*env)->ReleaseStringUTFChars(env, url, u);
    sp_enable_resume(h);
    return (jlong) h;
#else
    (void) env; (void) url;
    return 0;
#endif
}

JNIEXPORT jboolean JNICALL JNI_FN(nativePlay)(JNIEnv *env, jclass clazz, jlong handle, jboolean restart) {
    (void) env; (void) clazz;
    if (handle == 0) return JNI_FALSE;
    return BASS_ChannelPlay((HCHANNEL) handle, restart ? TRUE : FALSE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativePause)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle) BASS_ChannelPause((HCHANNEL) handle);
}

JNIEXPORT void JNICALL JNI_FN(nativeStop)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle) BASS_ChannelStop((HCHANNEL) handle);
}

JNIEXPORT void JNICALL JNI_FN(nativeFreeStream)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle) BASS_StreamFree((HSTREAM) handle);
}

/* 0=已停止 1=播放中 2=暂停 3=缓冲中(网络流 stalled) */
JNIEXPORT jint JNICALL JNI_FN(nativeState)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return 0;
    return (jint) BASS_ChannelIsActive((HCHANNEL) handle);
}

JNIEXPORT jdouble JNICALL JNI_FN(nativePositionSec)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return 0;
    QWORD pos = BASS_ChannelGetPosition((HCHANNEL) handle, BASS_POS_BYTE);
    if (pos == (QWORD) -1) return 0;
    return BASS_ChannelBytes2Seconds((HCHANNEL) handle, pos);
}

JNIEXPORT jdouble JNICALL JNI_FN(nativeDurationSec)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return 0;
    QWORD len = BASS_ChannelGetLength((HCHANNEL) handle, BASS_POS_BYTE);
    if (len == (QWORD) -1) return 0;   /* 网络流未知长度 */
    return BASS_ChannelBytes2Seconds((HCHANNEL) handle, len);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSeekSec)(JNIEnv *env, jclass clazz, jlong handle, jdouble sec) {
    (void) env; (void) clazz;
    if (handle == 0) return JNI_FALSE;
    QWORD pos = BASS_ChannelSeconds2Bytes((HCHANNEL) handle, sec);
    /* 某些网络流不支持任意定位，失败时如实返回 false，由 UI 提示 */
    return BASS_ChannelSetPosition((HCHANNEL) handle, pos, BASS_POS_BYTE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetVolume)(JNIEnv *env, jclass clazz, jlong handle, jfloat v) {
    (void) env; (void) clazz;
    if (handle == 0) return;
    if (v < 0) v = 0;
    if (v > 1) v = 1;
    BASS_ChannelSetAttribute((HCHANNEL) handle, BASS_ATTRIB_VOL, v);
}

/* BASS 错误码：Java 侧映射成可读文案（未知格式 / 网络失败 / 解码失败） */
JNIEXPORT jint JNICALL JNI_FN(nativeErrorCode)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return (jint) BASS_ErrorGetCode();
}

/* 网络流缓冲百分比（0-100，-1 表示不可用），用于显示「缓冲中…」 */
JNIEXPORT jint JNICALL JNI_FN(nativeBufferPercent)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return -1;
    /* BASS 里这个函数是 2 参数、直接返回 QWORD（不是出参形式） */
    QWORD pos = BASS_StreamGetFilePosition((HSTREAM) handle, BASS_FILEPOS_BUFFER);
    if (pos == (QWORD) -1) return -1;
    return (jint) pos;
}

/* 当前是否整体可播（BASS_ACTIVE_STALLED 时说明网速跟不上） */
JNIEXPORT jboolean JNICALL JNI_FN(nativeIsStalled)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return JNI_FALSE;
    return BASS_ChannelIsActive((HCHANNEL) handle) == BASS_ACTIVE_STALLED ? JNI_TRUE : JNI_FALSE;
}
