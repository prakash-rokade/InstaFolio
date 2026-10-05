package com.prakash.instafolio.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prakash.instafolio.core.model.*
import com.prakash.instafolio.core.network.InstagramEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private val thumbnailMemoryCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun rememberCachedThumbnail(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    thumbnailMemoryCache[url]?.let { return it }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = InstagramEngine.fetchBytes(url)
                val img = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                thumbnailMemoryCache[url] = img
                img
            }.onSuccess { value = it }
        }
    }
    return bitmap
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopUserPostsWorkspace(
    settings: DesktopSettings,
    effectiveSession: IgSession?,
    useDarkTheme: Boolean,
    onOpenLightbox: (MediaResult) -> Unit,
    onOpenSettings: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val colorScheme = MaterialTheme.colorScheme

    var userInput by remember { mutableStateOf("") }
    var isLoadingFeed by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var feedError by remember { mutableStateOf<String?>(null) }
    var userFeed by remember { mutableStateOf<UserProfileFeed?>(null) }
    var selectedPostIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeFilter by remember { mutableStateOf<MediaType?>(null) }

    // Batch download state
    var isBatchDownloading by remember { mutableStateOf(false) }
    var batchProgress by remember { mutableStateOf<BatchDownloadProgress?>(null) }
    var batchDownloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadCompletedMessage by remember { mutableStateOf<String?>(null) }
    var completedUserFolder by remember { mutableStateOf<File?>(null) }

    val igGradient = Brush.horizontalGradient(
        colors = if (useDarkTheme) listOf(IgPurpleDark, IgPinkDark, IgOrangeDark)
        else listOf(IgPurple, IgPink, IgOrange)
    )

    val fetchFeed: () -> Unit = {
        val trimmed = userInput.trim()
        if (trimmed.isEmpty()) {
            feedError = "Please enter an Instagram username or profile URL"
        } else {
            feedError = null
            downloadCompletedMessage = null
            isLoadingFeed = true
            selectedPostIds = emptySet()
            userFeed = null

            coroutineScope.launch {
                try {
                    val feed = withContext(Dispatchers.IO) {
                        InstagramEngine.fetchUserProfileFeed(
                            usernameOrUrl = trimmed,
                            session = effectiveSession,
                            count = 24
                        )
                    }
                    userFeed = feed
                    // Default to selecting all loaded posts
                    selectedPostIds = feed.posts.map { it.id }.toSet()
                } catch (e: Exception) {
                    feedError = e.message ?: "Failed to fetch user profile & posts"
                } finally {
                    isLoadingFeed = false
                }
            }
        }
    }

    val loadMorePosts: () -> Unit = {
        val currentFeed = userFeed
        val cursor = currentFeed?.endCursor
        if (currentFeed != null && cursor != null && !isLoadingMore) {
            isLoadingMore = true
            coroutineScope.launch {
                try {
                    val moreFeed = withContext(Dispatchers.IO) {
                        InstagramEngine.fetchUserProfileFeed(
                            usernameOrUrl = currentFeed.profile.username,
                            session = effectiveSession,
                            cursor = cursor,
                            count = 24
                        )
                    }
                    val existingIds = currentFeed.posts.map { it.id }.toSet()
                    val newUniquePosts = moreFeed.posts.filter { !existingIds.contains(it.id) }
                    val updatedPosts = currentFeed.posts + newUniquePosts
                    userFeed = currentFeed.copy(
                        posts = updatedPosts,
                        hasNextPage = moreFeed.hasNextPage,
                        endCursor = moreFeed.endCursor
                    )
                    // Add newly loaded post IDs to selection
                    selectedPostIds = selectedPostIds + newUniquePosts.map { it.id }
                } catch (e: Exception) {
                    feedError = "Error loading more posts: ${e.message}"
                } finally {
                    isLoadingMore = false
                }
            }
        }
    }

    val cancelBatchDownload: () -> Unit = {
        batchDownloadJob?.cancel()
        isBatchDownloading = false
        batchProgress = batchProgress?.copy(isCancelled = true, currentStatusMessage = "Cancelled by user")
    }

    val startBatchDownload: () -> Unit = {
        val currentFeed = userFeed
        val posts = currentFeed?.posts ?: emptyList()
        val selectedPosts = posts.filter { selectedPostIds.contains(it.id) }

        if (selectedPosts.isEmpty() || currentFeed == null) {
            feedError = "Please select at least one post to download"
        } else {
            feedError = null
            downloadCompletedMessage = null
            isBatchDownloading = true
            val cleanUsername = currentFeed.profile.username.trim().removePrefix("@")
            val baseDir = File(settings.downloadFolderPath)
            val userFolder = File(baseDir, "@$cleanUsername")

            batchDownloadJob = coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        if (!userFolder.exists()) userFolder.mkdirs()

                        var completedPostsCount = 0
                        var totalFilesCount = 0

                        selectedPosts.forEachIndexed { index, post ->
                            if (!isBatchDownloading) return@withContext

                            batchProgress = BatchDownloadProgress(
                                totalPosts = selectedPosts.size,
                                completedPosts = index,
                                totalFiles = totalFilesCount,
                                completedFiles = totalFilesCount,
                                currentItemTitle = "Post ${index + 1}/${selectedPosts.size} (${post.shortcode})",
                                currentStatusMessage = "Processing post @$cleanUsername (${post.shortcode})...",
                                downloadedFolder = userFolder.absolutePath
                            )

                            val postShortcode = post.shortcode
                            val savedPaths = mutableListOf<String>()

                            // Check if carousel
                            if (post.carouselMedia.size > 1) {
                                // PDF Export if enabled
                                val savePdf = settings.photoSaveFormat == PhotoSaveFormat.PDF ||
                                        (settings.photoSaveFormat == PhotoSaveFormat.BOTH && post.carouselMedia.any { !it.isVideo })

                                if (savePdf) {
                                    val photoItems = post.carouselMedia.filter { !it.isVideo }
                                    if (photoItems.isNotEmpty()) {
                                        val imagesBytes = photoItems.map { InstagramEngine.fetchBytes(it.url) }
                                        val pdfFile = File(userFolder, "${cleanUsername}_${postShortcode}_album.pdf")
                                        FileOutputStream(pdfFile).use { fos ->
                                            DesktopPdfHelper.createPdfFromImages(imagesBytes, fos)
                                        }
                                        savedPaths.add(pdfFile.absolutePath)
                                        totalFilesCount++
                                    }
                                }

                                if (settings.photoSaveFormat != PhotoSaveFormat.PDF) {
                                    post.carouselMedia.forEachIndexed { cIdx, item ->
                                        val ext = if (item.isVideo) "mp4" else "jpg"
                                        val outFile = File(userFolder, "${cleanUsername}_${postShortcode}_${cIdx + 1}.$ext")
                                        FileOutputStream(outFile).use { fos ->
                                            InstagramEngine.downloadToStream(item.url, fos)
                                        }
                                        savedPaths.add(outFile.absolutePath)
                                        totalFilesCount++
                                    }
                                }
                            } else if (post.isVideo) {
                                // Single Video or Reel
                                val videoDownloadUrl = post.videoUrl ?: runCatching {
                                    InstagramEngine.getMediaItems("https://www.instagram.com/p/$postShortcode/", effectiveSession, settings.quality)
                                        .firstOrNull { it.isVideo }?.url
                                }.getOrNull() ?: post.displayUrl

                                val outFile = File(userFolder, "${cleanUsername}_${postShortcode}.mp4")
                                FileOutputStream(outFile).use { fos ->
                                    InstagramEngine.downloadToStream(videoDownloadUrl, fos)
                                }
                                savedPaths.add(outFile.absolutePath)
                                totalFilesCount++
                            } else {
                                // Single Photo
                                val photoUrl = post.displayUrl
                                val outFile = File(userFolder, "${cleanUsername}_${postShortcode}.jpg")
                                FileOutputStream(outFile).use { fos ->
                                    InstagramEngine.downloadToStream(photoUrl, fos)
                                }
                                savedPaths.add(outFile.absolutePath)
                                totalFilesCount++
                            }

                            // Record in History Store
                            settings.historyStore.add(
                                DownloadHistoryItem(
                                    url = "https://www.instagram.com/p/$postShortcode/",
                                    shortcode = postShortcode,
                                    authorUsername = cleanUsername,
                                    authorFullName = post.authorFullName,
                                    caption = post.caption,
                                    mediaType = post.mediaType,
                                    filePaths = savedPaths,
                                    thumbnailPath = post.displayUrl,
                                    previewUrl = post.previewUrl,
                                    timestamp = System.currentTimeMillis()
                                )
                            )

                            completedPostsCount++
                        }

                        completedUserFolder = userFolder
                        downloadCompletedMessage = "Downloaded $completedPostsCount posts ($totalFilesCount files) into folder: @$cleanUsername"
                        batchProgress = BatchDownloadProgress(
                            totalPosts = selectedPosts.size,
                            completedPosts = completedPostsCount,
                            totalFiles = totalFilesCount,
                            completedFiles = totalFilesCount,
                            isFinished = true,
                            downloadedFolder = userFolder.absolutePath,
                            currentStatusMessage = "Batch download completed successfully!"
                        )
                    }
                } catch (e: Exception) {
                    feedError = "Download interrupted: ${e.message}"
                } finally {
                    isBatchDownloading = false
                }
            }
        }
    }

    val visiblePosts = remember(userFeed?.posts, activeFilter) {
        val all = userFeed?.posts ?: emptyList()
        when (activeFilter) {
            MediaType.PHOTO -> all.filter { it.mediaType == MediaType.PHOTO }
            MediaType.VIDEO, MediaType.REEL -> all.filter { it.mediaType == MediaType.VIDEO || it.mediaType == MediaType.REEL || it.isVideo }
            MediaType.CAROUSEL -> all.filter { it.mediaType == MediaType.CAROUSEL }
            else -> all
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── TOP SEARCH / INPUT BAR ───────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(igGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = DesktopIcons.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                OutlinedTextField(
                    value = userInput,
                    onValueChange = { userInput = it },
                    placeholder = {
                        Text(
                            "Enter Instagram Username or Profile URL (e.g. @natgeo, cristiano, instagram.com/nike)",
                            style = MaterialTheme.typography.bodyMedium.copy(color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IgPink,
                        unfocusedBorderColor = colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (userInput.isNotEmpty()) {
                                IconButton(onClick = { userInput = "" }) {
                                    Icon(DesktopIcons.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                            IconButton(
                                onClick = {
                                    clipboardManager.getText()?.text?.let {
                                        userInput = it
                                        fetchFeed()
                                    }
                                }
                            ) {
                                Icon(DesktopIcons.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                )

                Button(
                    onClick = fetchFeed,
                    enabled = !isLoadingFeed && userInput.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(),
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (!isLoadingFeed && userInput.isNotBlank()) igGradient else Brush.linearGradient(listOf(Color.Gray, Color.DarkGray)))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 18.dp)
                    ) {
                        if (isLoadingFeed) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Text("Fetching Feed…", color = Color.White, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(DesktopIcons.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text("Fetch User Posts", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ── ERROR BANNER ───────────────────────────────────────────────────────
        feedError?.let { err ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colorScheme.errorContainer.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, colorScheme.error.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(DesktopIcons.Close, contentDescription = null, tint = colorScheme.error, modifier = Modifier.size(20.dp))
                    Text(err, color = colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { feedError = null }) {
                        Icon(DesktopIcons.Close, contentDescription = "Dismiss", tint = colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // ── SUCCESS DOWNLOAD BANNER ───────────────────────────────────────────
        downloadCompletedMessage?.let { msg ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF10B981).copy(alpha = 0.15f),
                border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(DesktopIcons.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(24.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Download Complete!", fontWeight = FontWeight.Bold, color = if (useDarkTheme) Color(0xFF34D399) else Color(0xFF065F46))
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurface)
                    }
                    completedUserFolder?.let { uDir ->
                        FilledTonalButton(
                            onClick = {
                                runCatching {
                                    if (Desktop.isDesktopSupported() && uDir.exists()) {
                                        Desktop.getDesktop().open(uDir)
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(DesktopIcons.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open Folder")
                        }
                    }
                    IconButton(onClick = { downloadCompletedMessage = null }) {
                        Icon(DesktopIcons.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // ── PROFILE HERO CARD & ACTION BAR ─────────────────────────────────────
        userFeed?.let { feed ->
            val profile = feed.profile
            val avatarBitmap = rememberCachedThumbnail(profile.profilePicUrl)
            val cleanUsername = profile.username.removePrefix("@")
            val targetFolder = File(settings.downloadFolderPath, "@$cleanUsername")

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Avatar
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .border(2.dp, igGradient, CircleShape)
                                .background(colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarBitmap != null) {
                                Image(
                                    bitmap = avatarBitmap,
                                    contentDescription = profile.username,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(DesktopIcons.Person, contentDescription = null, modifier = Modifier.size(32.dp), tint = colorScheme.onSurfaceVariant)
                            }
                        }

                        // User info
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "@${profile.username}",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                )
                                if (profile.isVerified) {
                                    Icon(DesktopIcons.Verified, contentDescription = "Verified", tint = Color(0xFF3897F0), modifier = Modifier.size(18.dp))
                                }
                                if (profile.isPrivate) {
                                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFF59E0B).copy(alpha = 0.2f)) {
                                        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(DesktopIcons.Lock, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(12.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Private", style = MaterialTheme.typography.labelSmall, color = Color(0xFFD97706))
                                        }
                                    }
                                }
                            }

                            val fullName = profile.fullName
                            if (!fullName.isNullOrBlank()) {
                                Text(fullName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            }

                            val biography = profile.biography
                            if (!biography.isNullOrBlank()) {
                                Text(
                                    biography,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Stats counters
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            UserStatPill(label = "Total Posts", value = "${profile.mediaCount ?: feed.posts.size}")
                            profile.followerCount?.let { UserStatPill(label = "Followers", value = formatCount(it)) }
                            profile.followingCount?.let { UserStatPill(label = "Following", value = formatCount(it)) }
                        }
                    }

                    HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.15f))

                    // Location path and change folder
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(DesktopIcons.Folder, contentDescription = null, tint = IgPink, modifier = Modifier.size(18.dp))
                            Text(
                                "Download Target: ",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                targetFolder.absolutePath,
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val frame = Frame()
                                    val dialog = FileDialog(frame, "Select Base Download Folder", FileDialog.LOAD)
                                    System.setProperty("apple.awt.fileDialogForDirectories", "true")
                                    dialog.isVisible = true
                                    val selectedDir = dialog.directory
                                    val selectedFile = dialog.file
                                    System.setProperty("apple.awt.fileDialogForDirectories", "false")
                                    frame.dispose()
                                    if (selectedDir != null) {
                                        val fullPath = if (selectedFile != null) File(selectedDir, selectedFile).absolutePath else selectedDir
                                        settings.downloadFolderPath = fullPath
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Change Base Folder", fontSize = 12.sp)
                            }

                            if (targetFolder.exists()) {
                                OutlinedButton(
                                    onClick = {
                                        runCatching { Desktop.getDesktop().open(targetFolder) }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(DesktopIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Open Target Folder", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ── ACTION & SELECTION CONTROLS ─────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colorScheme.surfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Filter Chips
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val allCount = feed.posts.size
                        val photoCount = feed.posts.count { it.mediaType == MediaType.PHOTO }
                        val videoCount = feed.posts.count { it.mediaType == MediaType.VIDEO || it.mediaType == MediaType.REEL || it.isVideo }
                        val carouselCount = feed.posts.count { it.mediaType == MediaType.CAROUSEL }

                        FilterChip(
                            selected = activeFilter == null,
                            onClick = { activeFilter = null },
                            label = { Text("All ($allCount)") }
                        )
                        FilterChip(
                            selected = activeFilter == MediaType.PHOTO,
                            onClick = { activeFilter = if (activeFilter == MediaType.PHOTO) null else MediaType.PHOTO },
                            label = { Text("Photos ($photoCount)") }
                        )
                        FilterChip(
                            selected = activeFilter == MediaType.VIDEO || activeFilter == MediaType.REEL,
                            onClick = { activeFilter = if (activeFilter == MediaType.VIDEO) null else MediaType.VIDEO },
                            label = { Text("Videos/Reels ($videoCount)") }
                        )
                        FilterChip(
                            selected = activeFilter == MediaType.CAROUSEL,
                            onClick = { activeFilter = if (activeFilter == MediaType.CAROUSEL) null else MediaType.CAROUSEL },
                            label = { Text("Carousels ($carouselCount)") }
                        )
                    }

                    // Right: Select All & Download Action
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val currentVisibleIds = visiblePosts.map { it.id }.toSet()
                        val allVisibleSelected = currentVisibleIds.isNotEmpty() && currentVisibleIds.all { selectedPostIds.contains(it) }

                        OutlinedButton(
                            onClick = {
                                selectedPostIds = if (allVisibleSelected) {
                                    selectedPostIds - currentVisibleIds
                                } else {
                                    selectedPostIds + currentVisibleIds
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                if (allVisibleSelected) DesktopIcons.CheckBoxOutlineBlank else DesktopIcons.SelectAll,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (allVisibleSelected) "Deselect All" else "Select All (${visiblePosts.size})")
                        }

                        // Selected count pill
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (selectedPostIds.isNotEmpty()) IgPink.copy(alpha = 0.15f) else colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "${selectedPostIds.size} of ${feed.posts.size} Selected",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (selectedPostIds.isNotEmpty()) IgPink else colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }

                        // Primary Download Button
                        Button(
                            onClick = startBatchDownload,
                            enabled = selectedPostIds.isNotEmpty() && !isBatchDownloading,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues(),
                            modifier = Modifier
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selectedPostIds.isNotEmpty() && !isBatchDownloading) igGradient
                                    else Brush.linearGradient(listOf(Color.Gray, Color.DarkGray))
                                )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                Icon(DesktopIcons.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Text(
                                    "Download Selected (${selectedPostIds.size} Posts)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── LIVE BATCH DOWNLOAD PROGRESS CARD ────────────────────────────────
        if (isBatchDownloading) {
            batchProgress?.let { prog ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colorScheme.surfaceVariant.copy(alpha = 0.8f),
                    border = BorderStroke(1.5.dp, IgPink),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = IgPink)
                                Text(
                                    "Downloading in Progress… (${prog.completedPosts}/${prog.totalPosts} posts)",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Button(
                                onClick = cancelBatchDownload,
                                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Cancel", color = Color.White, fontSize = 12.sp)
                            }
                        }

                        val progressRatio = if (prog.totalPosts > 0) prog.completedPosts.toFloat() / prog.totalPosts.toFloat() else 0f
                        LinearProgressIndicator(
                            progress = { progressRatio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = IgPink,
                            trackColor = colorScheme.surfaceVariant
                        )

                        Text(
                            text = prog.currentStatusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── POSTS GRID ────────────────────────────────────────────────────────
        val currentFeed = userFeed
        if (currentFeed != null) {
            if (visiblePosts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(DesktopIcons.PhotoLibrary, contentDescription = null, modifier = Modifier.size(48.dp), tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Text("No posts match the active filter", style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 220.dp),
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(visiblePosts, key = { it.id }) { post ->
                        val isSelected = selectedPostIds.contains(post.id)
                        UserPostGridCard(
                            post = post,
                            isSelected = isSelected,
                            onToggleSelected = {
                                selectedPostIds = if (isSelected) {
                                    selectedPostIds - post.id
                                } else {
                                    selectedPostIds + post.id
                                }
                            },
                            onPreviewLightbox = {
                                val mediaRes = if (post.carouselMedia.isNotEmpty()) {
                                    post.carouselMedia.first()
                                } else {
                                    MediaResult(
                                        url = post.videoUrl ?: post.displayUrl,
                                        isVideo = post.isVideo,
                                        thumbnailUrl = post.displayUrl,
                                        caption = post.caption,
                                        authorUsername = post.authorUsername,
                                        authorFullName = post.authorFullName,
                                        authorProfilePic = post.authorProfilePic,
                                        timestamp = post.timestamp,
                                        shortcode = post.shortcode
                                    )
                                }
                                onOpenLightbox(mediaRes)
                            }
                        )
                    }

                    // Load More Footer
                    if (currentFeed.hasNextPage) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(
                                    onClick = loadMorePosts,
                                    enabled = !isLoadingMore,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(44.dp)
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Loading next 24 posts…")
                                    } else {
                                        Icon(DesktopIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Load More Posts (${currentFeed.posts.size} Loaded)")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else if (!isLoadingFeed) {
            // Empty State
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.widthIn(max = 500.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(IgPink.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(DesktopIcons.GridView, contentDescription = null, modifier = Modifier.size(40.dp), tint = IgPink)
                    }
                    Text(
                        "Batch User Posts Downloader",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        "Enter any public Instagram handle or profile link above to explore all their photos, reels, and carousels. Select all or pick specific posts to download them organized neatly inside a user-named folder!",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun UserStatPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UserPostGridCard(
    post: UserPostItem,
    isSelected: Boolean,
    onToggleSelected: () -> Unit,
    onPreviewLightbox: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val thumbnailBitmap = rememberCachedThumbnail(post.displayUrl)
    val formattedDate = remember(post.timestamp) {
        post.timestamp?.let {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(it * 1000))
        } ?: ""
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onToggleSelected() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        border = if (isSelected) BorderStroke(2.5.dp, IgPink) else BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail Image
            if (thumbnailBitmap != null) {
                Image(
                    bitmap = thumbnailBitmap,
                    contentDescription = post.caption,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = IgPink)
                }
            }

            // Top Gradient & Badges
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .align(Alignment.TopCenter)
            )

            // Top-Left Checkbox
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSelected) IgPink else Color.Black.copy(alpha = 0.5f),
                    border = BorderStroke(1.5.dp, if (isSelected) Color.White else Color.White.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .size(26.dp)
                        .clickable { onToggleSelected() }
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = DesktopIcons.CheckCircle,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.dp)
                        )
                    }
                }
            }

            // Top-Right Media Type Badge
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        when (post.mediaType) {
                            MediaType.CAROUSEL -> {
                                Icon(DesktopIcons.PhotoLibrary, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color.White)
                                Text("${post.carouselItemCount}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            MediaType.VIDEO, MediaType.REEL -> {
                                Icon(DesktopIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color.White)
                                Text("Video", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            else -> {
                                Icon(DesktopIcons.Image, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }

            // Bottom Gradient & Info
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .align(Alignment.BottomCenter)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (formattedDate.isNotBlank()) {
                        Text(
                            text = formattedDate,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                    val caption = post.caption
                    if (!caption.isNullOrBlank()) {
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Eye / Lightbox Button
                Surface(
                    onClick = onPreviewLightbox,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(DesktopIcons.Visibility, contentDescription = "Preview", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}
