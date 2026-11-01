package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.compat.PortalPageConfigCompat
import java.util.ArrayList
import java.util.HashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalPageConfigCompatTest {

    private val names = PortalPageConfigCompat.ClassNames(
        pageConfig = FakePageConfig::class.java.name,
        featureInfo = FakeFeatureInfo::class.java.name,
        configHelper = FakeConfigHelper::class.java.name,
    )

    @AfterTest
    fun clearSingleton() {
        FakeConfigHelper.singleton = null
    }

    @Test
    fun classifiesMissingEmptyAndPopulatedRomConfigurations() {
        val compat = PortalPageConfigCompat.resolve(javaClass.classLoader, names)

        assertEquals(
            PortalPageConfigCompat.PageState(
                present = false,
                features = PortalPageAdmission.ListState.ABSENT,
                catchers = PortalPageAdmission.ListState.ABSENT,
            ),
            compat.inspect(null),
        )
        assertEquals(
            PortalPageConfigCompat.PageState(
                present = true,
                features = PortalPageAdmission.ListState.EMPTY,
                catchers = PortalPageAdmission.ListState.EMPTY,
            ),
            compat.inspect(FakePageConfig(arrayListOf(), arrayListOf())),
        )
        assertEquals(
            PortalPageConfigCompat.PageState(
                present = true,
                features = PortalPageAdmission.ListState.NON_EMPTY,
                catchers = PortalPageAdmission.ListState.EMPTY,
            ),
            compat.inspect(FakePageConfig(arrayListOf(Any()), arrayListOf())),
        )
    }

    @Test
    fun readsOfficialTextTargetStateFromTheLiveConfigHelper() {
        val compat = PortalPageConfigCompat.resolve(javaClass.classLoader, names)
        FakeConfigHelper.singleton = FakeConfigHelper(
            arrayListOf(
                FakeTarget(enabled = true, target = "other", jobTag = "text_pick"),
                FakeTarget(
                    enabled = false,
                    target = "com.miui.contentextension",
                    jobTag = "text_pick",
                ),
            ),
        )

        assertFalse(compat.textTargetEnabled()!!)

        FakeConfigHelper.singleton!!.targets[1].enabled = true
        assertTrue(compat.textTargetEnabled()!!)
    }

    @Test
    fun missingOrAmbiguousTextTargetFailsClosed() {
        val compat = PortalPageConfigCompat.resolve(javaClass.classLoader, names)
        FakeConfigHelper.singleton = FakeConfigHelper(arrayListOf())
        assertNull(compat.textTargetEnabled())

        FakeConfigHelper.singleton = FakeConfigHelper(
            arrayListOf(
                FakeTarget(true, "com.miui.contentextension", "text_pick"),
                FakeTarget(false, "com.miui.contentextension", "text_pick"),
            ),
        )
        assertNull(compat.textTargetEnabled())
    }

    @Test
    fun buildsTheGenericTextFallbackExpectedByTheRomInterceptor() {
        val compat = PortalPageConfigCompat.resolve(javaClass.classLoader, names)

        val config = assertIs<FakePageConfig>(compat.buildTextFallback())
        assertTrue(config.catchers.isEmpty())
        val feature = assertIs<FakeFeatureInfo>(config.features.single())
        assertEquals("pick_mode", feature.name)
        assertEquals("com.miui.contentextension", feature.target)
        assertEquals("text_pick", feature.jobTag)
        assertTrue(feature.enable)
        assertEquals(
            "WebViewCatcher,NativeTextCatcher",
            feature.mParams["catchers"],
        )
    }

    private class FakePageConfig(
        val features: ArrayList<Any>,
        val catchers: ArrayList<Any>,
    )

    private class FakeFeatureInfo {
        var enable: Boolean = true
        var jobTag: String? = null
        var mParams: HashMap<String, Any> = HashMap()
        var name: String? = null
        var target: String? = null
    }

    private class FakeConfigHelper(val targets: ArrayList<FakeTarget>) {
        companion object {
            @JvmField
            var singleton: FakeConfigHelper? = null
        }
    }

    private class FakeTarget(
        var enabled: Boolean,
        val target: String,
        val jobTag: String,
    )
}
