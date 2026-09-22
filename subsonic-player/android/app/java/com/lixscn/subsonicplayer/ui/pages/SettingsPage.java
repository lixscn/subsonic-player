package com.lixscn.subsonicplayer.ui.pages;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.lixscn.subsonicplayer.R;
import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.core.Settings;
import com.lixscn.subsonicplayer.ui.CoverLoader;
import com.lixscn.subsonicplayer.ui.Page;
import com.lixscn.subsonicplayer.ui.Theme;
import com.lixscn.subsonicplayer.ui.Ui;

import java.util.List;

/**
 * 设置页：服务器配置（增删改 + 测试连接）、外观（主题/强调色）、播放、存储、关于。
 */
public class SettingsPage extends Page {

    private LinearLayout content;
    private LinearLayout serviceList;

    @Override
    public String title() {
        return "设置";
    }

    @Override
    protected View build() {
        Theme.Colors c = Ui.colors(act);
        ScrollView sv = new ScrollView(act);
        sv.setBackgroundColor(c.bg);
        sv.setVerticalScrollBarEnabled(false);
        content = Ui.column(act);
        content.setPadding(0, 0, 0, Ui.dp(act, 32));
        sv.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        buildServerSection();
        buildAppearanceSection();
        buildPlaybackSection();
        buildStorageSection();
        buildAboutSection();
        return sv;
    }

    // ---------------- 服务器 ----------------

