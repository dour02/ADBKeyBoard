package com.android.adbkeyboard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

public class ClipboardProvider extends ContentProvider {

    public static final String AUTHORITY = "com.android.adbkeyboard.clipboard";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/clip");

    public static final String PREF_NAME = "adb_keyboard_clipboard_cache";
    public static final String KEY_TEXT = "text";
    public static final String KEY_UPDATED_AT = "updated_at";

    private static final int ROOT_UID = 0;
    private static final int SHELL_UID = 2000;

    @Override
    public boolean onCreate() {
        return true;
    }

    // provider 是 exported 的，不做限制的话手机上任何 App 都能读写这里缓存的剪贴板内容。
    // 只放行 adb shell、root 和本应用自己。
    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid != ROOT_UID && uid != SHELL_UID && uid != Process.myUid()) {
            throw new SecurityException("Only adb shell or root may access " + AUTHORITY);
        }
    }

    @Override
    public String getType(Uri uri) {
        if (uri == null) {
            return null;
        }
        String path = uri.getPath();
        if ("/clip".equals(path)) {
            return "vnd.android.cursor.item/vnd.com.android.adbkeyboard.clip";
        }
        return null;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        enforceCaller();
        if (uri == null) {
            throw new IllegalArgumentException("uri == null");
        }

        String path = uri.getPath();
        if (!"/clip".equals(path)) {
            throw new IllegalArgumentException("Unsupported uri: " + uri);
        }

        SharedPreferences sp = getContext().getSharedPreferences(PREF_NAME, 0);
        String text = sp.getString(KEY_TEXT, "");
        long updatedAt = sp.getLong(KEY_UPDATED_AT, 0L);

        MatrixCursor cursor = new MatrixCursor(new String[] {
                "_id",
                "text",
                "updated_at"
        });
        cursor.addRow(new Object[] {
                1,
                text,
                updatedAt
        });
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("insert not supported: " + uri);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        enforceCaller();
        if (uri == null) {
            throw new IllegalArgumentException("uri == null");
        }

        String path = uri.getPath();
        if (!"/clip".equals(path)) {
            throw new IllegalArgumentException("Unsupported uri: " + uri);
        }

        SharedPreferences sp = getContext().getSharedPreferences(PREF_NAME, 0);
        sp.edit()
                .putString(KEY_TEXT, "")
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
        return 1;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        enforceCaller();
        if (uri == null) {
            throw new IllegalArgumentException("uri == null");
        }

        String path = uri.getPath();
        if (!"/clip".equals(path)) {
            throw new IllegalArgumentException("Unsupported uri: " + uri);
        }

        if (values == null) {
            return 0;
        }

        String text = values.getAsString("text");
        if (text == null) {
            text = "";
        }

        SharedPreferences sp = getContext().getSharedPreferences(PREF_NAME, 0);
        sp.edit()
                .putString(KEY_TEXT, text)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
        return 1;
    }
}
