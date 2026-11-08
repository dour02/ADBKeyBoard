package com.android.adbkeyboard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class TouchAccessibilityService extends AccessibilityService {

    public static final String ACTION_TAP = "ADB_TOUCH_TAP";
    public static final String ACTION_DOUBLE_TAP = "ADB_TOUCH_DOUBLE_TAP";
    public static final String ACTION_LONG_PRESS = "ADB_TOUCH_LONG_PRESS";
    public static final String ACTION_SWIPE = "ADB_TOUCH_SWIPE";
    public static final String ACTION_TWO_FINGER_SWIPE = "ADB_TOUCH_SWIPE2";
    public static final String ACTION_PINCH = "ADB_TOUCH_PINCH";
    public static final String ACTION_ZOOM = "ADB_TOUCH_ZOOM";
    public static final String ACTION_MOUSE_WHEEL = "ADB_MOUSE_WHEEL";

    // 发送方必须持有这个权限：adb shell 和 root 默认持有，普通 App 拿不到。
    // 否则手机上任何 App 发个广播就能借无障碍服务替你点屏幕。
    // 如果要让 Tasker 之类的 App 也能调用，把它改成 null（会失去这层保护）。
    private static final String SENDER_PERMISSION = "android.permission.DUMP";

    private BroadcastReceiver receiver;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        registerTouchReceiver();
    }

    @Override
    public void onDestroy() {
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            receiver = null;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private void registerTouchReceiver() {
        if (receiver != null) {
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TAP);
        filter.addAction(ACTION_DOUBLE_TAP);
        filter.addAction(ACTION_LONG_PRESS);
        filter.addAction(ACTION_SWIPE);
        filter.addAction(ACTION_TWO_FINGER_SWIPE);
        filter.addAction(ACTION_PINCH);
        filter.addAction(ACTION_ZOOM);
        filter.addAction(ACTION_MOUSE_WHEEL);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    handle(intent);
                } catch (Exception e) {
                    // 坐标为负、时长超过系统上限等都会抛异常，不能让服务崩溃
                    Log.w("AdbTouch", "bad gesture " + intent.getAction() + ": " + e);
                }
            }

            private void handle(Intent intent) {
                String action = intent.getAction();
                if (action == null) {
                    return;
                }

                if (ACTION_TAP.equals(action)) {
                    float x = getFloatExtra(intent, "x", 500f);
                    float y = getFloatExtra(intent, "y", 500f);
                    long duration = getLongExtra(intent, "duration", 60L);
                    dispatchTap(x, y, duration);
                    return;
                }

                if (ACTION_DOUBLE_TAP.equals(action)) {
                    float x = getFloatExtra(intent, "x", 500f);
                    float y = getFloatExtra(intent, "y", 500f);
                    long tapDuration = getLongExtra(intent, "duration", 50L);
                    long gap = getLongExtra(intent, "gap", 90L);
                    dispatchDoubleTap(x, y, tapDuration, gap);
                    return;
                }

                if (ACTION_LONG_PRESS.equals(action)) {
                    float x = getFloatExtra(intent, "x", 500f);
                    float y = getFloatExtra(intent, "y", 500f);
                    long duration = getLongExtra(intent, "duration", 700L);
                    dispatchTap(x, y, duration);
                    return;
                }

                if (ACTION_SWIPE.equals(action)) {
                    float x1 = getFloatExtra(intent, "x1", 300f);
                    float y1 = getFloatExtra(intent, "y1", 800f);
                    float x2 = getFloatExtra(intent, "x2", 300f);
                    float y2 = getFloatExtra(intent, "y2", 300f);
                    long duration = getLongExtra(intent, "duration", 300L);
                    dispatchSwipe(x1, y1, x2, y2, duration);
                    return;
                }

                if (ACTION_TWO_FINGER_SWIPE.equals(action)) {
                    float x1s = getFloatExtra(intent, "x1s", 300f);
                    float y1s = getFloatExtra(intent, "y1s", 900f);
                    float x1e = getFloatExtra(intent, "x1e", 300f);
                    float y1e = getFloatExtra(intent, "y1e", 300f);

                    float x2s = getFloatExtra(intent, "x2s", 700f);
                    float y2s = getFloatExtra(intent, "y2s", 900f);
                    float x2e = getFloatExtra(intent, "x2e", 700f);
                    float y2e = getFloatExtra(intent, "y2e", 300f);

                    long duration = getLongExtra(intent, "duration", 300L);
                    dispatchTwoFingerSwipe(x1s, y1s, x1e, y1e, x2s, y2s, x2e, y2e, duration);
                    return;
                }

                if (ACTION_PINCH.equals(action)) {
                    float cx = getFloatExtra(intent, "cx", 540f);
                    float cy = getFloatExtra(intent, "cy", 960f);
                    float startDistance = getFloatExtra(intent, "start_distance", 500f);
                    float endDistance = getFloatExtra(intent, "end_distance", 180f);
                    long duration = getLongExtra(intent, "duration", 250L);
                    dispatchPinchOrZoom(cx, cy, startDistance, endDistance, duration);
                    return;
                }

                if (ACTION_ZOOM.equals(action)) {
                    float cx = getFloatExtra(intent, "cx", 540f);
                    float cy = getFloatExtra(intent, "cy", 960f);
                    float startDistance = getFloatExtra(intent, "start_distance", 180f);
                    float endDistance = getFloatExtra(intent, "end_distance", 500f);
                    long duration = getLongExtra(intent, "duration", 250L);
                    dispatchPinchOrZoom(cx, cy, startDistance, endDistance, duration);
                    return;
                }

                if (ACTION_MOUSE_WHEEL.equals(action)) {
                    float x = getFloatExtra(intent, "x", 500f);
                    float y = getFloatExtra(intent, "y", 1000f);
                    int delta = intent.getIntExtra("delta", -120);
                    int steps = intent.getIntExtra("steps", 1);
                    long duration = getLongExtra(intent, "duration", 140L);
                    dispatchMouseWheel(x, y, delta, steps, duration);
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter, SENDER_PERMISSION, null);
        }
    }

    private static float c(float v) {
        return Math.max(0f, v);
    }

    private void dispatchTap(float x, float y, long duration) {
        Path path = new Path();
        path.moveTo(c(x), c(y));

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1L, duration)));
        dispatchGesture(builder.build(), null, null);
    }

    private void dispatchDoubleTap(float x, float y, long tapDuration, long gap) {
        Path path1 = new Path();
        path1.moveTo(c(x), c(y));

        Path path2 = new Path();
        path2.moveTo(c(x), c(y));

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path1, 0, Math.max(1L, tapDuration)));
        builder.addStroke(new GestureDescription.StrokeDescription(
                path2,
                Math.max(1L, tapDuration + gap),
                Math.max(1L, tapDuration)
        ));
        dispatchGesture(builder.build(), null, null);
    }

    private void dispatchSwipe(float x1, float y1, float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(c(x1), c(y1));
        path.lineTo(c(x2), c(y2));

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1L, duration)));
        dispatchGesture(builder.build(), null, null);
    }

    private void dispatchTwoFingerSwipe(
            float x1s, float y1s, float x1e, float y1e,
            float x2s, float y2s, float x2e, float y2e,
            long duration
    ) {
        Path path1 = new Path();
        path1.moveTo(c(x1s), c(y1s));
        path1.lineTo(c(x1e), c(y1e));

        Path path2 = new Path();
        path2.moveTo(c(x2s), c(y2s));
        path2.lineTo(c(x2e), c(y2e));

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path1, 0, Math.max(1L, duration)));
        builder.addStroke(new GestureDescription.StrokeDescription(path2, 0, Math.max(1L, duration)));
        dispatchGesture(builder.build(), null, null);
    }

    private void dispatchPinchOrZoom(float cx, float cy, float startDistance, float endDistance, long duration) {
        float startHalf = Math.max(20f, startDistance / 2f);
        float endHalf = Math.max(20f, endDistance / 2f);

        Path path1 = new Path();
        path1.moveTo(c(cx - startHalf), c(cy));
        path1.lineTo(c(cx - endHalf), c(cy));

        Path path2 = new Path();
        path2.moveTo(c(cx + startHalf), c(cy));
        path2.lineTo(c(cx + endHalf), c(cy));

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path1, 0, Math.max(1L, duration)));
        builder.addStroke(new GestureDescription.StrokeDescription(path2, 0, Math.max(1L, duration)));
        dispatchGesture(builder.build(), null, null);
    }

    private void dispatchMouseWheel(
            final float x,
            final float y,
            final int delta,
            final int steps,
            final long duration
    ) {
        final int count = Math.max(1, steps);
        final float distance = Math.max(40f, Math.min(320f, Math.abs(delta)));
        final boolean scrollDown = delta < 0;

        for (int i = 0; i < count; i++) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    float x1 = x;
                    float y1 = y;
                    float x2 = x;
                    float y2 = scrollDown ? (y - distance) : (y + distance);
                    try {
                        dispatchSwipe(x1, y1, x2, y2, duration);
                    } catch (Exception e) {
                        Log.w("AdbTouch", "bad wheel gesture: " + e);
                    }
                }
            }, i * (duration + 40L));
        }
    }

    private float getFloatExtra(Intent intent, String key, float defValue) {
        try {
            if (intent.hasExtra(key)) {
                Object obj = intent.getExtras().get(key);
                if (obj instanceof Float) {
                    return (Float) obj;
                }
                if (obj instanceof Double) {
                    return ((Double) obj).floatValue();
                }
                if (obj instanceof Integer) {
                    return ((Integer) obj).floatValue();
                }
                if (obj instanceof Long) {
                    return ((Long) obj).floatValue();
                }
                if (obj instanceof String) {
                    return Float.parseFloat((String) obj);
                }
            }
        } catch (Throwable ignored) {
        }
        return defValue;
    }

    private long getLongExtra(Intent intent, String key, long defValue) {
        try {
            if (intent.hasExtra(key)) {
                Object obj = intent.getExtras().get(key);
                if (obj instanceof Long) {
                    return (Long) obj;
                }
                if (obj instanceof Integer) {
                    return ((Integer) obj).longValue();
                }
                if (obj instanceof String) {
                    return Long.parseLong((String) obj);
                }
            }
        } catch (Throwable ignored) {
        }
        return defValue;
    }
}
