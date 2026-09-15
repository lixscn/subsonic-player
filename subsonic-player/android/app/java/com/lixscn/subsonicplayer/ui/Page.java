package com.lixscn.subsonicplayer.ui;

import android.view.View;

import com.lixscn.subsonicplayer.MainActivity;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.player.Player;

/**
 * 页面基类：一个页面 = 一个 View + 生命周期钩子。
 *
 * 路由由 MainActivity 维护（栈式：详情页 push，返回 pop），页面本身不关心导航。
 */
public abstract class Page {

    protected MainActivity act;
    protected Library lib;
    protected Player player;

    private View view;

    public void attach(MainActivity a) {
        this.act = a;
        this.lib = Library.get(a);
        this.player = Player.get(a);
    }

    /** 懒创建页面视图 */
    public final View view() {
        if (view == null) view = build();
        return view;
    }

    protected abstract View build();

    public void onResume() {
    }

    public void onPause() {
    }

    public void onDestroy() {
    }

    /** 顶部栏标题 */
    public String title() {
        return "";
    }

    /** 顶部栏副标题（可为空） */
    public String subtitle() {
        return "";
    }

    /** 拦截返回键；返回 true 表示已处理 */
    public boolean onBack() {
        return false;
    }

    /** 主题切换后重建（丢弃旧视图） */
    public void rebuild() {
        if (view != null) onDestroy();
        view = null;
    }

    /** 页面是否显示迷你播放条 */
    public boolean showMiniPlayer() {
        return true;
    }
}
