package com.prakash.instafolio.core.model

import java.io.Serializable

enum class DownloadQuality(val label: String, val description: String) {
    AUTO("Auto", "Best quality by default, or data saver when metered"),
    DATA_SAVER("Data Saver", "Use the smallest rendition Instagram provides"),
    BEST("Best quality", "Use the highest-quality rendition available")
}

enum class PhotoSaveFormat(val label: String, val description: String) {
    IMAGES("Images (.jpg)", "Save photos as individual image files"),
    PDF("PDF Document (.pdf)", "Compile photos into a single multi-page PDF document"),
    BOTH("Both (.jpg + .pdf)", "Save individual images and generate a PDF")
}

enum class AppTheme(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class MediaType(val label: String, val extension: String) {
    PHOTO("Photo", "jpg"),
    VIDEO("Video", "mp4"),
    REEL("Reel", "mp4"),
    CAROUSEL("Carousel", "zip"),
    AUDIO("Audio Track", "mp3"),
    PDF("PDF Album", "pdf"),
    PROFILE("Profile Picture", "jpg"),
    STORY("Story", "mp4")
}

data class AudioTrack(
    val title: String? = null,
    val artist: String? = null,
    val audioUrl: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val isOriginalSound: Boolean = false
) : Serializable

data class MediaResult(
    val url: String,
    val isVideo: Boolean,
    val thumbnailUrl: String? = null,
    val audioTrack: AudioTrack? = null,
    val caption: String? = null,
    val authorUsername: String? = null,
    val authorFullName: String? = null,
    val authorProfilePic: String? = null,
    val timestamp: Long? = null,
    val shortcode: String? = null,
    val width: Int? = null,
    val height: Int? = null
) : Serializable {
    val previewUrl: String? get() = thumbnailUrl ?: url.takeIf { !isVideo }
    val hasAudio: Boolean get() = audioTrack?.audioUrl != null || isVideo
}

data class InstagramUserProfile(
    val username: String,
    val userId: String,
    val fullName: String? = null,
    val profilePicUrl: String? = null,
    val isPrivate: Boolean = false,
    val isVerified: Boolean = false,
    val biography: String? = null,
    val mediaCount: Int? = null,
    val followerCount: Int? = null,
    val followingCount: Int? = null
) : Serializable

data class IgSession(
    val sessionId: String,
    val csrfToken: String? = null,
    val userId: String? = null
) : Serializable

enum class SessionHealthStatus {
    HEALTHY,
    EXPIRING_SOON,
    EXPIRED,
    UNCHECKED
}

data class SessionHealth(
    val status: SessionHealthStatus = SessionHealthStatus.UNCHECKED,
    val message: String = "Unchecked",
    val checkedAt: Long = 0L,
    val username: String? = null
) : Serializable

data class StoredAccount(
    val id: String,
    val username: String,
    val fullName: String? = null,
    val profilePicUrl: String? = null,
    val sessionId: String,
    val csrfToken: String? = null,
    val userId: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis(),
    val health: SessionHealth = SessionHealth(SessionHealthStatus.UNCHECKED)
) : Serializable {
    fun toIgSession() = IgSession(sessionId = sessionId, csrfToken = csrfToken, userId = userId)
}

data class DownloadHistoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String,
    val shortcode: String? = null,
    val authorUsername: String? = null,
    val authorFullName: String? = null,
    val caption: String? = null,
    val mediaType: MediaType = MediaType.PHOTO,
    val filePaths: List<String> = emptyList(),
    val thumbnailPath: String? = null,
    val previewUrl: String? = null,
    val audioTitle: String? = null,
    val audioArtist: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
) : Serializable

data class UserPostItem(
    val id: String,
    val shortcode: String,
    val mediaType: MediaType,
    val isVideo: Boolean,
    val displayUrl: String,
    val videoUrl: String? = null,
    val caption: String? = null,
    val timestamp: Long? = null,
    val likeCount: Int? = null,
    val commentCount: Int? = null,
    val carouselItemCount: Int = 1,
    val carouselMedia: List<MediaResult> = emptyList(),
    val authorUsername: String,
    val authorFullName: String? = null,
    val authorProfilePic: String? = null,
    val videoDuration: Double? = null
) : Serializable {
    val previewUrl: String get() = displayUrl
}

data class UserProfileFeed(
    val profile: InstagramUserProfile,
    val posts: List<UserPostItem>,
    val hasNextPage: Boolean = false,
    val endCursor: String? = null,
    val totalMediaCount: Int? = null
) : Serializable

data class BatchDownloadProgress(
    val totalPosts: Int = 0,
    val completedPosts: Int = 0,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val currentItemTitle: String = "",
    val currentStatusMessage: String = "",
    val isCancelled: Boolean = false,
    val isFinished: Boolean = false,
    val downloadedFolder: String? = null,
    val error: String? = null
) : Serializable

