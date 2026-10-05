package com.prakash.instafolio

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prakash.instafolio.core.model.*
import com.prakash.instafolio.core.network.InstagramEngine
import com.prakash.instafolio.ui.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private val IgPurple = Color(0xFF833AB4)
private val IgPink = Color(0xFFE1306C)
private val IgOrange = Color(0xFFF77737)

private val androidThumbnailCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun rememberAndroidCachedThumbnail(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    androidThumbnailCache[url]?.let { return it }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = InstagramEngine.fetchBytes(url)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                if (bmp != null) androidThumbnailCache[url] = bmp
                bmp
            }.onSuccess { value = it }
        }
    }
    return bitmap
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidUserPostsScreen(
    settings: AppSettings,
    effectiveSession: IgSession?,
    useDarkTheme: Boolean,
    onOpenLightbox: (MediaResult, Int) -> Unit,
    onHapticFeedback: () -> Unit
) {
    val context = LocalContext.current
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

    var isBatchDownloading by remember { mutableStateOf(false) }
    var batchProgress by remember { mutableStateOf<BatchDownloadProgress?>(null) }
    var batchDownloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadCompletedMessage by remember { mutableStateOf<String?>(null) }

    val igGradient = Brush.horizontalGradient(listOf(IgPurple, IgPink, IgOrange))

    val fetchFeed: () -> Unit = {
        val trimmed = userInput.trim()
        if (trimmed.isEmpty()) {
            feedError = "Please enter an Instagram username or profile link"
        } else {
            feedError = null
            downloadCompletedMessage = null
            isLoadingFeed = true
            selectedPostIds = emptySet()
            userFeed = null
            onHapticFeedback()

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
                    selectedPostIds = feed.posts.map { it.id }.toSet()
                } catch (e: Exception) {
                    feedError = e.message ?: "Failed to fetch user posts"
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
                    selectedPostIds = selectedPostIds + newUniquePosts.map { it.id }
                } catch (e: Exception) {
                    feedError = "Error loading more: ${e.message}"
                } finally {
                    isLoadingMore = false
                }
            }
        }
    }

    val cancelBatchDownload: () -> Unit = {
        batchDownloadJob?.cancel()
        isBatchDownloading = false
        batchProgress = batchProgress?.copy(isCancelled = true, currentStatusMessage = "Cancelled")
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
            onHapticFeedback()

            val cleanUsername = currentFeed.profile.username.trim().removePrefix("@")

            batchDownloadJob = coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        var completedCount = 0
                        var totalFiles = 0

                        selectedPosts.forEachIndexed { index, post ->
                            if (!isBatchDownloading) return@withContext

                            batchProgress = BatchDownloadProgress(
                                totalPosts = selectedPosts.size,
                                completedPosts = index,
                                totalFiles = totalFiles,
                                completedFiles = totalFiles,
                                currentItemTitle = "Post ${index + 1}/${selectedPosts.size} (${post.shortcode})",
                                currentStatusMessage = "Downloading post ${index + 1} of ${selectedPosts.size} (@$cleanUsername)..."
                            )

                            val postShortcode = post.shortcode
                            val savedUris = mutableListOf<String>()

                            if (post.carouselMedia.size > 1) {
                                val savePdf = settings.photoSaveFormat == PhotoSaveFormat.PDF ||
                                        (settings.photoSaveFormat == PhotoSaveFormat.BOTH && post.carouselMedia.any { !it.isVideo })

                                if (savePdf) {
                                    val photoItems = post.carouselMedia.filter { !it.isVideo }
                                    if (photoItems.isNotEmpty()) {
                                        val imagesBytes = photoItems.map { InstagramEngine.fetchBytes(it.url) }
                                        val pdfFile = File(context.cacheDir, "${cleanUsername}_${postShortcode}_album.pdf")
                                        FileOutputStream(pdfFile).use { fos ->
                                            PdfHelper.createPdfFromImages(imagesBytes, fos)
                                        }
                                        savedUris.add(pdfFile.absolutePath)
                                        totalFiles++
                                    }
                                }

                                if (settings.photoSaveFormat != PhotoSaveFormat.PDF) {
                                    post.carouselMedia.forEachIndexed { cIdx, item ->
                                        val ext = if (item.isVideo) "mp4" else "jpg"
                                        val mime = if (item.isVideo) "video/mp4" else "image/jpeg"
                                        val fileName = "${cleanUsername}_${postShortcode}_${cIdx + 1}.$ext"
                                        saveToMediaStore(context, item.url, fileName, mime, cleanUsername, item.isVideo)?.let {
                                            savedUris.add(it)
                                            totalFiles++
                                        }
                                    }
                                }
                            } else if (post.isVideo) {
                                val videoUrl = post.videoUrl ?: runCatching {
                                    InstagramEngine.getMediaItems("https://www.instagram.com/p/$postShortcode/", effectiveSession, settings.quality)
                                        .firstOrNull { it.isVideo }?.url
                                }.getOrNull() ?: post.displayUrl

                                val fileName = "${cleanUsername}_${postShortcode}.mp4"
                                saveToMediaStore(context, videoUrl, fileName, "video/mp4", cleanUsername, true)?.let {
                                    savedUris.add(it)
                                    totalFiles++
                                }
                            } else {
                                val fileName = "${cleanUsername}_${postShortcode}.jpg"
                                saveToMediaStore(context, post.displayUrl, fileName, "image/jpeg", cleanUsername, false)?.let {
                                    savedUris.add(it)
                                    totalFiles++
                                }
                            }

                            settings.historyStore.add(
                                DownloadHistoryItem(
                                    url = "https://www.instagram.com/p/$postShortcode/",
                                    shortcode = postShortcode,
                                    authorUsername = cleanUsername,
                                    authorFullName = post.authorFullName,
                                    caption = post.caption,
                                    mediaType = post.mediaType,
                                    filePaths = savedUris,
                                    thumbnailPath = post.displayUrl,
                                    previewUrl = post.previewUrl,
                                    timestamp = System.currentTimeMillis()
                                )
                            )

                            completedCount++
                        }

                        downloadCompletedMessage = "Downloaded $completedCount posts ($totalFiles files) into @$cleanUsername folder!"
                        batchProgress = BatchDownloadProgress(
                            totalPosts = selectedPosts.size,
                            completedPosts = completedCount,
                            totalFiles = totalFiles,
                            completedFiles = totalFiles,
                            isFinished = true,
                            currentStatusMessage = "Batch download finished!"
                        )
                    }
                } catch (e: Exception) {
                    feedError = "Download error: ${e.message}"
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
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── SEARCH BAR ────────────────────────────────────────────────────────
        OutlinedTextField(
            value = userInput,
            onValueChange = { userInput = it },
            placeholder = { Text("Enter Instagram @username or link…", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (userInput.isNotEmpty()) {
                        IconButton(onClick = { userInput = "" }) {
                            Icon(AppIcons.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
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
                        Icon(AppIcons.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(18.dp))
                    }
                }
            }
        )

        Button(
            onClick = fetchFeed,
            enabled = !isLoadingFeed && userInput.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = IgPink)
        ) {
            if (isLoadingFeed) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Fetching Feed…", fontWeight = FontWeight.Bold)
            } else {
                Icon(AppIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fetch User Posts", fontWeight = FontWeight.Bold)
            }
        }

        // ── ERROR BANNER ───────────────────────────────────────────────────────
        feedError?.let { err ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(err, color = colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { feedError = null }) {
                        Icon(AppIcons.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // ── SUCCESS BANNER ────────────────────────────────────────────────────
        downloadCompletedMessage?.let { msg ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF10B981).copy(alpha = 0.15f),
                border = BorderStroke(1.dp, Color(0xFF10B981)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { downloadCompletedMessage = null }) {
                        Icon(AppIcons.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // ── PROFILE HEADER & ACTIONS ──────────────────────────────────────────
        userFeed?.let { feed ->
            val profile = feed.profile
            val avatarBitmap = rememberAndroidCachedThumbnail(profile.profilePicUrl)

            ElevatedCard(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, IgPink, CircleShape)
                                .background(colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarBitmap != null) {
                                Image(
                                    bitmap = avatarBitmap,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(AppIcons.Person, contentDescription = null, modifier = Modifier.size(24.dp))
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("@${profile.username}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                if (profile.isVerified) {
                                    Icon(AppIcons.Verified, contentDescription = null, tint = Color(0xFF3897F0), modifier = Modifier.size(14.dp))
                                }
                            }
                            val fullName = profile.fullName
                            if (!fullName.isNullOrBlank()) {
                                Text(fullName, style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        Text(
                            "Posts: ${profile.mediaCount ?: feed.posts.size}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    // Target Folder Note
                    Text(
                        "📁 Saved to: Pictures/InstaFolio/@${profile.username}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            // Selection and Filters Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val currentVisibleIds = visiblePosts.map { it.id }.toSet()
                val allSelected = currentVisibleIds.isNotEmpty() && currentVisibleIds.all { selectedPostIds.contains(it) }

                OutlinedButton(
                    onClick = {
                        selectedPostIds = if (allSelected) {
                            selectedPostIds - currentVisibleIds
                        } else {
                            selectedPostIds + currentVisibleIds
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(if (allSelected) "Deselect All" else "Select All (${visiblePosts.size})", fontSize = 11.sp)
                }

                Text(
                    "${selectedPostIds.size} Selected",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = IgPink
                )

                Button(
                    onClick = startBatchDownload,
                    enabled = selectedPostIds.isNotEmpty() && !isBatchDownloading,
                    colors = ButtonDefaults.buttonColors(containerColor = IgPink),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download (${selectedPostIds.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── PROGRESS BAR ──────────────────────────────────────────────────────
        if (isBatchDownloading) {
            batchProgress?.let { prog ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Downloading: ${prog.completedPosts}/${prog.totalPosts} posts",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            TextButton(onClick = cancelBatchDownload, contentPadding = PaddingValues(0.dp)) {
                                Text("Cancel", color = colorScheme.error, fontSize = 12.sp)
                            }
                        }
                        val ratio = if (prog.totalPosts > 0) prog.completedPosts.toFloat() / prog.totalPosts.toFloat() else 0f
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = IgPink
                        )
                    }
                }
            }
        }

        // ── GRID OF POSTS ─────────────────────────────────────────────────────
        val currentFeed = userFeed
        if (currentFeed != null) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visiblePosts, key = { it.id }) { post ->
                    val isSelected = selectedPostIds.contains(post.id)
                    val thumbnail = rememberAndroidCachedThumbnail(post.displayUrl)

                    Card(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selectedPostIds = if (isSelected) selectedPostIds - post.id else selectedPostIds + post.id
                            },
                        shape = RoundedCornerShape(8.dp),
                        border = if (isSelected) BorderStroke(2.dp, IgPink) else null
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (thumbnail != null) {
                                Image(
                                    bitmap = thumbnail,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = IgPink)
                                }
                            }

                            // Checkbox Top-Left
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSelected) IgPink else Color.Black.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(18.dp)
                                    .align(Alignment.TopStart)
                            ) {
                                if (isSelected) {
                                    Icon(AppIcons.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.padding(1.dp))
                                }
                            }

                            // Media Type Top-Right
                            if (post.mediaType != MediaType.PHOTO) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color.Black.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(4.dp).align(Alignment.TopEnd)
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (post.mediaType == MediaType.CAROUSEL) {
                                            Icon(AppIcons.PhotoLibrary, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                                            Spacer(Modifier.width(2.dp))
                                            Text("${post.carouselItemCount}", fontSize = 9.sp, color = Color.White)
                                        } else {
                                            Icon(AppIcons.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (currentFeed.hasNextPage) {
                    item(span = { GridItemSpan(3) }) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = loadMorePosts, enabled = !isLoadingMore) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Loading…", fontSize = 12.sp)
                                } else {
                                    Text("Load More Posts", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        } else if (!isLoadingFeed) {
            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(AppIcons.GridView, contentDescription = null, modifier = Modifier.size(48.dp), tint = IgPink.copy(alpha = 0.6f))
                    Text("User Posts Downloader", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Enter any Instagram username above to browse all posts and download them into a user folder.", textAlign = TextAlign.Center, fontSize = 12.sp, color = colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun saveToMediaStore(
    context: Context,
    downloadUrl: String,
    fileName: String,
    mimeType: String,
    username: String,
    isVideo: Boolean
): String? {
    val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relDir/InstaFolio/@$username")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val uri = context.contentResolver.insert(collection, values) ?: return null
    context.contentResolver.openOutputStream(uri)?.use { out ->
        InstagramEngine.downloadToStream(downloadUrl, out)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
    }
    return uri.toString()
}
