package com.miui.catcherpatch;

import android.os.Bundle;
import android.view.View;
import io.github.kotoriinsky.tsuzuri.capture.SourceBitmapEnhancer;
import io.github.kotoriinsky.tsuzuri.capture.StockFetcher;
import java.util.Map;
import miui.contentcatcher.IViewContentFetcher;
import miui.contentcatcher.sdk.Token;

/**
 * 同名包装类：系统按这个类名从 ContentCatcher 的包上下文加载抓取器，
 * 本 APK 排在原厂 APK 前面，所以加载到的是这里。
 *
 * 所有方法原样转交原厂抓取器（另开 ClassLoader 加载，见 {@link StockFetcher}）；
 * 只在返回的 Bundle 带位图时，尝试换成 View 绘制时用到的源位图。
 * 原厂实现一行都不复制，只依赖其公开签名。
 */
public class ViewContentFetcher implements IViewContentFetcher {
    // 与原厂同名同值的常量：调用方编译时已内联，这里保留只为签名一致
    public static final String BUNDLE_CATCH_TYPE = "catchType";
    public static final String BUNDLE_VIEW_BITMAP = "viewBitmap";
    public static final String BUNDLE_VIEW_CONTENT = "viewContent";

    private final StockFetcher stock;

    public ViewContentFetcher() {
        stock = StockFetcher.create();
        // 每个 Activity 建拦截器时都会实例化本类：借这个时机在进程里挂上全局识别的区域取图接收端
        io.github.kotoriinsky.tsuzuri.capture.SourceImageServer.ensureRegistered();
    }

    @Override
    public void createFetcher(Token token, Map<String, String> map) {
        if (stock != null) stock.fetcher().createFetcher(token, map);
    }

    @Override
    public Bundle fetchViewContent(View view, int i) {
        if (stock == null) return null;
        return SourceBitmapEnhancer.enhance(view, stock.fetcher().fetchViewContent(view, i));
    }

    /** 调用方用反射调用这个三参版本（Interceptor 两处），必须提供。 */
    public Bundle fetchViewContent(View view, int i, boolean z) {
        if (stock == null) return null;
        return SourceBitmapEnhancer.enhance(view, stock.fetchViewContent3(view, i, z));
    }

    @Override
    public Bundle fetchWebViewBitmap(View view, int i) {
        android.util.Log.i("FP-CatcherShadow", "fetchWebViewBitmap " + (view == null ? null : view.getClass().getName()));
        return stock == null ? null : stock.fetcher().fetchWebViewBitmap(view, i);
    }

    @Override
    public Map<String, Object> reservedFunction(Map<String, Object> map, int i) {
        android.util.Log.i("FP-CatcherShadow", "reservedFunction " + i + " " + map);
        return stock == null ? null : stock.fetcher().reservedFunction(map, i);
    }
}
