# KeeShare Support for KeePassDX

## What This Document Covers

This document explains KeeShare — a protocol for sharing password groups between
devices — and how it has been implemented in KeePassDX for Android. It assumes no
prior knowledge of KeePassXC, KeePassDX, or KeeShare.

---

## Part 1: Understanding the Problem

### Password Managers and Database Files

KeePass-family password managers (KeePassXC on desktop, KeePassDX on Android)
store all your passwords in a single encrypted file called a **KDBX database**.
Inside this file, passwords are organized into **groups** (folders) and
**entries** (individual credentials like a login for Gmail or Netflix).

A typical database might look like this:

```
My Passwords (database root)
├── Personal/
│   ├── Gmail
│   ├── Netflix
│   └── Bank of America
├── Work/
│   ├── Slack
│   ├── GitHub
│   └── AWS Console
└── Shared with Family/
    ├── Netflix (family account)
    ├── Disney+
    └── Home WiFi
```

### The Sync Problem

If you use KeePassXC on your laptop and KeePassDX on your phone, you need some
way to keep the database in sync. The simplest approach is to sync the entire
`.kdbx` file using a service like Syncthing, Nextcloud, or Google Drive. This
works, but it has a fundamental limitation:

**You can only edit on one device at a time.**

If you add a password on your phone while your laptop also has the database open,
you get a conflict. One edit overwrites the other. KeePass databases do support
merge-on-conflict in some cases, but it is fragile across full-database syncs and
can lose data if both sides changed the same entry.

### The KeeShare Solution

KeeShare takes a different approach. Instead of syncing the *entire* database, it
syncs *individual groups*. Each shared group gets its own small container file
that holds just that group's entries. Devices exchange these container files
through a shared folder (typically managed by Syncthing), and each device merges
the incoming entries into its own database.

This means:

- Multiple devices can edit different groups simultaneously without conflict
- You can share specific groups with specific people (a "Family" group with your
  partner, a "Team" group with coworkers) without sharing your entire vault
- Each device keeps its own full database; KeeShare only moves the *shared* parts

### Where KeeShare Came From

