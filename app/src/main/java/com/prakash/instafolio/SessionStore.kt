package com.prakash.instafolio

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

import com.prakash.instafolio.core.model.IgSession

class SessionStore(private val context: Context) {

    private val prefs: SharedPreferences by lazy { openPrefs() }

    private fun openPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return try {
            EncryptedSharedPreferences.create(
                context,
                "ig_session",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.deleteSharedPreferences("ig_session")
            EncryptedSharedPreferences.create(
                context,
                "ig_session",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    val sessionId: String?
        get() = prefs.getString(KEY_SESSIONID, null)

    val csrfToken: String?
        get() = prefs.getString(KEY_CSRFTOKEN, null)

    val userId: String?
        get() = prefs.getString(KEY_DS_USER_ID, null)

    val username: String?
        get() = prefs.getString(KEY_USERNAME, null)

    val fullName: String?
        get() = prefs.getString(KEY_FULL_NAME, null)

    val profilePicUrl: String?
        get() = prefs.getString(KEY_PROFILE_PIC_URL, null)

    val isLoggedIn: Boolean
        get() = !sessionId.isNullOrEmpty()

    val session: IgSession?
        get() = sessionId?.takeIf { it.isNotEmpty() }?.let { IgSession(it, csrfToken, userId) }

    fun save(
        sessionId: String,
        csrfToken: String?,
        userId: String?,
        username: String? = null,
        fullName: String? = null,
        profilePicUrl: String? = null
    ) {
        prefs.edit()
            .putString(KEY_SESSIONID, sessionId)
            .putString(KEY_CSRFTOKEN, csrfToken)
            .putString(KEY_DS_USER_ID, userId)
            .apply {
                if (username != null) putString(KEY_USERNAME, username)
                if (fullName != null) putString(KEY_FULL_NAME, fullName)
                if (profilePicUrl != null) putString(KEY_PROFILE_PIC_URL, profilePicUrl)
            }
            .apply()
    }

    fun updateProfile(
        username: String?,
        fullName: String? = null,
        profilePicUrl: String? = null,
        userId: String? = null
    ) {
        prefs.edit().apply {
            if (username != null) putString(KEY_USERNAME, username)
            if (fullName != null) putString(KEY_FULL_NAME, fullName)
            if (profilePicUrl != null) putString(KEY_PROFILE_PIC_URL, profilePicUrl)
            if (userId != null) putString(KEY_DS_USER_ID, userId)
        }.apply()
    }

    fun applyCookieString(input: String) {
        val clean = input.trim()
        if (clean.isBlank()) return

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
        val curSessionId = sess ?: sessionId ?: ""
        val curUserId = map["ds_user_id"] ?: userId ?: run {
            val decoded = runCatching { java.net.URLDecoder.decode(curSessionId, "UTF-8") }.getOrDefault(curSessionId)
            if (decoded.contains(":")) decoded.substringBefore(":")
            else if (curSessionId.contains("%3A")) curSessionId.substringBefore("%3A")
            else null
        }
        val curCsrf = map["csrftoken"] ?: csrfToken

        if (curSessionId.isNotBlank()) {
            save(curSessionId, curCsrf, curUserId, username, fullName, profilePicUrl)
        }
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_SESSIONID = "sessionid"
        const val KEY_CSRFTOKEN = "csrftoken"
        const val KEY_DS_USER_ID = "ds_user_id"
        const val KEY_USERNAME = "username"
        const val KEY_FULL_NAME = "full_name"
        const val KEY_PROFILE_PIC_URL = "profile_pic_url"
    }
}
