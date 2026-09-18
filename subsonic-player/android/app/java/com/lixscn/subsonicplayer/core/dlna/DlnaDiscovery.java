package com.lixscn.subsonicplayer.core.dlna;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import com.lixscn.subsonicplayer.core.PlayLog;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSDP 发现局域网里的 DLNA MediaRenderer（纯 Android Framework，无第三方依赖）。
 *
 * <p>流程：向 {@code 239.255.255.250:1900} 多播 M-SEARCH（同时发 MediaRenderer 与 ssdp:all 两种 ST，
 * 有些设备只答其中一种）→ 收集应答里的 {@code LOCATION} → 逐个拉设备描述 XML →
 * 解析出 friendlyName / UDN / AVTransport / RenderingControl 控制地址 → 只保留能推的（有 AVTransport）。
 *
 * <p>整个扫描在后台线程做，结果回主线程；返回的 {@link Scan} 可以 {@link Scan#cancel()}。
 */
public final class DlnaDiscovery {

    private static final String TAG = "DlnaDiscovery";
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String ST_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String ST_ALL = "ssdp:all";
    private static final int HTTP_OK = 200;
    private static final int MAX_DESC_BYTES = 512 * 1024;

    private DlnaDiscovery() {
    }

    public interface Callback {
        /** 主线程回调。error 非空表示扫描本身失败（无网络等），此时 devices 通常为空 */
        void onDone(List<DlnaDevice> devices, String error);
    }

    /** 一次扫描的句柄 */
    public static final class Scan {
        private volatile boolean cancelled;
        private volatile DatagramSocket socket;

        public void cancel() {
            cancelled = true;
            DatagramSocket s = socket;
            if (s != null) {
                try {
                    s.close();          // 关掉能立刻打断 receive()
                } catch (Throwable ignored) {
                }
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** 开始扫描（立即返回，结果在主线程回调） */
    public static Scan scan(final Context ctx, final int waitMs, final Callback cb) {
        final Scan handle = new Scan();
        final Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                List<DlnaDevice> found = new ArrayList<DlnaDevice>();
                String error = null;
                try {
                    found = doScan(handle, waitMs);
                } catch (Throwable e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    PlayLog.w(TAG, "SSDP 扫描失败", e);
                }
                final List<DlnaDevice> result = found;
                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!handle.isCancelled()) cb.onDone(result, err);
                    }
                });
            }
        }, "dlna-scan");
        t.setDaemon(true);
        t.start();
        return handle;
    }

    // ------------------------------------------------------------------
    // 实现
    // ------------------------------------------------------------------

    private static List<DlnaDevice> doScan(Scan handle, int waitMs) throws Exception {
        DatagramSocket sock = new DatagramSocket();
        handle.socket = sock;
        try {
            sock.setSoTimeout(300);
            byte[] m1 = msearch(ST_RENDERER).getBytes("UTF-8");
            byte[] m2 = msearch(ST_ALL).getBytes("UTF-8");
            InetAddress group = InetAddress.getByName(SSDP_ADDR);

            Map<String, String> locations = new LinkedHashMap<String, String>();
            long deadline = System.currentTimeMillis() + Math.max(1500, waitMs);
            long nextSend = 0;
            int sent = 0;
            byte[] buf = new byte[4096];
            while (!handle.isCancelled() && System.currentTimeMillis() < deadline) {
                // M-SEARCH 走 UDP 多播：丢包很常见，而且有些设备只回应其中一种 ST
                // → 整个扫描窗口里每隔 1.2 秒重发一次（实测重发能多发现设备）
                long now = System.currentTimeMillis();
                if (now >= nextSend) {
                    sock.send(new DatagramPacket(m1, m1.length, group, SSDP_PORT));
                    sock.send(new DatagramPacket(m2, m2.length, group, SSDP_PORT));
                    sent++;
                    nextSend = now + 1200;
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    String text = new String(p.getData(), 0, p.getLength(), "UTF-8");
                    String loc = header(text, "LOCATION");
                    if (loc != null && loc.length() > 0 && !locations.containsKey(loc)) {
                        locations.put(loc, text);
                        PlayLog.w(TAG, "SSDP 应答 ← " + p.getAddress().getHostAddress() + "  " + loc);
                    }
                } catch (java.net.SocketTimeoutException te) {
                    // 正常：继续等剩下的时间
                }
            }
            PlayLog.w(TAG, "SSDP 扫描结束：M-SEARCH 发出 " + sent + " 轮，收到 "
                    + locations.size() + " 个应答");
            if (locations.isEmpty()) {
                PlayLog.w(TAG, "SSDP 没有任何应答（该网段可能没有 DLNA 渲染器，或不在同一局域网）");
            }

            List<DlnaDevice> out = new ArrayList<DlnaDevice>();
            for (Map.Entry<String, String> e : locations.entrySet()) {
                if (handle.isCancelled()) break;
                try {
                    DlnaDevice d = describe(e.getKey(), e.getValue());
                    if (d != null && d.isUsable() && !contains(out, d)) {
                        out.add(d);
                        PlayLog.w(TAG, "可推送设备：" + d.displayName() + "  AVTransport=" + d.avTransportControl);
                    } else if (d != null) {
                        PlayLog.w(TAG, "跳过（没有 AVTransport）：" + d.displayName());
                    }
                } catch (Throwable t) {
                    PlayLog.w(TAG, "解析设备描述失败 " + e.getKey() + " : " + t);
                }
            }
            return out;
        } finally {
            try {
                sock.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean contains(List<DlnaDevice> list, DlnaDevice d) {
        for (DlnaDevice x : list) {
            if (x.sameAs(d)) return true;
        }
        return false;
    }

    private static String msearch(String st) {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + st + "\r\n"
                + "\r\n";
    }

    /** 从 SSDP 应答里取某个头（大小写不敏感） */
    private static String header(String text, String key) {
        String[] lines = text.split("\r?\n");
        for (String line : lines) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            if (line.substring(0, c).trim().equalsIgnoreCase(key)) {
                return line.substring(c + 1).trim();
            }
        }
        return null;
    }

    /** 拉设备描述并解析出控制地址；失败返回 null */
    private static DlnaDevice describe(String location, String ssdpResponse) {
        String xml = httpGet(location, 4000);
        if (xml == null) return null;

        DlnaDevice d = new DlnaDevice();
        d.location = location;
        d.udn = firstTag(xml, "UDN");
        d.name = firstTag(xml, "friendlyName");
        d.model = firstTag(xml, "modelName");
        try {
            d.host = new URL(location).getHost();
        } catch (Throwable ignored) {
        }

        try {
            parseServices(xml, location, d);
        } catch (Throwable t) {
            PlayLog.w(TAG, "serviceList 解析失败 " + location + " : " + t);
        }

        if (d.udn == null || d.udn.length() == 0) {
            String usn = header(ssdpResponse, "USN");
            if (usn != null) {
                int i = usn.indexOf("::");
                d.udn = i > 0 ? usn.substring(0, i) : usn;
            }
        }
        return d;
    }

    /**
     * 用 XmlPullParser 单循环走文档：抓每个 {@code <service>} 的 serviceType / controlURL。
     *
     * <p>注意必须在**同一个循环**里累计 TEXT 事件（不能在被调用的辅助方法里 p.next()，
     * 那会把事件从外层循环里偷走）。
     */
    private static void parseServices(String xml, String location, DlnaDevice d) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));

        String svcType = null;
        String ctrl = null;
        boolean inService = false;
        String curTag = null;
        StringBuilder text = new StringBuilder();

        int guard = 0;
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT && guard++ < 200000) {
            if (event == XmlPullParser.START_TAG) {
                curTag = p.getName();
                text.setLength(0);
                if ("service".equalsIgnoreCase(curTag)) {
                    inService = true;
                    svcType = null;
                    ctrl = null;
                }
            } else if (event == XmlPullParser.TEXT) {
                if (inService && curTag != null) text.append(p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                String tag = p.getName();
                String val = text.toString().trim();
                if (inService && tag != null) {
                    if ("serviceType".equalsIgnoreCase(tag)) {
                        svcType = val;
                    } else if ("controlURL".equalsIgnoreCase(tag)) {
                        ctrl = val;
                    } else if ("service".equalsIgnoreCase(tag)) {
                        inService = false;
                        if (svcType != null && ctrl != null && ctrl.length() > 0) {
                            String abs = absolutize(location, ctrl);
                            if (svcType.contains("AVTransport") && d.avTransportControl.length() == 0) {
                                d.avTransportControl = abs;
                            } else if (svcType.contains("RenderingControl") && d.renderingControl.length() == 0) {
                                d.renderingControl = abs;
                            }
                        }
                    }
                }
                curTag = null;
                text.setLength(0);
            }
            event = p.next();
        }
    }

    private static String firstTag(String xml, String tag) {
        try {
            int s = xml.indexOf("<" + tag + ">");
            if (s < 0) return "";
            s += tag.length() + 2;
            int e = xml.indexOf("</" + tag + ">", s);
            if (e < 0) return "";
            return xml.substring(s, e).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 相对 URL → 绝对 URL */
    static String absolutize(String base, String ref) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Throwable t) {
            return ref;
        }
    }

    /** 简单 GET（UTF-8，带超时），失败返回 null */
    private static String httpGet(String url, int timeoutMs) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "SubsonicPlayer-Android/1.0 DLNA");
            if (c.getResponseCode() != HTTP_OK) return null;
            in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > MAX_DESC_BYTES) break;
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            if (c != null) c.disconnect();
        }
    }
}
