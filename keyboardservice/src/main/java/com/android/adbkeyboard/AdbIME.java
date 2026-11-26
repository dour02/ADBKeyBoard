package com.android.adbkeyboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import android.os.Handler;
import java.nio.charset.StandardCharsets;

public class AdbIME extends InputMethodService {
    private static final String TAG = "AdbIME";

    private static final String IME_MESSAGE = "ADB_INPUT_TEXT";
    private static final String IME_CHARS = "ADB_INPUT_CHARS";
    private static final String IME_KEYCODE = "ADB_INPUT_CODE";
    private static final String IME_EDITORCODE = "ADB_EDITOR_CODE";
    private static final String IME_MESSAGE_B64 = "ADB_INPUT_B64";
    private static final String IME_CLEAR_TEXT = "ADB_CLEAR_TEXT";
    private static final String IME_ACTION_SEARCH = "ADB_ACTION_SEARCH";
    private static final String IME_ACTION_GO = "ADB_ACTION_GO";
    private static final String IME_ACTION_DONE = "ADB_ACTION_DONE";
    private static final String IME_ACTION_NEXT = "ADB_ACTION_NEXT";
    private static final String IME_ACTION_SEND = "ADB_ACTION_SEND";
    private static final String IME_SELECT_ALL = "ADB_SELECT_ALL";
    private static final String IME_COPY = "ADB_COPY";

    // adb shell 持有 DUMP 权限；普通第三方 App 通常不持有，避免任意应用向输入框注入内容。
    private static final String SENDER_PERMISSION = "android.permission.DUMP";

