package com.android.adbkeyboard;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

public class ClipboardSyncActivity extends Activity {
    private static final String TAG = "ClipboardSync";

    // 焦点到达后先留出时间让系统切换前台窗口，再尝试读取剪贴板。
    private static final long FIRST_READ_DELAY_MS = 120L;
    private static final long RETRY_DELAY_MS = 100L;
    private static final long FOCUS_TIMEOUT_MS = 2500L;
    private static final int MAX_READ_ATTEMPTS = 15;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean windowFocused;
    private boolean finished;
    private int readAttempts;

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            trySyncAndFinish();
        }
    };

    private final Runnable focusTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (finished) {
                return;
            }
            if (windowFocused) {
                trySyncAndFinish();
            } else {
                // 没有取得窗口焦点时不能可靠读取剪贴板；保留上一次缓存，避免写入假空值。
                finishWithoutUpdatingCache();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new View(this));
        handler.postDelayed(focusTimeoutRunnable, FOCUS_TIMEOUT_MS);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        windowFocused = hasFocus;
        if (hasFocus && !finished) {
            scheduleAttempt(FIRST_READ_DELAY_MS);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void scheduleAttempt(long delayMs) {
        handler.removeCallbacks(retryRunnable);
        handler.postDelayed(retryRunnable, delayMs);
    }

    private void trySyncAndFinish() {
        if (finished || isFinishing()) {
            return;
        }

        readAttempts++;

        // 剪贴板读取需要本 Activity 真正处于前台并持有窗口焦点。
        if (!windowFocused || getWindow() == null
                || !getWindow().getDecorView().hasWindowFocus()) {
            if (readAttempts < MAX_READ_ATTEMPTS) {
                scheduleAttempt(RETRY_DELAY_MS);
            } else {
                finishWithoutUpdatingCache();
            }
            return;
        }

        ClipboardReadResult result = readClipboardText();
        if (!result.success) {
            if (readAttempts < MAX_READ_ATTEMPTS) {
                scheduleAttempt(RETRY_DELAY_MS);
            } else {
                Log.w(TAG, "Clipboard read failed after " + readAttempts
                        + " attempts; keeping the previous cached value");
                finishWithoutUpdatingCache();
            }
            return;
        }

        SharedPreferences sp = getSharedPreferences(
                ClipboardProvider.PREF_NAME, Context.MODE_PRIVATE);
        boolean committed = sp.edit()
                .putString(ClipboardProvider.KEY_TEXT, result.text)
                .putLong(ClipboardProvider.KEY_UPDATED_AT, System.currentTimeMillis())
                .commit();

        if (!committed) {
            Log.w(TAG, "Could not persist clipboard cache");
            if (readAttempts < MAX_READ_ATTEMPTS) {
                scheduleAttempt(RETRY_DELAY_MS);
            } else {
                finishWithoutUpdatingCache();
            }
            return;
        }

        finishSuccessfully();
    }

    private ClipboardReadResult readClipboardText() {
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return ClipboardReadResult.failure();
            }

            // null/无条目表示一次成功的空剪贴板读取，与“读取被系统拒绝”区分开。
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return ClipboardReadResult.success("");
            }

            ClipData.Item item = clip.getItemAt(0);
            if (item == null) {
                return ClipboardReadResult.success("");
            }

            CharSequence text = item.getText();
            if (text != null) {
                return ClipboardReadResult.success(text.toString());
            }

            CharSequence coerced = item.coerceToText(this);
            if (coerced != null) {
                return ClipboardReadResult.success(coerced.toString());
            }

            String html = item.getHtmlText();
            if (html != null) {
                return ClipboardReadResult.success(html);
            }

            if (item.getUri() != null) {
                return ClipboardReadResult.success(item.getUri().toString());
            }

            return ClipboardReadResult.success("");
        } catch (SecurityException e) {
            Log.w(TAG, "Clipboard access was denied", e);
            return ClipboardReadResult.failure();
        } catch (RuntimeException e) {
            Log.w(TAG, "Clipboard service temporarily unavailable", e);
            return ClipboardReadResult.failure();
        }
    }

    private void finishSuccessfully() {
        if (finished) {
            return;
        }
        finished = true;
        handler.removeCallbacksAndMessages(null);
        finish();
        overridePendingTransition(0, 0);
    }

    private void finishWithoutUpdatingCache() {
        if (finished) {
            return;
        }
        finished = true;
        handler.removeCallbacksAndMessages(null);
        finish();
        overridePendingTransition(0, 0);
    }

    private static final class ClipboardReadResult {
        final boolean success;
        final String text;

        private ClipboardReadResult(boolean success, String text) {
            this.success = success;
            this.text = text;
        }

        static ClipboardReadResult success(String text) {
            return new ClipboardReadResult(true, text == null ? "" : text);
        }

        static ClipboardReadResult failure() {
            return new ClipboardReadResult(false, "");
        }
    }
}
