package com.prakash.instafolio

import com.prakash.instafolio.core.model.*
import com.prakash.instafolio.core.network.InstagramEngine
import java.io.OutputStream

object InstagramDownloader {

    fun getMediaItems(
        postUrl: String,
        session: IgSession? = null,
        quality: DownloadQuality = DownloadQuality.BEST
    ): List<MediaResult> {
        return InstagramEngine.getMediaItems(postUrl, session, quality)
    }

    fun fetchCurrentAccountProfile(session: IgSession): InstagramUserProfile {
        return InstagramEngine.fetchCurrentAccountProfile(session)
    }

    fun downloadToStream(url: String, out: OutputStream) {
        InstagramEngine.downloadToStream(url, out)
    }

    fun fetchBytes(url: String): ByteArray {
        return InstagramEngine.fetchBytes(url)
    }

    fun extractShortcode(url: String): String? {
        return InstagramEngine.extractShortcode(url)
    }

    fun extractStory(url: String): InstagramEngine.StoryRequest? {
        return InstagramEngine.extractStory(url)
    }

    fun extractCleanUsername(input: String): String {
        return InstagramEngine.extractCleanUsername(input)
    }

    fun fetchUserProfileFeed(
        usernameOrUrl: String,
        session: IgSession? = null,
        cursor: String? = null,
        count: Int = 24
    ): UserProfileFeed {
        return InstagramEngine.fetchUserProfileFeed(usernameOrUrl, session, cursor, count)
    }
}