KeeShare was originally built into [KeePassXC](https://keepassxc.org/), the
desktop password manager for Linux, macOS, and Windows. It was designed for
desktop-to-desktop sharing and has been available in KeePassXC since version 2.4.

Until now, KeePassDX (the Android counterpart) did not support KeeShare. If you
had shared groups configured in KeePassXC, KeePassDX would see them as ordinary
groups and ignore the sharing configuration. Worse, older versions of KeePassDX
would silently strip the KeeShare metadata when saving, breaking the desktop sync.

This implementation brings KeeShare to Android.

---

## Part 2: How KeeShare Works (The Protocol)

### Shared Groups and References

When you mark a group as "shared" in KeePassXC, it stores a small piece of
configuration inside the group's metadata (KDBX `CustomData`). This configuration
is called a **KeeShare Reference** and contains:

| Field    | Purpose                                           |
|----------|---------------------------------------------------|
| Type     | Direction: import-only, export-only, or both       |
| Path     | Filesystem path to the container file              |
| Password | Encryption password for the container              |
| Group    | UUID of the shared group (for verification)        |

The reference is stored as base64-encoded XML under the key
`KeeShare/Reference`. This is the **classic** format used by KeePassXC.

### Container Files

A **container file** is a miniature KDBX database that holds only the entries
from one shared group. It is encrypted with the password specified in the
reference (separate from the main database password).

When KeeShareXC exports a shared group, it:

1. Creates a new temporary KDBX database
2. Copies all entries (and optionally subgroups) from the shared group into it
3. Encrypts it with the reference password
4. Writes it to the path specified in the reference

When another device imports, it:

1. Opens the container file with the reference password
2. Merges the entries into the corresponding group in its own database
3. Uses timestamps to resolve conflicts (newer entry wins)

### Sync Direction

The reference's **type** field controls which direction data flows:

| Type        | Value | Behavior                                |
|-------------|-------|-----------------------------------------|
| Inactive    | 0     | KeeShare is configured but disabled      |
| Import      | 1     | Read-only: pull entries from container    |
| Export      | 2     | Write-only: push entries to container     |
| Synchronize | 3     | Bidirectional: both import and export     |

### The File-Sync Layer

KeeShare itself does not handle network communication. It relies on an external
file synchronization tool to move container files between devices. The most
common choices are:

- **[Syncthing](https://syncthing.net/)** — peer-to-peer, no cloud, open-source
  (recommended for privacy-conscious users)
- **Nextcloud** — self-hosted cloud storage
- **Google Drive / Dropbox / OneDrive** — commercial cloud storage
- **Any tool that syncs folders** — rsync, rclone, etc.

KeeShare reads and writes container files to a local directory. The sync tool
handles getting those files to and from other devices.

---

## Part 3: Per-Device Sync (Our Innovation)

### The Problem with Classic KeeShare

KeePassXC's classic approach uses a **single container file per group**. All
devices read from and write to the same file. This works reasonably well on
desktop where Syncthing can manage file locks and conflict detection, but it
creates problems on mobile:

- Android apps cannot reliably lock files on shared storage
- If two devices export at nearly the same time, one write can overwrite the other
- There is no way to tell which device last updated the container

### Per-Device Containers

This implementation introduces a **per-device sync** model that solves these
problems. Instead of one container file per group, each device gets its own
container file:

```
Sync Folder/
├── Shared-Passwords/
│   ├── LAPTOP7.kdbx     ← KeePassXC desktop exported this
│   ├── PHONE01.kdbx     ← KeePassDX (your phone) exported this
│   └── TABLET.kdbx      ← KeePassDX (your tablet) exported this
```

Each device:
- **Writes only to its own file** (e.g., `PHONE01.kdbx`)
- **Reads from all other devices' files** (e.g., `LAPTOP7.kdbx`, `TABLET.kdbx`)

This completely eliminates write conflicts. Two devices can export simultaneously
without any risk of data loss, because they write to different files.

### Device Identity

Each device needs a short, unique identifier. The implementation resolves this
automatically through a priority chain:

1. **User-configured ID** — Set manually in KeeShare settings
2. **Syncthing device ID** — Queried from the local Syncthing REST API
   (`/rest/system/status`), truncated to 7 characters
3. **Generated fallback** — A random 5-character hex ID, generated once and
   stored for all future syncs

The device ID is sanitized to alphanumeric characters only (preventing filesystem
path traversal attacks) and used as the container filename: `{DEVICE_ID}.kdbx`.

### Per-Device Configuration

Per-device sync is configured via a separate custom data key
(`KeeShare/PerDeviceSync`) stored on the group, with these fields:

| Field     | Purpose                                              |
|-----------|------------------------------------------------------|
| SyncDir   | Path to the sync directory for this group             |
| Password  | Encryption password for all containers in this group  |
| KeepGroups| Whether to preserve subgroup structure in containers  |

When both classic and per-device configurations exist on the same group,
per-device takes priority.

### Stale Device Cleanup

Over time, devices may be retired (old phone, reformatted laptop). Their
container files would linger in the sync directory indefinitely. The
implementation includes automatic cleanup:

- Container files not modified in 90 days are considered stale
- Stale files from other devices are deleted during sync
- Your own device's file is never deleted
- The threshold is configurable (set to 0 to disable cleanup)

---

## Part 4: Using KeeShare in KeePassDX

### Prerequisites

Before using KeeShare, you need:

1. **A KDBX database** — KeeShare only works with the KDBX format (version 4),
   not older KDB databases
2. **A file sync tool** — Syncthing is recommended. Install it on both your
   Android device and your desktop/laptop
3. **A shared folder** — Configure your sync tool to keep a folder in sync
   between devices. On Android, this is typically under
   `/storage/emulated/0/Syncthing/` or similar

### Setting Up Syncthing Integration (Optional but Recommended)

KeeShareDX can communicate with Syncthing's local REST API to automatically
detect your device ID. To enable this:

1. Open KeePassDX
2. Go to **Settings > KeeShare**
3. Configure:
   - **Syncthing API URL**: Usually `http://localhost:8384` (the default)
   - **Syncthing API Key**: Found in Syncthing's web UI under
     Actions > Settings > API Key
   - **Device ID**: Leave blank to auto-detect from Syncthing, or enter a
     custom short identifier

If you don't use Syncthing, KeeShareDX will generate a random device ID
automatically on first sync.

### Syncing a Shared Group (Manual)

Once your database contains groups with KeeShare configuration (set up in
KeePassXC, or configured via per-device sync custom data):

1. Open your database in KeePassDX
2. Navigate to any group view
3. Tap the **overflow menu** (three dots, top right)
4. Select **"Sync KeeShare"**

KeePassDX will:
- Import entries from all other devices' container files
- Export your current entries to your own container file
- Save the database
- Display a summary: *"KeeShare: imported 5 entries from 2 devices, exported 3
  entries"*

### Identifying Shared Groups

Groups that have KeeShare configuration display a small **share icon** next to
their name in the group list. This makes it easy to see at a glance which groups
participate in KeeShare sync.

### Auto-Sync (Background)

KeeShareDX includes three automatic sync mechanisms that work while your database
is open. You do not need to configure these — they activate automatically when a
database with KeeShare groups is loaded.

#### 1. Filesystem Watching

KeePassDX monitors the sync directories for changes using Android's `FileObserver`
(built on Linux `inotify`). When Syncthing writes a new container file into a
watched directory, KeePassDX detects it within milliseconds and triggers a sync.

- **Events monitored**: File close-after-write, file moved into directory
- **Debounce**: 500ms per file (prevents duplicate triggers from rapid updates)
- **Scope**: Only `.kdbx` files in configured sync directories

#### 2. Syncthing Event Polling

If the Syncthing REST API is configured, KeePassDX also long-polls Syncthing's
event API for `ItemFinished` events. This catches syncs that the filesystem
watcher might miss (e.g., if the directory watcher was temporarily inactive).

- **Endpoint**: `GET /rest/events?events=ItemFinished&since={lastId}&timeout=60`
- **Behavior**: Blocks for up to 60 seconds waiting for events, then reconnects
- **Graceful degradation**: If Syncthing is unreachable, polling pauses silently

#### 3. Periodic Fallback

As a safety net, KeePassDX runs a periodic sync check every 15 minutes while
a database with KeeShare groups is open. This catches any changes that slipped
through the real-time mechanisms (e.g., if the device was asleep when Syncthing
completed a transfer).

The periodic check only triggers a full sync if it detects container files newer
than the last sync timestamp, avoiding unnecessary work.

### Sync Lifecycle

Auto-sync is tied to the database lifecycle:

| Event                  | Action                                          |
|------------------------|-------------------------------------------------|
| Database opened/loaded | Start FileObserver + Syncthing poller + timer    |
| Database reloaded      | Restart all watchers (directories may have changed) |
| Database closed/locked | Stop all watchers and timers                     |

---

## Part 5: Architecture Overview

This section describes how the implementation is structured for developers or
users who want to understand the internals.

### Layer Diagram

```
┌─────────────────────────────────────────────────────┐
│                    UI Layer                          │
│  GroupActivity  ·  EntryActivity  ·  Settings        │
│  (menu items, toasts, visual indicators)             │
├─────────────────────────────────────────────────────┤
│                 ViewModel Layer                       │
│  DatabaseViewModel.syncKeeShare()                    │
├─────────────────────────────────────────────────────┤
│                 Service Layer                         │
│  DatabaseTaskNotificationService                     │
│  (intent dispatch, auto-sync lifecycle)              │
├─────────────────────────────────────────────────────┤
│                 Action Layer                          │
│  KeeShareSyncRunnable (extends SaveDatabaseRunnable) │
│  (import → export → save pipeline)                   │
├─────────────────────────────────────────────────────┤
│               Auto-Sync Layer                        │
│  KeeShareFileObserver  ·  SyncthingEventPoller       │
│  (filesystem inotify)    (REST API long-poll)        │
├─────────────────────────────────────────────────────┤
│                 Core Layer                            │
│  KeeShareImport  ·  KeeShareExport                   │
│  KeeShareContainer  ·  KeeShareReference             │
│  PerDeviceSyncConfig  ·  DeviceIdentity              │
│  (protocol logic, container I/O, merge)              │
└─────────────────────────────────────────────────────┘
```

### Manual Sync Flow

When you tap "Sync KeeShare" in the menu:

```
GroupActivity
  → DatabaseViewModel.syncKeeShare(save=true)
    → DatabaseTaskProvider.startKeeShareSync()
      → DatabaseTaskNotificationService  (intent: ACTION_DATABASE_KEESHARE_SYNC_TASK)
        → KeeShareSyncRunnable.onActionRun()
          1. Resolve device ID (preferences → Syncthing API → fallback)
          2. KeeShareImport.importAll()
             - Walk all groups for KeeShare config
             - Open each container file from other devices
             - Merge entries into target groups (timestamp-based conflict resolution)
          3. KeeShareExport.exportAll()
             - Walk all groups with per-device config
             - Build container databases (clone entries + icons)
             - Write containers atomically (temp file → rename)
          4. Save database (parent class)
          5. Return results bundle
        → DatabaseLockActivity.onDatabaseActionFinished()
          → Toast with import/export counts
          → Reload activity to show merged entries
```

### Auto-Sync Flow

When a container file changes on disk:

```
Syncthing writes LAPTOP7.kdbx to sync directory
  → KeeShareFileObserver.onEvent(CLOSE_WRITE, "LAPTOP7.kdbx")
    → Debounce check (500ms since last event for this file?)
      → Callback fires on main thread
        → Check mActionRunning == 0 (no sync already in progress)
          → hasNewerContainerFiles() confirms file is newer than last sync
            → startDatabaseServiceForKeeShareSync()
              → Full KeeShareSyncRunnable pipeline (same as manual)
```

### Container File I/O

**Writing (atomic):**
```
Container data
  → Write to temp file: PHONE01.kdbx.tmp
  → Atomic rename: PHONE01.kdbx.tmp → PHONE01.kdbx
  → (Fallback: copy + delete if rename fails)
```

This guarantees Syncthing never picks up a partially-written file.

**Reading:**
```
Container file bytes
  → Read first 4 bytes (magic number)
  → 0x03D9A29A → Unsigned KDBX → decrypt with reference password
  → 0x504B0304 → Signed ZIP → extract container.share.kdbx → decrypt
```

### Scoped Merge

Unlike a full database merge, KeeShare performs a **scoped merge**: entries from
a container are merged into a specific target group, not the entire database.
This uses the existing `DatabaseKDBXMerger.mergeIntoGroup()` method with
timestamp-based conflict resolution:

- If an entry exists in both source and target, the newer one wins
- Entry history is preserved
- New entries are added; deleted entries are tracked
- Custom data and icons are carried over

---

## Part 6: Interoperability with KeePassXC

### What Works Today

| Scenario                                   | Status  |
|--------------------------------------------|---------|
| Import containers created by KeePassXC     | Works   |
| Export containers readable by KeePassXC    | Works   |
| Classic single-file sync (import direction)| Works   |
| Classic single-file sync (export direction)| Works   |
| Per-device sync between KeePassDX devices  | Works   |
| Mixed: KeePassXC classic + KeePassDX per-device on same group | Works (per-device takes priority) |
| Unsigned containers (.kdbx)                | Works   |
| Signed containers (.kdbx.share)            | Read-only (signature verification deferred) |

### Compatibility Notes

- KeePassXC stores references under `KeeShare/Reference`; KeePassDX reads and
  respects this format without modification
- KeePassDX's per-device sync uses a separate key (`KeeShare/PerDeviceSync`)
  that KeePassXC ignores, so the two do not interfere
- Container files use standard KDBX format — any KeePass-compatible tool can
  open them with the correct password
- KeePassDX preserves all KeeShare custom data on save (no data is stripped)

### Typical Multi-Device Setup

```
┌──────────────┐     Syncthing      ┌──────────────┐
│   KeePassXC  │ ◄────────────────► │  KeePassDX   │
│   (Laptop)   │    shared folder   │  (Phone)     │
└──────┬───────┘                    └──────┬───────┘
       │                                    │
       ▼                                    ▼
  ~/Sync/Passwords/                 /storage/.../Sync/Passwords/
  ├── Team/                         ├── Team/
  │   ├── LAPTOP7.kdbx             │   ├── LAPTOP7.kdbx
  │   └── PHONE01.kdbx             │   └── PHONE01.kdbx
  └── Family/                       └── Family/
      ├── LAPTOP7.kdbx                 ├── LAPTOP7.kdbx
      └── PHONE01.kdbx                 └── PHONE01.kdbx
```

Both devices write their own container files and read from the other's.
Syncthing keeps the directories mirrored. KeeShare handles the merge logic.

---

## Part 7: Security Considerations

### Container Encryption

Each container file is a fully encrypted KDBX database. The encryption password
is set per-group in the KeeShare reference and is separate from your main
database password. Anyone who intercepts a container file cannot read it without
the reference password.

### Atomic Writes

Container files are written atomically (write to temp file, then rename). This
prevents file sync tools from picking up a half-written, corrupted container.

### Device ID Sanitization

Device IDs are stripped to alphanumeric characters only before being used as
filenames. This prevents path traversal attacks where a malicious device ID like
`../../etc/passwd` could write outside the sync directory.

### Signed Containers (Future)

KeePassXC supports cryptographically signed containers (`.kdbx.share` files)
using RSA-2048 with SHA-256 signatures. KeeShareDX can read these files but does
not yet verify signatures. Full signature verification is planned for a future
phase.

### Trust Model

KeeShare's security depends on:
1. The strength of the reference password (protects container contents)
2. The security of the sync transport (Syncthing uses TLS; cloud services vary)
3. The trustworthiness of devices in the sync group (any device can write
   containers that other devices will merge)

---

## Part 8: Troubleshooting

### "Sync KeeShare" menu item is missing

The menu item only appears when:
- The database is KDBX format (not KDB)
- The database is not in read-only mode
- The database is open in default (non-special) mode

If you are viewing a single entry (EntryActivity), the sync option is hidden
since it operates at the group/database level.

### Sync completes but no entries appear

- Verify the container files exist in the sync directory
- Check that the reference password matches between devices
- Ensure the sync directory path is accessible to KeePassDX on Android
  (Android storage permissions may restrict access)
- Look for error details in the sync result toast

### Device ID keeps changing

If you haven't configured a device ID and Syncthing is not running, KeePassDX
generates a random fallback ID. This ID is stored in preferences after first
generation and reused. If app data is cleared, a new ID will be generated,
creating a new container file (the old one will be cleaned up after 90 days).

To prevent this, either:
- Configure Syncthing so the device ID is detected automatically
- Set a manual device ID in Settings > KeeShare

### Syncthing event polling is not working

- Verify the Syncthing API URL is correct (default: `http://localhost:8384`)
- Ensure the API key is entered correctly (found in Syncthing > Actions >
  Settings > API Key)
- Check that Syncthing is running on the device
- The poller degrades gracefully — filesystem watching and periodic sync will
  still work even if the Syncthing API is unreachable

### Stale container files accumulating

Container files from devices that no longer sync are automatically cleaned up
after 90 days. If you need to clean them up sooner, manually delete the
unwanted `.kdbx` files from the sync directory (never delete your own device's
file while the database is open).

---

## Appendix A: File Inventory

### New Files (This Implementation)

| File | Purpose |
|------|---------|
| `database/.../keeshare/KeeShareReference.kt` | Parse/serialize KeePassXC classic references |
| `database/.../keeshare/PerDeviceSyncConfig.kt` | Per-device sync configuration and file management |
| `database/.../keeshare/DeviceIdentity.kt` | Device identity resolution (Syncthing API / fallback) |
| `database/.../keeshare/KeeShareImport.kt` | Import orchestration across all groups |
| `database/.../keeshare/KeeShareExport.kt` | Export orchestration with container building |
| `database/.../keeshare/container/KeeShareContainer.kt` | Container file I/O with atomic writes |
| `database/.../keeshare/container/ContainerFormat.kt` | Container format detection (unsigned/signed) |
| `app/.../database/action/KeeShareSyncRunnable.kt` | Manual/auto sync action (import + export + save) |
| `app/.../keeshare/KeeShareFileObserver.kt` | Filesystem watching with debounce |
| `app/.../keeshare/SyncthingEventPoller.kt` | Syncthing REST API long-polling |
| `res/xml/preferences_keeshare.xml` | KeeShare settings screen layout |
| `res/drawable/ic_keeshare_sync_white_24dp.xml` | Sync menu icon (24dp) |
| `res/drawable/ic_keeshare_indicator_white_16dp.xml` | Shared group indicator icon (16dp) |

### Modified Files

| File | Change |
|------|--------|
| `DatabaseTaskNotificationService.kt` | New sync action, auto-sync lifecycle management |
| `DatabaseTaskProvider.kt` | `startKeeShareSync()` method |
| `DatabaseViewModel.kt` | `syncKeeShare()` bridge method |
| `DatabaseLockActivity.kt` | Sync result handling (toast, reload) |
| `GroupActivity.kt` | Menu item visibility and click handler |
| `EntryActivity.kt` | Hide sync menu item |
| `NodesAdapter.kt` | KeeShare indicator icon on shared groups |
| `NestedSettingsFragment.kt` | `KEESHARE` screen enum entry |
| `NestedAppSettingsFragment.kt` | KeeShare preferences inflation |
| `MainPreferenceFragment.kt` | Settings navigation to KeeShare screen |
| `PreferencesUtil.kt` | KeeShare preference accessors |
| `Database.kt` | Public `databaseKDBX` getter |
| `Group.kt` | `hasKeeShareConfig()` method |
| `database.xml` (menu) | "Sync KeeShare" menu item |
| `strings.xml` | 20 KeeShare-related string resources |
| `preferences.xml` | KeeShare entry in main settings list |
| `item_list_nodes_group.xml` | Share indicator ImageView |

---

## Appendix B: Phased Implementation

The KeeShare implementation was designed in phases for incremental delivery:

| Phase | Scope | Status |
|-------|-------|--------|
| **Phase 1** | Core protocol: reference parsing, container I/O, import/export, per-device sync, device identity | Complete |
| **Phase 2** | Minimal UI: manual sync menu, service integration, settings screen, visual indicators | Complete |
| **Phase 3** | Group-level configuration UI (edit dialog for setting up per-device sync) | Planned |
| **Phase 4** | Signed container support (RSA-2048 signature verification, trust management) | Planned |
| **Phase 5** | Auto-sync: FileObserver, Syncthing event polling, periodic sync, stale cleanup | Complete |
| **Phase 6** | Conflict resolution UI (manual merge for timestamp ties) | Planned |

Phases 1, 2, and 5 are implemented. Phases 3, 4, and 6 are planned for future
development.
