/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.keeshare

import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Long-polls the Syncthing REST API for ItemFinished events.
 *
 * When a .kdbx file in a watched sync directory finishes syncing,
 * triggers [onSyncComplete] for immediate KeeShare import.
 *
 * Falls back gracefully if Syncthing is unreachable — the periodic
 * sync timer in DatabaseTaskNotificationService handles that case.
 */
class SyncthingEventPoller(
    private val apiUrl: String,
    private val apiKey: String?,
    private val watchedDirs: Set<String>,
    private val onSyncComplete: (filePath: String) -> Unit
) {
    private var lastEventId = 0L

    /**
     * Perform a single long-poll to the Syncthing events API.
     * Blocks for up to [POLL_TIMEOUT_SECONDS] seconds waiting for events.
     *
     * @return true if the poll completed successfully, false if Syncthing is unreachable
     */
    fun poll(): Boolean {
        return try {
            val url = URL("$apiUrl/rest/events?events=ItemFinished&since=$lastEventId&timeout=$POLL_TIMEOUT_SECONDS")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = (POLL_TIMEOUT_SECONDS + 5) * 1000 // Extra buffer
                connection.setRequestProperty("Accept", "application/json")
                if (!apiKey.isNullOrEmpty()) {
                    connection.setRequestProperty("X-API-Key", apiKey)
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Syncthing events API returned ${connection.responseCode}")
                    return false
                }

                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                processEvents(responseBody)
                true
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Syncthing events API not available: ${e.message}")
            false
        }
    }

    private fun processEvents(json: String) {
        try {
            val events = JSONArray(json)
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val eventId = event.getLong("id")
                if (eventId > lastEventId) {
                    lastEventId = eventId
                }

                val data = event.optJSONObject("data") ?: continue
                val action = data.optString("action", "")
                if (action != "update") continue

                val folder = data.optString("folder", "")
                val item = data.optString("item", "")

                if (!item.endsWith(KDBX_EXTENSION, ignoreCase = true)) continue

                // Check if the folder maps to a watched directory
                // Syncthing folder IDs may differ from paths, but in simple setups
                // the folder path is often used as the ID or can be derived
                for (watchedDir in watchedDirs) {
                    if (watchedDir.contains(folder) || folder.contains(watchedDir)) {
                        val filePath = "$watchedDir/$item"
                        Log.i(TAG, "Syncthing finished syncing: $filePath")
                        onSyncComplete(filePath)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Syncthing events", e)
        }
    }

    companion object {
        private val TAG = SyncthingEventPoller::class.java.simpleName
        private const val KDBX_EXTENSION = ".kdbx"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val POLL_TIMEOUT_SECONDS = 60
    }
}
