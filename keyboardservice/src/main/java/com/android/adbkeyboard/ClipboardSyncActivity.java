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

    // Android 10+ 只有拿到窗口焦点的前台应用才能读剪贴板，
    // 所以必须等 onWindowFocusChanged(true) 之后再读，不能在 onCreate/onResume 里提前读。
    private static final long FOCUS_TIMEOUT_MS = 1500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new View(this));

        // 兜底：一直拿不到焦点（比如锁屏）时也要退出，别让透明 Activity 挡在前台
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                trySyncAndFinish();
            }
        }, FOCUS_TIMEOUT_MS);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 焦点刚到时剪贴板服务可能还没放行，稍等一帧
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    trySyncAndFinish();
                }
            }, 50);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void trySyncAndFinish() {
        if (synced || isFinishing()) {
            return;
        }
        synced = true;

        String text = readClipboardText();

        SharedPreferences sp = getSharedPreferences(
                ClipboardProvider.PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putString(ClipboardProvider.KEY_TEXT, text == null ? "" : text)
                .putLong(ClipboardProvider.KEY_UPDATED_AT, System.currentTimeMillis())
                .commit();   // commit：进程马上可能被回收，要保证写入落盘

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
