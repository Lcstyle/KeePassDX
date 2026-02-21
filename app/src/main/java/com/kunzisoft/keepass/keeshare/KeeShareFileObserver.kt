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

import android.os.FileObserver
import android.util.Log
import java.io.File

/**
 * Watches sync directories for changes to .kdbx container files.
 *
 * Uses Android's [FileObserver] (inotify-based) to detect:
 * - CLOSE_WRITE: A file was written and closed (direct writes)
 * - MOVED_TO: A file was renamed into the directory (Syncthing uses rename-after-write)
 *
 * When a matching event is detected, the debounced [onFileChanged] callback is triggered.
 */
class KeeShareFileObserver(
    private val syncDirs: List<File>,
    private val onFileChanged: (File) -> Unit
) {
    private val observers = mutableListOf<FileObserver>()
    private val debounceMap = mutableMapOf<String, Long>()

    @Volatile
    private var watching = false

    fun startWatching() {
        if (watching) return
        watching = true

        for (dir in syncDirs) {
            if (!dir.isDirectory) {
                Log.d(TAG, "Skipping non-existent sync dir: ${dir.absolutePath}")
                continue
            }

            val observer = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    if (!path.endsWith(KDBX_EXTENSION, ignoreCase = true)) return

                    val file = File(dir, path)
                    val now = System.currentTimeMillis()

                    synchronized(debounceMap) {
                        val lastEvent = debounceMap[file.absolutePath] ?: 0L
                        if (now - lastEvent < DEBOUNCE_MS) {
                            return
                        }
                        debounceMap[file.absolutePath] = now
                    }

                    Log.d(TAG, "File changed: ${file.absolutePath} (event=$event)")
                    onFileChanged(file)
                }
            }

            observer.startWatching()
            observers.add(observer)
            Log.i(TAG, "Watching sync dir: ${dir.absolutePath}")
        }
    }

    fun stopWatching() {
        watching = false
        for (observer in observers) {
            observer.stopWatching()
        }
        observers.clear()
        synchronized(debounceMap) {
            debounceMap.clear()
        }
        Log.i(TAG, "Stopped watching all sync dirs")
    }

    companion object {
        private val TAG = KeeShareFileObserver::class.java.simpleName
        private const val KDBX_EXTENSION = ".kdbx"
        private const val DEBOUNCE_MS = 500L
    }
}
