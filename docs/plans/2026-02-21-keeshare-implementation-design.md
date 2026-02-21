# KeeShare Implementation for KeePassDX

**Date**: 2026-02-21
**Target**: Upstream PR to Kunzisoft/KeePassDX
**Status**: Design — not yet started

---

## Problem Statement

KeePassXC has a feature called KeeShare that allows groups of entries to be shared
and synchronized between separate KeePass databases via container files. KeePassDX
(Android) does not implement KeeShare. This means:

- KeePassDX deletes KeeShare settings when editing databases created in KeePassXC
  (fixed in #1335 — custom data is now preserved on round-trip)
- Users cannot sync specific groups between desktop (KeePassXC) and mobile (KeePassDX)
- The only current sync option is copying the entire `.kdbx` file, which requires
  closing the database on one device before editing on another

Upstream issue: https://github.com/Kunzisoft/KeePassDX/issues/549

## Goal

Implement KeeShare support in KeePassDX so that shared groups automatically
synchronize with KeePassXC via file-based sync (Syncthing, Nextcloud, cloud drives).
Phased from barebones MVP to full feature parity.

---

## Background: How KeeShare Works in KeePassXC

### Data Storage

Each shared group stores its configuration in KDBX `CustomData` under the key
`KeeShare/Reference`. The value is a base64-encoded XML document:

```xml
<KeeShare>
  <Type>
    <Import/>    <!-- present if importing -->
    <Export/>    <!-- present if exporting -->
  </Type>
  <Group>...uuid-base64...</Group>
  <Path>...file-path-base64...</Path>
  <Password>...password-base64...</Password>
  <KeepGroups>True|False</KeepGroups>
</KeeShare>
```

**Type flags:**
- `ImportFrom = 1` — read-only from source container
- `ExportTo = 2` — write-only to target container
- `SynchronizeWith = 3` (Import | Export) — bidirectional

### Container Formats

**Unsigned container** (`.kdbx`):
- A plain KDBX database file containing the shared group's entries
- Protected by the password from the reference

**Signed container** (`.kdbx.share`):
- A ZIP archive containing:
  - `container.share.kdbx` — encrypted KDBX database
  - `container.share.signature` — XML with RSA-2048/SHA-256 signature and signer certificate

### Sync Flow

1. **Export**: On database save, groups with Export/Sync references are extracted
   into temporary KDBX databases, optionally signed, and written to container files
2. **File sync**: An external service (Syncthing, Nextcloud, etc.) copies container
   files between devices
3. **Import**: On database open (or file change), groups with Import/Sync references
   read their container files and merge entries using timestamp-based conflict resolution

### Key KeePassXC Source Files

All in `src/keeshare/`:

| File | Purpose |
|------|---------|
| `KeeShare.cpp/h` | Singleton — reads/writes `KeeShare/Reference` from group CustomData |
| `KeeShareSettings.cpp/h` | Data structs: Reference, Certificate, Active, Sign, Own + XML serialization |
| `ShareObserver.cpp/h` | Per-database file watcher, 30s poll, triggers import/export |
| `ShareExport.cpp/h` | Group -> container: clone entries, resolve references, write KDBX/ZIP |
| `ShareImport.cpp/h` | Container -> group: open container, `Merger(sourceRoot, targetGroup)` |

### Entry Reference Resolution

When exporting, entries with placeholder references (e.g. `{REF:U@T:"GroupName"}`)
to entries **outside** the shared group are resolved to their actual values. This
prevents broken references in the exported container.

### Certificate Trust Model

- Each device generates an RSA-2048 key pair on first use
- The signer name defaults to the OS `USER` environment variable
- Signatures are hex-encoded as `"rsa|<hex>"`
- Trust is established by importing signer certificates
- Unsigned containers are accepted without verification

---

## KeePassDX Current State

### Custom Data Infrastructure (Ready)

KeePassDX already has full custom data support at all three KDBX levels:

| Level | Class | Location |
|-------|-------|----------|
| Database | `DatabaseKDBX.kt:163` | `val customData = CustomData()` |
| Group | `GroupKDBX.kt:45` | `var customData = CustomData()` |
| Entry | `EntryKDBX.kt:57` | `var customData = CustomData()` |

- **Reading**: `DatabaseInputKDBX.kt` parses `<CustomData>` at all levels
- **Writing**: `DatabaseOutputKDBX.kt` serializes all custom data (lines 631-659)
- **Deep copies**: `GroupKDBX.updateWith()` and `EntryKDBX.updateWith()` both deep-copy CustomData
- **Round-trip**: KeeShare/Reference custom data is preserved (fixed in #1335)

### Merge Infrastructure (Ready)

`DatabaseKDBXMerger.kt` provides full database merging:

- Timestamp-based conflict resolution (`mergeCustomData()` at lines 417-434)
- Entry history preservation
- Group structure reconciliation
- Custom data merge at database, group, and entry levels

**Limitation**: Current merger operates on whole databases (`merge(databaseToMerge)`).
KeeShare needs a **scoped merge** into a specific target group.

### Existing Merge UI

KeePassDX already ships two merge features:

1. **"Merge from..."** (`GroupActivity.kt:368`) — pick an external `.kdbx`, enter
   credentials, merge into current database
2. **"Merge database"** (`GroupActivity.kt:1432`) — re-read same file from disk,
   merge into in-memory copy (handles external modification during open session)

Both use `MergeDatabaseRunnable` -> `Database.mergeData()` -> `DatabaseKDBXMerger`.

### Known Issue

Group and entry custom data `lastModificationTime` values are **read but ignored**
during KDBX parsing (`DatabaseInputKDBX.kt` lines 543, 595). This means merge
conflict resolution for custom data items always takes the "else" branch (prefer
incoming). Should be fixed but is not a blocker for MVP.

---

## Architecture Decision

### Approach: Library-level integration (Selected)

Add KeeShare logic in the `database` module alongside the existing `merge/` package.
This is where KDBX parsing, custom data, and merging already live. The app layer
calls into it with minimal wiring.

**New package**: `database/src/.../database/keeshare/`

**Rationale over alternatives:**

- **vs App-level service**: A background service needs Android permissions, foreground
  service management, and battery considerations. Overkill for MVP, harder to review
  upstream. Background watching is a Phase 5 concern.
- **vs Mirroring KeePassXC architecture**: C++ patterns (Qt signals, file watchers,
  singletons) don't map to Kotlin/Android lifecycle. Better to use idiomatic Kotlin
  and leverage existing KeePassDX patterns.

**Benefits:**
- Clean separation — testable without Android runtime
- Follows existing convention — merge logic is already in the library module
- Upstream-friendly — small, focused, reviewable changes
- Each phase adds a thin layer; nothing needs to be rewritten later

---

## Phased Implementation Plan

### Phase 1 — MVP: Import on Open (One-Way In)

**Goal**: When KeePassDX opens a database containing KeeShare-configured groups,
it finds the container files on the Android filesystem and merges their entries
into the correct groups. No UI changes — it works silently if the share files
exist where Syncthing placed them.

**Sync scenario**: KeePassXC exports shared groups -> Syncthing copies `.kdbx`
containers to Android -> KeePassDX imports on next database open.

#### New Files (database module)

**1. `keeshare/KeeShareReference.kt`**

Data class matching KeePassXC's Reference struct:

```kotlin
data class KeeShareReference(
    val type: Type,
    val uuid: UUID,
    val path: String,
    val password: String,
    val keepGroups: Boolean = true
) {
    enum class Type(val flags: Int) {
        INACTIVE(0),
        IMPORT_FROM(1),
        EXPORT_TO(2),
        SYNCHRONIZE_WITH(3);
    }

    companion object {
        const val CUSTOM_DATA_KEY = "KeeShare/Reference"

        fun fromCustomData(base64Xml: String): KeeShareReference? { ... }
        fun toCustomData(reference: KeeShareReference): String { ... }
    }
}
```

Parses the base64-encoded XML from group CustomData. Fields are base64-encoded
within the XML (path, password) per KeePassXC's serialization.

**2. `keeshare/KeeShareContainer.kt`**

Reads container files in both formats:

```kotlin
object KeeShareContainer {
    fun readUnsigned(file: InputStream, password: String): DatabaseKDBX { ... }
    fun readSigned(file: InputStream, password: String): Pair<DatabaseKDBX, SignatureInfo?> { ... }
    fun detect(file: InputStream): ContainerType { ... }  // ZIP magic bytes vs KDBX header

    enum class ContainerType { UNSIGNED_KDBX, SIGNED_ZIP }
}
```

- Unsigned: delegate to existing `DatabaseInputKDBX` with the reference password
- Signed: extract `container.share.kdbx` from ZIP, open with password
  (signature verification deferred to Phase 4)

**3. `keeshare/KeeShareImport.kt`**

Orchestrates import across all shared groups:

```kotlin
object KeeShareImport {
    data class ImportResult(
        val groupName: String,
        val entriesImported: Int,
        val success: Boolean,
        val error: String? = null
    )

    fun importAll(
        database: DatabaseKDBX,
        pathResolver: (String) -> InputStream?
    ): List<ImportResult> { ... }
}
```

Walk all groups -> check for `KeeShare/Reference` custom data -> filter for
Import or Synchronize type -> read container -> scoped merge into target group.

**4. Scoped merge in `DatabaseKDBXMerger.kt`**

New method alongside existing `merge()`:

```kotlin
fun mergeIntoGroup(
    sourceDatabase: DatabaseKDBX,
    targetGroup: GroupKDBX
) { ... }
```

Takes the source database's root group entries and merges them into the specified
target group using the same timestamp-based conflict resolution as the existing
whole-database merge. This is the key new capability.

#### App Layer Change

In `Database.kt` after the database is loaded (around line 692, after the existing
merge path), add a call to `KeeShareImport.importAll()`. The `pathResolver` lambda
resolves KeeShareXC desktop paths to Android filesystem paths by:

1. Extracting the filename from the reference path
2. Looking for it in the configured sync directory (default: same directory as the
   main database file)

Single integration point — approximately 10-15 lines of app code.

#### Testing

- Unit tests for `KeeShareReference` XML parsing (round-trip with KeePassXC format)
- Unit tests for `KeeShareContainer` reading both formats
- Integration test: create a database with KeeShare references, provide container
  files, verify entries merge into correct groups
- Test with actual KeePassXC-exported containers

---

### Phase 2 — Export on Save (Bidirectional Sync)

**Goal**: When KeePassDX saves a database, export groups marked with `ExportTo`
or `SynchronizeWith` to container files. Syncthing copies them back to desktop.
KeePassXC imports on next open. Full bidirectional sync achieved.

#### New Files

**5. `keeshare/KeeShareExport.kt`**

```kotlin
object KeeShareExport {
    data class ExportResult(
        val groupName: String,
        val entriesExported: Int,
        val success: Boolean,
        val error: String? = null
    )

    fun exportAll(
        database: DatabaseKDBX,
        pathResolver: (String) -> OutputStream?
    ): List<ExportResult> { ... }
}
```

For each group with Export/Synchronize reference:
1. Create a new temporary `DatabaseKDBX`
2. Clone the group's entries recursively (respecting `keepGroups` flag)
3. Copy custom icons used by cloned entries
4. Resolve field references that point outside the shared group to literal values
5. Set the container password from the reference
6. Write as unsigned `.kdbx` via existing `DatabaseOutputKDBX`

#### App Layer Change

In `SaveDatabaseRunnable`, after save completes, call
`KeeShareExport.exportAll(database)`. Single integration point.

#### Path Mapping

KeePassXC stores absolute desktop paths (`/home/user/Sync/share.kdbx`). These
don't resolve on Android. Phase 2 introduces a simple path resolution strategy:

1. Extract the **filename** from the reference path
2. Look for / write to that filename in the **sync directory**
3. Default sync directory: same parent directory as the main `.kdbx` file
4. Can be overridden by an app preference (see Phase 3)

This works naturally with Syncthing: both sides point at the same shared folder,
so the filename is all that matters.

#### Testing

- Unit tests for export: verify container is valid KDBX openable by KeePassXC
- Round-trip test: export from KeePassDX, import in a fresh database, verify entries
- Field reference resolution: entries referencing outside groups get literal values
- Custom icon copying: icons used by exported entries are included

---

### Phase 3 — Manual Trigger + Minimal UI

**Goal**: A menu action to force sync on demand, and a single setting to configure
the sync directory.

#### Menu Action

Add **"Sync KeeShare"** to the existing database menu in `GroupActivity.kt`, alongside
"Merge from..." and "Save copy to...":

- Runs import then export sequentially
- Shows a Toast with results: "KeeShare: imported 3 groups, exported 2 groups"
- Shows error Toast on failure: "KeeShare: failed to import 'Work Passwords' — file not found"
- Only visible when the database contains groups with KeeShare references

#### App Preference

One new setting in database settings (`NestedDatabaseSettingsFragment.kt`):

- **"KeeShare sync folder"** — Android folder picker (`ACTION_OPEN_DOCUMENT_TREE`)
- Stored in SharedPreferences per database
- Falls back to the database file's parent directory if not set

#### Visibility

- The "Sync KeeShare" menu item is hidden when no groups have `KeeShare/Reference`
  custom data — no visual noise for users who don't use KeeShare

#### Testing

- UI test: menu item appears when KeeShare references exist
- UI test: menu item hidden when no references
- Integration test: manual trigger produces correct import/export results

---

### Phase 4 — Signature Verification & Trust

**Goal**: Verify signed `.kdbx.share` containers and manage certificate trust.

#### Signature Verification

- Parse `container.share.signature` XML from ZIP containers
- Extract signer certificate (RSA-2048 public key) and signature (`"rsa|<hex>"`)
- Verify SHA-256 signature against `container.share.kdbx` content
- Use `java.security.Signature` with `SHA256withRSA` (available on all Android versions)

#### Trust Model

- **Trust-on-first-use (TOFU)**: First import from an unknown signer prompts the user
  to accept or reject the certificate
- Store trusted signer fingerprints in database-level custom data
  (following KeePassXC convention)
- Reject containers signed by untrusted or revoked certificates

#### Own Certificate

- Generate RSA-2048 key pair for signing exports
- Store in app preferences (encrypted with Android Keystore)
- Sign exported containers when writing `.kdbx.share` format

#### UI

- Database settings section: "KeeShare Certificates"
  - Display own certificate fingerprint and signer name
  - "Generate new certificate" button
  - List of trusted foreign certificates with trust/revoke toggle

---

### Phase 5 — Auto-Sync & Polish

**Goal**: Automatic background sync, visual indicators, and full KeeShare
configuration UI.

#### Background Sync

- Use `FileObserver` (inotify-based) to watch the sync directory for changes
  while a database is open
- Alternatively, `WorkManager` periodic task (minimum 15 minutes) for
  battery-friendly background checking
- Auto-import when container files change; auto-export on database save
  (already done in Phase 2)
- Configurable: off / on file change / periodic (15m/30m/1h)

#### Visual Indicators

- Small overlay icon on shared groups in the group list
- Different icon for import-only vs export-only vs bidirectional
- Shared group detail shows sync status: last sync time, sync direction, container path

#### Full KeeShare Group Configuration UI

In the group edit dialog (`EditGroupWidgetKeeShare` equivalent):

- Share type selector: Inactive / Import / Export / Synchronize
- Container file path picker
- Container password input
- "Keep group structure" checkbox
- Conflict detection warnings (multiple imports from same source, etc.)

#### Conflict Handling

- Detect when both sides modified entries since last sync
- Notification with options: auto-merge (default), show diff, skip
- Maintain sync timestamps per group for conflict detection

---

## File Map

### New Files by Phase

```
database/src/main/java/com/kunzisoft/keepass/database/keeshare/
├── KeeShareReference.kt          # Phase 1 — data class + XML parser
├── KeeShareContainer.kt          # Phase 1 — read container files
├── KeeShareImport.kt             # Phase 1 — import orchestration
└── KeeShareExport.kt             # Phase 2 — export orchestration

database/src/test/.../keeshare/
├── KeeShareReferenceTest.kt      # Phase 1 — XML parsing tests
├── KeeShareContainerTest.kt      # Phase 1 — container reading tests
├── KeeShareImportTest.kt         # Phase 1 — scoped merge tests
└── KeeShareExportTest.kt         # Phase 2 — export + round-trip tests
```

### Modified Files by Phase

```
Phase 1:
  database/.../merge/DatabaseKDBXMerger.kt    — add mergeIntoGroup() method
  database/.../element/Database.kt            — call KeeShareImport after load

Phase 2:
  app/.../database/action/SaveDatabaseRunnable.kt  — call KeeShareExport after save (or new KeeShareSaveDatabaseRunnable.kt)

Phase 3:
  app/.../activities/GroupActivity.kt               — add "Sync KeeShare" menu item
  app/.../settings/NestedDatabaseSettingsFragment.kt — add sync folder preference
  app/src/main/res/menu/database.xml                — new menu entry
  app/src/main/res/values/strings.xml               — new string resources

Phase 4:
  database/.../keeshare/KeeShareContainer.kt  — add signature verification
  database/.../keeshare/KeeShareCertificate.kt — new: certificate management
  app/.../settings/ ...                        — certificate UI

Phase 5:
  app/.../services/ ...                        — background sync service
  app/.../activities/GroupActivity.kt          — shared group indicators
  app/src/main/res/drawable/ ...               — share overlay icons
```

---

## Dependencies & Prerequisites

- **Syncthing (or equivalent)**: User must have a file sync service configured
  to copy container files between desktop and Android. This is external to KeePassDX.
- **KeePassXC**: User must configure KeeShare groups in KeePassXC first.
  KeePassDX reads the configuration that KeePassXC writes.
- **Storage permissions**: KeePassDX already requests storage access for opening
  databases. The same permissions cover reading container files from the sync folder.

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Scoped merge introduces bugs in existing merge logic | New `mergeIntoGroup()` is additive — doesn't modify existing `merge()`. Full test coverage. |
| Path mapping fails for non-standard sync setups | Default to database parent dir. User-configurable sync folder in Phase 3. |
| Unsigned containers accepted without verification (Phases 1-3) | Same security model as syncing the entire `.kdbx` — the file is already on the device. Signature verification added in Phase 4. |
| Battery drain from background file watching (Phase 5) | Deferred to last phase. Use WorkManager with minimum 15-minute interval. Default off. |
| Upstream maintainer rejects PR | Each phase is independently useful and minimally invasive. Phase 1 touches 2 existing files with ~15 lines of app code. Can be offered as separate small PRs. |

## Open Questions

1. **Path resolution strategy**: Should we support a mapping file (desktop path ->
   Android path) or is filename-in-sync-folder sufficient for most users?
2. **KDB format**: KeeShare is KDBX-only in KeePassXC. Should KeePassDX explicitly
   skip KeeShare for KDB databases? (Likely yes — KDB has no custom data.)
3. **Merge direction on conflict**: When both sides modified the same entry,
   KeePassXC uses "newer timestamp wins". Should KeePassDX match this exactly
   or offer a choice? (Phase 5 concern.)
4. **Container password UX**: In Phases 1-2, the password comes from the
   `KeeShare/Reference` stored in the database. What if the user changes it in
   KeePassXC? Need graceful error handling and clear error message.
