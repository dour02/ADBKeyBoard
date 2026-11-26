package com.android.adbkeyboard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Accessibility service used by ADBKeyBoard for touch gestures, Toast logging,
 * and copying text exposed by the currently active window.
 *
 * ADB_SELECT_ALL_COPY does not use InputConnection and does not require an
 * editable/focused text field. Android has no universal API to visually select
 * all text in every widget; this action copies the text exposed by the active
 * window's AccessibilityNodeInfo tree.
 */
public class TouchAccessibilityService extends AccessibilityService {
    public static final String ACTION_TAP = "ADB_TOUCH_TAP";
    public static final String ACTION_DOUBLE_TAP = "ADB_TOUCH_DOUBLE_TAP";
    public static final String ACTION_LONG_PRESS = "ADB_TOUCH_LONG_PRESS";
    public static final String ACTION_SWIPE = "ADB_TOUCH_SWIPE";
    public static final String ACTION_TWO_FINGER_SWIPE = "ADB_TOUCH_SWIPE2";
    public static final String ACTION_PINCH = "ADB_TOUCH_PINCH";
    public static final String ACTION_ZOOM = "ADB_TOUCH_ZOOM";
    public static final String ACTION_MOUSE_WHEEL = "ADB_MOUSE_WHEEL";
    public static final String ACTION_SELECT_ALL_COPY = "ADB_SELECT_ALL_COPY";

    private static final String TAG = "AdbTouch";
    // adb shell and root can hold DUMP; ordinary applications normally cannot.
    private static final String SENDER_PERMISSION = "android.permission.DUMP";
    private static final int COPY_MAX_NODES = 4096;
    private static final int COPY_MAX_CHARS = 524288;

    // Toast log: /data/data/com.android.adbkeyboard/files/toast.log
    private static final String TOAST_LOG_NAME = "toast.log";
    private static final long TOAST_LOG_MAX_BYTES = 2 * 1024 * 1024;

    private BroadcastReceiver receiver;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private File toastLogFile;
    private HandlerThread logThread;
    private Handler logHandler;
    private final SimpleDateFormat tsFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        registerTouchReceiver();

