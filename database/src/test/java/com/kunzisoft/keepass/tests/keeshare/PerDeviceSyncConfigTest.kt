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
package com.kunzisoft.keepass.tests.keeshare

import com.kunzisoft.keepass.database.keeshare.PerDeviceSyncConfig
import junit.framework.TestCase
import java.io.File

class PerDeviceSyncConfigTest : TestCase() {

    fun testContainerFileName() {
        assertEquals("P56IOI7.kdbx", PerDeviceSyncConfig.containerFileName("P56IOI7"))
        assertEquals("XKZU3E4.kdbx", PerDeviceSyncConfig.containerFileName("XKZU3E4"))
        assertEquals("abc1234.kdbx", PerDeviceSyncConfig.containerFileName("abc1234"))
    }

    fun testContainerFileNameSanitizesPathTraversal() {
        // Path separators and special chars are stripped
        assertEquals("etcpasswd.kdbx", PerDeviceSyncConfig.containerFileName("../etc/passwd"))
        assertEquals("ABC.kdbx", PerDeviceSyncConfig.containerFileName("A-B-C"))
    }

    fun testContainerFileNameRejectsEmpty() {
        try {
            PerDeviceSyncConfig.containerFileName("---")
            fail("Should have thrown IllegalArgumentException for all-special-char deviceId")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    fun testListOtherDeviceFilesExcludesOwn() {
        val tempDir = createTempDir("keeshare-test")
        try {
            // Create test container files
            File(tempDir, "P56IOI7.kdbx").createNewFile()
            File(tempDir, "XKZU3E4.kdbx").createNewFile()
            File(tempDir, "ABC1234.kdbx").createNewFile()
            // Create a non-kdbx file (should be ignored)
            File(tempDir, "readme.txt").createNewFile()

            val others = PerDeviceSyncConfig.listOtherDeviceFiles(tempDir, "P56IOI7")

            assertEquals(2, others.size)
            assertTrue(others.any { it.name == "ABC1234.kdbx" })
            assertTrue(others.any { it.name == "XKZU3E4.kdbx" })
            assertFalse(others.any { it.name == "P56IOI7.kdbx" })
            assertFalse(others.any { it.name == "readme.txt" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun testListOtherDeviceFilesEmptyDir() {
        val tempDir = createTempDir("keeshare-test")
        try {
            val others = PerDeviceSyncConfig.listOtherDeviceFiles(tempDir, "P56IOI7")
            assertTrue(others.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun testListAllDeviceFiles() {
        val tempDir = createTempDir("keeshare-test")
        try {
            File(tempDir, "P56IOI7.kdbx").createNewFile()
            File(tempDir, "XKZU3E4.kdbx").createNewFile()
            File(tempDir, "notes.txt").createNewFile()

            val all = PerDeviceSyncConfig.listAllDeviceFiles(tempDir)

            assertEquals(2, all.size)
            assertTrue(all.any { it.name == "P56IOI7.kdbx" })
            assertTrue(all.any { it.name == "XKZU3E4.kdbx" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun testListOtherDeviceFilesSorted() {
        val tempDir = createTempDir("keeshare-test")
        try {
            File(tempDir, "ZZZ0000.kdbx").createNewFile()
            File(tempDir, "AAA0000.kdbx").createNewFile()
            File(tempDir, "MMM0000.kdbx").createNewFile()

            val others = PerDeviceSyncConfig.listOtherDeviceFiles(tempDir, "XXXXXXX")

            assertEquals(3, others.size)
            assertEquals("AAA0000.kdbx", others[0].name)
            assertEquals("MMM0000.kdbx", others[1].name)
            assertEquals("ZZZ0000.kdbx", others[2].name)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun testListOtherDeviceFilesCaseInsensitiveExclusion() {
        val tempDir = createTempDir("keeshare-test")
        try {
            File(tempDir, "P56IOI7.kdbx").createNewFile()
            File(tempDir, "XKZU3E4.KDBX").createNewFile()

            // Own device ID in different case
            val others = PerDeviceSyncConfig.listOtherDeviceFiles(tempDir, "p56ioi7")

            assertEquals(1, others.size)
            assertEquals("XKZU3E4.KDBX", others[0].name)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun testListOtherDeviceFilesNonExistentDir() {
        val nonExistent = File("/tmp/keeshare-nonexistent-${System.nanoTime()}")
        val others = PerDeviceSyncConfig.listOtherDeviceFiles(nonExistent, "P56IOI7")
        assertTrue(others.isEmpty())
    }
}
