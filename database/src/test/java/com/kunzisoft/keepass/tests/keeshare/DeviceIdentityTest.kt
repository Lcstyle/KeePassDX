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

import com.kunzisoft.keepass.database.keeshare.DeviceIdentity
import junit.framework.TestCase

class DeviceIdentityTest : TestCase() {

    fun testParseValidSyncthingResponse() {
        val json = """
            {
                "alloc": 28987592,
                "connectionServiceStatus": {},
                "cpuPercent": 0.02,
                "discoveryEnabled": true,
                "discoveryErrors": {},
                "discoveryMethods": 8,
                "goroutines": 179,
                "guiAddressOverridden": false,
                "guiAddressUsed": "127.0.0.1:8384",
                "lastDialStatus": {},
                "myID": "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2",
                "pathSeparator": "/",
                "startTime": "2022-03-02T10:05:31+01:00",
                "sys": 42092840,
                "tilde": "/home/user",
                "uptime": 1234
            }
        """.trimIndent()

        val deviceId = DeviceIdentity.parseDeviceIdFromJson(json)
        assertEquals("P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2", deviceId)
    }

    fun testParseShortDeviceId() {
        val json = """{"myID": "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2"}"""

        val fullId = DeviceIdentity.parseDeviceIdFromJson(json)
        assertNotNull(fullId)
        // Short ID is first 7 chars
        assertEquals("P56IOI7", fullId!!.take(7))
    }

    fun testParseEmptyJson() {
        val deviceId = DeviceIdentity.parseDeviceIdFromJson("{}")
        // optString returns null for missing key
        assertNull(deviceId)
    }

    fun testParseInvalidJson() {
        val deviceId = DeviceIdentity.parseDeviceIdFromJson("not json")
        assertNull(deviceId)
    }

    fun testParseMissingMyId() {
        val json = """{"alloc": 28987592, "sys": 42092840}"""
        val deviceId = DeviceIdentity.parseDeviceIdFromJson(json)
        assertNull(deviceId)
    }

    fun testFallbackDeviceIdLength() {
        val fallbackId = DeviceIdentity.generateFallbackDeviceId()
        assertEquals(7, fallbackId.length)
    }

    fun testFallbackDeviceIdIsUppercase() {
        val fallbackId = DeviceIdentity.generateFallbackDeviceId()
        assertEquals(fallbackId, fallbackId.uppercase())
    }

    fun testFallbackDeviceIdUniqueness() {
        val ids = (1..100).map { DeviceIdentity.generateFallbackDeviceId() }.toSet()
        // With 7 hex chars, collisions in 100 trials are astronomically unlikely
        assertTrue("Expected unique fallback IDs", ids.size > 90)
    }

    fun testGetDeviceIdShortFromUnavailableApi() {
        // Using a URL that won't be running — should return null gracefully
        val result = DeviceIdentity.getDeviceIdShort("http://127.0.0.1:1")
        assertNull(result)
    }
}
