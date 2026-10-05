package com.prakash.instafolio.desktop

import com.prakash.instafolio.core.history.HistoryStore
import com.prakash.instafolio.core.model.*
import com.prakash.instafolio.core.session.MultiAccountStore
import org.json.JSONObject
import java.io.File

class DesktopSettings {
    private val configDir = File(System.getProperty("user.home"), ".instafolio")
    private val configFile = File(configDir, "settings.json")
    private val data = mutableMapOf<String, String>()

    val historyStore = HistoryStore(File(configDir, "history.json"))
    val accountStore = MultiAccountStore(File(configDir, "accounts.json"))

    init {
        loadFromFile()
        migrateLegacyAccountIfNeeded()
    }

    @Synchronized
    private fun loadFromFile() {
        if (configFile.exists()) {
            runCatching {
                val text = configFile.readText()
                if (text.isNotBlank()) {
                    val json = JSONObject(text)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        data[key] = json.optString(key)
                    }
                }
            }
        }
    }

    private fun migrateLegacyAccountIfNeeded() {
        val legacySession = sessionId
        if (!legacySession.isNullOrBlank() && accountStore.getAllAccounts().isEmpty()) {
            val account = StoredAccount(
                id = java.util.UUID.randomUUID().toString(),
                username = username ?: (userId ?: "instagram_user"),
                fullName = fullName,
                profilePicUrl = profilePicUrl,
                sessionId = legacySession,
                csrfToken = csrfToken,
                userId = userId
            )
            accountStore.addOrUpdateAccount(account)
        }
    }

    @Synchronized
    private fun saveToFile() {
        runCatching {
            configFile.parentFile?.mkdirs()
            val json = JSONObject()
            data.forEach { (k, v) -> json.put(k, v) }
            configFile.writeText(json.toString(2))
        }
    }

    @Synchronized
    private fun get(key: String, default: String? = null): String? = data[key] ?: default

    @Synchronized
    private fun put(key: String, value: String?) {
        if (value != null && value.isNotBlank()) {
            data[key] = value.trim()
        } else {
            data.remove(key)
        }
        saveToFile()
    }

    var downloadFolderPath: String
        get() = get("download_folder_path", DEFAULT_FOLDER_PATH) ?: DEFAULT_FOLDER_PATH
        set(value) = put("download_folder_path", value)

    var theme: AppTheme
        get() = enumValue(get("theme"), AppTheme.SYSTEM)
        set(value) = put("theme", value.name)

    var quality: DownloadQuality
        get() = enumValue(get("quality"), DownloadQuality.BEST)
        set(value) = put("quality", value.name)

    var photoSaveFormat: PhotoSaveFormat
        get() = enumValue(get("photo_save_format"), PhotoSaveFormat.IMAGES)
        set(value) = put("photo_save_format", value.name)

    var sessionId: String?
        get() = accountStore.getActiveAccount()?.sessionId ?: get("session_id")
        set(value) = put("session_id", value)

    var csrfToken: String?
        get() = accountStore.getActiveAccount()?.csrfToken ?: get("csrf_token")
        set(value) = put("csrf_token", value)

    var userId: String?
        get() = accountStore.getActiveAccount()?.userId ?: get("user_id")
        set(value) = put("user_id", value)

    var username: String?
        get() = accountStore.getActiveAccount()?.username ?: get("username")
        set(value) = put("username", value)

    var fullName: String?
        get() = accountStore.getActiveAccount()?.fullName ?: get("full_name")
        set(value) = put("full_name", value)

    var profilePicUrl: String?
        get() = accountStore.getActiveAccount()?.profilePicUrl ?: get("profile_pic_url")
        set(value) = put("profile_pic_url", value)

    var useInstagramLogin: Boolean
        get() = get("use_instagram_login", "true") == "true"
        set(value) = put("use_instagram_login", value.toString())

    val isLoggedIn: Boolean
        get() = accountStore.getActiveAccount() != null || !get("session_id").isNullOrBlank()

    fun getActiveSession(): IgSession? {
        if (!useInstagramLogin) return null
        return accountStore.getActiveSession() ?: sessionId?.takeIf { it.isNotBlank() }?.let {
            IgSession(sessionId = it, csrfToken = csrfToken, userId = userId)
        }
    }

    fun applyCookieString(input: String, customUsername: String? = null): StoredAccount? {
        val clean = input.trim()
        if (clean.isBlank()) return null

        val map = mutableMapOf<String, String>()
        if (clean.contains("=") || clean.contains(";")) {
            clean.split(";").forEach { part ->
                val kv = part.trim()
                if (kv.contains("=")) {
                    val k = kv.substringBefore("=").trim().lowercase()
                    val v = kv.substringAfter("=").trim().trim('"', '\'')
                    if (k.isNotBlank() && v.isNotBlank()) {
                        map[k] = v
                    }
                }
            }
        }

        val sess = map["sessionid"] ?: if (!clean.contains("=")) clean.trim('"', '\'') else null
        if (sess.isNullOrBlank()) return null

        val uid = map["ds_user_id"]
        val csrf = map["csrftoken"]
        val uName = customUsername ?: uid ?: "instagram_user"

        val account = StoredAccount(
            id = java.util.UUID.randomUUID().toString(),
            username = uName,
            sessionId = sess,
            csrfToken = csrf,
            userId = uid
        )
        accountStore.addOrUpdateAccount(account)
        return account
    }

    fun clearSession() {
        accountStore.clearAll()
        put("session_id", null)
        put("csrf_token", null)
        put("user_id", null)
        put("username", null)
        put("full_name", null)
        put("profile_pic_url", null)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value ?: "") }.getOrDefault(fallback)

    companion object {
        val DEFAULT_FOLDER_PATH: String =
            System.getProperty("user.home") + File.separator + "Downloads" + File.separator + "InstaFolio"
    }
}
