package com.prakash.instafolio

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.prakash.instafolio.core.history.HistoryStore
import com.prakash.instafolio.core.model.*
import com.prakash.instafolio.core.session.MultiAccountStore
import java.io.File

class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    val historyStore = HistoryStore(File(appContext.filesDir, "history.json"))
    val accountStore = MultiAccountStore(File(appContext.filesDir, "accounts.json"))

    var downloadTreeUri: String?
        get() = prefs.getString("download_tree_uri", null)
        set(value) = prefs.edit().putString("download_tree_uri", value).apply()

    var downloadFolderName: String
        get() = prefs.getString("download_folder_name", null) ?: DEFAULT_FOLDER_NAME
        set(value) = prefs.edit().putString("download_folder_name", value).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) = prefs.edit().putBoolean("haptics", value).apply()

    var useInstagramLogin: Boolean
        get() = prefs.getBoolean("use_instagram_login", true)
        set(value) = prefs.edit().putBoolean("use_instagram_login", value).apply()

    var theme: AppTheme
        get() = enumValue(prefs.getString("theme", null), AppTheme.SYSTEM)
        set(value) = prefs.edit().putString("theme", value.name).apply()

    var quality: DownloadQuality
        get() = enumValue(prefs.getString("quality", null), DownloadQuality.AUTO)
        set(value) = prefs.edit().putString("quality", value.name).apply()

    var photoSaveFormat: PhotoSaveFormat
        get() = enumValue(prefs.getString("photo_save_format", null), PhotoSaveFormat.IMAGES)
        set(value) = prefs.edit().putString("photo_save_format", value.name).apply()

    fun effectiveQuality(): DownloadQuality {
        if (quality != DownloadQuality.AUTO) return quality
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val dataSaverOn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        return if (dataSaverOn) DownloadQuality.DATA_SAVER else DownloadQuality.BEST
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value ?: "") }.getOrDefault(fallback)

    companion object {
        const val DEFAULT_FOLDER_NAME = "Downloads/InstaFolio (default)"
    }
}
