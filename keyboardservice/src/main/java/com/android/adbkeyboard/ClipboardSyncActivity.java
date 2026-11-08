package com.android.adbkeyboard;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

public class ClipboardSyncActivity extends Activity {

    private boolean synced = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = new View(this);
        setContentView(view);

        view.post(new Runnable() {
            @Override
            public void run() {
                trySyncAndFinish();
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            trySyncAndFinish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                trySyncAndFinish();
            }
        }, 120);
    }

    private void trySyncAndFinish() {
        if (synced) {
            return;
        }
        synced = true;

        String text = readClipboardText();

        SharedPreferences sp = getSharedPreferences(
                ClipboardProvider.PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putString(ClipboardProvider.KEY_TEXT, text == null ? "" : text)
                .putLong(ClipboardProvider.KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();

        finish();
        overridePendingTransition(0, 0);
    }

    private String readClipboardText() {
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return "";
            }

            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                ClipData.Item item = clip.getItemAt(0);
                if (item != null) {
                    CharSequence text = item.getText();
                    if (text != null) {
                        return text.toString();
                    }

                    CharSequence coerced = item.coerceToText(this);
                    if (coerced != null) {
                        return coerced.toString();
                    }

                    String html = item.getHtmlText();
                    if (html != null) {
                        return html;
                    }

                    if (item.getUri() != null) {
                        return item.getUri().toString();
                    }
                }
            }

            CharSequence oldText = cm.getText();
            return oldText == null ? "" : oldText.toString();
        } catch (Throwable e) {
            return "";
        }
    }
}
