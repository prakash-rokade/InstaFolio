package com.prakash.instafolio.core.history

import com.prakash.instafolio.core.model.DownloadHistoryItem
import com.prakash.instafolio.core.model.MediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class HistoryStore(private val storageFile: File) {

    private val items = mutableListOf<DownloadHistoryItem>()
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
    fun getAll(): List<DownloadHistoryItem> = items.toList()

    @Synchronized
    fun add(item: DownloadHistoryItem) {
        // Remove existing item with same shortcode or id if duplicate
        items.removeAll { it.id == item.id || (item.shortcode != null && it.shortcode == item.shortcode && it.mediaType == item.mediaType) }
        items.add(0, item) // newest first
        if (items.size > 500) {
            items.removeAt(items.lastIndex)
        }
        saveToFile()
        notifyChanged()
    }

    @Synchronized
    fun remove(id: String) {
        items.removeAll { it.id == id }
        saveToFile()
        notifyChanged()
    }

    @Synchronized
    fun clear() {
        items.clear()
        saveToFile()
        notifyChanged()
    }

    @Synchronized
    fun toggleFavorite(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val old = items[index]
            items[index] = old.copy(isFavorite = !old.isFavorite)
            saveToFile()
            notifyChanged()
        }
    }

    @Synchronized
    fun filter(
        mediaType: MediaType? = null,
        author: String? = null,
        query: String? = null,
        favoritesOnly: Boolean = false
    ): List<DownloadHistoryItem> {
        return items.filter { item ->
            if (favoritesOnly && !item.isFavorite) return@filter false
            if (mediaType != null && item.mediaType != mediaType) return@filter false
            if (!author.isNullOrBlank() && !item.authorUsername.equals(author, ignoreCase = true)) return@filter false
            if (!query.isNullOrBlank()) {
                val q = query.trim().lowercase()
                val matchAuthor = item.authorUsername?.lowercase()?.contains(q) == true
                val matchFull = item.authorFullName?.lowercase()?.contains(q) == true
                val matchCaption = item.caption?.lowercase()?.contains(q) == true
                val matchAudio = item.audioTitle?.lowercase()?.contains(q) == true || item.audioArtist?.lowercase()?.contains(q) == true
                val matchShortcode = item.shortcode?.lowercase()?.contains(q) == true
                if (!matchAuthor && !matchFull && !matchCaption && !matchAudio && !matchShortcode) {
                    return@filter false
                }
            }
            true
        }
    }

    @Synchronized
    private fun loadFromFile() {
        if (!storageFile.exists()) return
        runCatching {
            val text = storageFile.readText()
            if (text.isBlank()) return
            val jsonArray = JSONArray(text)
            items.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val filePathsList = mutableListOf<String>()
                obj.optJSONArray("filePaths")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        arr.optString(j)?.takeIf { it.isNotBlank() }?.let { filePathsList.add(it) }
                    }
                }

                val mediaType = runCatching {
                    MediaType.valueOf(obj.optString("mediaType", "PHOTO"))
                }.getOrDefault(MediaType.PHOTO)

                items.add(
                    DownloadHistoryItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        url = obj.optString("url"),
                        shortcode = obj.optString("shortcode").takeIf { it.isNotBlank() },
                        authorUsername = obj.optString("authorUsername").takeIf { it.isNotBlank() },
                        authorFullName = obj.optString("authorFullName").takeIf { it.isNotBlank() },
                        caption = obj.optString("caption").takeIf { it.isNotBlank() },
                        mediaType = mediaType,
                        filePaths = filePathsList,
                        thumbnailPath = obj.optString("thumbnailPath").takeIf { it.isNotBlank() },
                        previewUrl = obj.optString("previewUrl").takeIf { it.isNotBlank() },
                        audioTitle = obj.optString("audioTitle").takeIf { it.isNotBlank() },
                        audioArtist = obj.optString("audioArtist").takeIf { it.isNotBlank() },
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        isFavorite = obj.optBoolean("isFavorite", false)
                    )
                )
            }
        }
    }

    @Synchronized
    private fun saveToFile() {
        runCatching {
            storageFile.parentFile?.mkdirs()
            val array = JSONArray()
            items.forEach { item ->
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("url", item.url)
                obj.put("shortcode", item.shortcode ?: "")
                obj.put("authorUsername", item.authorUsername ?: "")
                obj.put("authorFullName", item.authorFullName ?: "")
                obj.put("caption", item.caption ?: "")
                obj.put("mediaType", item.mediaType.name)
                obj.put("filePaths", JSONArray(item.filePaths))
                obj.put("thumbnailPath", item.thumbnailPath ?: "")
                obj.put("previewUrl", item.previewUrl ?: "")
                obj.put("audioTitle", item.audioTitle ?: "")
                obj.put("audioArtist", item.audioArtist ?: "")
                obj.put("timestamp", item.timestamp)
                obj.put("isFavorite", item.isFavorite)
                array.put(obj)
            }
            storageFile.writeText(array.toString(2))
        }
    }
}
