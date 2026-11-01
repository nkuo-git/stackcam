package com.nick.stackcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCoreTest {

    @Test fun parseVersionVariants() {
        assertEquals(listOf(2, 1), UpdateCore.parseVersion("v2.1"))
        assertEquals(listOf(2, 1, 0), UpdateCore.parseVersion("V2.1.0"))
        assertEquals(listOf(3), UpdateCore.parseVersion("3"))
        assertEquals(listOf(2, 2), UpdateCore.parseVersion("v2.2-beta"))
        assertNull(UpdateCore.parseVersion("latest"))
        assertNull(UpdateCore.parseVersion(""))
        assertNull(UpdateCore.parseVersion(null))
    }

    @Test fun compareNumerically() {
        assertTrue(UpdateCore.compareVersions("v2.2", "2.1") > 0)
        assertTrue(UpdateCore.compareVersions("v2.10", "2.9") > 0)
        assertTrue(UpdateCore.compareVersions("v3.0", "2.99") > 0)
        assertTrue(UpdateCore.compareVersions("v2.1.1", "2.1") > 0)
        assertEquals(0, UpdateCore.compareVersions("v2.1", "2.1"))
        assertEquals(0, UpdateCore.compareVersions("v2.1.0", "2.1"))
        assertTrue(UpdateCore.compareVersions("v2.0", "2.1") < 0)
    }

    @Test fun isNewer() {
        assertTrue(UpdateCore.isNewer("v2.2", "2.1"))
        assertFalse(UpdateCore.isNewer("v2.1", "2.1"))
        assertFalse(UpdateCore.isNewer("v2.0", "2.1"))
        assertFalse(UpdateCore.isNewer("nightly", "2.1"))
    }

    @Test fun parseReleaseJson() {
        val json = """
        {"tag_name":"v2.2","name":"StackCam 2.2","body":"- 新增功能\r\n- 修正閃退",
         "assets":[
           {"name":"notes.txt","size":10,"browser_download_url":"https://example.com/notes.txt"},
           {"name":"StackCam.apk","size":4123456,"browser_download_url":"https://github.com/nkuo-git/stackcam/releases/download/v2.2/StackCam.apk"},
           {"name":"Other.apk","size":1,"browser_download_url":"https://example.com/Other.apk"}
         ]}
        """.trimIndent()
        val r = UpdateCore.parseRelease(json)
        assertEquals("v2.2", r.tag)
        assertEquals("2.2", r.version)
        assertEquals("StackCam.apk", r.apkName)
        assertEquals("https://github.com/nkuo-git/stackcam/releases/download/v2.2/StackCam.apk", r.apkUrl)
        assertEquals(4123456L, r.apkSize)
        assertEquals("- 新增功能\n- 修正閃退", r.changelog)
    }

    @Test fun parseReleaseWithoutApkOrBody() {
        val r = UpdateCore.parseRelease("""{"tag_name":"v2.3","body":null,"assets":[]}""")
        assertNull(r.apkUrl)
        assertEquals("", r.changelog)
        val r2 = UpdateCore.parseRelease("""{"tag_name":"v2.3"}""")
        assertNull(r2.apkUrl)
    }

    @Test(expected = Exception::class) fun parseReleaseMissingTag() {
        UpdateCore.parseRelease("""{"message":"Not Found"}""")
    }
}
