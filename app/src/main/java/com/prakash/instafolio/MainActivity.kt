package com.prakash.instafolio

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.prakash.instafolio.core.audio.Id3TagHelper
import com.prakash.instafolio.core.model.*
import com.prakash.instafolio.core.network.InstagramEngine
import com.prakash.instafolio.ui.AppIcons
import com.prakash.instafolio.ui.theme.InstaFolioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

// Instagram Brand Colors
private val IgPurple = Color(0xFF833AB4)
private val IgPink = Color(0xFFE1306C)
private val IgOrange = Color(0xFFF77737)

private val IgPurpleDark = Color(0xFF1E1022)
private val IgPinkDark = Color(0xFF350E1D)
private val IgOrangeDark = Color(0xFF2B1309)

enum class AppNavigationTab(val label: String) {
    DOWNLOADER("Single Post"),
    USER_POSTS("User Posts"),
    VAULT("Archive Vault")
}

class MainActivity : ComponentActivity() {

    private val appSettings by lazy { AppSettings(this) }
    private val selectedFolderName = mutableStateOf(AppSettings.DEFAULT_FOLDER_NAME)

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val sessionStore = SessionStore(this)
            val session = sessionStore.session
            if (session != null) {
                appSettings.accountStore.addOrUpdateAccount(
                    StoredAccount(
                        id = UUID.randomUUID().toString(),
                        username = sessionStore.username ?: "instagram_user",
                        fullName = sessionStore.fullName,
                        profilePicUrl = sessionStore.profilePicUrl,
                        sessionId = session.sessionId,
                        csrfToken = session.csrfToken,
                        userId = session.userId
                    )
                )
            }
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val name = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':') }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "Selected folder"
        appSettings.downloadTreeUri = uri.toString()
        appSettings.downloadFolderName = name
        selectedFolderName.value = name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedFolderName.value = appSettings.downloadFolderName

        // Migrate legacy session if needed
        val sessionStore = SessionStore(this)
        if (sessionStore.isLoggedIn && appSettings.accountStore.getAllAccounts().isEmpty()) {
            sessionStore.session?.let { s ->
                appSettings.accountStore.addOrUpdateAccount(
                    StoredAccount(
                        id = UUID.randomUUID().toString(),
                        username = sessionStore.username ?: "instagram_user",
                        fullName = sessionStore.fullName,
                        profilePicUrl = sessionStore.profilePicUrl,
                        sessionId = s.sessionId,
                        csrfToken = s.csrfToken,
                        userId = s.userId
                    )
                )
            }
        }

        setContent {
            val settings = appSettings
            var selectedTheme by remember { mutableStateOf(settings.theme) }
            val useDarkTheme = when (selectedTheme) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }

            InstaFolioTheme(darkTheme = useDarkTheme) {
                AndroidMainScreen(
                    settings = settings,
                    useDarkTheme = useDarkTheme,
                    selectedFolderName = selectedFolderName.value,
                    onThemeChanged = { selectedTheme = it },
                    onChooseFolder = { folderPickerLauncher.launch(null) },
                    onLaunchLogin = { loginLauncher.launch(Intent(this, LoginActivity::class.java)) },
                    onHapticFeedback = { triggerHaptic() }
                )
            }
        }
    }

    private fun triggerHaptic() {
        if (!appSettings.hapticsEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    v.vibrate(25)
                }
            }
        } catch (_: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroidMainScreen(
    settings: AppSettings,
    useDarkTheme: Boolean,
    selectedFolderName: String,
    onThemeChanged: (AppTheme) -> Unit,
    onChooseFolder: () -> Unit,
    onLaunchLogin: () -> Unit,
    onHapticFeedback: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(AppNavigationTab.DOWNLOADER) }
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

    // Lightbox & Preview state
    var lightboxMediaItem by remember { mutableStateOf<MediaResult?>(null) }
    var lightboxInitialIndex by remember { mutableStateOf(0) }

    val coroutineScope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val clipboardManager = LocalClipboardManager.current

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
            onHapticFeedback()

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
                        onHapticFeedback()
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
        onHapticFeedback()
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val targetAudioUrl = audioTrack?.audioUrl
                        ?: mediaList.firstOrNull { it.isVideo }?.url
                        ?: throw Exception("No audio stream available in this post")

                    val rawAudioBytes = InstagramEngine.fetchBytes(targetAudioUrl)
                    val firstItem = mediaList.firstOrNull()
                    val authorName = firstItem?.authorUsername ?: "instagram"
                    val songTitle = audioTrack?.title ?: firstItem?.shortcode ?: "Audio_${System.currentTimeMillis()}"
                    val artistName = audioTrack?.artist ?: authorName

                    val coverBytes = runCatching {
                        val coverUrl = audioTrack?.coverUrl ?: firstItem?.thumbnailUrl
                        if (!coverUrl.isNullOrBlank()) InstagramEngine.fetchBytes(coverUrl) else null
                    }.getOrNull()

                    val safeAuthor = authorName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val safeTitle = songTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val fileName = "${safeAuthor}_${safeTitle}.mp3"

                    // Save using MediaStore Audio or App Storage
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/InstaFolio")
                            put(MediaStore.Audio.Media.IS_PENDING, 1)
                        }
                    }

                    val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let { destUri ->
                        context.contentResolver.openOutputStream(destUri)?.use { out ->
                            Id3TagHelper.writeTaggedMp3(
                                rawAudioBytes = rawAudioBytes,
                                out = out,
                                title = songTitle,
                                artist = artistName,
                                albumName = "Instagram Audio",
                                albumArtBytes = coverBytes
                            )
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                            context.contentResolver.update(destUri, values, null, null)
                        }
                    }

                    settings.historyStore.add(
                        DownloadHistoryItem(
                            url = url,
                            shortcode = firstItem?.shortcode,
                            authorUsername = authorName,
                            authorFullName = firstItem?.authorFullName,
                            caption = firstItem?.caption,
                            mediaType = MediaType.AUDIO,
                            filePaths = listOf(uri?.toString() ?: fileName),
                            thumbnailPath = firstItem?.thumbnailUrl,
                            previewUrl = firstItem?.previewUrl,
                            audioTitle = songTitle,
                            audioArtist = artistName,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                audioDownloadComplete = true
                onHapticFeedback()
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
            onHapticFeedback()
            coroutineScope.launch {
                try {
                    val savedUris = mutableListOf<String>()
                    withContext(Dispatchers.IO) {
                        val selectedItems = currentMedia.filterIndexed { index, _ ->
                            !deselectedIndices.contains(index)
                        }
                        val itemsToProcess = if (selectedItems.isEmpty()) currentMedia else selectedItems
                        val isMulti = itemsToProcess.size > 1
                        val firstItem = itemsToProcess.firstOrNull()
                        val shortcode = firstItem?.shortcode ?: System.currentTimeMillis().toString()
                        val author = firstItem?.authorUsername ?: "instagram"

                        val savePdf = settings.photoSaveFormat == PhotoSaveFormat.PDF ||
                                (settings.photoSaveFormat == PhotoSaveFormat.BOTH && itemsToProcess.any { !it.isVideo })

                        if (savePdf) {
                            val photoItems = itemsToProcess.filter { !it.isVideo }
                            if (photoItems.isNotEmpty()) {
                                val imagesBytes = photoItems.map { InstagramEngine.fetchBytes(it.url) }
                                val pdfFileName = "${author}_${shortcode}_album.pdf"
                                val pdfFile = File(context.cacheDir, pdfFileName)
                                FileOutputStream(pdfFile).use { fos ->
                                    PdfHelper.createPdfFromImages(imagesBytes, fos)
                                }
                                savedUris.add(pdfFile.absolutePath)
                            }
                        }

                        if (settings.photoSaveFormat != PhotoSaveFormat.PDF) {
                            itemsToProcess.forEachIndexed { idx, item ->
                                val ext = if (item.isVideo) "mp4" else "jpg"
                                val mime = if (item.isVideo) "video/mp4" else "image/jpeg"
                                val suffix = if (isMulti) "_${idx + 1}" else ""
                                val fileName = "${author}_${shortcode}$suffix.$ext"

                                val collection = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                val values = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        val relDir = if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$relDir/InstaFolio")
                                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                                    }
                                }

                                val uri = context.contentResolver.insert(collection, values)
                                uri?.let { destUri ->
                                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                                        InstagramEngine.downloadToStream(item.url, out)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        values.clear()
                                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                        context.contentResolver.update(destUri, values, null, null)
                                    }
                                    savedUris.add(destUri.toString())
                                }
                            }
                        }

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
                                filePaths = savedUris,
                                thumbnailPath = firstItem?.thumbnailUrl,
                                previewUrl = firstItem?.previewUrl,
                                audioTitle = firstItem?.audioTrack?.title,
                                audioArtist = firstItem?.audioTrack?.artist,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                    downloadComplete = true
                    onHapticFeedback()
                } catch (e: Exception) {
                    fullError = "Download error: ${e.message}"
                } finally {
                    isSaving = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(igGradient),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(AppIcons.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            "InstaFolio",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Account Indicator Pill
                        Surface(
                            onClick = { showAccountSwitcher = true },
                            shape = RoundedCornerShape(16.dp),
                            color = if (effectiveSession != null) Color(0xFF10B981).copy(alpha = 0.15f)
                            else colorScheme.surfaceVariant
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (effectiveSession != null) Color(0xFF10B981) else Color.Gray)
                                )
                                Text(
                                    text = if (effectiveSession != null) {
                                        activeAccount?.let { "@${it.username}" } ?: "Active"
                                    } else "Guest",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }

                        IconButton(onClick = { showSettings = true }, modifier = Modifier.size(36.dp)) {
                            Icon(AppIcons.Settings, contentDescription = "Settings", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(tonalElevation = 3.dp) {
                NavigationBarItem(
                    selected = selectedTab == AppNavigationTab.DOWNLOADER,
                    onClick = { selectedTab = AppNavigationTab.DOWNLOADER },
                    icon = { Icon(AppIcons.Download, contentDescription = null) },
                    label = { Text("Single URL") }
                )
                NavigationBarItem(
                    selected = selectedTab == AppNavigationTab.USER_POSTS,
                    onClick = { selectedTab = AppNavigationTab.USER_POSTS },
                    icon = { Icon(AppIcons.GridView, contentDescription = null) },
                    label = { Text("User Posts") }
                )
                NavigationBarItem(
                    selected = selectedTab == AppNavigationTab.VAULT,
                    onClick = { selectedTab = AppNavigationTab.VAULT },
                    icon = { Icon(AppIcons.History, contentDescription = null) },
                    label = { Text("Vault (${settings.historyStore.getAll().size})") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                AppNavigationTab.DOWNLOADER -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Input Box
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Paste Instagram URL…", fontSize = 14.sp) },
                            singleLine = true,
                            isError = urlError != null,
                            supportingText = urlError?.let { { Text(it, color = colorScheme.error) } },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (url.isNotEmpty()) {
                                        IconButton(onClick = { url = ""; media = null }) {
                                            Icon(AppIcons.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            clipboardManager.getText()?.text?.let {
                                                url = it
                                                extractMedia()
                                            }
                                        }
                                    ) {
                                        Icon(AppIcons.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp)
                        )

                        // Fetch Media Button
                        Button(
                            onClick = extractMedia,
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = IgPink),
                            enabled = !isLoading && !isSaving && url.isNotBlank()
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Fetching Media…", fontWeight = FontWeight.Bold)
                            } else {
                                Icon(AppIcons.Sparkles, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Fetch & Preview", fontWeight = FontWeight.Bold)
                            }
                        }

                        // Error Card
                        fullError?.let {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = colorScheme.errorContainer)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(it, style = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onErrorContainer), modifier = Modifier.weight(1f))
                                    TextButton(onClick = { fullError = null }) {
                                        Text("Dismiss", color = colorScheme.onErrorContainer)
                                    }
                                }
                            }
                        }

                        // Media Preview & Actions
                        if (media != null && media!!.isNotEmpty()) {
                            AndroidMediaPreviewCard(
                                media = media!!,
                                deselectedIndices = deselectedIndices,
                                onToggleIndex = { idx ->
                                    deselectedIndices = if (deselectedIndices.contains(idx)) deselectedIndices - idx else deselectedIndices + idx
                                },
                                onOpenLightbox = { item, idx ->
                                    lightboxMediaItem = item
                                    lightboxInitialIndex = idx
                                }
                            )

                            // Actions
                            Button(
                                onClick = onDownloadMedia,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                enabled = !isSaving && !isSavingAudio
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Saving to Gallery…", fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Download Media", fontWeight = FontWeight.Bold)
                                }
                            }

                            // Audio Extract Action
                            val firstItem = media!!.firstOrNull()
                            if (firstItem?.hasAudio == true || media!!.any { it.isVideo }) {
                                OutlinedButton(
                                    onClick = { onDownloadAudio(firstItem?.audioTrack, media!!) },
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = IgPink),
                                    border = BorderStroke(1.dp, IgPink),
                                    enabled = !isSaving && !isSavingAudio
                                ) {
                                    if (isSavingAudio) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = IgPink, strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Extracting & Tagging Audio…")
                                    } else {
                                        Icon(AppIcons.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Extract Audio Only (.mp3)", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                AppNavigationTab.USER_POSTS -> {
                    AndroidUserPostsScreen(
                        settings = settings,
                        effectiveSession = effectiveSession,
                        useDarkTheme = useDarkTheme,
                        onOpenLightbox = { mediaRes, idx ->
                            lightboxMediaItem = mediaRes
                            lightboxInitialIndex = idx
                        },
                        onHapticFeedback = onHapticFeedback
                    )
                }

                AppNavigationTab.VAULT -> {
                    AndroidHistoryScreen(
                        historyStore = settings.historyStore,
                        onOpenLightbox = { item ->
                            val mediaRes = MediaResult(
                                url = item.previewUrl ?: item.url,
                                isVideo = item.mediaType == MediaType.REEL || item.mediaType == MediaType.VIDEO,
                                thumbnailUrl = item.thumbnailPath ?: item.previewUrl,
                                caption = item.caption,
                                authorUsername = item.authorUsername,
                                authorFullName = item.authorFullName,
                                audioTrack = item.audioTitle?.let { AudioTrack(title = it, artist = item.audioArtist) },
                                shortcode = item.shortcode
                            )
                            lightboxMediaItem = mediaRes
                            lightboxInitialIndex = 0
                        },
                        onShare = { item ->
                            val uriStr = item.filePaths.firstOrNull()
                            if (uriStr != null) {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = if (item.mediaType == MediaType.AUDIO) "audio/mpeg" else "*/*"
                                    putExtra(Intent.EXTRA_STREAM, Uri.parse(uriStr))
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Media"))
                            }
                        }
                    )
                }
            }
        }
    }

    // ── LIGHTBOX DIALOG ────────────────────────────────────────────────────
    lightboxMediaItem?.let { currentItem ->
        val currentList = media ?: listOf(currentItem)
        AndroidLightboxDialog(
            mediaList = currentList,
            initialIndex = lightboxInitialIndex,
            onDismiss = { lightboxMediaItem = null },
            onDownloadSingle = { item ->
                coroutineScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val values = ContentValues().apply {
                                val ext = if (item.isVideo) "mp4" else "jpg"
                                put(MediaStore.MediaColumns.DISPLAY_NAME, "slide_${System.currentTimeMillis()}.$ext")
                                put(MediaStore.MediaColumns.MIME_TYPE, if (item.isVideo) "video/mp4" else "image/jpeg")
                            }
                            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            uri?.let { destUri ->
                                context.contentResolver.openOutputStream(destUri)?.use { out ->
                                    InstagramEngine.downloadToStream(item.url, out)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        )
    }

    // ── ACCOUNT SWITCHER MODAL ─────────────────────────────────────────────
    if (showAccountSwitcher) {
        AlertDialog(
            onDismissRequest = { showAccountSwitcher = false },
            title = { Text("Instagram Accounts", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val accounts = settings.accountStore.getAllAccounts()
                    if (accounts.isEmpty()) {
                        Text("No connected accounts. Operating in Guest Mode.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        accounts.forEach { acc ->
                            val isActive = acc.id == activeAccount?.id
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    settings.accountStore.setActiveAccount(acc.id)
                                    showAccountSwitcher = false
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("@${acc.username}", fontWeight = FontWeight.Bold)
                                        Text(acc.health.message, fontSize = 10.sp)
                                    }
                                    if (isActive) {
                                        Icon(AppIcons.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            showAccountSwitcher = false
                            onLaunchLogin()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = IgPink)
                    ) {
                        Text("Login New Account (WebView)", fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccountSwitcher = false }) { Text("Done") }
            }
        )
    }

    // ── SETTINGS MODAL ─────────────────────────────────────────────────────
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Theme", fontWeight = FontWeight.Bold)
                    AppTheme.values().forEach { t ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                settings.theme = t
                                onThemeChanged(t)
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = settings.theme == t, onClick = {
                                settings.theme = t
                                onThemeChanged(t)
                            })
                            Text(t.label, modifier = Modifier.padding(start = 6.dp))
                        }
                    }

                    HorizontalDivider()

                    Text("Download Quality", fontWeight = FontWeight.Bold)
                    DownloadQuality.values().forEach { q ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { settings.quality = q },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = settings.quality == q, onClick = { settings.quality = q })
                            Text(q.label, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("Close") }
            }
        )
    }
}

