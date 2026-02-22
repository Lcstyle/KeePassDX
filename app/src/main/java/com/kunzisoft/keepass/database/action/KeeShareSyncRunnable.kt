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
package com.kunzisoft.keepass.database.action

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.element.binary.BinaryData
import com.kunzisoft.keepass.database.keeshare.DeviceIdentity
import com.kunzisoft.keepass.database.keeshare.KeeShareExport
import com.kunzisoft.keepass.database.keeshare.KeeShareImport
import com.kunzisoft.keepass.database.keeshare.PerDeviceSyncConfig
import com.kunzisoft.keepass.hardware.HardwareKey
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.ProgressTaskUpdater
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class KeeShareSyncRunnable(
    context: Context,
    database: ContextualDatabase,
    saveDatabase: Boolean,
    challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
    private val progressTaskUpdater: ProgressTaskUpdater?
) : SaveDatabaseRunnable(
    context,
    database,
    saveDatabase,
    null,
    challengeResponseRetriever
) {

    var importedEntries: Int = 0
    var importedDevices: Int = 0
    var exportedEntries: Int = 0

    override fun onStartRun() {
        database.wasReloaded = true
        super.onStartRun()
    }

    override fun onActionRun() {
        try {
            val kdbx = database.databaseKDBX
            if (kdbx == null) {
                setError("KeeShare sync requires a KDBX database")
                return
            }

            val deviceId = resolveDeviceId(context)
            val cacheDir = File(context.cacheDir, "keeshare")
            cacheDir.mkdirs()

            // Auto-upgrade classic SYNCHRONIZE references to include per-device config.
            // This adds per-device alongside the classic ref (not replacing it), so
            // KeePassDX writes its own container file while still writing the classic
            // path for KeePassXC interop.
            val upgraded = PerDeviceSyncConfig.autoUpgradeClassicReferences(kdbx)
            if (upgraded > 0) {
                Log.i(TAG, "Auto-upgraded $upgraded groups to per-device sync")
            }

            // 1. Import from all other device containers
            val importResults = KeeShareImport.importAll(
                database = kdbx,
                ownDeviceId = deviceId,
                cacheDirectory = cacheDir,
                fileProvider = { syncDir, ownId ->
                    val dir = File(syncDir)
                    if (!dir.isDirectory) {
                        emptyList()
                    } else {
                        PerDeviceSyncConfig.listOtherDeviceFiles(dir, ownId).map { file ->
                            file.name to FileInputStream(file) as InputStream
                        }
                    }
                },
                singleFileProvider = { path ->
                    val file = File(path)
                    if (file.exists()) FileInputStream(file) else null
                },
                isRAMSufficient = { memoryWanted ->
                    BinaryData.canMemoryBeAllocatedInRAM(context, memoryWanted)
                }
            )

            importedEntries = importResults.filter { it.success }.sumOf { it.entriesImported }
            importedDevices = importResults.filter { it.success }
                .map { it.containerName }.distinct().size

            // 2. Export own container for each shared group
            val exportResults = KeeShareExport.exportAll(
                database = kdbx,
                deviceId = deviceId,
                cacheDirectory = cacheDir,
                targetFileProvider = { syncDir, devId ->
                    val dir = File(syncDir)
                    if (!dir.isDirectory) {
                        dir.mkdirs()
                    }
                    File(dir, PerDeviceSyncConfig.containerFileName(devId))
                }
            )

            exportedEntries = exportResults.filter { it.success }.sumOf { it.entriesExported }

            // Log results
            val failedImports = importResults.filter { !it.success }
            val failedExports = exportResults.filter { !it.success }
            if (failedImports.isNotEmpty()) {
                Log.w(TAG, "Failed imports: ${failedImports.map { "${it.containerName}: ${it.errorMessage}" }}")
            }
            if (failedExports.isNotEmpty()) {
                Log.w(TAG, "Failed exports: ${failedExports.map { "${it.groupName}: ${it.errorMessage}" }}")
            }

            // Update last sync time
            PreferencesUtil.setKeeShareLastSyncTime(context, System.currentTimeMillis())

            // Put results in bundle for the activity callback
            result.data = Bundle().apply {
                putInt(RESULT_IMPORTED_ENTRIES, importedEntries)
                putInt(RESULT_IMPORTED_DEVICES, importedDevices)
                putInt(RESULT_EXPORTED_ENTRIES, exportedEntries)
            }
        } catch (e: Exception) {
            Log.e(TAG, "KeeShare sync failed", e)
            setError(e)
        }

        super.onActionRun()
    }

    companion object {
        private val TAG = KeeShareSyncRunnable::class.java.simpleName

        const val RESULT_IMPORTED_ENTRIES = "KEESHARE_IMPORTED_ENTRIES"
        const val RESULT_IMPORTED_DEVICES = "KEESHARE_IMPORTED_DEVICES"
        const val RESULT_EXPORTED_ENTRIES = "KEESHARE_EXPORTED_ENTRIES"

        /**
         * Resolve the device ID for this device.
         *
         * Priority:
         * 1. User-configured device ID in preferences
         * 2. Syncthing API (using configured URL and API key)
         * 3. Generate and persist a fallback random ID
         */
        fun resolveDeviceId(context: Context): String {
            // Check for user-configured device ID
            val configuredId = PreferencesUtil.getKeeShareDeviceId(context)
            if (!configuredId.isNullOrEmpty()) {
                return configuredId
            }

            // Try Syncthing API
            val apiUrl = PreferencesUtil.getKeeShareSyncthingApiUrl(context)
            val apiKey = PreferencesUtil.getKeeShareSyncthingApiKey(context)
            val syncthingId = DeviceIdentity.getDeviceIdShort(apiUrl, apiKey)
            if (!syncthingId.isNullOrEmpty()) {
                // Persist the detected ID
                PreferencesUtil.setKeeShareDeviceId(context, syncthingId)
                return syncthingId
            }

            // Generate and persist a fallback ID
            val fallbackId = DeviceIdentity.generateFallbackDeviceId()
            PreferencesUtil.setKeeShareDeviceId(context, fallbackId)
            return fallbackId
        }
    }
}
