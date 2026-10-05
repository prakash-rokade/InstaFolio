package com.prakash.instafolio.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.prakash.instafolio.core.audio.Id3TagHelper
import com.prakash.instafolio.core.model.*
import com.prakash.instafolio.core.network.InstagramEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.regex.Pattern

enum class DesktopNavigationTab(val label: String) {
    STUDIO("Studio Workspace"),
    USER_POSTS("User Posts Downloader"),
    VAULT("Archive Vault (History)")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun DesktopDownloaderScreen(
    settings: DesktopSettings = remember { DesktopSettings() },
    useDarkTheme: Boolean = false,
    onThemeChanged: (AppTheme) -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(DesktopNavigationTab.STUDIO) }
    var url by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isSavingAudio by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var fullError by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<List<MediaResult>?>(null) }
    var deselectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var downloadComplete by remember { mutableStateOf(false) }
    var audioDownloadComplete by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAccountSwitcher by remember { mutableStateOf(false) }
    var selectedFolderName by remember { mutableStateOf(settings.downloadFolderPath) }

    // Lightbox & Preview state
    var lightboxMediaItem by remember { mutableStateOf<MediaResult?>(null) }
    var lightboxInitialIndex by remember { mutableStateOf(0) }

    val coroutineScope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    // Observe account store & history store changes
    var accountVersion by remember { mutableStateOf(0) }
    var historyVersion by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val accListener: () -> Unit = { accountVersion++ }
        val histListener: () -> Unit = { historyVersion++ }
        settings.accountStore.addListener(accListener)
        settings.historyStore.addListener(histListener)
        onDispose {
            settings.accountStore.removeListener(accListener)
            settings.historyStore.removeListener(histListener)
        }
    }

    val activeAccount = remember(accountVersion) { settings.accountStore.getActiveAccount() }
    val effectiveSession = remember(activeAccount, settings.useInstagramLogin) {
        if (settings.useInstagramLogin) activeAccount?.toIgSession() else null
    }

    val igGradient = Brush.verticalGradient(
        colors = if (useDarkTheme) {
            listOf(IgPurpleDark, IgPinkDark, IgOrangeDark)
        } else {
            listOf(IgPurple, IgPink, IgOrange)
        }
    )

    // Account verification effect
    LaunchedEffect(activeAccount?.id) {
        activeAccount?.let { acc ->
            if (acc.username.isBlank() || acc.fullName.isNullOrBlank() || acc.health.status == SessionHealthStatus.UNCHECKED) {
                val session = acc.toIgSession()
                val health = withContext(Dispatchers.IO) {
                    InstagramEngine.checkSessionHealth(session)
                }
                settings.accountStore.updateHealth(acc.id, health)
            }
        }
    }

    val extractMedia: () -> Unit = {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            urlError = "Please enter an Instagram URL"
        } else if (!isInstagramUrl(trimmed)) {
            urlError = "Invalid Instagram URL format"
        } else {
            urlError = null
            fullError = null
            isLoading = true
            downloadComplete = false
            audioDownloadComplete = false
            media = null
            deselectedIndices = emptySet()

            coroutineScope.launch {
                try {
                    val results = withContext(Dispatchers.IO) {
                        InstagramEngine.getMediaItems(
                            postUrl = trimmed,
                            session = effectiveSession,
                            quality = settings.quality
                        )
                    }
                    if (results.isEmpty()) {
                        fullError = "No media found in this Instagram post"
                    } else {
                        media = results
                    }
                } catch (e: Exception) {
                    fullError = e.message ?: "Failed to fetch media from Instagram"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val onDownloadAudio: (AudioTrack?, List<MediaResult>) -> Unit = { audioTrack, mediaList ->
        isSavingAudio = true
        audioDownloadComplete = false
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val destDir = File(settings.downloadFolderPath)
                    if (!destDir.exists()) destDir.mkdirs()

                    val targetAudioUrl = audioTrack?.audioUrl
                        ?: mediaList.firstOrNull { it.isVideo }?.url
                        ?: throw Exception("No audio stream available in this post")

                    val rawAudioBytes = InstagramEngine.fetchBytes(targetAudioUrl)
                    val firstItem = mediaList.firstOrNull()
                    val authorName = firstItem?.authorUsername ?: "instagram"
                    val songTitle = audioTrack?.title ?: firstItem?.shortcode ?: "Audio_${System.currentTimeMillis()}"
                    val artistName = audioTrack?.artist ?: authorName

                    // Fetch album art if available
                    val coverBytes = runCatching {
                        val coverUrl = audioTrack?.coverUrl ?: firstItem?.thumbnailUrl
                        if (!coverUrl.isNullOrBlank()) InstagramEngine.fetchBytes(coverUrl) else null
                    }.getOrNull()

                    val safeAuthor = authorName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val safeTitle = songTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val fileName = "${safeAuthor}_${safeTitle}.mp3"
                    val outputFile = File(destDir, fileName)

                    FileOutputStream(outputFile).use { fos ->
                        Id3TagHelper.writeTaggedMp3(
                            rawAudioBytes = rawAudioBytes,
                            out = fos,
                            title = songTitle,
                            artist = artistName,
                            albumName = "Instagram Audio",
                            albumArtBytes = coverBytes
                        )
                    }

                    // Record in History Store
                    settings.historyStore.add(
                        DownloadHistoryItem(
                            url = url,
                            shortcode = firstItem?.shortcode,
                            authorUsername = authorName,
                            authorFullName = firstItem?.authorFullName,
                            caption = firstItem?.caption,
                            mediaType = MediaType.AUDIO,
                            filePaths = listOf(outputFile.absolutePath),
                            thumbnailPath = firstItem?.thumbnailUrl,
                            previewUrl = firstItem?.previewUrl,
                            audioTitle = songTitle,
                            audioArtist = artistName,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                audioDownloadComplete = true
            } catch (e: Exception) {
                fullError = "Audio export error: ${e.message}"
            } finally {
                isSavingAudio = false
            }
        }
    }

    val onDownloadMedia: () -> Unit = {
        val currentMedia = media
        if (currentMedia != null && currentMedia.isNotEmpty()) {
            isSaving = true
            downloadComplete = false
            coroutineScope.launch {
                try {
                    val savedPaths = mutableListOf<String>()
                    withContext(Dispatchers.IO) {
                        val destDir = File(settings.downloadFolderPath)
                        if (!destDir.exists()) destDir.mkdirs()

                        val selectedItems = currentMedia.filterIndexed { index, _ ->
                            !deselectedIndices.contains(index)
                        }
                        val itemsToProcess = if (selectedItems.isEmpty()) currentMedia else selectedItems
                        val isMulti = itemsToProcess.size > 1
                        val firstItem = itemsToProcess.firstOrNull()
                        val shortcode = firstItem?.shortcode ?: System.currentTimeMillis().toString()
                        val author = firstItem?.authorUsername ?: "instagram"

                        // PDF Export
                        val savePdf = settings.photoSaveFormat == PhotoSaveFormat.PDF ||
                                (settings.photoSaveFormat == PhotoSaveFormat.BOTH && itemsToProcess.any { !it.isVideo })

                        if (savePdf) {
                            val photoItems = itemsToProcess.filter { !it.isVideo }
                            if (photoItems.isNotEmpty()) {
                                val imagesBytes = photoItems.map { InstagramEngine.fetchBytes(it.url) }
                                val pdfFile = File(destDir, "${author}_${shortcode}_album.pdf")
                                FileOutputStream(pdfFile).use { fos ->
                                    DesktopPdfHelper.createPdfFromImages(imagesBytes, fos)
                                }
                                savedPaths.add(pdfFile.absolutePath)
                            }
                        }

                        // Individual Media Export
                        if (settings.photoSaveFormat != PhotoSaveFormat.PDF) {
                            itemsToProcess.forEachIndexed { idx, item ->
                                val ext = if (item.isVideo) "mp4" else "jpg"
                                val suffix = if (isMulti) "_${idx + 1}" else ""
                                val fileName = "${author}_${shortcode}$suffix.$ext"
                                val outFile = File(destDir, fileName)
                                FileOutputStream(outFile).use { fos ->
                                    InstagramEngine.downloadToStream(item.url, fos)
                                }
                                savedPaths.add(outFile.absolutePath)
                            }
                        }

                        // Record in History Store
                        val computedType = when {
                            savePdf && settings.photoSaveFormat == PhotoSaveFormat.PDF -> MediaType.PDF
                            itemsToProcess.size > 1 -> MediaType.CAROUSEL
                            itemsToProcess.first().isVideo -> MediaType.REEL
                            else -> MediaType.PHOTO
                        }

                        settings.historyStore.add(
                            DownloadHistoryItem(
                                url = url,
                                shortcode = shortcode,
                                authorUsername = author,
                                authorFullName = firstItem?.authorFullName,
                                caption = firstItem?.caption,
                                mediaType = computedType,
                                filePaths = savedPaths,
                                thumbnailPath = firstItem?.thumbnailUrl,
                                previewUrl = firstItem?.previewUrl,
                                audioTitle = firstItem?.audioTrack?.title,
                                audioArtist = firstItem?.audioTrack?.artist,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                    downloadComplete = true
                } catch (e: Exception) {
                    fullError = "Download error: ${e.message}"
                } finally {
                    isSaving = false
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        val isWide = maxWidth >= 860.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // ── TOP NAVIGATION BAR & HEADER ────────────────────────────────
            Surface(
                color = colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Brand & Tabs
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(igGradient),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = DesktopIcons.Download,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                text = "InstaFolio",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.5).sp
                                )
                            )
                        }

                        // Navigation Tab Switcher
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(modifier = Modifier.padding(3.dp)) {
                                TabButton(
                                    selected = selectedTab == DesktopNavigationTab.STUDIO,
                                    label = "Single URL Downloader",
                                    icon = DesktopIcons.Sparkles,
                                    onClick = { selectedTab = DesktopNavigationTab.STUDIO }
                                )
                                TabButton(
                                    selected = selectedTab == DesktopNavigationTab.USER_POSTS,
                                    label = "User Posts Downloader",
                                    icon = DesktopIcons.GridView,
                                    onClick = { selectedTab = DesktopNavigationTab.USER_POSTS }
                                )
                                TabButton(
                                    selected = selectedTab == DesktopNavigationTab.VAULT,
                                    label = "Archive Vault (${settings.historyStore.getAll().size})",
                                    icon = DesktopIcons.History,
                                    onClick = { selectedTab = DesktopNavigationTab.VAULT }
                                )
                            }
                        }
                    }

                    // Top Action Bar: Account & Settings
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Account Status Pill (Clickable)
                        Surface(
                            onClick = { showAccountSwitcher = true },
                            shape = RoundedCornerShape(20.dp),
                            color = if (effectiveSession != null) Color(0xFF10B981).copy(alpha = 0.15f)
                            else if (settings.isLoggedIn) Color(0xFFF59E0B).copy(alpha = 0.15f)
                            else colorScheme.surfaceVariant
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (effectiveSession != null) Color(0xFF10B981)
                                            else if (settings.isLoggedIn) Color(0xFFF59E0B)
                                            else Color.Gray
                                        )
                                )
                                Text(
                                    text = if (effectiveSession != null) {
                                        activeAccount?.let { "@${it.username}" } ?: "Active Session"
                                    } else "Guest Mode",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Icon(
                                    imageVector = DesktopIcons.SwitchAccount,
                                    contentDescription = "Switch Account",
                                    modifier = Modifier.size(14.dp),
                                    tint = colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = { showSettings = true },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(DesktopIcons.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // ── MAIN CONTENT AREA ──────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                when (selectedTab) {
                    DesktopNavigationTab.STUDIO -> {
                        DesktopStudioWorkspace(
                            isWide = isWide,
                            url = url,
                            onUrlChange = { url = it },
                            urlError = urlError,
                            fullError = fullError,
                            isLoading = isLoading,
                            isSaving = isSaving,
                            isSavingAudio = isSavingAudio,
                            media = media,
                            deselectedIndices = deselectedIndices,
                            downloadComplete = downloadComplete,
                            audioDownloadComplete = audioDownloadComplete,
                            effectiveSession = effectiveSession,
                            settings = settings,
                            selectedFolderName = selectedFolderName,
                            onClearUrl = { url = ""; media = null; urlError = null; fullError = null },
                            onPasteUrl = {
                                clipboardManager.getText()?.text?.let {
                                    url = it
                                    extractMedia()
                                }
                            },
                            onExtractMedia = extractMedia,
                            onToggleIndex = { idx ->
                                deselectedIndices = if (deselectedIndices.contains(idx)) {
                                    deselectedIndices - idx
                                } else {
                                    deselectedIndices + idx
                                }
                            },
                            onToggleSelectAll = {
                                media?.let { m ->
                                    deselectedIndices = if (deselectedIndices.isEmpty()) {
                                        m.indices.toSet()
                                    } else {
                                        emptySet()
                                    }
                                }
                            },
                            onDownloadMedia = onDownloadMedia,
                            onDownloadAudio = {
                                media?.let { m ->
                                    onDownloadAudio(m.firstOrNull()?.audioTrack, m)
                                }
                            },
                            onOpenLightbox = { item, idx ->
                                lightboxMediaItem = item
                                lightboxInitialIndex = idx
                            },
                            onChooseFolder = {
                                val frame = Frame()
                                val dialog = FileDialog(frame, "Select Download Folder", FileDialog.LOAD)
                                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                                dialog.isVisible = true
                                val selectedDir = dialog.directory
                                val selectedFile = dialog.file
                                System.setProperty("apple.awt.fileDialogForDirectories", "false")
                                frame.dispose()
                                if (selectedDir != null) {
                                    val fullPath = if (selectedFile != null) File(selectedDir, selectedFile).absolutePath else selectedDir
                                    settings.downloadFolderPath = fullPath
                                    selectedFolderName = fullPath
                                }
                            },
                            onOpenSettings = { showSettings = true },
                            onDismissError = { fullError = null }
                        )
                    }

                    DesktopNavigationTab.USER_POSTS -> {
                        DesktopUserPostsWorkspace(
                            settings = settings,
                            effectiveSession = effectiveSession,
                            useDarkTheme = useDarkTheme,
                            onOpenLightbox = { item ->
                                lightboxMediaItem = item
                                lightboxInitialIndex = 0
                            },
                            onOpenSettings = { showSettings = true }
                        )
                    }

                    DesktopNavigationTab.VAULT -> {
                        DesktopHistoryScreen(
                            historyStore = settings.historyStore,
                            onOpenLightbox = { item ->
                                val mediaRes = MediaResult(
                                    url = item.previewUrl ?: item.url,
                                    isVideo = item.mediaType == MediaType.REEL || item.mediaType == MediaType.VIDEO,
                                    thumbnailUrl = item.thumbnailPath ?: item.previewUrl,
                                    caption = item.caption,
                                    authorUsername = item.authorUsername,
                                    authorFullName = item.authorFullName,
                                    audioTrack = item.audioTitle?.let {
                                        AudioTrack(title = it, artist = item.audioArtist)
                                    },
                                    shortcode = item.shortcode
                                )
                                lightboxMediaItem = mediaRes
                                lightboxInitialIndex = 0
                            },
                            onReExportPdf = { item ->
                                coroutineScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val validImages = item.filePaths
                                                .map { File(it) }
                                                .filter { it.exists() && (it.name.endsWith(".jpg") || it.name.endsWith(".png")) }
                                                .map { it.readBytes() }

                                            if (validImages.isNotEmpty()) {
                                                val destDir = File(settings.downloadFolderPath)
                                                val pdfFile = File(destDir, "${item.authorUsername ?: "album"}_${item.shortcode ?: "reexport"}.pdf")
                                                FileOutputStream(pdfFile).use { fos ->
                                                    DesktopPdfHelper.createPdfFromImages(validImages, fos)
                                                }
                                                Desktop.getDesktop().open(pdfFile)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        fullError = "Re-export PDF error: ${e.message}"
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // ── LIGHTBOX DIALOG ────────────────────────────────────────────────────
    lightboxMediaItem?.let { currentItem ->
        val currentList = media ?: listOf(currentItem)
        DesktopLightboxDialog(
            mediaList = currentList,
            initialIndex = lightboxInitialIndex,
            onDismiss = { lightboxMediaItem = null },
            onDownloadSingle = { item ->
                coroutineScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val destDir = File(settings.downloadFolderPath)
                            if (!destDir.exists()) destDir.mkdirs()
                            val ext = if (item.isVideo) "mp4" else "jpg"
                            val outFile = File(destDir, "${item.authorUsername ?: "ig"}_${item.shortcode ?: System.currentTimeMillis()}.$ext")
                            FileOutputStream(outFile).use { fos ->
                                InstagramEngine.downloadToStream(item.url, fos)
                            }
                            Desktop.getDesktop().open(outFile)
                        }
                    } catch (_: Exception) {}
                }
            },
            onExtractAudio = { item ->
                onDownloadAudio(item.audioTrack, listOf(item))
            }
        )
    }

    // ── ACCOUNT SWITCHER DIALOG ────────────────────────────────────────────
    if (showAccountSwitcher) {
        DesktopAccountSwitcherDialog(
            accountStore = settings.accountStore,
            onDismiss = { showAccountSwitcher = false },
            onAddAccount = { cookieStr ->
                settings.applyCookieString(cookieStr)
            }
        )
    }

    // ── SETTINGS DIALOG ────────────────────────────────────────────────────
    if (showSettings) {
        DesktopSettingsDialog(
            settings = settings,
            selectedFolderPath = selectedFolderName,
            onChooseFolder = {
                val frame = Frame()
                val dialog = FileDialog(frame, "Select Download Folder", FileDialog.LOAD)
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                dialog.isVisible = true
                val selectedDir = dialog.directory
                val selectedFile = dialog.file
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
                frame.dispose()
                if (selectedDir != null) {
                    val fullPath = if (selectedFile != null) File(selectedDir, selectedFile).absolutePath else selectedDir
                    settings.downloadFolderPath = fullPath
                    selectedFolderName = fullPath
                }
            },
            onThemeChanged = onThemeChanged,
            onDismiss = { showSettings = false }
        )
    }
}

// ── TAB BUTTON COMPOSABLE ─────────────────────────────────────────────────
@Composable
private fun TabButton(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) colorScheme.primary else Color.Transparent,
        contentColor = if (selected) colorScheme.onPrimary else colorScheme.onSurfaceVariant
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        }
    }
}

// ── STUDIO WORKSPACE (SPLIT OR SINGLE COLUMN) ──────────────────────────────
@Composable
private fun DesktopStudioWorkspace(
    isWide: Boolean,
    url: String,
    onUrlChange: (String) -> Unit,
    urlError: String?,
    fullError: String?,
    isLoading: Boolean,
    isSaving: Boolean,
    isSavingAudio: Boolean,
    media: List<MediaResult>?,
    deselectedIndices: Set<Int>,
    downloadComplete: Boolean,
    audioDownloadComplete: Boolean,
    effectiveSession: IgSession?,
    settings: DesktopSettings,
    selectedFolderName: String,
    onClearUrl: () -> Unit,
    onPasteUrl: () -> Unit,
    onExtractMedia: () -> Unit,
    onToggleIndex: (Int) -> Unit,
    onToggleSelectAll: () -> Unit,
    onDownloadMedia: () -> Unit,
    onDownloadAudio: () -> Unit,
    onOpenLightbox: (MediaResult, Int) -> Unit,
    onChooseFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    if (isWide) {
        // ── 2-COLUMN STUDIO WORKSPACE ──────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Left Column: Input & Controls
            Column(
                modifier = Modifier
                    .weight(1.05f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DesktopInputCard(
                    url = url,
                    onUrlChange = onUrlChange,
                    urlError = urlError,
                    onClearUrl = onClearUrl,
                    onPasteUrl = onPasteUrl,
                    isSaving = isSaving || isSavingAudio,
                    useInstaLogin = settings.useInstagramLogin,
                    settings = settings,
                    onOpenSettings = onOpenSettings,
                    onExtractMedia = onExtractMedia,
                    isLoading = isLoading
                )

                fullError?.let {
                    DesktopErrorCard(errorText = it, onDismiss = onDismissError)
                }

                DesktopStudioGuideCard(
                    downloadFolderPath = selectedFolderName,
                    onChooseFolder = onChooseFolder,
                    onOpenSettings = onOpenSettings
                )
            }

            // Right Column: Live Media Preview, PDF & Audio Actions
            Column(
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (media != null && media.isNotEmpty()) {
                    DesktopPreviewCard(
                        media = media,
                        deselectedIndices = deselectedIndices,
                        onToggleIndex = onToggleIndex,
                        onToggleSelectAll = onToggleSelectAll,
                        onOpenLightbox = onOpenLightbox
                    )

                    DesktopActionsCard(
                        media = media,
                        deselectedIndices = deselectedIndices,
                        isSaving = isSaving,
                        isSavingAudio = isSavingAudio,
                        downloadComplete = downloadComplete,
                        audioDownloadComplete = audioDownloadComplete,
                        photoFormat = settings.photoSaveFormat,
                        onDownloadMedia = onDownloadMedia,
                        onDownloadAudio = onDownloadAudio,
                        downloadFolderPath = settings.downloadFolderPath
                    )
                } else if (!isLoading) {
                    DesktopEmptyPlaceholderCard()
                }
            }
        }
    } else {
        // ── COMPACT SINGLE COLUMN ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DesktopInputCard(
                url = url,
                onUrlChange = onUrlChange,
                urlError = urlError,
                onClearUrl = onClearUrl,
                onPasteUrl = onPasteUrl,
                isSaving = isSaving || isSavingAudio,
                useInstaLogin = settings.useInstagramLogin,
                settings = settings,
                onOpenSettings = onOpenSettings,
                onExtractMedia = onExtractMedia,
                isLoading = isLoading
            )

            fullError?.let {
                DesktopErrorCard(errorText = it, onDismiss = onDismissError)
            }

            if (media != null && media.isNotEmpty()) {
                DesktopPreviewCard(
                    media = media,
                    deselectedIndices = deselectedIndices,
                    onToggleIndex = onToggleIndex,
                    onToggleSelectAll = onToggleSelectAll,
                    onOpenLightbox = onOpenLightbox
                )

                DesktopActionsCard(
                    media = media,
                    deselectedIndices = deselectedIndices,
                    isSaving = isSaving,
                    isSavingAudio = isSavingAudio,
                    downloadComplete = downloadComplete,
                    audioDownloadComplete = audioDownloadComplete,
                    photoFormat = settings.photoSaveFormat,
                    onDownloadMedia = onDownloadMedia,
                    onDownloadAudio = onDownloadAudio,
                    downloadFolderPath = settings.downloadFolderPath
                )
            } else if (!isLoading) {
                DesktopStudioGuideCard(
                    downloadFolderPath = selectedFolderName,
                    onChooseFolder = onChooseFolder,
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }
}

// ── INPUT CARD ────────────────────────────────────────────────────────────
@Composable
private fun DesktopInputCard(
    url: String,
    onUrlChange: (String) -> Unit,
    urlError: String?,
    onClearUrl: () -> Unit,
    onPasteUrl: () -> Unit,
    isSaving: Boolean,
    useInstaLogin: Boolean,
    settings: DesktopSettings,
    onOpenSettings: () -> Unit,
    onExtractMedia: () -> Unit,
    isLoading: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(DesktopIcons.Download, contentDescription = null, tint = IgPink, modifier = Modifier.size(22.dp))
                Text(
                    text = "Extract Instagram Media",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "Paste Instagram post, reel, carousel, or story URL…",
                        style = MaterialTheme.typography.bodyMedium.copy(color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    )
                },
                singleLine = true,
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it, color = colorScheme.error) } },
                trailingIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        if (url.isNotEmpty()) {
                            IconButton(onClick = onClearUrl, modifier = Modifier.size(32.dp)) {
                                Icon(DesktopIcons.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                            }
                        }
                        IconButton(onClick = onPasteUrl, modifier = Modifier.size(32.dp)) {
                            Icon(DesktopIcons.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = IgPink,
                    unfocusedBorderColor = colorScheme.outline.copy(alpha = 0.4f)
                )
            )

            // Extract Button
            Button(
                onClick = onExtractMedia,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = IgPink),
                enabled = !isLoading && !isSaving && url.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Fetching Media Stream…", fontWeight = FontWeight.Bold)
                } else {
                    Icon(DesktopIcons.Sparkles, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fetch & Preview Media", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── PREVIEW CARD ──────────────────────────────────────────────────────────
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DesktopPreviewCard(
    media: List<MediaResult>,
    deselectedIndices: Set<Int>,
    onToggleIndex: (Int) -> Unit,
    onToggleSelectAll: () -> Unit,
    onOpenLightbox: (MediaResult, Int) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val carouselScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(DesktopIcons.PhotoLibrary, contentDescription = null, tint = IgPink, modifier = Modifier.size(20.dp))
                    Text(
                        if (media.size > 1) "Carousel Preview (${media.size} items)" else "Media Preview",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                if (media.size > 1) {
                    TextButton(onClick = onToggleSelectAll) {
                        Text(
                            if (deselectedIndices.isEmpty()) "Deselect All" else "Select All",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = IgPink)
                        )
                    }
                }
            }

            // Post Author / Caption Snippet
            media.firstOrNull()?.let { first ->
                if (!first.authorUsername.isNullOrBlank() || !first.caption.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(IgPink.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(DesktopIcons.Person, contentDescription = null, tint = IgPink, modifier = Modifier.size(18.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                first.authorUsername?.let {
                                    Text("@$it", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                }
                                first.caption?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onSurfaceVariant),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Media Thumbnails Carousel / Grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (scrollDelta != 0f) {
                            coroutineScope.launch {
                                carouselScrollState.scrollBy(scrollDelta * 60f)
                            }
                        }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(carouselScrollState)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    media.forEachIndexed { index, item ->
                        DesktopThumbnail(
                            item = item,
                            index = index,
                            totalCount = media.size,
                            isCarousel = media.size > 1,
                            isSelected = !deselectedIndices.contains(index),
                            onToggleSelected = { onToggleIndex(index) },
                            onOpenLightbox = { onOpenLightbox(item, index) }
                        )
                    }
                }

                if (media.size > 1) {
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(carouselScrollState),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

// ── THUMBNAIL COMPOSABLE ──────────────────────────────────────────────────
@Composable
private fun DesktopThumbnail(
    item: MediaResult,
    index: Int = 0,
    totalCount: Int = 1,
    isCarousel: Boolean,
    isSelected: Boolean,
    onToggleSelected: () -> Unit,
    onOpenLightbox: () -> Unit
) {
    val previewUrl = item.previewUrl
    val bitmap by produceState<ImageBitmap?>(initialValue = null, previewUrl) {
        if (previewUrl == null) {
            value = null
            return@produceState
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = InstagramEngine.fetchBytes(previewUrl)
                SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
            }.onSuccess { value = it }
        }
    }

    Card(
        modifier = Modifier
            .width(180.dp)
            .height(230.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onOpenLightbox() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        border = if (isSelected) BorderStroke(2.dp, IgPink) else BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.3f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.05f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = IgPink)
                }
            }

            // Top overlay: Index badge & Selection checkbox
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isCarousel) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.65f)
                    ) {
                        Text(
                            "${index + 1}/$totalCount",
                            style = MaterialTheme.typography.labelSmall.copy(color = Color.White, fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.size(1.dp))
                }

                // Checkbox for selection
                if (isCarousel) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) IgPink else Color.Black.copy(alpha = 0.5f))
                            .clickable { onToggleSelected() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(DesktopIcons.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Bottom overlay: Media Type & Fullscreen indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        if (item.isVideo) DesktopIcons.Movie else DesktopIcons.Image,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        if (item.isVideo) "Video" else "Photo",
                        style = MaterialTheme.typography.labelSmall.copy(color = Color.White, fontSize = 11.sp)
                    )
                }

                Icon(
                    DesktopIcons.ZoomIn,
                    contentDescription = "Expand",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ── ACTIONS CARD (DOWNLOAD, PDF, AUDIO EXTRACTION) ─────────────────────────
@Composable
private fun DesktopActionsCard(
    media: List<MediaResult>,
    deselectedIndices: Set<Int>,
    isSaving: Boolean,
    isSavingAudio: Boolean,
    downloadComplete: Boolean,
    audioDownloadComplete: Boolean,
    photoFormat: PhotoSaveFormat,
    onDownloadMedia: () -> Unit,
    onDownloadAudio: () -> Unit,
    downloadFolderPath: String
) {
    val colorScheme = MaterialTheme.colorScheme
    val selectedCount = media.size - deselectedIndices.size
    val firstItem = media.firstOrNull()
    val hasAudio = firstItem?.hasAudio == true || media.any { it.isVideo }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Export Options",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            // Primary Download Button
            Button(
                onClick = onDownloadMedia,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                enabled = !isSaving && !isSavingAudio && selectedCount > 0
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Saving Media ($selectedCount items)…", fontWeight = FontWeight.Bold)
                } else {
                    Icon(DesktopIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (media.size > 1) "Download Selected ($selectedCount/${media.size})" else "Download Media File",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Audio Extraction Button (Reels / Videos / Music)
            if (hasAudio) {
                OutlinedButton(
                    onClick = onDownloadAudio,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = IgPink),
                    border = BorderStroke(1.5.dp, IgPink),
                    enabled = !isSaving && !isSavingAudio
                ) {
                    if (isSavingAudio) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = IgPink, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Extracting & Tagging ID3 Audio…", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(DesktopIcons.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        val trackTitle = firstItem?.audioTrack?.title ?: "Original Audio"
                        Text("Extract Audio Only • $trackTitle (.mp3)", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Success feedback
            if (downloadComplete || audioDownloadComplete) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF10B981).copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(DesktopIcons.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                            Text(
                                if (audioDownloadComplete) "Audio saved & ID3-tagged!" else "Download completed successfully!",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF047857))
                            )
                        }

                        TextButton(
                            onClick = {
                                val dir = File(downloadFolderPath)
                                if (!dir.exists()) dir.mkdirs()
                                Desktop.getDesktop().open(dir)
                            }
                        ) {
                            Text("Open in Finder", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF047857)))
                        }
                    }
                }
            }
        }
    }
}