// ── ANDROID MEDIA PREVIEW CARD ────────────────────────────────────────────
@Composable
private fun AndroidMediaPreviewCard(
    media: List<MediaResult>,
    deselectedIndices: Set<Int>,
    onToggleIndex: (Int) -> Unit,
    onOpenLightbox: (MediaResult, Int) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                if (media.size > 1) "Carousel Slides (${media.size} items)" else "Media Preview",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                media.forEachIndexed { idx, item ->
                    val isSelected = !deselectedIndices.contains(idx)
                    Card(
                        modifier = Modifier
                            .width(140.dp)
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenLightbox(item, idx) },
                        border = if (isSelected) BorderStroke(2.dp, IgPink) else null
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Text(
                                "Slide ${idx + 1}",
                                modifier = Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── ANDROID HISTORY SCREEN ────────────────────────────────────────────────
@Composable
private fun AndroidHistoryScreen(
    historyStore: com.prakash.instafolio.core.history.HistoryStore,
    onOpenLightbox: (DownloadHistoryItem) -> Unit,
    onShare: (DownloadHistoryItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val items = remember(historyStore.getAll(), searchQuery) {
        historyStore.filter(query = searchQuery)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search vault…") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Text("No downloaded items in Vault yet.")
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.forEach { item ->
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.authorUsername?.let { "@$it" } ?: item.mediaType.label,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    item.audioTitle ?: item.caption ?: "Saved Media",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row {
                                IconButton(onClick = { onShare(item) }) {
                                    Icon(AppIcons.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { historyStore.remove(item.id) }) {
                                    Icon(AppIcons.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── ANDROID LIGHTBOX DIALOG ───────────────────────────────────────────────
@Composable
private fun AndroidLightboxDialog(
    mediaList: List<MediaResult>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
    onDownloadSingle: (MediaResult) -> Unit
) {
    var currentIndex by remember { mutableStateOf(initialIndex) }
    val currentItem = mediaList.getOrNull(currentIndex) ?: return
    var scale by remember { mutableStateOf(1f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF141414)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${currentIndex + 1}/${mediaList.size}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(AppIcons.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 4f)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (currentItem.isVideo) "Video Preview" else "Photo Preview",
                        color = Color.White,
                        modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
                    )
                }

                Button(
                    onClick = { onDownloadSingle(currentItem) },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = IgPink)
                ) {
                    Text("Save This Slide", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun isInstagramUrl(url: String): Boolean {
    val pattern = Pattern.compile(
        "^https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/.*$",
        Pattern.CASE_INSENSITIVE
    )
    return pattern.matcher(url.trim()).matches()
}
