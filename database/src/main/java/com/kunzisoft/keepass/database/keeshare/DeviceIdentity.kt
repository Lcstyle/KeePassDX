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
package com.kunzisoft.keepass.database.keeshare

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Retrieves the device identity for per-device KeeShare sync.
 *
 * Priority chain:
 * 1. Syncthing REST API (`/rest/system/status` → `myID` field, first 7 chars)
 * 2. User-configured device name (from app preferences)
 * 3. Fallback: hostname or stored random UUID
 */
object DeviceIdentity {

    private val TAG = DeviceIdentity::class.java.simpleName
    private const val DEVICE_ID_SHORT_LENGTH = 7
    private const val DEFAULT_SYNCTHING_URL = "http://localhost:8384"
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 3000

    /**
     * Get the full Syncthing device ID from the local REST API.
     *
     * @param syncthingApiUrl Base URL of the Syncthing REST API
     * @param apiKey Optional API key for authentication
     * @return The full device ID string, or null if unavailable
     */
    fun getDeviceId(
        syncthingApiUrl: String = DEFAULT_SYNCTHING_URL,
        apiKey: String? = null
    ): String? {
        return try {
            querySyncthingDeviceId(syncthingApiUrl, apiKey)
        } catch (e: Exception) {
            Log.d(TAG, "Syncthing API not available: ${e.message}")
            null
        }
    }

    /**
     * Get the short device ID (first 7 characters of the Syncthing device ID).
     *
     * @param syncthingApiUrl Base URL of the Syncthing REST API
     * @param apiKey Optional API key for authentication
     * @return The short device ID, or null if unavailable
     */
    fun getDeviceIdShort(
        syncthingApiUrl: String = DEFAULT_SYNCTHING_URL,
        apiKey: String? = null
    ): String? {
        return getDeviceId(syncthingApiUrl, apiKey)?.take(DEVICE_ID_SHORT_LENGTH)
    }

    /**
     * Generate a fallback device ID when Syncthing is not available.
     * Uses a deterministic short hash of a random UUID.
     *
     * The caller should persist this value (e.g., in SharedPreferences) so it
     * remains stable across app restarts.
     */
    fun generateFallbackDeviceId(): String {
        return UUID.randomUUID().toString()
            .replace("-", "")
            .take(DEVICE_ID_SHORT_LENGTH)
            .uppercase()
    }

    /**
     * Parse a Syncthing device ID from a JSON response body.
     * Exported for testing.
     */
    fun parseDeviceIdFromJson(json: String): String? {
        return try {
            val jsonObject = JSONObject(json)
            jsonObject.optString("myID", null)?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Syncthing status JSON", e)
            null
        }
    }

    private fun querySyncthingDeviceId(baseUrl: String, apiKey: String?): String? {
        val url = URL("$baseUrl/rest/system/status")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            if (!apiKey.isNullOrEmpty()) {
                connection.setRequestProperty("X-API-Key", apiKey)
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "Syncthing API returned ${connection.responseCode}")
                return null
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            return parseDeviceIdFromJson(responseBody)
        } finally {
            connection.disconnect()
        }
    }
}
