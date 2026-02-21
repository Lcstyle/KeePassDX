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
import com.kunzisoft.keepass.database.element.Attachment
import com.kunzisoft.keepass.database.element.DeletedObject
import com.kunzisoft.keepass.database.element.database.DatabaseKDBX
import com.kunzisoft.keepass.database.element.entry.EntryKDBX
import com.kunzisoft.keepass.database.element.group.GroupKDBX
import com.kunzisoft.keepass.database.element.node.NodeHandler
import com.kunzisoft.keepass.utils.readAllBytes
import java.io.File

/**
 * Orchestrates KeeShare export: walks all groups in the database looking for
 * per-device sync config in custom data, clones group content into temporary
 * container databases, and writes them as per-device KDBX files.
 */
object KeeShareExport {

    private val TAG = KeeShareExport::class.java.simpleName

    data class ExportResult(
        val groupName: String,
        val containerPath: String,
        val entriesExported: Int,
        val success: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Export all groups that have per-device sync config to their respective
     * container files.
     *
     * @param database The source database containing shared groups
     * @param deviceId This device's short ID (for naming the container file)
     * @param cacheDirectory Directory for temporary binary storage
     * @param targetFileProvider Resolves (syncDir, deviceId) to a target File for
     *        atomic writing. The file will be written atomically (write-to-temp,
     *        then rename) to prevent Syncthing from syncing partial writes.
     * @return List of export results for each group processed
     */
    fun exportAll(
        database: DatabaseKDBX,
        deviceId: String,
        cacheDirectory: File,
        targetFileProvider: (syncDir: String, deviceId: String) -> File?
    ): List<ExportResult> {
        val results = mutableListOf<ExportResult>()
        val groupsToProcess = mutableListOf<GroupKDBX>()

        // Collect all groups with per-device sync config
        database.rootGroup?.doForEachChild(
            null,
            object : NodeHandler<GroupKDBX>() {
                override fun operate(node: GroupKDBX): Boolean {
                    if (node.customData.get(KeeShareReference.PER_DEVICE_KEY) != null) {
                        groupsToProcess.add(node)
                    }
                    return true
                }
            }
        )

        for (group in groupsToProcess) {
            results.add(exportGroup(database, group, deviceId, cacheDirectory, targetFileProvider))
        }

        return results
    }

    private fun exportGroup(
        database: DatabaseKDBX,
        group: GroupKDBX,
        deviceId: String,
        cacheDirectory: File,
        targetFileProvider: (syncDir: String, deviceId: String) -> File?
    ): ExportResult {
        val groupName = group.title
        val perDeviceData = group.customData.get(KeeShareReference.PER_DEVICE_KEY)
            ?: return ExportResult(groupName, "", 0, false, "No per-device sync config")

        val config = PerDeviceSyncConfig.fromCustomData(perDeviceData.value)
            ?: return ExportResult(groupName, "", 0, false, "Failed to parse sync config")

        val containerPath = "${config.syncDir}/${PerDeviceSyncConfig.containerFileName(deviceId)}"

        return try {
            val targetFile = targetFileProvider(config.syncDir, deviceId)
                ?: return ExportResult(groupName, containerPath, 0, false, "Could not resolve target file")

            val containerDb = buildContainerDatabase(database, group, config, cacheDirectory)
            val entryCount = countEntries(containerDb)

            // Atomic write: write to temp file, then rename to target.
            // This guarantees other devices (via Syncthing) never see a
            // partially-written container file.
            KeeShareContainer.writeUnsignedAtomic(containerDb, targetFile, config.password)

            Log.i(TAG, "Exported $entryCount entries from $groupName to $containerPath")
            ExportResult(groupName, containerPath, entryCount, success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export $groupName to $containerPath", e)
            ExportResult(groupName, containerPath, 0, success = false,
                errorMessage = e.message)
        }
    }

    /**
     * Build a temporary container database with the entries from [sourceGroup].
     * The container database has a single root group containing copies of all
     * entries (and optionally subgroups) from the source.
     */
    private fun buildContainerDatabase(
        sourceDatabase: DatabaseKDBX,
        sourceGroup: GroupKDBX,
        config: PerDeviceSyncConfig,
        cacheDirectory: File
    ): DatabaseKDBX {
        val containerDb = DatabaseKDBX(
            databaseName = sourceGroup.title,
            rootName = sourceGroup.title
        ).apply {
            binaryCache.cacheDirectory = cacheDirectory
        }

        val containerRoot = containerDb.rootGroup!!

        // Copy entries from source group to container root
        cloneEntriesInto(sourceDatabase, containerDb, sourceGroup, containerRoot)

        // Optionally copy subgroups
        if (config.keepGroups) {
            sourceGroup.getChildGroups().forEach { srcGroup ->
                cloneGroupRecursive(sourceDatabase, containerDb, srcGroup, containerRoot)
            }
        }

        // Copy custom icons used by entries
        copyReferencedIcons(sourceDatabase, containerDb, sourceGroup)

        // Propagate deleted objects from source database
        sourceDatabase.deletedObjects.forEach { deletedObject ->
            containerDb.addDeletedObject(
                DeletedObject(deletedObject.uuid, deletedObject.deletionTime)
            )
        }

        return containerDb
    }

    private fun cloneGroupRecursive(
        sourceDatabase: DatabaseKDBX,
        containerDb: DatabaseKDBX,
        sourceGroup: GroupKDBX,
        parentGroup: GroupKDBX
    ) {
        val clonedGroup = GroupKDBX().apply {
            updateWith(sourceGroup, updateParents = false)
        }
        // Don't copy KeeShare config into exported containers
        // (prevents re-export loops)

        containerDb.addGroupTo(clonedGroup, parentGroup)
        cloneEntriesInto(sourceDatabase, containerDb, sourceGroup, clonedGroup)

        sourceGroup.getChildGroups().forEach { childGroup ->
            cloneGroupRecursive(sourceDatabase, containerDb, childGroup, clonedGroup)
        }
    }

    /**
     * Clone all entries from [sourceGroup] into [targetGroup] in [containerDb],
     * including deep-copying attachments between binary pools.
     */
    private fun cloneEntriesInto(
        sourceDatabase: DatabaseKDBX,
        containerDb: DatabaseKDBX,
        sourceGroup: GroupKDBX,
        targetGroup: GroupKDBX
    ) {
        sourceGroup.getChildEntries().forEach { srcEntry ->
            val clonedEntry = EntryKDBX().apply {
                updateWith(srcEntry, copyHistory = true, updateParents = false)
            }

            srcEntry.getAttachments(sourceDatabase.attachmentPool).forEach { attachment ->
                val binaryData = containerDb.buildNewBinaryAttachment(
                    true,
                    attachment.binaryData.isCompressed,
                    attachment.binaryData.isProtected
                )
                attachment.binaryData.getInputDataStream(sourceDatabase.binaryCache).use { inputStream ->
                    binaryData.getOutputDataStream(containerDb.binaryCache).use { outputStream ->
                        inputStream.readAllBytes { buffer ->
                            outputStream.write(buffer)
                        }
                    }
                }
                clonedEntry.putAttachment(
                    Attachment(attachment.name, binaryData),
                    containerDb.attachmentPool
                )
            }

            containerDb.addEntryTo(clonedEntry, targetGroup)
        }
    }

    private fun copyReferencedIcons(
        sourceDatabase: DatabaseKDBX,
        containerDb: DatabaseKDBX,
        group: GroupKDBX
    ) {
        group.doForEachChild(
            object : NodeHandler<EntryKDBX>() {
                override fun operate(node: EntryKDBX): Boolean {
                    val customIcon = node.icon.custom
                    if (customIcon.isUnknown) return true
                    val customIconUuid = customIcon.uuid
                    if (containerDb.iconsManager.getIcon(customIconUuid) != null) return true

                    sourceDatabase.iconsManager.doForEachCustomIcon { iconImage, binaryData ->
                        if (iconImage.uuid == customIconUuid) {
                            containerDb.addCustomIcon(
                                customIconUuid,
                                iconImage.name,
                                iconImage.lastModificationTime,
                                false
                            ) { _, newBinaryData ->
                                binaryData.getInputDataStream(sourceDatabase.binaryCache).use { inputStream ->
                                    newBinaryData?.getOutputDataStream(containerDb.binaryCache).use { outputStream ->
                                        inputStream.readAllBytes { buffer ->
                                            outputStream?.write(buffer)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return true
                }
            },
            null
        )
    }

    private fun countEntries(database: DatabaseKDBX): Int {
        var count = 0
        database.rootGroup?.doForEachChild(
            object : NodeHandler<EntryKDBX>() {
                override fun operate(node: EntryKDBX): Boolean {
                    count++
                    return true
                }
            },
            null
        )
        return count
    }
}
