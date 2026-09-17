package com.lixscn.subsonicplayer.ui.pages;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.lixscn.subsonicplayer.R;
import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.core.SubsonicClient;
import com.lixscn.subsonicplayer.ui.CircleCover;
import com.lixscn.subsonicplayer.ui.CoverLoader;
import com.lixscn.subsonicplayer.ui.ItemAdapter;
import com.lixscn.subsonicplayer.ui.Menus;
import com.lixscn.subsonicplayer.ui.Page;
import com.lixscn.subsonicplayer.ui.Theme;
import com.lixscn.subsonicplayer.ui.Ui;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索页（底部 Tab）。
 *
 * <p>结构：固定顶部搜索框 + 可滚动结果区。
 * 结果区只有一个 ListView 承担滚动：艺术家 / 专辑两个横向卡片行与「歌曲」小标题都放在
 * ListView 的 header 里，歌曲本体交给 {@link ItemAdapter}（列表模式，自带 ⋮ 菜单），
 * 这样整页只滚一次，也不会出现「ListView 嵌在 ScrollView 里只显示一行」的经典坑。
 *
 * <p>输入防抖：文本变化后 350ms 无新输入才请求（{@link Handler#postDelayed} + 单个
 * Runnable，每次变化先 {@link Handler#removeCallbacks} 掉上一次）；并用自增 token 丢弃
 * 过期响应，避免「后发的请求先回来」把结果写乱。
 *
 * <p>最近搜索：本机 {@code sp_search} 里最多存 10 条，只在「明确搜索」（回车 / 点搜索图标 /
 * 点历史词）时记录，防抖搜索只在有结果时记录，并且会用前缀规则清掉「输入到一半」的中间词
 * （例如记住 beatles 时顺手丢掉 bea）。
 */
public class SearchPage extends Page {

    private static final String PREFS = "sp_search";
    private static final String KEY_HISTORY = "items";
    private static final int MAX_HISTORY = 10;
    private static final int COUNT = 30;
    private static final long DEBOUNCE_MS = 350L;

    private static final int AVATAR_DP = 72;
    private static final int ARTIST_CARD_DP = 88;
    private static final int ALBUM_COVER_DP = 110;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Item> songs = new ArrayList<Item>();

    private EditText input;
    private View clearIcon;
    private FrameLayout stateHost;
    private ListView list;
    private ItemAdapter adapter;
    private LinearLayout artistsBox;
    private LinearLayout albumsBox;
    private LinearLayout songsBox;

    /** 请求令牌：只有等于最新 token 的回调才允许改 UI */
    private int token;
    /** 已经渲染出结果的查询词（防抖重复触发时直接跳过） */
    private String shownQuery = "";
    /** 最近一次发起请求的查询词（失败重试用） */
    private String currentQuery = "";
    private boolean destroyed;

    /** 防抖任务：执行时再取输入框内容，天然只保留最后一次 */
    private final Runnable debounce = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            String q = query();
            if (q.length() == 0) return;
            runSearch(q, false);
        }
    };

    @Override
    public String title() {
        return "搜索";
    }

    // ---------------- 视图 ----------------

    @Override
    protected View build() {
        destroyed = false;
        token++;
        Theme.Colors c = Ui.colors(act);

        LinearLayout root = Ui.column(act);
        root.setBackgroundColor(c.bg);
        root.addView(searchBar(c));

        FrameLayout body = new FrameLayout(act);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list = new ListView(act);
        list.setDivider(null);
        list.setDividerHeight(0);
        // 清掉系统默认点击高亮（MIUI 上是一块亮色闪烁）
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setPadding(0, 0, 0, Ui.dp(act, 8));
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);

        LinearLayout header = Ui.column(act);
        artistsBox = Ui.column(act);
        albumsBox = Ui.column(act);
        songsBox = Ui.column(act);
        header.addView(artistsBox);
        header.addView(albumsBox);
        header.addView(songsBox);
        list.addHeaderView(header, null, false);

        adapter = new ItemAdapter(act, songs, ItemAdapter.MODE_LIST, new ItemAdapter.Listener() {
            @Override
            public void onItemClick(Item item, int position) {
                act.playNow(songs, position);
            }

            @Override
            public void onItemMore(Item item, int position, View anchor) {
                Menus.song(act, item);
            }
        });
        list.setAdapter(adapter);
        // 自动切歌时刷新「正在播放」高亮
        watchPlaying(new Runnable() {
            @Override
            public void run() {
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        });
        // ItemAdapter 的行自己处理点击，行数很少时部分机型仍会把长按交给 ListView，兜一层
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                int index = position - list.getHeaderViewsCount();
                if (index >= 0 && index < songs.size()) {
                    Menus.song(act, songs.get(index));
                    return true;
                }
                return false;
            }
        });
        body.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        stateHost = new FrameLayout(act);
        body.addView(stateHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        showInitial();
        return root;
    }

    /** 顶部搜索框：圆角底 + 左侧搜索图标 + 右侧清除按钮 */
    private View searchBar(Theme.Colors c) {
        LinearLayout bar = Ui.row(act);
        Ui.pad(bar, act, 12, 6, 12, 8);

        FrameLayout box = new FrameLayout(act);
        box.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(act, 20)));

        input = new EditText(act);
        input.setSingleLine(true);
        input.setTextColor(c.text);
        input.setHintTextColor(c.textFaint);
        input.setHint("搜索歌曲 / 专辑 / 艺术家");
        input.setTextSize(15);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackground(null);
        input.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(input, act, 48, 11, 46, 11);
        box.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View searchIcon = Ui.iconButton(act, R.drawable.ic_search, 19, 40, c.textDim,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        submit();
                    }
                });
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40));
        slp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        slp.leftMargin = Ui.dp(act, 2);
        box.addView(searchIcon, slp);

        clearIcon = Ui.iconButton(act, R.drawable.ic_close, 16, 32, c.textDim,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        input.setText("");
                    }
                });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(Ui.dp(act, 32), Ui.dp(act, 32));
        clp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        clp.rightMargin = Ui.dp(act, 7);
        box.addView(clearIcon, clp);
        clearIcon.setVisibility(View.GONE);

        bar.addView(box, Ui.lpMatchWrap());

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (destroyed) return;
                int len = s == null ? 0 : s.length();
                clearIcon.setVisibility(len > 0 ? View.VISIBLE : View.GONE);
                schedule(len == 0 ? "" : s.toString().trim());
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN;
                if (enter || actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_DONE) {
                    submit();
                    return true;
                }
                return false;
            }
        });
        input.requestFocus();
        return bar;
    }

    // ---------------- 输入与防抖 ----------------

    private String query() {
        return input == null ? "" : input.getText().toString().trim();
    }

    /** 每次文本变化都重置 350ms 计时；清空则立即回到初始态 */
    private void schedule(String q) {
        handler.removeCallbacks(debounce);
        if (destroyed) return;
        if (q.length() == 0) {
            token++;              // 让在途请求的回调失效
            currentQuery = "";
            shownQuery = "";
            clearResults();
            showInitial();
            return;
        }
        handler.postDelayed(debounce, DEBOUNCE_MS);
    }

    /** 回车 / 点搜索图标 / 点历史词：立即搜并收起键盘 */
    private void submit() {
        String q = query();
        if (q.length() == 0) {
            act.toast("请输入搜索关键词");
            return;
        }
        hideKeyboard();
        runSearch(q, true);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
        input.clearFocus();
    }

    private void runSearch(final String q, final boolean explicit) {
        if (destroyed) return;
        handler.removeCallbacks(debounce);
        if (!explicit && q.equals(shownQuery)) return;   // 同一个词不重复打服务端

        currentQuery = q;
        clearResults();
        if (lib == null || !lib.isConfigured()) {
            showState(Ui.message(act, "未配置服务器", "请先在「设置」里填写音乐服务器地址", null, null));
            return;
        }
        showLoading();

        final int mine = ++token;
        lib.search(q, COUNT, new Library.Done<SubsonicClient.SearchResult>() {
            @Override
            public void ok(SubsonicClient.SearchResult value) {
                if (destroyed || mine != token) return;
                if (value == null || value.isEmpty()) {
                    remember(q, explicit, false);
                    showEmpty();
                    return;
                }
                shownQuery = q;
                remember(q, explicit, true);
                render(value);
            }

            @Override
            public void fail(String message) {
                if (destroyed || mine != token) return;
                showError(message);
            }
        });
    }

    private void clearResults() {
        songs.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (artistsBox != null) artistsBox.removeAllViews();
        if (albumsBox != null) albumsBox.removeAllViews();
        if (songsBox != null) songsBox.removeAllViews();
    }

    // ---------------- 渲染 ----------------

    private void render(SubsonicClient.SearchResult r) {
        int n = Math.min(r.songs.size(), COUNT);
        for (int i = 0; i < n; i++) songs.add(r.songs.get(i));
        adapter.notifyDataSetChanged();

        if (!r.artists.isEmpty()) artistsBox.addView(artistSection(r.artists));
        if (!r.albums.isEmpty()) albumsBox.addView(albumSection(r.albums));
        if (!songs.isEmpty()) songsBox.addView(sectionTitle("歌曲", songs.size() + " 首"));

        showList();
        list.setSelection(0);
    }

    private View artistSection(List<Item> artists) {
        LinearLayout col = Ui.column(act);
        col.addView(sectionTitle("艺术家", artists.size() + " 位"));
        LinearLayout row = Ui.row(act);
        row.setPadding(Ui.dp(act, 16), Ui.dp(act, 4), Ui.dp(act, 16), Ui.dp(act, 6));
        for (Item it : artists) row.addView(artistCard(it));
        col.addView(hScroll(row));
        return col;
    }

    private View albumSection(List<Item> albums) {
        LinearLayout col = Ui.column(act);
        col.addView(sectionTitle("专辑", albums.size() + " 张"));
        LinearLayout row = Ui.row(act);
        row.setPadding(Ui.dp(act, 16), Ui.dp(act, 4), Ui.dp(act, 16), Ui.dp(act, 6));
        for (Item it : albums) row.addView(albumCard(it));
        col.addView(hScroll(row));
        return col;
    }

    private HorizontalScrollView hScroll(View content) {
        HorizontalScrollView hsv = new HorizontalScrollView(act);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipToPadding(false);
        hsv.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hsv;
    }

    private View sectionTitle(String text, String count) {
        Theme.Colors c = Ui.colors(act);
        LinearLayout head = (LinearLayout) Ui.sectionHeader(act, text, null, null);
        if (count != null && count.length() > 0) {
            head.addView(Ui.text(act, count, 12, c.textDim));
        }
        return head;
    }

    private View artistCard(final Item it) {
        Theme.Colors c = Ui.colors(act);
        LinearLayout card = Ui.column(act);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout cover = coverBox(it, AVATAR_DP, AVATAR_DP / 2f, true, R.drawable.ic_artist);
        card.addView(cover, Ui.lp(Ui.dp(act, AVATAR_DP), Ui.dp(act, AVATAR_DP)));

        TextView name = Ui.text(act, it.title, 13, c.text);
        Ui.ellipsize(name);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = Ui.lp(Ui.dp(act, ARTIST_CARD_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Ui.dp(act, 7);
        card.addView(name, nlp);

        if (it.subtitle != null && it.subtitle.length() > 0) {
            TextView sub = Ui.text(act, it.subtitle, 11, c.textDim);
            Ui.ellipsize(sub);
            sub.setGravity(Gravity.CENTER);
            card.addView(sub, Ui.lp(Ui.dp(act, ARTIST_CARD_DP), ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.openArtist(it.id, it.title);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Menus.artist(act, it);
                return true;
            }
        });
        Ui.tappable(card, act, c.text);

        LinearLayout.LayoutParams clp = Ui.lp(Ui.dp(act, ARTIST_CARD_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = Ui.dp(act, 10);
        card.setLayoutParams(clp);
        return card;
    }

    private View albumCard(final Item it) {
        Theme.Colors c = Ui.colors(act);
        LinearLayout card = Ui.column(act);
        int w = Ui.dp(act, ALBUM_COVER_DP);

        FrameLayout cover = coverBox(it, ALBUM_COVER_DP, 10f, false, R.drawable.ic_album);
        card.addView(cover, Ui.lp(w, w));

        TextView title = Ui.text(act, it.title, 13, c.text);
        Ui.ellipsize(title);
        LinearLayout.LayoutParams tlp = Ui.lp(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(act, 8);
        card.addView(title, tlp);

        if (it.subtitle != null && it.subtitle.length() > 0) {
            TextView sub = Ui.text(act, it.subtitle, 11.5f, c.textDim);
            Ui.ellipsize(sub);
            card.addView(sub, Ui.lp(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.openAlbum(it.id);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Menus.album(act, it);
                return true;
            }
        });
        Ui.tappable(card, act, c.text);

        LinearLayout.LayoutParams clp = Ui.lp(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = Ui.dp(act, 12);
        card.setLayoutParams(clp);
        return card;
    }

    /** 封面盒：圆角/圆形封面 + 无封面时的占位图标（图标在下层，封面加载好自动盖住） */
    private FrameLayout coverBox(Item it, int sizeDp, float radiusDp, boolean circle, int fallbackIcon) {
        Theme.Colors c = Ui.colors(act);
        FrameLayout box = new FrameLayout(act);

        CircleCover cover = new CircleCover(act);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setCornerRadius(Ui.dp(act, radiusDp));
        if (circle) cover.setCircle(true);
        cover.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(act, circle ? sizeDp / 2f : radiusDp)));
        box.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView placeholder = new ImageView(act);
        placeholder.setImageResource(fallbackIcon);
        placeholder.setColorFilter(c.textFaint);
        placeholder.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int px = Math.max(Ui.dp(act, 16), Ui.dp(act, sizeDp * 0.34f));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(px, px);
        plp.gravity = Gravity.CENTER;
        box.addView(placeholder, plp);

        String url = coverUrl(it, circle ? 256 : 512);
        if (url.length() > 0) CoverLoader.get(act).load(url, cover, 0);
        return box;
    }

    private String coverUrl(Item it, int size) {
        if (it.coverUrl != null && it.coverUrl.length() > 0) return it.coverUrl;
        if (it.coverArt == null || it.coverArt.length() == 0) return "";
        return lib.coverUrl(it.coverArt, size);
    }

    // ---------------- 状态切换 ----------------

    private void showList() {
        if (stateHost == null || list == null) return;
        stateHost.removeAllViews();
        stateHost.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
    }

    private void showState(View v) {
        if (stateHost == null || list == null) return;
        stateHost.removeAllViews();
        list.setVisibility(View.GONE);
        stateHost.setVisibility(View.VISIBLE);
        stateHost.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showLoading() {
        showState(Ui.loading(act));
    }

    private void showEmpty() {
        showState(Ui.message(act, "没有找到", "换个关键词试试", null, null));
    }

    private void showError(String message) {
        showState(Ui.message(act, "搜索失败", message == null ? "" : message, "重试",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String q = query();
                        runSearch(q.length() > 0 ? q : currentQuery, true);
                    }
                }));
    }

    /** 初始态：提示语 + 最近搜索 */
    private void showInitial() {
        if (destroyed) return;
        Theme.Colors c = Ui.colors(act);
        LinearLayout col = Ui.column(act);
        col.setPadding(0, Ui.dp(act, 30), 0, Ui.dp(act, 24));

        TextView hint = Ui.bold(act, "搜索歌曲 / 专辑 / 艺术家", 16, c.textDim);
        hint.setGravity(Gravity.CENTER);
        col.addView(hint, Ui.lpMatchWrap());

        TextView sub = Ui.text(act, "输入关键词，结果会自动出现", 13, c.textFaint);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(act, 8), 0, 0);
        col.addView(sub, Ui.lpMatchWrap());

        final List<String> history = readHistory();
        if (!history.isEmpty()) {
            col.addView(Ui.sectionHeader(act, "最近搜索", "清空", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearHistory();
                    showInitial();
                }
            }));
            for (String q : history) col.addView(historyRow(q));
        }

        ScrollView sv = new ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        showState(sv);
    }

    private View historyRow(final String q) {
        Theme.Colors c = Ui.colors(act);
        LinearLayout row = Ui.row(act);
        Ui.pad(row, act, 16, 13, 16, 13);

        ImageView ic = new ImageView(act);
        ic.setImageResource(R.drawable.ic_history);
        ic.setColorFilter(c.textFaint);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(ic, Ui.lp(Ui.dp(act, 18), Ui.dp(act, 18)));

        TextView tv = Ui.text(act, q, 14.5f, c.text);
        Ui.ellipsize(tv);
        LinearLayout.LayoutParams tlp = Ui.lpWeight(1);
        tlp.leftMargin = Ui.dp(act, 13);
        row.addView(tv, tlp);

        ImageView chevron = new ImageView(act);
        chevron.setImageResource(R.drawable.ic_chevron);
        chevron.setColorFilter(c.textFaint);
        chevron.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(chevron, Ui.lp(Ui.dp(act, 16), Ui.dp(act, 16)));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText(q);
                input.setSelection(input.getText().length());
                runSearch(q, true);
            }
        });
        Ui.tappable(row, act, c.text);
        return row;
    }

    // ---------------- 最近搜索（本机） ----------------

    private List<String> readHistory() {
        List<String> out = new ArrayList<String>();
        try {
            SharedPreferences sp = act.getSharedPreferences(PREFS, 0);
            JSONArray arr = new JSONArray(sp.getString(KEY_HISTORY, "[]"));
            for (int i = 0; i < arr.length() && out.size() < MAX_HISTORY; i++) {
                String s = arr.optString(i, "");
                if (s != null && s.trim().length() > 0) out.add(s.trim());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveHistory(List<String> items) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < items.size() && i < MAX_HISTORY; i++) arr.put(items.get(i));
        act.getSharedPreferences(PREFS, 0).edit().putString(KEY_HISTORY, arr.toString()).apply();
    }

    private void clearHistory() {
        act.getSharedPreferences(PREFS, 0).edit().remove(KEY_HISTORY).apply();
        act.toast("已清空最近搜索");
    }

    /**
     * 记录搜索词。
     *
     * @param force true = 用户明确搜索（回车 / 图标 / 历史词），一定记；
     *              false = 防抖触发，只在有结果时记，且若已有更长的词以它开头（说明用户还在输入）
     *              就跳过，避免历史里塞满 "b" / "be" / "bea"。
     */
    private void remember(String q, boolean force, boolean hasResult) {
        String term = q == null ? "" : q.trim();
        if (term.length() == 0) return;
        if (!force && !hasResult) return;

        List<String> old = readHistory();
        if (!force) {
            for (String h : old) {
                if (h.length() > term.length() && h.startsWith(term)) return;
            }
        }
        List<String> out = new ArrayList<String>();
        out.add(term);
        for (String h : old) {
            if (h.equals(term)) continue;
            if (term.startsWith(h)) continue;   // 丢掉「当前词的前缀」这类中间态
            if (out.size() >= MAX_HISTORY) break;
            out.add(h);
        }
        saveHistory(out);
    }

    // ---------------- 生命周期 ----------------

    @Override
    public boolean onBack() {
        if (query().length() > 0) {
            // 先清关键词回到初始态，再按一次才退出/切 Tab（afterTextChanged 里会刷新界面）
            input.setText("");
            return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        unwatchPlaying();
        token++;
        handler.removeCallbacks(debounce);
    }
}
