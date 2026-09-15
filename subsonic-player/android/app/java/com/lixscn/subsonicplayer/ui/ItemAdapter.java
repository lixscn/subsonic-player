package com.lixscn.subsonicplayer.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lixscn.subsonicplayer.R;
import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;

import java.util.List;

/**
 * 通用条目适配器：列表行 / 网格卡两种形态，覆盖歌曲、专辑、艺术家、歌单、流派。
 *
 * 用 ListView / GridView（Framework 自带）而不是 RecyclerView —— 本工程零第三方依赖，
 * 没有 androidx，所以走 AbsListView 的 ViewHolder 复用模式。
 */
public class ItemAdapter extends BaseAdapter {

    public static final int MODE_LIST = 0;
    public static final int MODE_GRID = 1;

    public interface Listener {
        void onItemClick(Item item, int position);

        /** 点击行尾「⋮」（网格模式为长按） */
        void onItemMore(Item item, int position, View anchor);
    }

    private final Context ctx;
    private final List<Item> items;
    private final int mode;
    private final Listener listener;
    private final int gridColumns;

    public ItemAdapter(Context ctx, List<Item> items, int mode, Listener listener) {
        this(ctx, items, mode, 3, listener);
    }

    public ItemAdapter(Context ctx, List<Item> items, int mode, int gridColumns, Listener listener) {
        this.ctx = ctx;
        this.items = items;
        this.mode = mode;
        this.gridColumns = Math.max(2, gridColumns);
        this.listener = listener;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (mode == MODE_GRID) return gridView(position, convertView, parent);
        return listView(position, convertView, parent);
    }

    // ---------------- 列表行 ----------------

    private static class RowHolder {
        LinearLayout root;
        CircleCover cover;
        TextView title, subtitle, trailing;
        ImageView more, chevron;
    }

    /** 这一条是否正是当前播放的曲目（用于列表高亮） */
    private static boolean isCurrentPlaying(Item it) {
        if (it == null || it.kind != Item.SONG || it.id == null) return false;
        com.lixscn.subsonicplayer.player.Player p = com.lixscn.subsonicplayer.player.Player.peek();
        if (p == null) return false;
        Item cur = p.current();
        return cur != null && cur.id != null && cur.id.equals(it.id);
    }

    private View listView(final int position, View convertView, ViewGroup parent) {
        Theme.Colors t = Ui.colors(ctx);
        RowHolder h;
        if (convertView == null) {
            LinearLayout row = Ui.row(ctx);
            row.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8));
            row.setMinimumHeight(Ui.dp(ctx, 64));

            CircleCover cover = new CircleCover(ctx);
            cover.setCornerRadius(Ui.dp(ctx, 8));
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(cover, Ui.lp(Ui.dp(ctx, 48), Ui.dp(ctx, 48)));