// ── EMPTY PLACEHOLDER CARD ────────────────────────────────────────────────
@Composable
private fun DesktopEmptyPlaceholderCard() {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().height(260.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(DesktopIcons.PhotoLibrary, contentDescription = null, tint = colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "No Media Loaded Yet",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Paste an Instagram post or reel link above to preview slides and extract media.",
                style = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onSurfaceVariant),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── STUDIO GUIDE CARD ─────────────────────────────────────────────────────
@Composable
private fun DesktopStudioGuideCard(
    downloadFolderPath: String,
    onChooseFolder: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(IgPink.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(DesktopIcons.Sparkles, contentDescription = null, tint = IgPink, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text("Studio Workspace", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Lossless media extraction and album tools", style = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onSurfaceVariant))
                }
            }

            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioFeatureRow(
                    icon = DesktopIcons.MusicNote,
                    title = "Audio & Music Track Extraction",
                    description = "Extract original background tracks and songs tagged with ID3 album tags."
                )
                StudioFeatureRow(
                    icon = DesktopIcons.Movie,
                    title = "Reels & High-Bitrate Video",
                    description = "Extract original MP4 streams with inline video player preview."
                )
                StudioFeatureRow(
                    icon = DesktopIcons.PictureAsPdf,
                    title = "Multi-Photo Carousels & PDF Export",
                    description = "Combine carousel slides directly into an elegant multi-page PDF."
                )
            }

            // Download Destination Folder Box
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(DesktopIcons.Folder, contentDescription = null, tint = IgPink, modifier = Modifier.size(18.dp))
                        Text("Destination Folder", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    Text(
                        downloadFolderPath,
                        style = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onSurfaceVariant, fontSize = 12.sp),
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onChooseFolder,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Change Folder", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                val dir = File(downloadFolderPath)
                                if (!dir.exists()) dir.mkdirs()
                                Desktop.getDesktop().open(dir)
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = IgPink),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Open in Finder", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioFeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(IgPink.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = IgPink, modifier = Modifier.size(15.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(description, style = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onSurfaceVariant, fontSize = 12.sp))
        }
    }
}

