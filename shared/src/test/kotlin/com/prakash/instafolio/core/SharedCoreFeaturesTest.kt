package com.prakash.instafolio.core

import com.prakash.instafolio.core.audio.Id3TagHelper
import com.prakash.instafolio.core.history.HistoryStore
import com.prakash.instafolio.core.model.*
import com.prakash.instafolio.core.network.InstagramEngine
import com.prakash.instafolio.core.session.MultiAccountStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedCoreFeaturesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testId3TagHelperEmbedding() {
        val dummyAudio = "FAKE_MP3_STREAM_BYTES_CONTENT_FOR_TESTING".toByteArray()
        val tagged = Id3TagHelper.wrapMp3WithId3(
            rawAudioBytes = dummyAudio,
            title = "Test Viral Track",
            artist = "Top Artist",
            albumName = "Instagram Audio"
        )

        assertNotNull(tagged)
        assertTrue("Tagged audio must be larger than raw audio", tagged.size > dummyAudio.size)
        // Check ID3 header signature
        assertEquals('I'.code.toByte(), tagged[0])
        assertEquals('D'.code.toByte(), tagged[1])
        assertEquals('3'.code.toByte(), tagged[2])
    }

    @Test
    fun testHistoryStoreOperations() {
        val historyFile = File(tempFolder.root, "test_history.json")
        val store = HistoryStore(historyFile)

        assertEquals(0, store.getAll().size)

        val item1 = DownloadHistoryItem(
            id = "item-1",
            url = "https://instagram.com/reel/C12345",
            shortcode = "C12345",
            authorUsername = "natgeo",
            mediaType = MediaType.REEL,
            audioTitle = "Nature Sounds"
        )
        val item2 = DownloadHistoryItem(
            id = "item-2",
            url = "https://instagram.com/p/D98765",
            shortcode = "D98765",
            authorUsername = "art_daily",
            mediaType = MediaType.CAROUSEL
        )

        store.add(item1)
        store.add(item2)

        assertEquals(2, store.getAll().size)

        // Filter by media type
        val reels = store.filter(mediaType = MediaType.REEL)
        assertEquals(1, reels.size)
        assertEquals("natgeo", reels[0].authorUsername)

        // Search query
        val searchResults = store.filter(query = "Nature")
        assertEquals(1, searchResults.size)
        assertEquals("item-1", searchResults[0].id)

        // Toggle Favorite
        store.toggleFavorite("item-1")
        val favorites = store.filter(favoritesOnly = true)
        assertEquals(1, favorites.size)
        assertTrue(favorites[0].isFavorite)

        // Remove item
        store.remove("item-2")
        assertEquals(1, store.getAll().size)
    }

    @Test
    fun testMultiAccountStoreOperations() {
        val accountFile = File(tempFolder.root, "test_accounts.json")
        val store = MultiAccountStore(accountFile)

        assertEquals(0, store.getAllAccounts().size)
        assertNull(store.getActiveAccount())

        val acc1 = StoredAccount(
            id = "acc-1",
            username = "photographer",
            sessionId = "session_111",
            csrfToken = "csrf_111"
        )
        val acc2 = StoredAccount(
            id = "acc-2",
            username = "creator_pro",
            sessionId = "session_222",
            csrfToken = "csrf_222"
        )

        store.addOrUpdateAccount(acc1)
        store.addOrUpdateAccount(acc2)

        assertEquals(2, store.getAllAccounts().size)
        assertEquals("acc-2", store.getActiveAccount()?.id)

        // Switch active account
        store.setActiveAccount("acc-1")
        assertEquals("acc-1", store.getActiveAccount()?.id)
        assertEquals("photographer", store.getActiveAccount()?.username)

        // Update health
        store.updateHealth(
            "acc-1",
            SessionHealth(
                status = SessionHealthStatus.HEALTHY,
                message = "Verified",
                username = "photographer"
            )
        )
        assertEquals(SessionHealthStatus.HEALTHY, store.getActiveAccount()?.health?.status)

        // Remove account
        store.removeAccount("acc-1")
        assertEquals(1, store.getAllAccounts().size)
        assertEquals("acc-2", store.getActiveAccount()?.id)
    }

    @Test
    fun testInstagramEngineUrlParsing() {
        assertEquals("Dc7jd-aChKI", InstagramEngine.extractShortcode("https://www.instagram.com/p/Dc7jd-aChKI/?img_index=1"))
        assertEquals("Cz9Reel123", InstagramEngine.extractShortcode("https://www.instagram.com/reel/Cz9Reel123/"))
        assertEquals("tv12345", InstagramEngine.extractShortcode("https://www.instagram.com/tv/tv12345/"))

        val story = InstagramEngine.extractStory("https://www.instagram.com/stories/cristiano/321456987123/")
        assertNotNull(story)
        assertEquals("cristiano", story?.username)
        assertEquals("321456987123", story?.mediaId)

        assertEquals("natgeo", InstagramEngine.extractProfileUsername("https://www.instagram.com/natgeo/"))
        assertNull(InstagramEngine.extractProfileUsername("https://www.instagram.com/explore/"))
    }

    @Test
    fun testCleanUsernameExtraction() {
        assertEquals("natgeo", InstagramEngine.extractCleanUsername("natgeo"))
        assertEquals("natgeo", InstagramEngine.extractCleanUsername("@natgeo"))
        assertEquals("natgeo", InstagramEngine.extractCleanUsername("  @natgeo  "))
        assertEquals("natgeo", InstagramEngine.extractCleanUsername("https://www.instagram.com/natgeo/"))
        assertEquals("natgeo", InstagramEngine.extractCleanUsername("https://instagram.com/natgeo?igsh=abc123"))
        assertEquals("cristiano", InstagramEngine.extractCleanUsername("instagram.com/cristiano/#"))
    }

    @Test
    fun testUserPostItemAndFeedModels() {
        val post1 = UserPostItem(
            id = "1001",
            shortcode = "C123",
            mediaType = MediaType.PHOTO,
            isVideo = false,
            displayUrl = "https://instagram.com/photo1.jpg",
            caption = "Sunset at the beach",
            authorUsername = "photographer"
        )
        val post2 = UserPostItem(
            id = "1002",
            shortcode = "C456",
            mediaType = MediaType.VIDEO,
            isVideo = true,
            displayUrl = "https://instagram.com/thumb2.jpg",
            videoUrl = "https://instagram.com/video2.mp4",
            caption = "Ocean waves",
            authorUsername = "photographer"
        )
        val post3 = UserPostItem(
            id = "1003",
            shortcode = "C789",
            mediaType = MediaType.CAROUSEL,
            isVideo = false,
            displayUrl = "https://instagram.com/thumb3.jpg",
            carouselItemCount = 3,
            carouselMedia = listOf(
                MediaResult(url = "https://instagram.com/slide1.jpg", isVideo = false),
                MediaResult(url = "https://instagram.com/slide2.jpg", isVideo = false),
                MediaResult(url = "https://instagram.com/slide3.jpg", isVideo = false)
            ),
            caption = "Photo dump",
            authorUsername = "photographer"
        )

        val profile = InstagramUserProfile(
            username = "photographer",
            userId = "987654",
            fullName = "Nature Photographer",
            mediaCount = 42,
            followerCount = 150000,
            followingCount = 450
        )

        val feed = UserProfileFeed(
            profile = profile,
            posts = listOf(post1, post2, post3),
            hasNextPage = true,
            endCursor = "cursor_token_abc"
        )

        assertEquals("photographer", feed.profile.username)
        assertEquals(3, feed.posts.size)
        assertTrue(feed.hasNextPage)
        assertEquals("cursor_token_abc", feed.endCursor)

        assertEquals(MediaType.PHOTO, post1.mediaType)
        assertEquals(MediaType.VIDEO, post2.mediaType)
        assertEquals(MediaType.CAROUSEL, post3.mediaType)
        assertEquals(3, post3.carouselMedia.size)
    }
}