            LinearLayout mid = Ui.column(ctx);
            mid.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 8), 0);
            TextView title = Ui.text(ctx, "", 15, t.text);
            Ui.ellipsize(title);
            TextView subtitle = Ui.text(ctx, "", 12.5f, t.textDim);
            Ui.ellipsize(subtitle);
            subtitle.setPadding(0, Ui.dp(ctx, 3), 0, 0);
            mid.addView(title);
            mid.addView(subtitle);
            row.addView(mid, Ui.lpWeight(1));

            TextView trailing = Ui.text(ctx, "", 12, t.textFaint);
            trailing.setGravity(Gravity.CENTER);
            row.addView(trailing, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView more = Ui.icon(ctx, R.drawable.ic_more, 20, t.textDim);
            LinearLayout.LayoutParams mlp = Ui.lp(Ui.dp(ctx, 36), Ui.dp(ctx, 36));
            mlp.leftMargin = Ui.dp(ctx, 2);
            row.addView(more, mlp);

            ImageView chevron = Ui.icon(ctx, R.drawable.ic_chevron, 18, t.textFaint);
            row.addView(chevron, Ui.lp(Ui.dp(ctx, 20), Ui.dp(ctx, 20)));
            chevron.setVisibility(View.GONE);

            h = new RowHolder();
            h.root = row;
            h.cover = cover;
            h.title = title;
            h.subtitle = subtitle;
            h.trailing = trailing;
            h.more = more;
            h.chevron = chevron;
            row.setTag(h);
            // 涟漪只在创建时设置一次：每绑一次就再套一层 RippleDrawable 会层层嵌套，
            // 长列表滚动后背景树会深到影响绘制/点击响应
            Ui.tappable(row, ctx, t.text);
            convertView = row;
        } else {
            h = (RowHolder) convertView.getTag();
        }

        final Item it = items.get(position);
        h.title.setText(it.title);
        // 正在播放的那首高亮：标题用主题色 + 加粗。
        // 靠 getView 每次重绘时判定，所以滚动、切页、点击后（下面会通知刷新）都会自动更新。
        boolean playingNow = isCurrentPlaying(it);
        h.title.setTextColor(playingNow ? t.accent : t.text);
        h.title.setTypeface(null, playingNow ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        h.subtitle.setText(it.subtitle == null ? "" : it.subtitle);
        h.subtitle.setVisibility(it.subtitle == null || it.subtitle.length() == 0 ? View.GONE : View.VISIBLE);

        // 封面
        String url = coverUrlFor(it);
        h.cover.setBackground(Ui.rect(t.surfaceAlt, Ui.dp(ctx, 8)));
        if (url.length() > 0) CoverLoader.get(ctx).load(url, h.cover, 0);
        else h.cover.setImageDrawable(null);

        // 艺术家用圆形头像
        h.cover.setCircle(it.kind == Item.ARTIST);

        boolean isSong = it.kind == Item.SONG;
        h.trailing.setVisibility(isSong ? View.VISIBLE : View.GONE);
        h.trailing.setText(isSong ? it.durationText() : "");
        h.trailing.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        if (isSong && it.starred) {
            h.trailing.setText("★ " + it.durationText());
            h.trailing.setTextColor(t.accent);
        } else {
            h.trailing.setTextColor(t.textFaint);
        }
        h.chevron.setVisibility(isSong ? View.GONE : View.VISIBLE);
        h.more.setVisibility(isSong ? View.VISIBLE : View.VISIBLE);

        final int pos = position;
        h.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onItemClick(it, pos);
                // 点了之后马上重绘，让"正在播放"高亮即时生效（不用等滚动或重进页面）
                notifyDataSetChanged();
            }
        });
        h.more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onItemMore(it, pos, v);
            }
        });
        return convertView;
    }

    // ---------------- 网格卡 ----------------

    private static class GridHolder {
        LinearLayout root;
        CircleCover cover;
        TextView title, subtitle;
    }

    private View gridView(final int position, View convertView, ViewGroup parent) {
        Theme.Colors t = Ui.colors(ctx);
        GridHolder h;
        int cellW = 0;
        if (parent instanceof android.widget.GridView) {
            cellW = ((android.widget.GridView) parent).getColumnWidth();
        }
        if (cellW <= 0) {
            int screen = ctx.getResources().getDisplayMetrics().widthPixels;
            cellW = screen / gridColumns;
        }
        int coverSize = cellW - Ui.dp(ctx, 12);

        if (convertView == null) {
            LinearLayout card = Ui.column(ctx);
            card.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 12));

            CircleCover cover = new CircleCover(ctx);
            cover.setCornerRadius(Ui.dp(ctx, 10));
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(cover, Ui.lp(coverSize, coverSize));

            TextView title = Ui.text(ctx, "", 13.5f, t.text);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = Ui.lpMatchWrap();
            tlp.topMargin = Ui.dp(ctx, 8);
            card.addView(title, tlp);

            TextView sub = Ui.text(ctx, "", 11.5f, t.textDim);
            Ui.ellipsize(sub);
            card.addView(sub);

            h = new GridHolder();
            h.root = card;
            h.cover = cover;
            h.title = title;
            h.subtitle = sub;
            card.setTag(h);
            // 涟漪只在创建时设置一次，避免每次重绑嵌套一层 RippleDrawable
            Ui.tappable(card, ctx, t.text);
            convertView = card;
        } else {
            h = (GridHolder) convertView.getTag();
            ViewGroup.LayoutParams clp = h.cover.getLayoutParams();
            if (clp != null && (clp.width != coverSize || clp.height != coverSize)) {
                clp.width = coverSize;
                clp.height = coverSize;
                h.cover.setLayoutParams(clp);
            }
        }

        final Item it = items.get(position);
        h.title.setText(it.title);
        // 正在播放的那首高亮：标题用主题色 + 加粗。
        // 靠 getView 每次重绘时判定，所以滚动、切页、点击后（下面会通知刷新）都会自动更新。
        boolean playingNow = isCurrentPlaying(it);
        h.title.setTextColor(playingNow ? t.accent : t.text);
        h.title.setTypeface(null, playingNow ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        String sub = it.subtitle == null ? "" : it.subtitle;
        h.subtitle.setText(sub);
        h.subtitle.setVisibility(sub.length() == 0 ? View.GONE : View.VISIBLE);
        h.cover.setBackground(Ui.rect(t.surfaceAlt, Ui.dp(ctx, 10)));
        h.cover.setCircle(it.kind == Item.ARTIST);
        String url = coverUrlFor(it);
        if (url.length() > 0) CoverLoader.get(ctx).load(url, h.cover, 0);
        else h.cover.setImageDrawable(null);

        final int pos = position;
        h.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onItemClick(it, pos);
                // 点了之后马上重绘，让"正在播放"高亮即时生效（不用等滚动或重进页面）
                notifyDataSetChanged();
            }
        });
        h.root.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) listener.onItemMore(it, pos, v);
                return true;
            }
        });
        return convertView;
    }

    /** 由 Item 推出封面 URL（走 Library 的封面地址构造） */
    private String coverUrlFor(Item it) {
        if (it.coverUrl != null && it.coverUrl.length() > 0) return it.coverUrl;
        if (it.coverArt == null || it.coverArt.length() == 0) {
            return "";
        }
        int size = mode == MODE_GRID ? 512 : 256;
        return Library.get(ctx).coverUrl(it.coverArt, size);
    }

    /** 列表中间插入一条「加载更多/状态」行用的通用单行视图 */
    public static View footer(Context ctx, String text) {
        Theme.Colors t = Ui.colors(ctx);
        LinearLayout ll = Ui.row(ctx);
        ll.setGravity(Gravity.CENTER);
        ll.setPadding(0, Ui.dp(ctx, 20), 0, Ui.dp(ctx, 20));
        TextView tv = Ui.text(ctx, text, 13, t.textFaint);
        ll.addView(tv);
        return ll;
    }
}
