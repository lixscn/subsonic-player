package com.lixscn.subsonicplayer.core;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * 从 JSON 文件导入服务器配置。
 *
 * 用途：① 换机 / 批量部署时不用手敲地址账号；② 自动化联调（把配置 push 到应用外部目录后启动即可）。
 *
 * 文件位置：`Android/data/&lt;包名&gt;/files/server.json`（adb push 可直接写这里）。
 * 格式（单服务器对象或数组均可）：
 * <pre>
 * { "name":"我的 NAS", "lanUrl":"http://192.168.1.10:4533",
 *   "wanUrl":"https://music.example.com", "username":"your-name", "password":"******" }
 * </pre>
 * 导入成功后**立即删除该文件**，避免明文密码长期留在存储上。
 */
public final class ConfigImport {

    private static final String TAG = "ConfigImport";
    public static final String FILE_NAME = "server.json";

    private ConfigImport() {
    }

    public static File configFile(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return null;
        return new File(dir, FILE_NAME);
    }

    /** 若存在导入文件则导入；返回导入的服务器数量（0 表示没有文件或解析失败） */
    public static int importIfPresent(Context ctx) {
        File f = configFile(ctx);
        if (f == null || !f.exists()) return 0;
        int n = 0;
        try {
            String text = readAll(f);
            Settings settings = Settings.get(ctx);
            JSONArray arr;
            String t = text.trim();
            if (t.startsWith("[")) {
                arr = new JSONArray(t);
            } else {
                arr = new JSONArray();
                arr.put(new JSONObject(t));
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Settings.Service svc = new Settings.Service();
                svc.name = o.optString("name", "");
                svc.lanUrl = o.optString("lanUrl", "");
                svc.wanUrl = o.optString("wanUrl", "");
                svc.username = o.optString("username", "");
                svc.password = o.optString("password", "");
                if (svc.lanUrl.length() == 0 && svc.wanUrl.length() == 0) continue;
                Settings.Service saved = settings.saveService(svc);
                if (n == 0) settings.setCurrentServiceId(saved.id);
                n++;
            }
            // 明文密码不留盘
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            Log.i(TAG, "已导入 " + n + " 个服务器配置并删除导入文件");
        } catch (Exception e) {
            Log.w(TAG, "导入配置失败: " + e.getMessage());
        }
        return n;
    }

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