    private BroadcastReceiver mReceiver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        registerAdbReceiver();
    }

    private void registerAdbReceiver() {
        if (mReceiver != null) {
            return;
        }

        IntentFilter filter = new IntentFilter(IME_MESSAGE);
        filter.addAction(IME_CHARS);
        filter.addAction(IME_KEYCODE);
        filter.addAction(IME_EDITORCODE);
        filter.addAction(IME_MESSAGE_B64);
        filter.addAction(IME_CLEAR_TEXT);
        filter.addAction(IME_ACTION_SEARCH);
        filter.addAction(IME_ACTION_GO);
        filter.addAction(IME_ACTION_DONE);
        filter.addAction(IME_ACTION_NEXT);
        filter.addAction(IME_ACTION_SEND);
        filter.addAction(IME_SELECT_ALL);
        filter.addAction(IME_COPY);

        mReceiver = new AdbReceiver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    mReceiver,
                    filter,
                    SENDER_PERMISSION,
                    null,
                    Context.RECEIVER_EXPORTED
            );
        } else {
            registerReceiver(mReceiver, filter, SENDER_PERMISSION, null);
        }
    }

    @Override
    public View onCreateInputView() {
        View view = getLayoutInflater().inflate(R.layout.view, null);
        View switchBtn = view.findViewById(R.id.switch_ime);
        if (switchBtn != null) {
            switchBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    InputMethodManager imm =
                            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showInputMethodPicker();
                    }
                }
            });
            switchBtn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    switchToNextIme();
                    return true;
                }
            });
        }
        return view;
    }

    private void switchToNextIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false);
            } else {
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && getWindow() != null && getWindow().getWindow() != null) {
                    IBinder token = getWindow().getWindow().getAttributes().token;
                    imm.switchToNextInputMethod(token, false);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "switch ime failed", e);
        }
    }

    @Override
    public void onDestroy() {
        if (mReceiver != null) {
            try {
                unregisterReceiver(mReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver may already have been unregistered during service teardown.
            }
            mReceiver = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void sendKeyEventPair(InputConnection ic, int keyCode, int metaState) {
        if (ic == null) {
            return;
        }

        long downTime = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(
                downTime,
                downTime,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                metaState,
                0,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD
        );
        ic.sendKeyEvent(down);

        long upTime = Math.max(downTime, SystemClock.uptimeMillis());
        KeyEvent up = new KeyEvent(
                downTime,
                upTime,
                KeyEvent.ACTION_UP,
                keyCode,
                0,
                metaState,
                0,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD
        );
        ic.sendKeyEvent(up);
    }

    private int parseMetaState(String value) {
        int metaState = 0;
        String[] parts = value.split("\\+");
        for (String part : parts) {
            String token = part.trim();
            if (!token.isEmpty()) {
                metaState |= Integer.parseInt(token);
            }
        }
        return metaState;
    }

    private void sendMetaCodeSequence(InputConnection ic, String metaCodes) {
        String[] entries = metaCodes.split(",");
        if ((entries.length & 1) != 0) {
            Log.w(TAG, "Ignoring unmatched trailing item in mcode: " + metaCodes);
        }
        for (int i = 0; i + 1 < entries.length; i += 2) {
            int metaState = parseMetaState(entries[i].trim());
            int keyCode = Integer.parseInt(entries[i + 1].trim());
            sendKeyEventPair(ic, keyCode, metaState);
        }
    }

    private boolean performSelectAll(InputConnection ic) {
        if (ic == null) {
            return false;
        }
        boolean handled = ic.performContextMenuAction(android.R.id.selectAll);
        if (!handled) {
            // 某些编辑器不实现 context-menu action，回退到 Ctrl+A。
            sendKeyEventPair(ic, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON);
        }
        return handled;
    }

    private boolean performCopy(InputConnection ic) {
        if (ic == null) {
            return false;
        }
        boolean handled = ic.performContextMenuAction(android.R.id.copy);
        if (!handled) {
            sendKeyEventPair(ic, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON);
        }
        return handled;
    }

    private void clearInputText(InputConnection ic) {
        if (ic == null) {
            return;
        }
        // 先使用编辑器原生的“全选”，失败时 performSelectAll 会回退到 Ctrl+A。
        performSelectAll(ic);
        ic.commitText("", 1);
    }

    class AdbReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            try {
                handle(intent);
            } catch (Exception e) {
                // 错误参数或编辑器异常不应导致输入法进程退出。
                Log.w(TAG, "Failed to handle broadcast " + intent.getAction(), e);
            }
        }

        private void handle(Intent intent) {
            String action = intent.getAction();
            InputConnection ic;

            if (IME_MESSAGE.equals(action)) {
                String msg = intent.getStringExtra("msg");
                if (msg != null) {
                    ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(msg, 1);
                    }
                }
                String metaCodes = intent.getStringExtra("mcode");
                if (metaCodes != null) {
                    ic = getCurrentInputConnection();
                    if (ic != null) {
                        sendMetaCodeSequence(ic, metaCodes);
                    }
                }
                return;
            }

            if (IME_MESSAGE_B64.equals(action)) {
                String data = intent.getStringExtra("msg");
                if (data == null) {
                    return;
                }
                byte[] decoded = Base64.decode(data, Base64.DEFAULT);
                String msg = new String(decoded, StandardCharsets.UTF_8);
                ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.commitText(msg, 1);
                }
                return;
            }

            if (IME_CHARS.equals(action)) {
                int[] chars = intent.getIntArrayExtra("chars");
                if (chars != null) {
                    String msg = new String(chars, 0, chars.length);
                    ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(msg, 1);
                    }
                }
                return;
            }

            if (IME_KEYCODE.equals(action)) {
                int code = intent.getIntExtra("code", -1);
                if (code != -1) {
                    sendKeyEventPair(getCurrentInputConnection(), code, 0);
                }
                return;
            }

            if (IME_EDITORCODE.equals(action)) {
                int code = intent.getIntExtra("code", -1);
                if (code != -1) {
                    ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.performEditorAction(code);
                    }
                }
                return;
            }

            if (IME_CLEAR_TEXT.equals(action)) {
                clearInputText(getCurrentInputConnection());
                return;
            }

            if (IME_SELECT_ALL.equals(action)) {
                performSelectAll(getCurrentInputConnection());
                return;
            }

            if (IME_COPY.equals(action)) {
                performCopy(getCurrentInputConnection());
                return;
            }


            if (IME_ACTION_SEARCH.equals(action)) {
                performEditorAction(EditorInfo.IME_ACTION_SEARCH);
                return;
            }
            if (IME_ACTION_GO.equals(action)) {
                performEditorAction(EditorInfo.IME_ACTION_GO);
                return;
            }
            if (IME_ACTION_DONE.equals(action)) {
                performEditorAction(EditorInfo.IME_ACTION_DONE);
                return;
            }
            if (IME_ACTION_NEXT.equals(action)) {
                performEditorAction(EditorInfo.IME_ACTION_NEXT);
                return;
            }
            if (IME_ACTION_SEND.equals(action)) {
                performEditorAction(EditorInfo.IME_ACTION_SEND);
            }
        }

        private void performEditorAction(int action) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.performEditorAction(action);
            }
        }
    }
}
