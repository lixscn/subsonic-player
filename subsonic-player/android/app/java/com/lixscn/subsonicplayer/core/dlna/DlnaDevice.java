package com.lixscn.subsonicplayer.core.dlna;

/**
 * 一台 DLNA/UPnP 渲染器（音箱、功放、电视盒子…）。
 *
 * <p>只保留推送播放需要的两件事：设备描述地址，以及 AVTransport / RenderingControl 的控制地址。
 * 纯 Android Framework（{@code XmlPullParser} + {@code HttpURLConnection}），无第三方依赖。
 */
public final class DlnaDevice {

    /** 友好名（界面上给用户看的名字） */
    public String name = "";
    public String model = "";
    /** 唯一标识（响应里的 USN/UDN），用来去重与判断「是不是同一台」 */
    public String udn = "";
    /** 设备描述 XML 的地址 */
    public String location = "";
    /** 从 location 解析出来的 host（日志用） */
    public String host = "";
    /** AVTransport 的 SOAP 控制地址（绝对 URL）—— 没有它就不是可推送的渲染器 */
    public String avTransportControl = "";
    /** RenderingControl 的 SOAP 控制地址（音量），可能为空 */
    public String renderingControl = "";

    public boolean isUsable() {
        return avTransportControl != null && avTransportControl.length() > 0;
    }

    public boolean sameAs(DlnaDevice other) {
        if (other == null) return false;
        if (udn != null && udn.length() > 0 && other.udn != null && other.udn.length() > 0) {
            return udn.equals(other.udn);
        }
        return location != null && location.equals(other.location);
    }

    /** 界面显示名（没有友好名就退回型号/host） */
    public String displayName() {
        if (name != null && name.length() > 0) return name;
        if (model != null && model.length() > 0) return model;
        return host == null ? "DLNA 设备" : host;
    }

    @Override
    public String toString() {
        return displayName() + " (" + host + ")";
    }
}
