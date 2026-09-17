package com.lixscn.subsonicplayer.ui;

import android.view.View;

import com.lixscn.subsonicplayer.MainActivity;
import com.lixscn.subsonicplayer.core.Item;
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

    // ---------------- 「正在播放」高亮刷新 ----------------

    private Player.Listener playingWatcher;

    /**
     * 订阅「当前播放曲目变化」，用来刷新列表里「正在播放」那一行的高亮。
     *
     * <p>为什么需要：行高亮是在 {@code ItemAdapter.getView()} 里按 {@code Player.current()} 判定的，
     * **只有重绘才会更新**。用户手点某一首时适配器会自己 {@code notifyDataSetChanged()}，
     * 但**自动切歌**（一首放完自动下一首 / 通知栏切歌 / 播放页切歌）没有任何人通知列表 ——
     * 结果旧曲目那一行一直亮着、新曲目不亮，看着就是「选中状态错了」。
     *
     * <p>子类务必在 {@code onDestroy()} 里调用 {@link #unwatchPlaying()}（项目铁律：注册了监听就要注销）。
     */
    protected void watchPlaying(final Runnable onChanged) {
        unwatchPlaying();
        playingWatcher = new Player.Listener() {
            @Override
            public void onTrackChanged(Item song) {
                if (onChanged != null) onChanged.run();
            }

            @Override
            public void onProgress(boolean playing, int positionMs, int durationMs) {
            }

            @Override
            public void onQueueChanged() {
                if (onChanged != null) onChanged.run();
            }

            @Override
            public void onModeChanged(int mode) {
            }

            @Override
            public void onPlaybackError(String message) {
            }
        };
        if (player != null) player.addListener(playingWatcher);
    }

    /** 注销 {@link #watchPlaying} 注册的监听 */
    protected void unwatchPlaying() {
        if (playingWatcher != null && player != null) player.removeListener(playingWatcher);
        playingWatcher = null;
    }
}
