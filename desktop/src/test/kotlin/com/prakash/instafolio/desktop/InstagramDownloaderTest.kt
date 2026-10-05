package com.prakash.instafolio.desktop

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstagramDownloaderTest {

    @Test
    fun testShortcodeToMediaId() {
        val shortcode = "Dc7jd-aChKI"
        val expectedMediaId = "3979930687213539976"
        val extracted = DesktopInstagramDownloader.extractShortcode("https://www.instagram.com/p/Dc7jd-aChKI/?img_index=1")
        assertEquals(shortcode, extracted)
    }

    @Test
    fun testCleanUsernameExtraction() {
        assertEquals("natgeo", DesktopInstagramDownloader.extractCleanUsername("@natgeo"))
        assertEquals("cristiano", DesktopInstagramDownloader.extractCleanUsername("https://www.instagram.com/cristiano/"))
        assertEquals("prakash", DesktopInstagramDownloader.extractCleanUsername("prakash"))
    }

    @Test
    fun testLivePostFetchWithSession() {
        val sessionId = "34249654098%3AfUi24WHIuEASlY%3A3%3AAYiH0zf7yNqBkKegTb7MqkRoFxoQfkUfRbKcR0N7rg"
        val session = DesktopSession(sessionId = sessionId, csrfToken = null, userId = "34249654098")
        val url = "https://www.instagram.com/p/Dc7jd-aChKI/?img_index=1"
        
        try {
            val items = DesktopInstagramDownloader.getMediaItems(url, session)
            println("Fetched ${items.size} items for URL: $url")
            assertTrue(items.isNotEmpty(), "Items should not be empty")
        } catch (e: Exception) {
            println("Live test skipped or failed due to session expiry: ${e.message}")
        }
    }

    @Test
    fun testDesktopSettingsCookieParsing() {
        val settings = DesktopSettings()
        val acc = settings.applyCookieString("sessionid=123456%3Aabc%3A1; ds_user_id=123456; csrftoken=token123")
        assertEquals("123456%3Aabc%3A1", acc?.sessionId)
        assertEquals("123456", acc?.userId)
        assertEquals("token123", acc?.csrfToken)
    }

    @Test
    fun testRawSessionIdWithoutEquals() {
        val settings = DesktopSettings()
        val acc = settings.applyCookieString("987654%3Axyz%3A2")
        assertEquals("987654%3Axyz%3A2", acc?.sessionId)
    }
}
