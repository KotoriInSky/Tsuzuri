package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.compat.PortalMediaPageCompat
import java.util.ArrayList
import java.util.HashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalMediaPageCompatTest {
    private class Page(val features: ArrayList<Info>, val catchers: ArrayList<Any>)
    private class Info {
        var name = ""
        var enable = true
        var target = "com.miui.contentextension"
        var jobTag = ""
        var mParams = hashMapOf<String, Any>()
        fun getCatcherNameList(): List<String>? {
            val names = mParams["catchers"] as? String ?: return null
            if (names.isEmpty()) return null
            return java.util.regex.Pattern.compile(",").split(names).toList()
        }
    }
    private fun page(): Page = Page(arrayListOf(
        Info().apply { name = "pick_mode"; jobTag = "text_pick"; mParams["catchers"] = "NativeTextCatcher"; mParams["reflectioninfo"] = hashMapOf("other.View" to "conversionBitmap") },
        Info().apply { name = "image_pick_mode"; jobTag = "image_pick"; mParams["catchers"] = "ImageViewCatcher" },
    ), arrayListOf())
    private fun compat() = PortalMediaPageCompat.resolve(javaClass.classLoader, Page::class.java.name, Info::class.java.name)

    @Test fun clonesSharedRomConfigAndHandsRulesToPickMode() {
        val original = page()
        val out = compat().augment(original, mapOf("test.Image" to "viewBitmap,a/Uri")) as Page
        assertNotSame(original, out)
        assertNotSame(original.features[0], out.features[0])
        assertEquals("NativeTextCatcher", original.features[0].mParams["catchers"])
        assertEquals(",", out.features[0].mParams["catchers"])
        val paths = out.features[0].mParams["reflectioninfo"] as Map<*, *>
        assertEquals("a/Uri,viewBitmap", paths["test.Image"])
        assertEquals("conversionBitmap", paths["other.View"])
        assertEquals(original.features[1].mParams, out.features[1].mParams)
    }
    @Test fun disabledOrAmbiguousTargetsAreNeverReenabled() {
        val original = page()
        original.features[1].enable = false
        assertNull(compat().augment(original, mapOf("test.Image" to "viewBitmap,a/Uri")))
        original.features[1].enable = true
        original.features.add(Info().apply { name = "pick_mode" })
        assertNull(compat().augment(original, mapOf("test.Image" to "viewBitmap,a/Uri")))
    }
    @Test fun initializesFieldFetcherWithoutEnablingTextCatchers() {
        val out = compat().augment(page(), mapOf("test.Image" to "viewBitmap,a/Uri")) as Page
        val catchers = out.features[0].getCatcherNameList()
        // null 会让 ROM 跳过 createFetcher；只有非 null 空列表才保留初始化且不置文本位。
        assertNotNull(catchers, "字段采集初始化分支被跳过")
        assertTrue(catchers.isEmpty(), "不应给媒体页附加文本采集器")
    }
    @Test fun existingViewRulesDoNotSilentlyDisableEntireMediaPage() {
        val original = page()
        original.features[0].mParams["reflectioninfo"] = hashMapOf(
            "other.View" to "viewBitmap,mDrawable/Bitmap,legacy/Object/url/String",
            "unrelated.View" to "conversionBitmap")
        val out = compat().augment(original, mapOf("other.View" to "viewBitmap,a/Uri")) as Page
        val updated = out.features[0].mParams["reflectioninfo"] as Map<*, *>
        assertEquals("a/Uri,legacy/Object/url/String,viewBitmap", updated["other.View"])
        assertEquals("conversionBitmap", updated["unrelated.View"])
        assertEquals("viewBitmap,mDrawable/Bitmap,legacy/Object/url/String",
            (original.features[0].mParams["reflectioninfo"] as Map<*, *>)["other.View"])
        assertNotSame(original.features[0].mParams, out.features[0].mParams)
    }
    @Test fun unsupportedExistingRuleShapeKeepsRomConfiguration() {
        val original = page()
        original.features[0].mParams["reflectioninfo"] = hashMapOf("other.View" to Any())
        assertNull(compat().augment(original, mapOf("other.View" to "viewBitmap,a/Uri")))
    }
}