// ── ERROR CARD ────────────────────────────────────────────────────────────
@Composable
private fun DesktopErrorCard(errorText: String, onDismiss: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Error", style = MaterialTheme.typography.labelLarge.copy(color = colorScheme.onErrorContainer, fontWeight = FontWeight.Bold))
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = colorScheme.onErrorContainer)
                }
            }
            SelectionContainer {
                Text(errorText, style = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onErrorContainer))
            }
        }
    }
}

// ── ARCHIVE VAULT (HISTORY SCREEN) ─────────────────────────────────────────
@Composable
private fun DesktopHistoryScreen(
    historyStore: com.prakash.instafolio.core.history.HistoryStore,
    onOpenLightbox: (DownloadHistoryItem) -> Unit,
    onReExportPdf: (DownloadHistoryItem) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterType by remember { mutableStateOf<MediaType?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }

    val items = remember(historyStore.getAll(), searchQuery, selectedFilterType, favoritesOnly) {
        historyStore.filter(
            mediaType = selectedFilterType,
            query = searchQuery,
            favoritesOnly = favoritesOnly
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Search & Filter Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search vault by @creator, caption, or song…") },
                leadingIcon = { Icon(DesktopIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(DesktopIcons.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Clear Vault Button
            if (historyStore.getAll().isNotEmpty()) {
                OutlinedButton(
                    onClick = { historyStore.clear() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(DesktopIcons.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear Vault")
                }
            }
        }

        // Filter Chips Row
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilterType == null && !favoritesOnly,
                onClick = { selectedFilterType = null; favoritesOnly = false },
                label = { Text("All Items (${historyStore.getAll().size})") }
            )
            FilterChip(
                selected = selectedFilterType == MediaType.REEL || selectedFilterType == MediaType.VIDEO,
                onClick = { selectedFilterType = MediaType.REEL; favoritesOnly = false },
                label = { Text("Reels & Videos") }
            )
            FilterChip(
                selected = selectedFilterType == MediaType.PHOTO,
                onClick = { selectedFilterType = MediaType.PHOTO; favoritesOnly = false },
                label = { Text("Photos") }
            )
            FilterChip(
                selected = selectedFilterType == MediaType.CAROUSEL,
                onClick = { selectedFilterType = MediaType.CAROUSEL; favoritesOnly = false },
                label = { Text("Carousels") }
            )
            FilterChip(
                selected = selectedFilterType == MediaType.AUDIO,
                onClick = { selectedFilterType = MediaType.AUDIO; favoritesOnly = false },
                label = { Text("Audio Tracks (.mp3)") }
            )
            FilterChip(
                selected = selectedFilterType == MediaType.PDF,
                onClick = { selectedFilterType = MediaType.PDF; favoritesOnly = false },
                label = { Text("PDF Albums") }
            )
            FilterChip(
                selected = favoritesOnly,
                onClick = { favoritesOnly = !favoritesOnly; selectedFilterType = null },
                label = { Text("Favorites") },
                leadingIcon = { Icon(DesktopIcons.Favorite, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Red) }
            )
        }

        // Items Grid / List
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(DesktopIcons.History, contentDescription = null, modifier = Modifier.size(48.dp), tint = colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("No Archive Records Found", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Downloaded media files and audio will appear here in your vault.", style = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onSurfaceVariant))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items.forEach { item ->
                    DesktopHistoryCard(
                        item = item,
                        onOpenLightbox = { onOpenLightbox(item) },
                        onToggleFavorite = { historyStore.toggleFavorite(item.id) },
                        onDelete = { historyStore.remove(item.id) },
                        onReExportPdf = { onReExportPdf(item) }
                    )
                }
            }
        }
    }
}

// ── HISTORY CARD COMPOSABLE ────────────────────────────────────────────────
@Composable
private fun DesktopHistoryCard(
    item: DownloadHistoryItem,
    onOpenLightbox: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onReExportPdf: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()) }
    val formattedDate = remember(item.timestamp) { dateFormat.format(Date(item.timestamp)) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Thumbnail / Icon
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colorScheme.surfaceVariant)
                    .clickable { onOpenLightbox() },
                contentAlignment = Alignment.Center
            ) {
                val previewUrl = item.thumbnailPath ?: item.previewUrl
                val bitmap by produceState<ImageBitmap?>(initialValue = null, previewUrl) {
                    if (previewUrl == null) {
                        value = null
                        return@produceState
                    }
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = if (File(previewUrl).exists()) File(previewUrl).readBytes() else InstagramEngine.fetchBytes(previewUrl)
                            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                        }.onSuccess { value = it }
                    }
                }

                if (bitmap != null) {
                    Image(bitmap = bitmap!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(
                        when (item.mediaType) {
                            MediaType.AUDIO -> DesktopIcons.MusicNote
                            MediaType.PDF -> DesktopIcons.PictureAsPdf
                            MediaType.REEL, MediaType.VIDEO -> DesktopIcons.Movie
                            else -> DesktopIcons.Image
                        },
                        contentDescription = null,
                        tint = IgPink,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Details
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when (item.mediaType) {
                            MediaType.AUDIO -> IgPink.copy(alpha = 0.2f)
                            MediaType.PDF -> Color(0xFFEF4444).copy(alpha = 0.2f)
                            MediaType.REEL -> Color(0xFF8B5CF6).copy(alpha = 0.2f)
                            else -> Color(0xFF10B981).copy(alpha = 0.2f)
                        }
                    ) {
                        Text(
                            item.mediaType.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    item.authorUsername?.let {
                        Text("@$it", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }

                item.audioTitle?.let {
                    Text("🎵 $it • ${item.audioArtist ?: "Instagram"}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                } ?: item.caption?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onSurfaceVariant), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Text(formattedDate, style = MaterialTheme.typography.labelSmall.copy(color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 11.sp))
            }

            // Action Buttons
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onOpenLightbox) {
                    Icon(DesktopIcons.ZoomIn, contentDescription = "Preview", modifier = Modifier.size(18.dp))
                }

                if (item.mediaType == MediaType.CAROUSEL && item.filePaths.isNotEmpty()) {
                    IconButton(onClick = onReExportPdf) {
                        Icon(DesktopIcons.PictureAsPdf, contentDescription = "Export to PDF", modifier = Modifier.size(18.dp), tint = Color(0xFFEF4444))
                    }
                }

                IconButton(
                    onClick = {
                        val file = item.filePaths.firstOrNull()?.let { File(it) }
                        if (file != null && file.exists()) {
                            Desktop.getDesktop().open(file.parentFile ?: file)
                        }
                    }
                ) {
                    Icon(DesktopIcons.Folder, contentDescription = "Open folder", modifier = Modifier.size(18.dp))
                }

                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (item.isFavorite) DesktopIcons.Favorite else DesktopIcons.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (item.isFavorite) Color.Red else colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(DesktopIcons.Delete, contentDescription = "Delete", tint = colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ── FULL-SCREEN LIGHTBOX & VIDEO PLAYER DIALOG ─────────────────────────────
@Composable
private fun DesktopLightboxDialog(
    mediaList: List<MediaResult>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
    onDownloadSingle: (MediaResult) -> Unit,
    onExtractAudio: (MediaResult) -> Unit
) {
    var currentIndex by remember { mutableStateOf(initialIndex) }
    val currentItem = mediaList.getOrNull(currentIndex) ?: return
    var zoomLevel by remember { mutableStateOf(1f) }
    val isVideo = currentItem.isVideo

    // Inline video preview simulation state
    var isPlaying by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var progress by remember { mutableStateOf(0.35f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.9f),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF121212)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Lightbox Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(shape = CircleShape, color = IgPink.copy(alpha = 0.2f)) {
                            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                Icon(DesktopIcons.Person, contentDescription = null, tint = IgPink, modifier = Modifier.size(18.dp))
                            }
                        }
                        Column {
                            currentItem.authorUsername?.let {
                                Text("@$it", style = MaterialTheme.typography.titleSmall.copy(color = Color.White, fontWeight = FontWeight.Bold))
                            }
                            Text(
                                "Slide ${currentIndex + 1} of ${mediaList.size}",
                                style = MaterialTheme.typography.labelSmall.copy(color = Color.White.copy(alpha = 0.6f))
                            )
                        }
                    }

                    // Zoom Controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(onClick = { zoomLevel = (zoomLevel - 0.25f).coerceAtLeast(0.5f) }) {
                            Text("－", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Text("${(zoomLevel * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { zoomLevel = (zoomLevel + 0.25f).coerceAtMost(3.0f) }) {
                            Text("＋", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        IconButton(onClick = { zoomLevel = 1.0f }) {
                            Text("1:1", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(DesktopIcons.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                // Middle Media Viewer
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    val previewUrl = currentItem.previewUrl
                    val bitmap by produceState<ImageBitmap?>(initialValue = null, previewUrl) {
                        if (previewUrl == null) {
                            value = null
                            return@produceState
                        }
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val bytes = InstagramEngine.fetchBytes(previewUrl)
                                SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                            }.onSuccess { value = it }
                        }
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        )
                    } else {
                        CircularProgressIndicator(color = IgPink)
                    }

                    // Carousel Navigation Arrows
                    if (mediaList.size > 1) {
                        if (currentIndex > 0) {
                            IconButton(
                                onClick = { currentIndex--; zoomLevel = 1f },
                                modifier = Modifier.align(Alignment.CenterStart).padding(16.dp)
                            ) {
                                Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.6f)) {
                                    Icon(DesktopIcons.ChevronLeft, contentDescription = "Previous", tint = Color.White, modifier = Modifier.padding(8.dp))
                                }
                            }
                        }
                        if (currentIndex < mediaList.lastIndex) {
                            IconButton(
                                onClick = { currentIndex++; zoomLevel = 1f },
                                modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp)
                            ) {
                                Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.6f)) {
                                    Icon(DesktopIcons.ChevronRight, contentDescription = "Next", tint = Color.White, modifier = Modifier.padding(8.dp))
                                }
                            }
                        }
                    }
                }

                // Video Player Controls (If Video)
                if (isVideo) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.85f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            // Scrubber
                            Slider(
                                value = progress,
                                onValueChange = { progress = it },
                                colors = SliderDefaults.colors(
                                    thumbColor = IgPink,
                                    activeTrackColor = IgPink,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    IconButton(onClick = { isPlaying = !isPlaying }) {
                                        Icon(if (isPlaying) DesktopIcons.Pause else DesktopIcons.PlayArrow, contentDescription = null, tint = Color.White)
                                    }
                                    IconButton(onClick = { isMuted = !isMuted }) {
                                        Icon(if (isMuted) DesktopIcons.VolumeOff else DesktopIcons.VolumeUp, contentDescription = null, tint = Color.White)
                                    }
                                    Text("0:12 / 0:30", style = MaterialTheme.typography.labelSmall.copy(color = Color.White))
                                }

                                // Playback Speed
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { spd ->
                                        Surface(
                                            onClick = { playbackSpeed = spd },
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (playbackSpeed == spd) IgPink else Color.White.copy(alpha = 0.15f)
                                        ) {
                                            Text("${spd}x", style = MaterialTheme.typography.labelSmall.copy(color = Color.White), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Action Bar
                Surface(
                    color = Color.Black.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        currentItem.caption?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.8f)),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 16.dp)
                            )
                        } ?: Spacer(Modifier.weight(1f))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (currentItem.hasAudio) {
                                OutlinedButton(
                                    onClick = { onExtractAudio(currentItem) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = BorderStroke(1.dp, IgPink)
                                ) {
                                    Icon(DesktopIcons.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp), tint = IgPink)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Extract Audio (.mp3)", fontSize = 12.sp)
                                }
                            }

                            Button(
                                onClick = { onDownloadSingle(currentItem) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = IgPink)
                            ) {
                                Icon(DesktopIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Download This Slide", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── MULTI-ACCOUNT SWITCHER DIALOG ──────────────────────────────────────────
@Composable
private fun DesktopAccountSwitcherDialog(
    accountStore: com.prakash.instafolio.core.session.MultiAccountStore,
    onDismiss: () -> Unit,
    onAddAccount: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val accounts = accountStore.getAllAccounts()
    val active = accountStore.getActiveAccount()
    var showAddCard by remember { mutableStateOf(false) }
    var newCookieInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Instagram Accounts", fontWeight = FontWeight.Bold)
                TextButton(onClick = { showAddCard = !showAddCard }) {
                    Text(if (showAddCard) "Cancel" else "＋ Add Account", color = IgPink, fontWeight = FontWeight.Bold)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.width(420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Add Account Input Card
                if (showAddCard) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Paste Instagram Session Cookies", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                            OutlinedTextField(
                                value = newCookieInput,
                                onValueChange = { newCookieInput = it },
                                placeholder = { Text("sessionid=...; ds_user_id=...", fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 3,
                                shape = RoundedCornerShape(8.dp)
                            )
                            Button(
                                onClick = {
                                    if (newCookieInput.isNotBlank()) {
                                        onAddAccount(newCookieInput)
                                        newCookieInput = ""
                                        showAddCard = false
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = IgPink),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Connect Account", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Accounts List
                if (accounts.isEmpty()) {
                    Text("No connected accounts. Operating in Guest Mode.", style = MaterialTheme.typography.bodySmall)
                } else {
                    accounts.forEach { acc ->
                        val isActive = acc.id == active?.id
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            border = if (isActive) BorderStroke(1.5.dp, IgPink) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(36.dp).clip(CircleShape).background(IgPink.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(DesktopIcons.Person, contentDescription = null, tint = IgPink, modifier = Modifier.size(20.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("@${acc.username}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                        if (isActive) {
                                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF10B981)) {
                                                Text("ACTIVE", color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.ExtraBold), modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                            }
                                        }
                                    }
                                    // Health badge
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(
                                            modifier = Modifier.size(6.dp).clip(CircleShape).background(
                                                when (acc.health.status) {
                                                    SessionHealthStatus.HEALTHY -> Color(0xFF10B981)
                                                    SessionHealthStatus.EXPIRING_SOON -> Color(0xFFF59E0B)
                                                    SessionHealthStatus.EXPIRED -> Color.Red
                                                    SessionHealthStatus.UNCHECKED -> Color.Gray
                                                }
                                            )
                                        )
                                        Text(acc.health.message, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!isActive) {
                                        TextButton(onClick = { accountStore.setActiveAccount(acc.id) }) {
                                            Text("Switch", color = IgPink, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    IconButton(onClick = { accountStore.removeAccount(acc.id) }) {
                                        Icon(DesktopIcons.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

// ── SETTINGS DIALOG ────────────────────────────────────────────────────────
@Composable
private fun DesktopSettingsDialog(
    settings: DesktopSettings,
    selectedFolderPath: String,
    onChooseFolder: () -> Unit,
    onThemeChanged: (AppTheme) -> Unit,
    onDismiss: () -> Unit
) {
    var quality by remember { mutableStateOf(settings.quality) }
    var photoFormat by remember { mutableStateOf(settings.photoSaveFormat) }
    var theme by remember { mutableStateOf(settings.theme) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("InstaFolio Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.width(440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Download Quality
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Download Quality", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    DownloadQuality.values().forEach { q ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { quality = q; settings.quality = q },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = quality == q, onClick = { quality = q; settings.quality = q })
                            Column(modifier = Modifier.padding(start = 6.dp)) {
                                Text(q.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(q.description, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Photo Save Format
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Photo & Carousel Format", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    PhotoSaveFormat.values().forEach { fmt ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { photoFormat = fmt; settings.photoSaveFormat = fmt },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = photoFormat == fmt, onClick = { photoFormat = fmt; settings.photoSaveFormat = fmt })
                            Column(modifier = Modifier.padding(start = 6.dp)) {
                                Text(fmt.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(fmt.description, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Theme
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    AppTheme.values().forEach { t ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                theme = t
                                settings.theme = t
                                onThemeChanged(t)
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = theme == t, onClick = {
                                theme = t
                                settings.theme = t
                                onThemeChanged(t)
                            })
                            Text(t.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = IgPink)) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}

// ── UTILITIES ─────────────────────────────────────────────────────────────
private fun isInstagramUrl(url: String): Boolean {
    val pattern = Pattern.compile(
        "^https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/.*$",
        Pattern.CASE_INSENSITIVE
    )
    return pattern.matcher(url.trim()).matches()
}
