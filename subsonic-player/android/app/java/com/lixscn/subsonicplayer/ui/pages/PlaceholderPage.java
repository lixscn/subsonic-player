package com.lixscn.subsonicplayer.ui.pages;

import android.view.View;

import com.lixscn.subsonicplayer.ui.Page;
import com.lixscn.subsonicplayer.ui.Ui;

/** 占位页（页面未接线/未实现时用，避免外壳出现空白）。 */
public class PlaceholderPage extends Page {

    private final String title;
    private final String message;

    public PlaceholderPage(String title, String message) {
        this.title = title;
        this.message = message;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    protected View build() {
        return Ui.message(act, message == null ? "建设中" : message, "", null, null);
    }
}
