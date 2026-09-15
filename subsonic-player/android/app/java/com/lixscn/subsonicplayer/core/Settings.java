package com.lixscn.subsonicplayer.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 应用设置的持久化（SharedPreferences）。
 *
 * 服务器密码经 {@link Secret} 加密后存 "password_enc" 字段，内存中的 {@link Service#password}
 * 始终是明文，仅存在于进程内，不落盘、不打印。
 */
public class Settings {

    private static final String PREF = "sp_settings";
    private static Settings sInst;

    private final Context appCtx;
    private final SharedPreferences sp;

    /** 一个音乐服务器配置 */
    public static class Service {
        public String id = "";
        public String name = "";
        public String lanUrl = "";
        public String wanUrl = "";
        public String username = "";
        /** 明文密码（仅内存） */
        public String password = "";

        public Service copy() {
            Service s = new Service();
            s.id = id;
            s.name = name;
            s.lanUrl = lanUrl;
            s.wanUrl = wanUrl;
            s.username = username;
            s.password = password;
            return s;
        }

        public String displayName() {
            if (name != null && name.length() > 0) return name;
            String u = lanUrl != null && lanUrl.length() > 0 ? lanUrl : wanUrl;
            if (u == null) return "未命名";
            return u.replaceFirst("^https?://", "");
        }
    }

    private Settings(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.sp = appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static synchronized Settings get(Context ctx) {
        if (sInst == null) sInst = new Settings(ctx);
        return sInst;
    }

    // ---------------- 服务器配置 ----------------

    public List<Service> services() {
        List<Service> out = new ArrayList<Service>();
        String raw = sp.getString("services", "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Service s = new Service();
                s.id = o.optString("id", "");
                s.name = o.optString("name", "");
                s.lanUrl = o.optString("lanUrl", "");
                s.wanUrl = o.optString("wanUrl", "");
                s.username = o.optString("username", "");
                s.password = Secret.unprotect(appCtx, o.optString("password_enc", ""));
                out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public Service findService(String id) {
        if (id == null) return null;
        for (Service s : services()) {
            if (id.equals(s.id)) return s;
        }
        return null;
    }

    public String currentServiceId() {
        return sp.getString("currentServiceId", "");
    }

    public void setCurrentServiceId(String id) {
        sp.edit().putString("currentServiceId", id == null ? "" : id).apply();
    }

    public Service currentService() {
        Service s = findService(currentServiceId());
        if (s != null) return s;
        List<Service> all = services();
        return all.isEmpty() ? null : all.get(0);
    }

    /** 新增或更新（按 id 匹配）。id 为空时自动生成。密码自动加密。 */
    public Service saveService(Service svc) {
        if (svc.id == null || svc.id.length() == 0) {
            svc.id = "svc_" + UUID.randomUUID().toString().substring(0, 8);
        }
        List<Service> all = services();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(svc.id)) {
                all.set(i, svc);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(svc);
        persist(all);
        if (currentServiceId().length() == 0) setCurrentServiceId(svc.id);
        return svc;
    }

    public void deleteService(String id) {
        List<Service> all = services();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id.equals(id)) all.remove(i);
        }
        persist(all);
        if (id != null && id.equals(currentServiceId())) {
            setCurrentServiceId(all.isEmpty() ? "" : all.get(0).id);
        }
    }

    private void persist(List<Service> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Service s : list) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("lanUrl", s.lanUrl);
                o.put("wanUrl", s.wanUrl);
                o.put("username", s.username);
                o.put("password_enc", Secret.protect(appCtx, s.password));
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        sp.edit().putString("services", arr.toString()).apply();
    }

    // ---------------- 偏好项 ----------------

    public String themeId() {
        return sp.getString("themeId", "dark");
    }

    public void setThemeId(String id) {
        sp.edit().putString("themeId", id == null ? "dark" : id).apply();
    }

    public int accentIndex() {
        return sp.getInt("accentIndex", 0);
    }

    public void setAccentIndex(int i) {
        sp.edit().putInt("accentIndex", i).apply();
    }

    /** 播放模式：0 顺序 1 随机 2 列表循环 3 单曲循环 */
    public int playMode() {
        return sp.getInt("playMode", 0);
    }

    public void setPlayMode(int m) {
        sp.edit().putInt("playMode", m).apply();
    }

    /** 网络质量：0 原始 1 高(320) 2 中(192) 3 低(96) */
    public int networkQuality() {
        return sp.getInt("networkQuality", 0);
    }

    public void setNetworkQuality(int q) {
        sp.edit().putInt("networkQuality", q).apply();
    }

    public boolean volumeNormalization() {
        return sp.getBoolean("volumeNormalization", false);
    }

    public void setVolumeNormalization(boolean b) {
        sp.edit().putBoolean("volumeNormalization", b).apply();
    }

    public int volume() {
        return sp.getInt("volume", 100);
    }

    public void setVolume(int v) {
        sp.edit().putInt("volume", v).apply();
    }

    /** 睡眠定时器剩余分钟（0 = 关闭） */
    public int sleepTimerMinutes() {
        return sp.getInt("sleepTimer", 0);
    }

    public void setSleepTimerMinutes(int m) {
        sp.edit().putInt("sleepTimer", m).apply();
    }

    public String librarySort() {
        return sp.getString("librarySort", "alphabeticalByArtist");
    }

    public void setLibrarySort(String s) {
        sp.edit().putString("librarySort", s).apply();
    }

    public boolean firstRunDone() {
        return sp.getBoolean("firstRunDone", false);
    }

    public void setFirstRunDone(boolean b) {
        sp.edit().putBoolean("firstRunDone", b).apply();
    }
}
