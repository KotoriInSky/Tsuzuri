package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortalTempImageContractTest {

    @Test
    fun privateUriIsNotMediaStore() {
        val uri = PortalTempImageContract.uri("share", "abc123.png")

        assertEquals("content", uri.substringBefore("://"))
        assertEquals(PortalTempImageContract.AUTHORITY, uri.substringAfter("://").substringBefore('/'))
        assertFalse(uri.startsWith("content://media/"))
    }

    @Test
    fun tokenAcceptsOnlySingleSafePngName() {
        assertTrue(PortalTempImageContract.isSafeToken("abc123.png"))
        assertFalse(PortalTempImageContract.isSafeToken("../abc123.png"))
        assertFalse(PortalTempImageContract.isSafeToken("nested/abc123.png"))
        assertFalse(PortalTempImageContract.isSafeToken("abc123.jpg"))
        assertFalse(PortalTempImageContract.isSafeToken(""))
    }

    @Test
    fun collectionAndItemPathsResolveTheSameSegment() {
        assertEquals(
            PortalTempImageContract.EDIT_SEGMENT,
            PortalTempImageContract.segmentOf(listOf("edit")),
        )
        assertEquals(
            PortalTempImageContract.EDIT_SEGMENT,
            PortalTempImageContract.segmentOf(listOf("edit", "source.png")),
        )
        assertEquals(
            PortalTempImageContract.SHARE_SEGMENT,
            PortalTempImageContract.segmentOf(listOf("share", "source.png")),
        )
        assertEquals(null, PortalTempImageContract.segmentOf(emptyList()))
        assertEquals(null, PortalTempImageContract.segmentOf(listOf("edit", "source.png", "extra")))
    }

    @Test
    fun onlyMediaEditorCanDirectlyAccessEditSources() {
        assertTrue(
            PortalTempImageContract.isDirectAccessCaller(
                PortalTempImageContract.EDIT_SEGMENT,
                listOf("com.miui.mediaeditor"),
            ),
        )
        assertFalse(
            PortalTempImageContract.isDirectAccessCaller(
                PortalTempImageContract.SHARE_SEGMENT,
                listOf("com.miui.mediaeditor"),
            ),
        )
        assertFalse(
            PortalTempImageContract.isDirectAccessCaller(
                PortalTempImageContract.EDIT_SEGMENT,
                listOf("com.example.other"),
            ),
        )
    }

    @Test
    fun shareTokenGrantsReadOnlyCapabilityToItsHolder() {
        assertTrue(
            PortalTempImageContract.isTokenCapabilityAllowed(
                PortalTempImageContract.SHARE_SEGMENT,
                write = false,
            ),
        )
        assertFalse(
            PortalTempImageContract.isTokenCapabilityAllowed(
                PortalTempImageContract.SHARE_SEGMENT,
                write = true,
            ),
        )
        assertFalse(
            PortalTempImageContract.isTokenCapabilityAllowed(
                PortalTempImageContract.EDIT_SEGMENT,
                write = false,
            ),
        )
    }

    @Test
    fun uriSeparatesEditAndShareSessions() {
        assertEquals(
            "content://${PortalTempImageContract.AUTHORITY}/edit/edit.png",
            PortalTempImageContract.uri("edit", "edit.png"),
        )
        assertEquals(
            "content://${PortalTempImageContract.AUTHORITY}/share/share.png",
            PortalTempImageContract.uri("share", "share.png"),
        )
    }
}
