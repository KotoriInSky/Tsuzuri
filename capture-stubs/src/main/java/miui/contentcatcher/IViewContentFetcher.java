package miui.contentcatcher;

import android.os.Bundle;
import android.view.View;
import java.util.Map;
import miui.contentcatcher.sdk.Token;

/** 签名桩：运行时由 miui-framework 提供。方法表取自本机反编译。 */
public interface IViewContentFetcher {
    void createFetcher(Token token, Map<String, String> map);

    Bundle fetchViewContent(View view, int i);

    Bundle fetchWebViewBitmap(View view, int i);

    Map<String, Object> reservedFunction(Map<String, Object> map, int i);
}