    private void buildServerSection() {
        Theme.Colors c = Ui.colors(act);
        content.addView(Ui.sectionHeader(act, "音乐服务器", null, null));

        serviceList = Ui.column(act);
        content.addView(serviceList);
        renderServices();

        TextView add = Ui.text(act, "＋ 添加服务器", 14, c.accent);
        add.setGravity(Gravity.CENTER);
        add.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 14));
        add.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(act, 12)));
        LinearLayout.LayoutParams lp = Ui.lpMatchWrap();
        lp.leftMargin = Ui.dp(act, 16);
        lp.rightMargin = Ui.dp(act, 16);
        lp.topMargin = Ui.dp(act, 8);
        content.addView(add, lp);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editService(null);
            }
        });

        // 连接状态 + 手动测试
        final TextView status = Ui.text(act, "", 12.5f, c.textDim);
        status.setPadding(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 16), 0);
        updateStatus(status);
        content.addView(status);

        TextView test = Ui.text(act, "测试连接", 14, c.accent);
        test.setGravity(Gravity.CENTER);
        test.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 12));
        test.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(act, 12)));
        LinearLayout.LayoutParams tlp = Ui.lpMatchWrap();
        tlp.leftMargin = Ui.dp(act, 16);
        tlp.rightMargin = Ui.dp(act, 16);
        tlp.topMargin = Ui.dp(act, 10);
        content.addView(test, tlp);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                status.setText("正在连接…");
                act.library().rebuildClient();
                act.library().connect(new Library.Done<Boolean>() {
                    @Override
                    public void ok(Boolean value) {
                        act.toast("连接成功");
                        updateStatus(status);
                        act.library().clearCache();
                    }

                    @Override
                    public void fail(String message) {
                        act.toast(message);
                        updateStatus(status);
                    }
                });
            }
        });
    }

    private void updateStatus(TextView status) {
        Settings.Service svc = lib.settings().currentService();
        if (svc == null) {
            status.setText("尚未配置服务器");
            return;
        }
        String connected = lib.connectedUrl();
        if (connected == null || connected.length() == 0) {
            status.setText("当前：未连接（点「测试连接」重试）");
        } else {
            status.setText("当前已连接：" + connected.replaceFirst("^https?://", "")
                    + "　账号：" + svc.username);
        }
    }

    private void renderServices() {
        serviceList.removeAllViews();
        Theme.Colors c = Ui.colors(act);
        List<Settings.Service> all = lib.settings().services();
        if (all.isEmpty()) {
            TextView tv = Ui.text(act, "还没有服务器，点下面「添加服务器」开始", 13, c.textFaint);
            tv.setPadding(Ui.dp(act, 16), Ui.dp(act, 6), Ui.dp(act, 16), Ui.dp(act, 6));
            serviceList.addView(tv);
            return;
        }
        final String currentId = lib.settings().currentServiceId();
        for (final Settings.Service s : all) {
            LinearLayout row = Ui.row(act);
            Ui.pad(row, act, 16, 12, 8, 12);
            row.setBackground(Ui.rect(c.surface, Ui.dp(act, 12)));
            LinearLayout.LayoutParams rlp = Ui.lpMatchWrap();
            rlp.leftMargin = Ui.dp(act, 16);
            rlp.rightMargin = Ui.dp(act, 16);
            rlp.topMargin = Ui.dp(act, 6);
            row.setLayoutParams(rlp);

            boolean active = s.id.equals(currentId);
            if (active) {
                row.addView(Ui.icon(act, R.drawable.ic_check, 18, c.accent),
                        Ui.lp(Ui.dp(act, 26), Ui.dp(act, 22)));
            } else {
                row.addView(new View(act), Ui.lp(Ui.dp(act, 26), Ui.dp(act, 22)));
            }

            LinearLayout mid = Ui.column(act);
            TextView name = Ui.text(act, s.displayName(), 15, active ? c.accent : c.text);
            TextView url = Ui.text(act, s.lanUrl + (s.wanUrl.length() > 0 ? "　|　" + s.wanUrl : ""),
                    11.5f, c.textFaint);
            Ui.ellipsize(url);
            url.setPadding(0, Ui.dp(act, 3), 0, 0);
            mid.addView(name);
            mid.addView(url);
            row.addView(mid, Ui.lpWeight(1));

            View edit = Ui.iconButton(act, R.drawable.ic_edit, 18, 36, c.textDim, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editService(s);
                }
            });
            row.addView(edit);
            View del = Ui.iconButton(act, R.drawable.ic_trash, 18, 36, c.danger, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Ui.confirm(act, "删除服务器", "确定删除「" + s.displayName() + "」？", "删除", new Runnable() {
                        @Override
                        public void run() {
                            lib.settings().deleteService(s.id);
                            lib.rebuildClient();
                            renderServices();
                            act.library().clearCache();
                        }
                    });
                }
            });
            row.addView(del);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lib.settings().setCurrentServiceId(s.id);
                    lib.rebuildClient();
                    renderServices();
                    act.toast("已切换到 " + s.displayName());
                    act.connect();
                }
            });
            Ui.tappable(row, act, c.text);
            serviceList.addView(row);
        }
    }

    /** 服务器编辑表单（内联展开，不用对话框 —— 字段较多） */
    private void editService(final Settings.Service existing) {
        Theme.Colors c = Ui.colors(act);
        final Settings.Service svc = existing == null ? new Settings.Service() : existing.copy();

        LinearLayout form = Ui.column(act);
        form.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(act, 12)));
        Ui.pad(form, act, 14, 12, 14, 12);
        LinearLayout.LayoutParams flp = Ui.lpMatchWrap();
        flp.leftMargin = Ui.dp(act, 16);
        flp.rightMargin = Ui.dp(act, 16);
        flp.topMargin = Ui.dp(act, 8);
        form.setLayoutParams(flp);

        TextView head = Ui.bold(act, existing == null ? "添加服务器" : "编辑服务器", 16, c.text);
        form.addView(head);

        final EditText name = field(form, "名称（可空）", svc.name, InputType.TYPE_CLASS_TEXT);
        final EditText lan = field(form, "内网地址，如 http://192.168.1.10:4533", svc.lanUrl,
                InputType.TYPE_TEXT_VARIATION_URI);
        final EditText wan = field(form, "外网地址（可空）", svc.wanUrl, InputType.TYPE_TEXT_VARIATION_URI);
        final EditText user = field(form, "用户名", svc.username, InputType.TYPE_CLASS_TEXT);
        final EditText pass = field(form, "密码", svc.password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout actions = Ui.row(act);
        actions.setPadding(0, Ui.dp(act, 12), 0, 0);
        TextView save = Ui.text(act, "保存并连接", 14, c.accentText);
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 12));
        save.setBackground(Ui.rect(c.accent, Ui.dp(act, 12)));
        actions.addView(save, Ui.lpWeight(1));
        TextView cancel = Ui.text(act, "取消", 14, c.textDim);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 12));
        cancel.setBackground(Ui.rect(c.surface, Ui.dp(act, 12)));
        LinearLayout.LayoutParams clp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.5f);
        clp.leftMargin = Ui.dp(act, 8);
        actions.addView(cancel, clp);
        form.addView(actions);

        content.addView(form, 1);

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                content.removeView(form);
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                svc.name = name.getText().toString().trim();
                svc.lanUrl = normalizeUrl(lan.getText().toString());
                svc.wanUrl = normalizeUrl(wan.getText().toString());
                svc.username = user.getText().toString().trim();
                svc.password = pass.getText().toString();
                if (svc.lanUrl.length() == 0 && svc.wanUrl.length() == 0) {
                    act.toast("请至少填写一个服务器地址");
                    return;
                }
                Settings.Service saved = lib.settings().saveService(svc);
                lib.settings().setCurrentServiceId(saved.id);
                lib.rebuildClient();
                lib.clearCache();
                content.removeView(form);
                renderServices();
                act.toast("已保存，正在连接…");
                act.connect();
            }
        });
    }

    private static String normalizeUrl(String s) {
        String v = s == null ? "" : s.trim();
        if (v.length() == 0) return "";
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://" + v;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private EditText field(LinearLayout parent, String hint, String value, int inputType) {
        Theme.Colors c = Ui.colors(act);
        EditText et = new EditText(act);
        et.setHint(hint);
        et.setText(value == null ? "" : value);
        et.setTextSize(14);
        et.setTextColor(c.text);
        et.setHintTextColor(c.textFaint);
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setBackground(Ui.rect(c.surface, Ui.dp(act, 10), c.border, Ui.dp(act, 1)));
        et.setPadding(Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12));
        LinearLayout.LayoutParams lp = Ui.lpMatchWrap();
        lp.topMargin = Ui.dp(act, 10);
        parent.addView(et, lp);
        return et;
    }

    // ---------------- 外观 ----------------

    private void buildAppearanceSection() {
        Theme.Colors c = Ui.colors(act);
        content.addView(Ui.sectionHeader(act, "外观", null, null));

        TextView label = Ui.text(act, "主题", 13, c.textDim);
        label.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 4));
        content.addView(label);

        LinearLayout themes = Ui.column(act);
        themes.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 12), Ui.dp(act, 8));
        Theme.Colors[] all = Theme.all();
        final String currentTheme = lib.settings().themeId();
        LinearLayout rowWrap = null;
        for (int i = 0; i < all.length; i++) {
            if (i % 2 == 0) {
                rowWrap = Ui.row(act);
                themes.addView(rowWrap);
            }
            final Theme.Colors th = all[i];
            boolean active = th.id.equals(currentTheme);
            LinearLayout card = Ui.row(act);
            Ui.pad(card, act, 12, 12, 12, 12);
            card.setBackground(Ui.rect(th.surface, Ui.dp(act, 12),
                    active ? c.accent : th.border, active ? Ui.dp(act, 2) : Ui.dp(act, 1)));

            View dot = new View(act);
            dot.setBackground(Ui.circle(th.accent));
            card.addView(dot, Ui.lp(Ui.dp(act, 18), Ui.dp(act, 18)));

            TextView tv = Ui.text(act, th.label, 14, th.text);
            tv.setPadding(Ui.dp(act, 10), 0, 0, 0);
            card.addView(tv, Ui.lpWeight(1));
            if (active) card.addView(Ui.icon(act, R.drawable.ic_check, 16, c.accent),
                    Ui.lp(Ui.dp(act, 18), Ui.dp(act, 18)));

            LinearLayout.LayoutParams lp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = Ui.dp(act, 8);
            lp.topMargin = Ui.dp(act, 8);
            rowWrap.addView(card, lp);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lib.settings().setThemeId(th.id);
                    act.applyTheme();
                    act.toast("已切换到「" + th.label + "」");
                }
            });
            Ui.tappable(card, act, th.text);
        }
        content.addView(themes);

        TextView accentLabel = Ui.text(act, "强调色", 13, c.textDim);
        accentLabel.setPadding(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 16), Ui.dp(act, 4));
        content.addView(accentLabel);

        LinearLayout accents = Ui.chipRow(act);
        final String[] names = Theme.accentNames();
        final int currentAccent = lib.settings().accentIndex();
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            boolean active = i == currentAccent;
            TextView chip = Ui.chip(act, names[i], active, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lib.settings().setAccentIndex(index);
                    act.applyTheme();
                }
            });
            LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(act, 8);
            accents.addView(chip, lp);
        }
        content.addView(accents);
    }

    // ---------------- 播放 ----------------

    private void buildPlaybackSection() {
        Theme.Colors c = Ui.colors(act);
        content.addView(Ui.sectionHeader(act, "播放", null, null));

        final String[] quality = {"原始（不转码）", "高（320 kbps）", "中（192 kbps）", "低（96 kbps）"};
        choiceRow("音质", quality[Math.min(3, Math.max(0, lib.settings().networkQuality()))],
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Ui.choose(act, "音质", quality, lib.settings().networkQuality(), new Ui.OnInput() {
                            @Override
                            public void onInput(String text) {
                                try {
                                    lib.settings().setNetworkQuality(Integer.parseInt(text));
                                } catch (Exception ignored) {
                                }
                                act.refreshCurrent();
                            }
                        });
                    }
                });

        LinearLayout vn = Ui.row(act);
        Ui.pad(vn, act, 16, 12, 16, 12);
        TextView vnLabel = Ui.text(act, "音量标准化", 15, c.text);
        vn.addView(vnLabel, Ui.lpWeight(1));
        final CheckBox cb = new CheckBox(act);
        cb.setChecked(lib.settings().volumeNormalization());
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(c.accent));
        vn.addView(cb);
        vn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.setChecked(!cb.isChecked());
                lib.settings().setVolumeNormalization(cb.isChecked());
            }
        });
        content.addView(vn);
        cb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lib.settings().setVolumeNormalization(cb.isChecked());
            }
        });

        choiceRow("默认播放模式", modeName(lib.settings().playMode()), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] modes = {"顺序播放", "随机播放", "列表循环", "单曲循环"};
                Ui.choose(act, "默认播放模式", modes, lib.settings().playMode(), new Ui.OnInput() {
                    @Override
                    public void onInput(String text) {
                        try {
                            lib.settings().setPlayMode(Integer.parseInt(text));
                        } catch (Exception ignored) {
                        }
                        act.refreshCurrent();
                    }
                });
            }
        });

        // 流量模式：控制「预取下一首 / 边播边存」在什么网络下做 —— 这是流量的大头
        choiceRow("流量模式", dataModeName(lib.settings().dataMode()), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] modes = {"省流（仅 WiFi 预取/缓存）", "标准（总是预取/缓存）", "关闭（不预取/不缓存）"};
                Ui.choose(act, "流量模式", modes, lib.settings().dataMode(), new Ui.OnInput() {
                    @Override
                    public void onInput(String text) {
                        try {
                            lib.settings().setDataMode(Integer.parseInt(text));
                        } catch (Exception ignored) {
                        }
                        act.refreshCurrent();
                    }
                });
            }
        });

        // 缓存用量 + 一键清空（上限 4GB，用户得看得见它吃了多少）
        choiceRow("播放缓存", playCacheText(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(act)
                        .setTitle("播放缓存")
                        .setMessage(playCacheText()
                                + "\n\n听过的曲子会存在这里，重播时零流量。\n清空后下次播放要重新下载。")
                        .setPositiveButton("清空缓存", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                long freed = com.lixscn.subsonicplayer.core.MediaCache.clear(act);
                                android.widget.Toast.makeText(act,
                                        "已清空 " + (freed / 1048576) + " MB",
                                        android.widget.Toast.LENGTH_SHORT).show();
                                act.refreshCurrent();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
    }

    private static String dataModeName(int m) {
        switch (m) {
            case 1:
                return "标准（总是预取/缓存）";
            case 2:
                return "关闭（不预取/不缓存）";
            default:
                return "省流（仅 WiFi）";
        }
    }

    private static String modeName(int m) {
        switch (m) {
            case 1:
                return "随机播放";
            case 2:
                return "列表循环";
            case 3:
                return "单曲循环";
            default:
                return "顺序播放";
        }
    }

    private void choiceRow(String label, final String value, View.OnClickListener l) {
        Theme.Colors c = Ui.colors(act);
        LinearLayout row = Ui.row(act);
        Ui.pad(row, act, 16, 14, 16, 14);
        TextView tv = Ui.text(act, label, 15, c.text);
        row.addView(tv, Ui.lpWeight(1));
        TextView v = Ui.text(act, value, 14, c.textDim);
        row.addView(v);
        row.addView(Ui.icon(act, R.drawable.ic_chevron, 18, c.textFaint),
                Ui.lp(Ui.dp(act, 20), Ui.dp(act, 20)));
        row.setOnClickListener(l);
        Ui.tappable(row, act, c.text);
        content.addView(row);
    }

    // ---------------- 存储 ----------------

    private void buildStorageSection() {
        final Theme.Colors c = Ui.colors(act);
        content.addView(Ui.sectionHeader(act, "存储", null, null));

        final TextView cacheInfo = Ui.text(act, cacheText(), 13, c.textDim);
        cacheInfo.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8));
        content.addView(cacheInfo);

        choiceRow("清除封面缓存", "立即清理", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CoverLoader.get(act).clearMemory();
                CoverLoader.get(act).clearDisk();
                cacheInfo.setText(cacheText());
                act.toast("封面缓存已清除");
            }
        });
        // ★ 一键缓存整个队列：专家确认这是唯一真正省电的杠杆 ——
        //   命中缓存的歌在蜂窝上播放几乎不开射频（44 分钟车程 203mAh → 5~15mAh）。
        choiceRow("缓存整个队列", "开始", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                com.lixscn.subsonicplayer.player.Player.get(act).cacheWholeQueue();
            }
        });
        choiceRow("清除列表缓存", "立即清理", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.library().clearCache();
                act.toast("列表缓存已清除");
            }
        });
        choiceRow("清除播放记录与书签", "立即清理", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.confirm(act, "清除本机记录", "将清空最近播放与书签（不影响服务端数据）", "清除", new Runnable() {
                    @Override
                    public void run() {
                        act.getSharedPreferences("sp_history", 0).edit().clear().apply();
                        act.toast("已清除");
                    }
                });
            }
        });
    }

    /** 封面磁盘缓存（CoverLoader）用量 */
    private String cacheText() {
        long bytes = CoverLoader.get(act).diskCacheSize();
        return "封面磁盘缓存：" + (bytes / 1024 / 1024) + " MB";
    }

    /**
     * **播放缓存**（{@code sp-cache}：边播边存 / 整段下载下来的音频）用量。
     *
     * <p>注意别和封面缓存混了：这里原来错用了 {@link #cacheText()}（封面），
     * 于是「播放缓存」一行显示的是封面的体积，用户根本看不到缓存吃了多少。
     */
    private String playCacheText() {
        return com.lixscn.subsonicplayer.core.MediaCache.sizeText(act)
                + " · " + com.lixscn.subsonicplayer.core.MediaCache.count(act) + " 首";
    }

    // ---------------- 关于 ----------------

    private void buildAboutSection() {
        Theme.Colors c = Ui.colors(act);
        content.addView(Ui.sectionHeader(act, "关于", null, null));

        LinearLayout box = Ui.column(act);
        Ui.pad(box, act, 16, 6, 16, 8);
        TextView t1 = Ui.text(act, "Subsonic Player for Android", 14, c.text);
        TextView t2 = Ui.text(act, "版本 " + appVersion() + " · 纯 Java 原生客户端", 12, c.textDim);
        t2.setPadding(0, Ui.dp(act, 4), 0, 0);
        TextView t3 = Ui.text(act, "支持 Subsonic / OpenSubsonic 兼容服务（Navidrome、Gonic、Jellyfin 等）",
                12, c.textDim);
        t3.setPadding(0, Ui.dp(act, 4), 0, 0);
        box.addView(t1);
        box.addView(t2);
        box.addView(t3);

        lib.run(new Library.Work<String>() {
            @Override
            public String run() {
                if (lib.client() == null) return "";
                org.json.JSONObject st = lib.client().getScanStatus();
                if (st == null) return "";
                int songs = st.optInt("songCount", 0);
                if (songs == 0) songs = st.optInt("count", 0);
                return "服务端：" + songs + " 首歌曲 / "
                        + st.optInt("albumCount", 0) + " 张专辑 / "
                        + st.optInt("artistCount", 0) + " 位艺术家";
            }
        }, new Library.Done<String>() {
            @Override
            public void ok(String value) {
                if (value != null && value.length() > 0) {
                    TextView t4 = Ui.text(act, value, 12, c.textFaint);
                    t4.setPadding(0, Ui.dp(act, 4), 0, 0);
                    box.addView(t4);
                }
            }

            @Override
            public void fail(String message) {
            }
        });
        content.addView(box);
    }

    /** 从包管理器读版本号：别再硬编码（改一次忘一次，关于页会一直是旧号） */
    private String appVersion() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }
}