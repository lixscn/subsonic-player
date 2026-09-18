package com.lixscn.subsonicplayer.core.dlna;

import com.lixscn.subsonicplayer.core.PlayLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 一台 DLNA 渲染器的 SOAP 控制端（AVTransport / RenderingControl）。
 *
 * <p>纯 {@code HttpURLConnection} + 字符串解析：DLNA 的响应很小、结构固定，
 * 没必要为它引一个 XML 库（工程铁律也是零第三方依赖）。
 *
 * <p>所有方法都是**同步阻塞**的，必须在后台线程调用（本工程的 {@code Library.run} / 自建线程都行）。
 */
public final class DlnaRenderer {

    private static final String TAG = "DlnaRenderer";
    private static final String SVC_AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String SVC_RCS = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final int HTTP_OK = 200;

    private final DlnaDevice device;
    private final int timeoutMs;

    public DlnaRenderer(DlnaDevice device) {
        this(device, 4000);
    }

    public DlnaRenderer(DlnaDevice device, int timeoutMs) {
        this.device = device;
        this.timeoutMs = timeoutMs;
    }

    public DlnaDevice device() {
        return device;
    }

    // ------------------------------------------------------------------
    // AVTransport
    // ------------------------------------------------------------------

    /** 设置当前播放地址（音箱会自己去拉这个 URL）；meta 是 DIDL-Lite，很多设备缺了它就不认 */
    public boolean setAvTransportUri(String uri, String didlMeta) {
        String inner = "<InstanceID>0</InstanceID>"
                + "<CurrentURI>" + esc(uri) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + esc(didlMeta) + "</CurrentURIMetaData>";
        return soap(device.avTransportControl, SVC_AVT, "SetAVTransportURI", inner) != null;
    }

    public boolean play() {
        return soap(device.avTransportControl, SVC_AVT, "Play",
                "<InstanceID>0</InstanceID><Speed>1</Speed>") != null;
    }

    public boolean pause() {
        return soap(device.avTransportControl, SVC_AVT, "Pause",
                "<InstanceID>0</InstanceID>") != null;
    }

    public boolean stop() {
        return soap(device.avTransportControl, SVC_AVT, "Stop",
                "<InstanceID>0</InstanceID>") != null;
    }

    public boolean seek(long ms) {
        return soap(device.avTransportControl, SVC_AVT, "Seek",
                "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>"
                        + hms(ms) + "</Target>") != null;
    }

    /** 传输状态：PLAYING / PAUSED_PLAYBACK / STOPPED / TRANSITIONING…；失败返回 null */
    public String transportState() {
        String r = soap(device.avTransportControl, SVC_AVT, "GetTransportInfo",
                "<InstanceID>0</InstanceID>");
        return r == null ? null : tag(r, "CurrentTransportState");
    }

    /** [positionMs, durationMs]；失败返回 null */
    public long[] positionAndDuration() {
        String r = soap(device.avTransportControl, SVC_AVT, "GetPositionInfo",
                "<InstanceID>0</InstanceID>");
        if (r == null) return null;
        long pos = parseHms(tag(r, "RelTime"));
        long dur = parseHms(tag(r, "TrackDuration"));
        return new long[]{pos, dur};
    }

    /** 可达性检查（顺带拿到状态） */
    public boolean reachable() {
        return transportState() != null;
    }

    // ------------------------------------------------------------------
    // RenderingControl（音量）
    // ------------------------------------------------------------------

    public int volume() {
        if (device.renderingControl == null || device.renderingControl.length() == 0) return -1;
        String r = soap(device.renderingControl, SVC_RCS, "GetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>");
        if (r == null) return -1;
        try {
            return Integer.parseInt(tag(r, "CurrentVolume"));
        } catch (Throwable t) {
            return -1;
        }
    }

    public boolean setVolume(int v) {
        if (device.renderingControl == null || device.renderingControl.length() == 0) return false;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return soap(device.renderingControl, SVC_RCS, "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>"
                        + v + "</DesiredVolume>") != null;
    }

    // ------------------------------------------------------------------
    // SOAP 底层
    // ------------------------------------------------------------------

    /** 发一个 SOAP action；成功返回响应体，失败返回 null */
    private String soap(String controlUrl, String service, String action, String inner) {
        if (controlUrl == null || controlUrl.length() == 0) return null;
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + service + "\">"
                + inner
                + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(controlUrl).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + service + "#" + action + "\"");
            c.setRequestProperty("User-Agent", "SubsonicPlayer-Android/1.0 DLNA");
            byte[] payload = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream out = c.getOutputStream();
            out.write(payload);
            out.flush();
            out.close();

            int code = c.getResponseCode();
            InputStream in = code == HTTP_OK ? c.getInputStream() : c.getErrorStream();
            String resp = in == null ? "" : readAll(in);
            if (code != HTTP_OK) {
                PlayLog.w(TAG, action + " 失败 HTTP " + code + "  " + trim(resp, 200));
                return null;
            }
            return resp;
        } catch (Throwable t) {
            PlayLog.w(TAG, action + " 异常: " + t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 256 * 1024) break;
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 取 <tag>…</tag> 的文本（DLNA 响应里没有嵌套同名标签） */
    static String tag(String xml, String tag) {
        if (xml == null) return "";
        try {
            int s = xml.indexOf("<" + tag + ">");
            if (s < 0) {
                int s2 = xml.indexOf("<" + tag + " ");
                if (s2 < 0) return "";
                s = xml.indexOf('>', s2);
                if (s < 0) return "";
                s += 1;
            } else {
                s += tag.length() + 2;
            }
            int e = xml.indexOf("</" + tag + ">", s);
            if (e < 0) return "";
            return xml.substring(s, e).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 毫秒 → DLNA 的 H:MM:SS[.mmm] */
    static String hms(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h + ":" + (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    /** DLNA 的 H:MM:SS[.mmm] → 毫秒；解析不出来返回 0 */
    static long parseHms(String text) {
        if (text == null) return 0;
        String t = text.trim();
        if (t.length() == 0 || "NOT_IMPLEMENTED".equalsIgnoreCase(t)) return 0;
        try {
            String[] parts = t.split(":");
            if (parts.length == 3) {
                double h = Double.parseDouble(parts[0]);
                double m = Double.parseDouble(parts[1]);
                double s = Double.parseDouble(parts[2]);
                return (long) ((h * 3600 + m * 60 + s) * 1000);
            }
            if (parts.length == 2) {
                double m = Double.parseDouble(parts[0]);
                double s = Double.parseDouble(parts[1]);
                return (long) ((m * 60 + s) * 1000);
            }
            return (long) (Double.parseDouble(t) * 1000);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
