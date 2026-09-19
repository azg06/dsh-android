package com.dsh.harness;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * 把设备上的 APK 以 content:// 交给系统安装器的只读出口。
 *
 * Android 7 起禁止用 file:// 把文件递给别的应用，否则抛 FileUriExposedException —— 
 * 自更新安装必然踩到。这里只放行 .apk 且限定在应用目录或公共存储之内。
 */
public class ApkInstallProvider extends ContentProvider {

    public static final String AUTHORITY = "com.dsh.harness.apk";

    public static Uri uriFor(Context ctx, File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(file.getAbsolutePath()));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            throw new FileNotFoundException("空路径");
        }
        File file = new File(path);
        if (!file.isFile() || !file.getName().toLowerCase().endsWith(".apk")) {
            throw new FileNotFoundException("只允许读取 APK 文件");
        }
        Context ctx = getContext();
        if (ctx != null) {
            boolean allowed = false;
            try {
                String canonical = file.getCanonicalPath();
                allowed = canonical.startsWith(HarnessPaths.workspace(ctx).getCanonicalPath())
                        || canonical.startsWith(Environment.getExternalStorageDirectory().getCanonicalPath())
                        || canonical.startsWith(ctx.getFilesDir().getCanonicalPath());
            } catch (IOException ignored) {
                allowed = false;
            }
            if (!allowed) {
                throw new FileNotFoundException("路径不在允许范围内");
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
