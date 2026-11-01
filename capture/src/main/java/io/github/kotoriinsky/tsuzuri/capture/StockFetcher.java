package io.github.kotoriinsky.tsuzuri.capture;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;
import miui.contentcatcher.IViewContentFetcher;

/**
 * 原厂抓取器的独立加载。
 *
 * 另开一个只含原厂 ContentCatcher APK 的 PathClassLoader（父加载器是启动类加载器），
 * 这样原厂的 {@code com.miui.catcherpatch.ViewContentFetcher} 不会被本 APK 的同名类遮住。
 * 实例强转为启动类路径上的 {@link IViewContentFetcher}，跨加载器可用。
 */
public final class StockFetcher {
    static final String TAG = "FP-CatcherShadow";
    private static final String CATCHER_PKG = "com.miui.contentcatcher";
    private static final String FETCHER_CLS = "com.miui.catcherpatch.ViewContentFetcher";

    private final IViewContentFetcher fetcher;
    private final Method fetch3;

    private StockFetcher(IViewContentFetcher fetcher, Method fetch3) {
        this.fetcher = fetcher;
        this.fetch3 = fetch3;
    }

    public IViewContentFetcher fetcher() {
        return fetcher;
    }

    /** 失败返回 null；调用方此时什么都不返回，与原厂抓取失败时的表现一致。 */
    public static StockFetcher create() {
        long t0 = android.os.SystemClock.uptimeMillis();
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            ApplicationInfo ai = app.getPackageManager().getApplicationInfo(CATCHER_PKG, 0);
            ClassLoader boot = Context.class.getClassLoader();
            ClassLoader cl = new PathClassLoader(ai.sourceDir, ai.nativeLibraryDir, boot);
            Class<?> c = cl.loadClass(FETCHER_CLS);
            IViewContentFetcher f = (IViewContentFetcher) c.getConstructor().newInstance();
            Method m3 = c.getMethod("fetchViewContent", View.class, int.class, boolean.class);
            Log.i(TAG, "原厂抓取器已加载（" + (android.os.SystemClock.uptimeMillis() - t0)
                    + "ms）进程=" + app.getPackageName());
            return new StockFetcher(f, m3);
        } catch (Throwable t) {
            Log.w(TAG, "原厂抓取器加载失败，本次不抓取", t);
            return null;
        }
    }

    public Bundle fetchViewContent3(View view, int i, boolean z) {
        try {
            return (Bundle) fetch3.invoke(fetcher, view, i, z);
        } catch (Throwable t) {
            Log.w(TAG, "原厂 fetchViewContent(3) 失败", t);
            return null;
        }
    }
}
