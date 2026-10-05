package com.prakash.instafolio.core.session

import com.prakash.instafolio.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MultiAccountStore(private val storageFile: File) {

    private val accounts = mutableListOf<StoredAccount>()
    private var activeAccountId: String? = null
    private val listeners = mutableListOf<() -> Unit>()

    init {
        loadFromFile()
    }

    @Synchronized
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        val currentListeners = synchronized(this) { listeners.toList() }
        currentListeners.forEach { it.invoke() }
    }

    @Synchronized
    fun getAllAccounts(): List<StoredAccount> = accounts.toList()

    @Synchronized
    fun getActiveAccount(): StoredAccount? {
        val active = activeAccountId?.let { id -> accounts.find { it.id == id } }
        return active ?: accounts.firstOrNull()
    }

    @Synchronized
    fun getActiveSession(): IgSession? = getActiveAccount()?.toIgSession()

    @Synchronized
    fun setActiveAccount(id: String) {
        if (accounts.any { it.id == id }) {
            activeAccountId = id
            val idx = accounts.indexOfFirst { it.id == id }
            if (idx >= 0) {
                accounts[idx] = accounts[idx].copy(lastActiveAt = System.currentTimeMillis())
            }
            saveToFile()
            notifyChanged()
        }
    }

    @Synchronized
    fun addOrUpdateAccount(account: StoredAccount) {
        val existingIndex = accounts.indexOfFirst {
            it.id == account.id ||
            it.username.equals(account.username, ignoreCase = true) ||
            (it.userId != null && it.userId == account.userId) ||
            it.sessionId == account.sessionId
        }

        if (existingIndex >= 0) {
            accounts[existingIndex] = account.copy(lastActiveAt = System.currentTimeMillis())
            if (activeAccountId == null) activeAccountId = account.id
        } else {
            accounts.add(0, account)
            activeAccountId = account.id
        }
        saveToFile()
        notifyChanged()
    }

    @Synchronized
    fun removeAccount(id: String) {
        accounts.removeAll { it.id == id }
        if (activeAccountId == id) {
            activeAccountId = accounts.firstOrNull()?.id
        }
        saveToFile()
        notifyChanged()
    }

    @Synchronized
    fun updateHealth(id: String, health: SessionHealth) {
        val index = accounts.indexOfFirst { it.id == id }
        if (index >= 0) {
            val old = accounts[index]
            val updated = old.copy(
                health = health,
                username = health.username ?: old.username
            )
            accounts[index] = updated
            saveToFile()
            notifyChanged()
        }
    }

    @Synchronized
    fun clearAll() {
        accounts.clear()
        activeAccountId = null
        saveToFile()
        notifyChanged()
    }

    @Synchronized
    private fun loadFromFile() {
        if (!storageFile.exists()) return
        runCatching {
            val text = storageFile.readText()
            if (text.isBlank()) return
            val json = JSONObject(text)
            activeAccountId = json.optString("activeAccountId").takeIf { it.isNotBlank() }

            val arr = json.optJSONArray("accounts") ?: JSONArray()
            accounts.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val healthObj = obj.optJSONObject("health")
                val healthStatus = runCatching {
                    SessionHealthStatus.valueOf(healthObj?.optString("status") ?: "UNCHECKED")
                }.getOrDefault(SessionHealthStatus.UNCHECKED)

                val health = SessionHealth(
                    status = healthStatus,
                    message = healthObj?.optString("message") ?: "Unchecked",
                    checkedAt = healthObj?.optLong("checkedAt") ?: 0L,
                    username = healthObj?.optString("username").takeIf { !it.isNullOrBlank() }
                )

                accounts.add(
                    StoredAccount(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        username = obj.optString("username"),
                        fullName = obj.optString("fullName").takeIf { it.isNotBlank() },
                        profilePicUrl = obj.optString("profilePicUrl").takeIf { it.isNotBlank() },
                        sessionId = obj.optString("sessionId"),
                        csrfToken = obj.optString("csrfToken").takeIf { it.isNotBlank() },
                        userId = obj.optString("userId").takeIf { it.isNotBlank() },
                        addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
                        lastActiveAt = obj.optLong("lastActiveAt", System.currentTimeMillis()),
                        health = health
                    )
                )
            }
        }
    }

    @Synchronized
    private fun saveToFile() {
        runCatching {
            storageFile.parentFile?.mkdirs()
            val json = JSONObject()
            json.put("activeAccountId", activeAccountId ?: "")
            val arr = JSONArray()
            accounts.forEach { acc ->
                val obj = JSONObject()
                obj.put("id", acc.id)
                obj.put("username", acc.username)
                obj.put("fullName", acc.fullName ?: "")
                obj.put("profilePicUrl", acc.profilePicUrl ?: "")
                obj.put("sessionId", acc.sessionId)
                obj.put("csrfToken", acc.csrfToken ?: "")
                obj.put("userId", acc.userId ?: "")
                obj.put("addedAt", acc.addedAt)
                obj.put("lastActiveAt", acc.lastActiveAt)

                val healthObj = JSONObject()
                healthObj.put("status", acc.health.status.name)
                healthObj.put("message", acc.health.message)
                healthObj.put("checkedAt", acc.health.checkedAt)
                healthObj.put("username", acc.health.username ?: "")
                obj.put("health", healthObj)

                arr.put(obj)
            }
            json.put("accounts", arr)
            storageFile.writeText(json.toString(2))
        }
    }
}
