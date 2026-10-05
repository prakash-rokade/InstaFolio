package com.prakash.instafolio.core.network

import com.prakash.instafolio.core.model.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.math.BigInteger
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object InstagramEngine {

    private val SHORTCODE_REGEX = Pattern.compile(
        "(?:instagram\\.com|instagr\\.am)/(?:reel|reels|p|tv|share/p|share/reel|share/r)/([A-Za-z0-9_-]+)"
    )
    private val STORY_REGEX = Pattern.compile(
        "(?:instagram\\.com|instagr\\.am)/stories/([A-Za-z0-9._]+)/([0-9]+)"
    )
    private val PROFILE_REGEX = Pattern.compile(
        "^https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/([A-Za-z0-9_.]+)/?(?:[?#].*)?$"
    )
    private val RESERVED_PROFILE_PATHS = setOf(
        "p", "reel", "reels", "tv", "stories", "explore", "accounts", "direct",
        "about", "developer", "legal", "privacy", "graphql", "web", "download", "emails", "topics"
    )

    private const val SHORTCODE_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    data class StoryRequest(val username: String, val mediaId: String)

    private val allCookies = mutableListOf<Cookie>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(allCookies) {
                allCookies.removeAll { existing -> cookies.any { it.name == existing.name } }
                allCookies.addAll(cookies)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return synchronized(allCookies) {
                allCookies.filter { it.matches(url) }
            }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(cookieJar)
        .build()

    const val DESKTOP_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    const val ANDROID_UA = "Instagram 319.0.0.38.109 Android (31/12; 480dpi; 1080x2400; samsung; SM-G998B; o1s; exynos2100; en_US; 576020586)"

    fun getMediaItems(
        postUrl: String,
        session: IgSession? = null,
        quality: DownloadQuality = DownloadQuality.BEST
    ): List<MediaResult> {
        extractStory(postUrl)?.let { story ->
            val s = session ?: throw UnsupportedOperationException(
                "Stories require an Instagram account. Please log in from Settings to download Stories."
            )
            return tryMediaInfoApi(story.mediaId, s, quality)
        }

        val shortcode = extractShortcode(postUrl) ?: run {
            extractProfileUsername(postUrl)?.let { username ->
                return fetchProfilePicture(username, session)
            }
            throw IllegalArgumentException("Invalid Instagram URL: $postUrl")
        }

        if (session != null) {
            try {
                val mediaId = shortcodeToMediaId(shortcode)
                val results = tryMediaInfoApi(mediaId, session, quality)
                if (results.isNotEmpty()) return results
            } catch (_: Exception) {}
        }

        var postPageError: String? = null
        try {
            return tryPostPage(shortcode, quality)
        } catch (postFailure: Exception) {
            postPageError = postFailure.message ?: postFailure.javaClass.simpleName
        }

        if (session != null) {
            throw Exception(
                "Could not fetch this post.\n\n" +
                        "Public scraper: $postPageError\n" +
                        "Logged-in API: unable to retrieve media"
            )
        }

        throw Exception(
            "Could not fetch this post — it may be private, age-restricted, or deleted.\n" +
                    "Log in from Settings to download content visible to your account.\n\n" +
                    "Public scraper: $postPageError"
        )
    }

    fun checkSessionHealth(session: IgSession): SessionHealth {
        return try {
            val profile = fetchCurrentAccountProfile(session)
            if (profile.username.isNotBlank()) {
                SessionHealth(
                    status = SessionHealthStatus.HEALTHY,
                    message = "Active & verified (@${profile.username})",
                    checkedAt = System.currentTimeMillis(),
                    username = profile.username
                )
            } else {
                SessionHealth(
                    status = SessionHealthStatus.EXPIRED,
                    message = "Session expired or invalid",
                    checkedAt = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            SessionHealth(
                status = SessionHealthStatus.EXPIRED,
                message = e.message ?: "Authentication failed",
                checkedAt = System.currentTimeMillis()
            )
        }
    }

    fun fetchCurrentAccountProfile(session: IgSession): InstagramUserProfile {
        val cookieHeader = buildCookieHeader(session)
        val response = client.newCall(
            Request.Builder()
                .url("https://i.instagram.com/api/v1/accounts/current_user/?edit=true")
                .header("User-Agent", ANDROID_UA)
                .header("X-IG-App-ID", "936619743392459")
                .header("Cookie", cookieHeader)
                .get().build()
        ).execute()

        val body = response.body?.string() ?: throw Exception("Empty response from Instagram")
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $body")

        val json = JSONObject(body)
        val user = json.optJSONObject("user") ?: throw Exception("No user data in response")

        return InstagramUserProfile(
            username = user.optString("username"),
            userId = user.optString("pk").takeIf { it.isNotBlank() } ?: user.optString("id"),
            fullName = user.optString("full_name").takeIf { it.isNotBlank() },
            profilePicUrl = user.optString("profile_pic_url").takeIf { it.isNotBlank() },
            isPrivate = user.optBoolean("is_private", false),
            isVerified = user.optBoolean("is_verified", false)
        )
    }

    private fun tryMediaInfoApi(
        mediaId: String,
        session: IgSession,
        quality: DownloadQuality
    ): List<MediaResult> {
        val cookie = buildCookieHeader(session)
        val endpoints = listOf(
            "https://i.instagram.com/api/v1/media/$mediaId/info/",
            "https://www.instagram.com/api/v1/media/$mediaId/info/"
        )

        for (url in endpoints) {
            val resp = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", if (url.contains("i.instagram.com")) ANDROID_UA else DESKTOP_UA)
                    .header("X-IG-App-ID", "936619743392459")
                    .header("Accept-Language", "en-US")
                    .header("Cookie", cookie)
                    .get().build()
            ).execute()

            val rawBytes = resp.body?.bytes() ?: continue
            val isGzip = (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) ||
                    resp.header("Content-Encoding")?.contains("gzip", ignoreCase = true) == true
            val body = if (isGzip) {
                runCatching {
                    java.util.zip.GZIPInputStream(rawBytes.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrElse { String(rawBytes, Charsets.UTF_8) }
            } else {
                String(rawBytes, Charsets.UTF_8)
            }

            if (!body.trimStart().startsWith('<')) {
                val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
                val item = json.optJSONArray("items")?.optJSONObject(0) ?: continue

                val postCaption = item.optJSONObject("caption")?.optString("text")
                val authorUser = item.optJSONObject("user")
                val authorUsername = authorUser?.optString("username")
                val authorFullName = authorUser?.optString("full_name")
                val authorPic = authorUser?.optString("profile_pic_url")
                val timestamp = item.optLong("taken_at").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
                val audioTrack = extractAudioTrack(item)

                item.optJSONArray("carousel_media")?.let { slides ->
                    val items = (0 until slides.length()).mapNotNull { i ->
                        slides.optJSONObject(i)?.let { node ->
                            extractSingleItem(
                                item = node,
                                quality = quality,
                                fallbackCaption = postCaption,
                                fallbackAuthor = authorUsername,
                                fallbackFullName = authorFullName,
                                fallbackPic = authorPic,
                                fallbackTimestamp = timestamp,
                                fallbackAudio = audioTrack
                            )
                        }
                    }
                    if (items.isNotEmpty()) return items
                }

                extractSingleItem(
                    item = item,
                    quality = quality,
                    fallbackCaption = postCaption,
                    fallbackAuthor = authorUsername,
                    fallbackFullName = authorFullName,
                    fallbackPic = authorPic,
                    fallbackTimestamp = timestamp,
                    fallbackAudio = audioTrack
                )?.let { return listOf(it) }
            }
        }
        throw Exception("Media info: no downloadable media found")
    }

    private fun tryPostPage(shortcode: String, quality: DownloadQuality): List<MediaResult> {
        val request = Request.Builder()
            .url("https://www.instagram.com/p/$shortcode/")
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .get().build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: throw Exception("Empty response from Instagram")

        // 1. Embedded JSON in script tags
        val scriptPattern = Pattern.compile(
            """<script\s+type="application/json"\s+data-sjs>(\{"require":.*?)</script>""",
            Pattern.DOTALL
        )
        val matcher = scriptPattern.matcher(html)
        while (matcher.find()) {
            val jsonStr = matcher.group(1) ?: continue
            val candidate = runCatching { JSONObject(jsonStr) }.getOrNull() ?: continue
            findPublicProduct(candidate, shortcode)?.let { product ->
                val results = extractProductMedia(product, quality, shortcode)
                if (results.isNotEmpty()) return results
            }
        }

        // 2. _sharedData script tag
        val sharedDataPattern = Pattern.compile("""window\._sharedData\s*=\s*(\{.+?\});</script>""")
        val sharedMatcher = sharedDataPattern.matcher(html)
        if (sharedMatcher.find()) {
            val jsonStr = sharedMatcher.group(1) ?: ""
            val json = runCatching { JSONObject(jsonStr) }.getOrNull()
            json?.optJSONObject("entry_data")
                ?.optJSONArray("PostPage")
                ?.optJSONObject(0)
                ?.optJSONObject("graphql")
                ?.optJSONObject("shortcode_media")?.let { media ->
                    val results = extractProductMedia(media, quality, shortcode)
                    if (results.isNotEmpty()) return results
                }
        }

        // 3. OpenGraph fallback
        val ogImage = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
        val ogVideo = Regex("""<meta property="og:video" content="([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
        val ogDescription = Regex("""<meta property="og:description" content="([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("&amp;", "&")

        if (ogVideo != null) {
            return listOf(MediaResult(ogVideo, isVideo = true, thumbnailUrl = ogImage, caption = ogDescription, shortcode = shortcode))
        }
        if (ogImage != null) {
            return listOf(MediaResult(ogImage, isVideo = false, thumbnailUrl = ogImage, caption = ogDescription, shortcode = shortcode))
        }

        throw Exception("Post HTTP ${response.code}: no media found in page HTML")
    }

    private fun findPublicProduct(value: Any?, expectedMediaId: String): JSONObject? {
        when (value) {
            is JSONObject -> {
                if (value.optString("shortcode") == expectedMediaId ||
                    value.optString("pk") == expectedMediaId ||
                    value.optString("id") == expectedMediaId
                ) {
                    if (value.has("video_versions") || value.has("carousel_media") || value.has("image_versions2") ||
                        value.has("video_url") || value.has("display_url") || value.has("edge_sidecar_to_children")
                    ) {
                        return value
                    }
                }
                value.optJSONObject("if_not_gated_logged_out")?.let {
                    if (it.optString("pk") == expectedMediaId || it.optString("id") == expectedMediaId)
                        return it
                }
                if (value.has("video_versions") || value.has("carousel_media") || value.has("image_versions2") ||
                    value.has("video_url") || value.has("display_url")
                ) {
                    return value
                }

                val keys = value.keys()
                while (keys.hasNext()) {
                    findPublicProduct(value.opt(keys.next()), expectedMediaId)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                findPublicProduct(value.opt(i), expectedMediaId)?.let { return it }
            }
        }
        return null
    }

    private fun extractProductMedia(product: JSONObject, quality: DownloadQuality, shortcode: String?): List<MediaResult> {
        val caption = product.optJSONObject("edge_media_to_caption")
            ?.optJSONArray("edges")?.optJSONObject(0)
            ?.optJSONObject("node")?.optString("text")
            ?: product.optJSONObject("caption")?.optString("text")

        val owner = product.optJSONObject("owner")
        val authorUser = owner?.optString("username")
        val authorFullName = owner?.optString("full_name")
        val authorPic = owner?.optString("profile_pic_url")
        val timestamp = product.optLong("taken_at_timestamp").takeIf { it > 0 }
            ?: product.optLong("taken_at").takeIf { it > 0 }

        val audioTrack = extractAudioTrack(product)

        // Carousel via carousel_media
        product.optJSONArray("carousel_media")?.let { carousel ->
            val items = (0 until carousel.length()).mapNotNull { i ->
                carousel.optJSONObject(i)?.let {
                    extractSingleItem(it, quality, caption, authorUser, authorFullName, authorPic, timestamp, audioTrack, shortcode)
                }
            }
            if (items.isNotEmpty()) return items
        }

        // Carousel via edge_sidecar_to_children
        product.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")?.let { edges ->
            val items = (0 until edges.length()).mapNotNull { i ->
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: return@mapNotNull null
                val isVideo = node.optBoolean("is_video", false)
                val url = if (isVideo) node.optString("video_url") else node.optString("display_url")
                val thumb = node.optString("display_url")
                if (url.isNotBlank()) {
                    MediaResult(
                        url = url,
                        isVideo = isVideo,
                        thumbnailUrl = thumb,
                        caption = caption,
                        authorUsername = authorUser,
                        authorFullName = authorFullName,
                        authorProfilePic = authorPic,
                        timestamp = timestamp,
                        audioTrack = audioTrack,
                        shortcode = shortcode
                    )
                } else null
            }
            if (items.isNotEmpty()) return items
        }

        return listOfNotNull(extractSingleItem(product, quality, caption, authorUser, authorFullName, authorPic, timestamp, audioTrack, shortcode))
    }

    fun extractAudioTrack(item: JSONObject): AudioTrack? {
        // 1. clips_metadata -> music_info -> music_asset_info
        item.optJSONObject("clips_metadata")?.let { clips ->
            clips.optJSONObject("music_info")?.optJSONObject("music_asset_info")?.let { music ->
                val audioUrl = music.optString("progressive_download_url").takeIf { it.isNotBlank() }
                    ?: music.optString("fast_start_progressive_download_url").takeIf { it.isNotBlank() }
                if (audioUrl != null) {
                    return AudioTrack(
                        title = music.optString("title").takeIf { it.isNotBlank() } ?: "Instagram Audio",
                        artist = music.optString("display_artist").takeIf { it.isNotBlank() }
                            ?: music.optString("artist_name").takeIf { it.isNotBlank() } ?: "Original Audio",
                        audioUrl = audioUrl,
                        durationMs = music.optLong("duration_in_ms").takeIf { it > 0 },
                        coverUrl = music.optString("cover_artwork_uri").takeIf { it.isNotBlank() }
                    )
                }
            }

            // 2. clips_metadata -> original_sound_info
            clips.optJSONObject("original_sound_info")?.let { sound ->
                val audioUrl = sound.optString("progressive_download_url").takeIf { it.isNotBlank() }
                if (audioUrl != null) {
                    val igArtist = sound.optJSONObject("ig_artist")?.optString("username")
                    return AudioTrack(
                        title = sound.optString("original_audio_title").takeIf { it.isNotBlank() } ?: "Original Audio",
                        artist = igArtist ?: "Instagram Creator",
                        audioUrl = audioUrl,
                        durationMs = sound.optLong("duration_in_ms").takeIf { it > 0 },
                        isOriginalSound = true
                    )
                }
            }
        }

        // 3. music_metadata -> music_info
        item.optJSONObject("music_metadata")?.optJSONObject("music_info")?.optJSONObject("music_asset_info")?.let { music ->
            val audioUrl = music.optString("progressive_download_url").takeIf { it.isNotBlank() }
            if (audioUrl != null) {
                return AudioTrack(
                    title = music.optString("title").takeIf { it.isNotBlank() } ?: "Instagram Audio",
                    artist = music.optString("display_artist").takeIf { it.isNotBlank() } ?: "Original Audio",
                    audioUrl = audioUrl,
                    durationMs = music.optLong("duration_in_ms").takeIf { it > 0 },
                    coverUrl = music.optString("cover_artwork_uri").takeIf { it.isNotBlank() }
                )
            }
        }

        return null
    }

    private fun extractSingleItem(
        item: JSONObject,
        quality: DownloadQuality,
        fallbackCaption: String? = null,
        fallbackAuthor: String? = null,
        fallbackFullName: String? = null,
        fallbackPic: String? = null,
        fallbackTimestamp: Long? = null,
        fallbackAudio: AudioTrack? = null,
        shortcode: String? = null
    ): MediaResult? {
        val poster = item.optJSONObject("image_versions2")?.optJSONArray("candidates").pickUrl(quality)
            ?: item.optString("display_url").takeIf { it.isNotBlank() }

        val caption = item.optJSONObject("caption")?.optString("text") ?: fallbackCaption
        val audioTrack = extractAudioTrack(item) ?: fallbackAudio

        item.optJSONArray("video_versions").pickUrl(quality)?.let {
            return MediaResult(
                url = it,
                isVideo = true,
                thumbnailUrl = poster,
                caption = caption,
                authorUsername = fallbackAuthor,
                authorFullName = fallbackFullName,
                authorProfilePic = fallbackPic,
                timestamp = fallbackTimestamp,
                audioTrack = audioTrack,
                shortcode = shortcode
            )
        }

        item.optString("video_url").takeIf { it.isNotBlank() }?.let {
            return MediaResult(
                url = it,
                isVideo = true,
                thumbnailUrl = poster,
                caption = caption,
                authorUsername = fallbackAuthor,
                authorFullName = fallbackFullName,
                authorProfilePic = fallbackPic,
                timestamp = fallbackTimestamp,
                audioTrack = audioTrack,
                shortcode = shortcode
            )
        }

        return poster?.let {
            MediaResult(
                url = it,
                isVideo = false,
                thumbnailUrl = it,
                caption = caption,
                authorUsername = fallbackAuthor,
                authorFullName = fallbackFullName,
                authorProfilePic = fallbackPic,
                timestamp = fallbackTimestamp,
                audioTrack = audioTrack,
                shortcode = shortcode
            )
        }
    }

    private fun JSONArray?.pickUrl(quality: DownloadQuality): String? {
        val len = this?.length() ?: return null
        if (len == 0) return null
        val index = if (quality == DownloadQuality.DATA_SAVER) len - 1 else 0
        return this.optJSONObject(index)?.optString("url")?.takeIf { it.isNotBlank() }
    }

    private fun fetchProfilePicture(username: String, session: IgSession?): List<MediaResult> {
        val encodedUsername = URLEncoder.encode(username, "UTF-8")
        val reqBuilder = Request.Builder()
            .url("https://www.instagram.com/api/v1/users/web_profile_info/?username=$encodedUsername")
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://www.instagram.com/$username/")
            .header("X-IG-App-ID", "936619743392459")
            .header("X-ASBD-ID", "129477")
            .header("X-Requested-With", "XMLHttpRequest")

        if (session != null) {
            reqBuilder.header("Cookie", buildCookieHeader(session))
        }

        val response = client.newCall(reqBuilder.get().build()).execute()
        val body = response.body?.string() ?: throw Exception("Profile HTTP ${response.code}: empty body")
        if (!response.isSuccessful) throw Exception("Profile HTTP ${response.code}: ${body.take(200)}")

        val user = JSONObject(body).optJSONObject("data")?.optJSONObject("user")
            ?: throw Exception("Profile response did not include user data")

        val hdPic = user.optString("profile_pic_url_hd").takeIf { it.isNotBlank() }
            ?: user.optString("profile_pic_url").takeIf { it.isNotBlank() }
            ?: throw Exception("No profile picture found for @$username")

        val bio = user.optString("biography")
        val fullName = user.optString("full_name")

        return listOf(
            MediaResult(
                url = hdPic,
                isVideo = false,
                thumbnailUrl = hdPic,
                caption = bio,
                authorUsername = username,
                authorFullName = fullName,
                authorProfilePic = hdPic
            )
        )
    }

    fun downloadToStream(url: String, out: OutputStream) {
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "*/*")
                .get().build()
        ).execute()

        if (!response.isSuccessful) throw Exception("Download HTTP ${response.code}")
        response.body?.byteStream()?.copyTo(out) ?: throw Exception("Empty download body")
    }

    fun fetchBytes(url: String): ByteArray {
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "*/*")
                .get().build()
        ).execute()

        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        return response.body?.bytes() ?: throw Exception("Empty body")
    }

    fun extractShortcode(url: String): String? {
        val m = SHORTCODE_REGEX.matcher(url)
        return if (m.find()) m.group(1) else null
    }

    fun extractStory(url: String): StoryRequest? {
        val m = STORY_REGEX.matcher(url)
        return if (m.find()) StoryRequest(m.group(1)!!, m.group(2)!!) else null
    }

    fun extractProfileUsername(url: String): String? {
        val m = PROFILE_REGEX.matcher(url)
        if (!m.find()) return null
        val username = m.group(1) ?: return null
        return if (username.lowercase() in RESERVED_PROFILE_PATHS) null else username
    }

    fun extractCleanUsername(input: String): String {
        val trimmed = input.trim()
        val profileMatch = extractProfileUsername(trimmed)
        if (profileMatch != null) return profileMatch
        val cleaned = trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.instagram.com/")
            .removePrefix("instagram.com/")
            .removePrefix("@")
            .trim()
        val slashIdx = cleaned.indexOf('/')
        val withoutPath = if (slashIdx >= 0) cleaned.substring(0, slashIdx) else cleaned
        val queryIdx = withoutPath.indexOf('?')
        val withoutQuery = if (queryIdx >= 0) withoutPath.substring(0, queryIdx) else withoutPath
        val hashIdx = withoutQuery.indexOf('#')
        val candidate = if (hashIdx >= 0) withoutQuery.substring(0, hashIdx) else withoutQuery
        return candidate.trim().replace("@", "")
    }

    fun fetchUserProfileFeed(
        usernameOrUrl: String,
        session: IgSession? = null,
        cursor: String? = null,
        count: Int = 24
    ): UserProfileFeed {
        val username = extractCleanUsername(usernameOrUrl)
        if (username.isBlank()) {
            throw IllegalArgumentException("Please enter a valid Instagram username or ID")
        }

        if (cursor != null && cursor.isNotBlank()) {
            return fetchPaginatedPosts(username, session, cursor, count)
        }

        val encodedUsername = URLEncoder.encode(username, "UTF-8")
        val reqBuilder = Request.Builder()
            .url("https://www.instagram.com/api/v1/users/web_profile_info/?username=$encodedUsername")
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://www.instagram.com/$username/")
            .header("X-IG-App-ID", "936619743392459")
            .header("X-ASBD-ID", "129477")
            .header("X-Requested-With", "XMLHttpRequest")

        if (session != null) {
            reqBuilder.header("Cookie", buildCookieHeader(session))
        }

        val response = client.newCall(reqBuilder.get().build()).execute()
        val body = response.body?.string() ?: throw Exception("Profile response was empty")
        if (!response.isSuccessful) {
            if (response.code == 404) {
                throw Exception("Instagram user '@$username' was not found.")
            } else if (response.code == 429) {
                throw Exception("Instagram rate limit reached. Please log in from Settings to continue browsing.")
            } else {
                throw Exception("Failed to fetch user @$username (HTTP ${response.code})")
            }
        }

        val json = JSONObject(body)
        val data = json.optJSONObject("data")
        val userObj = data?.optJSONObject("user") ?: json.optJSONObject("user")
            ?: throw Exception("User '@$username' not found or profile data is restricted.")

        val userId = userObj.optString("id").takeIf { it.isNotBlank() } ?: userObj.optString("pk") ?: ""
        val actualUsername = userObj.optString("username").takeIf { it.isNotBlank() } ?: username
        val fullName = userObj.optString("full_name").takeIf { it.isNotBlank() }
        val biography = userObj.optString("biography").takeIf { it.isNotBlank() }
        val profilePicUrl = userObj.optString("profile_pic_url_hd").takeIf { it.isNotBlank() }
            ?: userObj.optString("profile_pic_url").takeIf { it.isNotBlank() }
        val isPrivate = userObj.optBoolean("is_private", false)
        val isVerified = userObj.optBoolean("is_verified", false)
        val followerCount = userObj.optJSONObject("edge_followed_by")?.optInt("count") ?: userObj.optInt("follower_count")
        val followingCount = userObj.optJSONObject("edge_follow")?.optInt("count") ?: userObj.optInt("following_count")

        val profile = InstagramUserProfile(
            username = actualUsername,
            userId = userId,
            fullName = fullName,
            profilePicUrl = profilePicUrl,
            isPrivate = isPrivate,
            isVerified = isVerified,
            biography = biography,
            followerCount = followerCount,
            followingCount = followingCount,
            mediaCount = null
        )

        val timelineMedia = userObj.optJSONObject("edge_owner_to_timeline_media")
        val totalMediaCount = timelineMedia?.optInt("count") ?: userObj.optInt("media_count")
        val updatedProfile = profile.copy(mediaCount = totalMediaCount)

        if (isPrivate && session == null) {
            return UserProfileFeed(
                profile = updatedProfile,
                posts = emptyList(),
                hasNextPage = false,
                endCursor = null,
                totalMediaCount = totalMediaCount
            )
        }

        val pageInfo = timelineMedia?.optJSONObject("page_info")
        val hasNextPage = pageInfo?.optBoolean("has_next_page", false) ?: false
        val endCursor = pageInfo?.optString("end_cursor")?.takeIf { it.isNotBlank() }
        val edges = timelineMedia?.optJSONArray("edges") ?: JSONArray()

        val posts = mutableListOf<UserPostItem>()
        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            val node = edge.optJSONObject("node") ?: continue
            parseNodeToUserPost(node, actualUsername, fullName, profilePicUrl)?.let { posts.add(it) }
        }

        return UserProfileFeed(
            profile = updatedProfile,
            posts = posts,
            hasNextPage = hasNextPage,
            endCursor = endCursor,
            totalMediaCount = totalMediaCount
        )
    }

    private fun fetchPaginatedPosts(
        username: String,
        session: IgSession?,
        cursor: String,
        count: Int
    ): UserProfileFeed {
        val dummyProfile = InstagramUserProfile(username = username, userId = "")
        try {
            val variables = JSONObject().apply {
                put("first", count)
                put("after", cursor)
            }
            val queryHashes = listOf(
                "69cba802625028468e32f23843f8295c",
                "8c215260169c0ab333da54e429f25e4c",
                "e74e4d06d737e40c2d5e4f70b6087d74"
            )
            for (qh in queryHashes) {
                val url = "https://www.instagram.com/graphql/query/?query_hash=$qh&variables=${URLEncoder.encode(variables.toString(), "UTF-8")}"
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "*/*")
                    .header("Referer", "https://www.instagram.com/$username/")
                    .header("X-IG-App-ID", "936619743392459")
                    .header("X-Requested-With", "XMLHttpRequest")

                if (session != null) {
                    reqBuilder.header("Cookie", buildCookieHeader(session))
                }

                val resp = client.newCall(reqBuilder.get().build()).execute()
                if (!resp.isSuccessful) continue
                val body = resp.body?.string() ?: continue
                val json = JSONObject(body)
                val timeline = json.optJSONObject("data")?.optJSONObject("user")?.optJSONObject("edge_owner_to_timeline_media")
                    ?: continue
                val pageInfo = timeline.optJSONObject("page_info")
                val hasNext = pageInfo?.optBoolean("has_next_page", false) ?: false
                val nextCursor = pageInfo?.optString("end_cursor")?.takeIf { it.isNotBlank() }
                val edges = timeline.optJSONArray("edges") ?: JSONArray()

                val posts = mutableListOf<UserPostItem>()
                for (i in 0 until edges.length()) {
                    val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                    parseNodeToUserPost(node, username, null, null)?.let { posts.add(it) }
                }

                if (posts.isNotEmpty()) {
                    return UserProfileFeed(
                        profile = dummyProfile,
                        posts = posts,
                        hasNextPage = hasNext,
                        endCursor = nextCursor,
                        totalMediaCount = timeline.optInt("count")
                    )
                }
            }
        } catch (_: Exception) {}

        return UserProfileFeed(
            profile = dummyProfile,
            posts = emptyList(),
            hasNextPage = false,
            endCursor = null
        )
    }

    private fun parseNodeToUserPost(
        node: JSONObject,
        authorUsername: String,
        authorFullName: String?,
        authorProfilePic: String?
    ): UserPostItem? {
        val id = node.optString("id").takeIf { it.isNotBlank() } ?: node.optString("pk") ?: return null
        val shortcode = node.optString("shortcode").takeIf { it.isNotBlank() }
            ?: node.optString("code").takeIf { it.isNotBlank() }
            ?: return null

        val typename = node.optString("__typename")
        val isVideo = node.optBoolean("is_video", false) || typename == "GraphVideo"
        val displayUrl = node.optString("display_url").takeIf { it.isNotBlank() }
            ?: node.optJSONObject("image_versions2")?.optJSONArray("candidates")?.optJSONObject(0)?.optString("url")
            ?: ""
        val videoUrl = node.optString("video_url").takeIf { it.isNotBlank() }
            ?: node.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url")

        val caption = node.optJSONObject("edge_media_to_caption")
            ?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.optString("text")
            ?: node.optJSONObject("caption")?.optString("text")

        val timestamp = node.optLong("taken_at_timestamp").takeIf { it > 0 }
            ?: node.optLong("taken_at").takeIf { it > 0 }

        val likeCount = node.optJSONObject("edge_media_preview_like")?.optInt("count")
            ?: node.optJSONObject("edge_liked_by")?.optInt("count")
            ?: node.optInt("like_count").takeIf { it > 0 }

        val commentCount = node.optJSONObject("edge_media_to_comment")?.optInt("count")
            ?: node.optInt("comment_count").takeIf { it > 0 }

        val videoDuration = node.optDouble("video_duration").takeIf { !it.isNaN() && it > 0 }

        // Parse Carousel children
        val carouselList = mutableListOf<MediaResult>()
        val sidecarEdges = node.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        if (sidecarEdges != null && sidecarEdges.length() > 0) {
            for (j in 0 until sidecarEdges.length()) {
                val child = sidecarEdges.optJSONObject(j)?.optJSONObject("node") ?: continue
                val childIsVideo = child.optBoolean("is_video", false)
                val childUrl = if (childIsVideo) {
                    child.optString("video_url").takeIf { it.isNotBlank() } ?: child.optString("display_url")
                } else {
                    child.optString("display_url")
                }
                val childThumb = child.optString("display_url")
                if (childUrl.isNotBlank()) {
                    carouselList.add(
                        MediaResult(
                            url = childUrl,
                            isVideo = childIsVideo,
                            thumbnailUrl = childThumb,
                            caption = caption,
                            authorUsername = authorUsername,
                            authorFullName = authorFullName,
                            authorProfilePic = authorProfilePic,
                            timestamp = timestamp,
                            shortcode = shortcode
                        )
                    )
                }
            }
        } else {
            val carouselItems = node.optJSONArray("carousel_media")
            if (carouselItems != null && carouselItems.length() > 0) {
                for (j in 0 until carouselItems.length()) {
                    val child = carouselItems.optJSONObject(j) ?: continue
                    val childPoster = child.optJSONObject("image_versions2")?.optJSONArray("candidates")?.optJSONObject(0)?.optString("url")
                        ?: child.optString("display_url")
                    val childVideo = child.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url")
                    val childIsVid = childVideo != null
                    val childUrl = childVideo ?: childPoster
                    if (childUrl.isNotBlank()) {
                        carouselList.add(
                            MediaResult(
                                url = childUrl,
                                isVideo = childIsVid,
                                thumbnailUrl = childPoster,
                                caption = caption,
                                authorUsername = authorUsername,
                                authorFullName = authorFullName,
                                authorProfilePic = authorProfilePic,
                                timestamp = timestamp,
                                shortcode = shortcode
                            )
                        )
                    }
                }
            }
        }

        val mediaType = when {
            carouselList.size > 1 || typename == "GraphSidecar" -> MediaType.CAROUSEL
            isVideo -> if (typename == "GraphVideo" || (videoDuration ?: 0.0) > 0) MediaType.REEL else MediaType.VIDEO
            else -> MediaType.PHOTO
        }

        return UserPostItem(
            id = id,
            shortcode = shortcode,
            mediaType = mediaType,
            isVideo = isVideo,
            displayUrl = displayUrl,
            videoUrl = videoUrl,
            caption = caption,
            timestamp = timestamp,
            likeCount = likeCount,
            commentCount = commentCount,
            carouselItemCount = carouselList.size.coerceAtLeast(1),
            carouselMedia = carouselList,
            authorUsername = authorUsername,
            authorFullName = authorFullName,
            authorProfilePic = authorProfilePic,
            videoDuration = videoDuration
        )
    }

    private fun shortcodeToMediaId(shortcode: String): String =
        shortcode.fold(BigInteger.ZERO) { id, c ->
            val idx = SHORTCODE_ALPHABET.indexOf(c)
            if (idx >= 0) {
                id * BigInteger.valueOf(64) + BigInteger.valueOf(idx.toLong())
            } else {
                id
            }
        }.toString()

    private fun buildCookieHeader(session: IgSession): String =
        listOfNotNull(
            "sessionid=${session.sessionId}",
            session.csrfToken?.let { "csrftoken=$it" },
            session.userId?.let { "ds_user_id=$it" },
            "ig_did=A906C0AE-2770-458E-9243-B28465CA5FF7",
            "ig_nrcb=1"
        ).joinToString("; ")
}