        toastLogFile = new File(getFilesDir(), TOAST_LOG_NAME);
        logThread = new HandlerThread("toast-log");
        logThread.start();
        logHandler = new Handler(logThread.getLooper());
        Log.i(TAG, "TouchAccessibilityService connected");
    }

    @Override
    public void onDestroy() {
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Throwable ignored) {
                // The service may already be tearing down.
            }
            receiver = null;
        }
        if (logThread != null) {
            logThread.quitSafely();
            logThread = null;
            logHandler = null;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null
                || event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            return;
        }

        // Notification and Toast can both use this event type. Notifications
        // carry a Notification Parcelable; only log Toast text here.
        Parcelable data = event.getParcelableData();
        if (data instanceof Notification) {
            return;
        }

        final String pkg = event.getPackageName() == null
                ? "?" : event.getPackageName().toString();
        final String text = joinText(event.getText());
        if (text.isEmpty()) {
            return;
        }

        final String line = tsFormat.format(new Date()) + "\t" + pkg + "\t" + text + "\n";
        Handler h = logHandler;
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    appendLog(line);
                }
            });
        }
    }

    private String joinText(List<CharSequence> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : parts) {
            if (cs == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(cs);
        }
        // One Toast per line in the log file.
        return sb.toString().replace('\n', ' ').replace('\r', ' ')
                .replace('\t', ' ').trim();
    }

    private void appendLog(String line) {
        try {
            File f = toastLogFile;
            if (f == null) {
                return;
            }
            if (f.exists() && f.length() > TOAST_LOG_MAX_BYTES) {
                // Simple rotation: truncate after 2 MB.
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            Writer w = new OutputStreamWriter(
                    new FileOutputStream(f, true), Charset.forName("UTF-8"));
            try {
                w.write(line);
                w.flush();
            } finally {
                w.close();
            }
        } catch (Throwable e) {
            Log.w("AdbToast", "write toast log failed", e);
        }
    }

    @Override
    public void onInterrupt() {
        // No persistent gesture is active when the service is interrupted.
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
        filter.addAction(ACTION_SELECT_ALL_COPY);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                try {
                    handle(intent);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to handle " + intent.getAction(), e);
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
                    dispatchTwoFingerSwipe(
                            x1s, y1s, x1e, y1e, x2s, y2s, x2e, y2e, duration);
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
                    return;
                }
                if (ACTION_SELECT_ALL_COPY.equals(action)) {
                    Log.i(TAG, "Received ADB_SELECT_ALL_COPY");
                    copyActiveWindowText();
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, SENDER_PERMISSION, null,
                    Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter, SENDER_PERMISSION, null);
        }
    }

    /**
     * Collect readable text from the active window and place it on the
     * clipboard. This does not require an editable field or input cursor.
     */
    private void copyActiveWindowText() {
        AccessibilityNodeInfo root = null;
        String windowPackage = "?";
        int visitedCount = 0;
        try {
            root = getRootInActiveWindow();
            if (root == null) {
                Log.w(TAG, "ADB_SELECT_ALL_COPY: active window has no accessibility root");
                return;
            }
            CharSequence packageName = root.getPackageName();
            if (packageName != null) {
                windowPackage = packageName.toString();
            }

            StringBuilder out = new StringBuilder();
            Set<String> seen = new LinkedHashSet<>();
            ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
            pending.add(new NodeDepth(root, 0));
            root = null; // The queued root is now recycled with the other nodes.

            while (!pending.isEmpty()
                    && visitedCount < COPY_MAX_NODES
                    && out.length() < COPY_MAX_CHARS) {
                NodeDepth entry = pending.removeFirst();
                AccessibilityNodeInfo node = entry.node;
                try {
                    visitedCount++;
                    boolean password = node.isPassword();
                    if (!password && node.isVisibleToUser()) {
                        CharSequence nodeText = node.getText();
                        CharSequence description = node.getContentDescription();
                        appendAccessibleText(nodeText, out, seen);
                        if (description != null && (nodeText == null
                                || !description.toString().contentEquals(nodeText))) {
                            appendAccessibleText(description, out, seen);
                        }
                    }

                    // Do not descend into password nodes, even if an app exposes
                    // their internal children separately.
                    if (!password && entry.depth < 80) {
                        int childCount = node.getChildCount();
                        for (int i = 0; i < childCount
                                && visitedCount + pending.size() < COPY_MAX_NODES; i++) {
                            AccessibilityNodeInfo child = null;
                            try {
                                child = node.getChild(i);
                                if (child != null) {
                                    pending.addLast(new NodeDepth(child, entry.depth + 1));
                                    child = null; // Ownership transferred to the queue.
                                }
                            } finally {
                                if (child != null) {
                                    child.recycle();
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "Unable to read an accessibility node", e);
                } finally {
                    try {
                        node.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }

            // Release any nodes that were queued after reaching the limits.
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.removeFirst().node;
                try {
                    node.recycle();
                } catch (Throwable ignored) {
                }
            }

            String text = out.toString().trim();
            if (text.isEmpty()) {
                Log.w(TAG, "ADB_SELECT_ALL_COPY: no accessible text in " + windowPackage
                        + " (visited " + visitedCount + " nodes)");
                return;
            }

            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                Log.e(TAG, "ADB_SELECT_ALL_COPY: ClipboardManager unavailable");
                return;
            }

            clipboard.setPrimaryClip(ClipData.newPlainText("ADBKeyBoard", text));

            // Also update the existing provider cache so root/shell can read
            // the result without launching ClipboardSyncActivity.
            SharedPreferences prefs = getSharedPreferences(
                    ClipboardProvider.PREF_NAME, Context.MODE_PRIVATE);
            boolean saved = prefs.edit()
                    .putString(ClipboardProvider.KEY_TEXT, text)
                    .putLong(ClipboardProvider.KEY_UPDATED_AT, System.currentTimeMillis())
                    .commit();

            Log.i(TAG, "ADB_SELECT_ALL_COPY: copied " + text.length() + " chars from "
                    + windowPackage + "; visited=" + visitedCount
                    + "; providerCache=" + (saved ? "saved" : "failed"));
        } catch (Throwable e) {
            Log.e(TAG, "ADB_SELECT_ALL_COPY failed", e);
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class NodeDepth {
        final AccessibilityNodeInfo node;
        final int depth;

        NodeDepth(AccessibilityNodeInfo node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }

    private void appendAccessibleText(
            CharSequence value, StringBuilder out, Set<String> seen) {
        if (value == null || out.length() >= COPY_MAX_CHARS) {
            return;
        }
        String item = value.toString().replace('\u0000', ' ').trim();
        if (item.isEmpty() || !seen.add(item)) {
            return;
        }
        if (out.length() > 0) {
            out.append('\n');
        }
        int remaining = COPY_MAX_CHARS - out.length();
        if (remaining <= 0) {
            return;
        }
        if (item.length() > remaining) {
            item = item.substring(0, remaining);
        }
        out.append(item);
    }

    private static float clampCoordinate(float value) {
        return Math.max(0f, value);
    }

    private void dispatchTap(float x, float y, long duration) {
        Path path = new Path();
        path.moveTo(clampCoordinate(x), clampCoordinate(y));
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(
                path, 0, Math.max(1L, duration)));
        dispatchGesture(builder.build(), null, null);
    }

    private void dispatchDoubleTap(float x, float y, long tapDuration, long gap) {
        Path path1 = new Path();
        path1.moveTo(clampCoordinate(x), clampCoordinate(y));
        Path path2 = new Path();
        path2.moveTo(clampCoordinate(x), clampCoordinate(y));

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(
                path1, 0, Math.max(1L, tapDuration)));
        builder.addStroke(new GestureDescription.StrokeDescription(
                path2,
                Math.max(1L, tapDuration + gap),
                Math.max(1L, tapDuration)));
        dispatchGesture(builder.build(), null, null);
    }

    private void dispatchSwipe(float x1, float y1, float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(clampCoordinate(x1), clampCoordinate(y1));
        path.lineTo(clampCoordinate(x2), clampCoordinate(y2));

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(
                path, 0, Math.max(1L, duration)));
        dispatchGesture(builder.build(), null, null);
    }

    private void dispatchTwoFingerSwipe(
            float x1s, float y1s, float x1e, float y1e,
            float x2s, float y2s, float x2e, float y2e,
            long duration) {
        Path path1 = new Path();
        path1.moveTo(clampCoordinate(x1s), clampCoordinate(y1s));
        path1.lineTo(clampCoordinate(x1e), clampCoordinate(y1e));

        Path path2 = new Path();
        path2.moveTo(clampCoordinate(x2s), clampCoordinate(y2s));
        path2.lineTo(clampCoordinate(x2e), clampCoordinate(y2e));

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(
                path1, 0, Math.max(1L, duration)));
        builder.addStroke(new GestureDescription.StrokeDescription(
                path2, 0, Math.max(1L, duration)));
        dispatchGesture(builder.build(), null, null);
    }

    private void dispatchPinchOrZoom(
            float cx, float cy, float startDistance, float endDistance, long duration) {
        float startHalf = Math.max(20f, startDistance / 2f);
        float endHalf = Math.max(20f, endDistance / 2f);

        Path path1 = new Path();
        path1.moveTo(clampCoordinate(cx - startHalf), clampCoordinate(cy));
        path1.lineTo(clampCoordinate(cx - endHalf), clampCoordinate(cy));
        Path path2 = new Path();
        path2.moveTo(clampCoordinate(cx + startHalf), clampCoordinate(cy));
        path2.lineTo(clampCoordinate(cx + endHalf), clampCoordinate(cy));

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(
                path1, 0, Math.max(1L, duration)));
        builder.addStroke(new GestureDescription.StrokeDescription(
                path2, 0, Math.max(1L, duration)));
        dispatchGesture(builder.build(), null, null);
    }

    private void dispatchMouseWheel(
            final float x, final float y, final int delta,
            final int steps, final long duration) {
        final int count = Math.max(1, Math.min(100, steps));
        final float distance = Math.max(40f, Math.min(320f, Math.abs(delta)));
        final boolean scrollDown = delta < 0;
        for (int i = 0; i < count; i++) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    float y2 = scrollDown ? (y - distance) : (y + distance);
                    try {
                        dispatchSwipe(x, y, x, y2, duration);
                    } catch (Exception e) {
                        Log.w(TAG, "bad wheel gesture", e);
                    }
                }
            }, i * (duration + 40L));
        }
    }

    private float getFloatExtra(Intent intent, String key, float defaultValue) {
        try {
            if (intent.hasExtra(key) && intent.getExtras() != null) {
                Object value = intent.getExtras().get(key);
                if (value instanceof Float) {
                    return (Float) value;
                }
                if (value instanceof Double) {
                    return ((Double) value).floatValue();
                }
                if (value instanceof Integer) {
                    return ((Integer) value).floatValue();
                }
                if (value instanceof Long) {
                    return ((Long) value).floatValue();
                }
                if (value instanceof String) {
                    return Float.parseFloat((String) value);
                }
            }
        } catch (Throwable ignored) {
            // Use default for malformed values.
        }
        return defaultValue;
    }

    private long getLongExtra(Intent intent, String key, long defaultValue) {
        try {
            if (intent.hasExtra(key) && intent.getExtras() != null) {
                Object value = intent.getExtras().get(key);
                if (value instanceof Long) {
                    return (Long) value;
                }
                if (value instanceof Integer) {
                    return ((Integer) value).longValue();
                }
                if (value instanceof String) {
                    return Long.parseLong((String) value);
                }
            }
        } catch (Throwable ignored) {
            // Use default for malformed values.
        }
        return defaultValue;
    }
}
