# Backup and restore

Manual backups use `.mmbackup`. Export lets you select accounts, credentials, app preferences,
server caches, downloaded media, and drafts. All are selected initially. Queued edits are a
separate optional category, **off by default**. Password protection is optional.

Restore validates the file before asking which accounts and categories to replace. Unchecked
categories and other accounts remain intact. Restore is available on the login screen as well
as in Settings. A backup without credentials requires the original account to exist locally.

The server remains authoritative. Restoring a cache cannot create pending operations, upload
jobs, or server writes. The next successful refresh replaces stale server data and removes
remotely deleted entries. Existing unsent work on the destination is protected. Optimistic
source cache rows are excluded from exports even when queued edits are selected.

Drafts and their local attachments survive Android cache eviction and remain unpublished.
Restored queued edits are saved in a separate review area under Backup & Restore. Memo edits
can be saved as drafts for explicit review and publication; deletions and reactions must be
performed using the normal memo controls. Restored work never joins the live sync queue.

Android Auto Backup stores **only accounts and login tokens**, and only when its transport
supports client-side encryption. Direct device transfer includes all standard categories,
excluding queued edits. Android controls backup timing, encryption availability, and quotas.
On older devices where transport encryption cannot be verified, credential cloud backup is
skipped. Device setup restores the native snapshot automatically before app sessions start.
The backup agent works in Android's restricted process without Koin, WorkManager, or app providers.

Legacy `memosm-local-recovery` version-1 JSON archives remain readable for the original account.
Their pending operations and associated optimistic cache rows are discarded during import.

## Binary format: container 1, schema 1

Every file begins with a fixed **49-byte header**, before any compressed or encrypted content.
Integers in the header are big-endian. Both versions are independently checked; unsupported
container or schema versions fail before decoding or modifying live storage. The payload also
contains `schemaVersion`, which must agree with the header.

| Offset | Bytes | Meaning |
| --- | --- | --- |
| 0 | 8 | ASCII magic signature `MMBACKUP` |
| 8 | 4 | Container format version, currently `1` |
| 12 | 4 | Backup data schema version, currently `1` |
| 16 | 1 | Encryption flags: `0` unencrypted, `1` password protected |
| 17 | 4 | PBKDF2 iteration count: `0` or `1300000` |
| 21 | 16 | Random password salt |
| 37 | 12 | Random base GCM nonce |
| 49 | variable | Compressed payload, optionally encrypted in frames |
| file size − 32 | 32 | SHA-256 checksum over header and entire stored payload |

The unencrypted payload is a gzip stream containing two MessagePack maps: a manifest, followed
by content-addressed binary attachment records. The manifest includes creation time, schema
version, selected categories, account identities, and category-specific data. Cached server
objects are encoded as nested MessagePack values, not escaped JSON strings. Attachment keys
are lowercase SHA-256 digests of their bytes. Paths from another device are never restored as
filesystem paths; private local paths are rebuilt from validated blob IDs.

Password protection uses AES-256-GCM and PBKDF2-HMAC-SHA1 at 1,300,000 iterations, supported
from the app's API 24 minimum. The gzip stream is encrypted in frames of at most 65,536 plaintext
bytes. Each frame is prefixed with its encrypted length (4-byte big-endian) and ends in a 16-byte
GCM authentication tag. The base nonce's final 8 bytes are XORed with the frame index. Associated
data is the entire fixed header followed by the 8-byte big-endian frame index. An authenticated
empty final frame is mandatory. Frames bound memory usage even on providers that buffer GCM
messages before authentication. Passwords and derived key bytes are not persisted.

Limits: 4 GiB stored archive, 8 GiB expanded data, 2 GiB minus one byte per binary record,
64 MiB metadata strings with a 128 MiB accounting budget, 16 MiB per string, depth 64,
1,000 accounts, 50,000 memos/drafts/queued edits per account, and 100,000 attachment records.
Imports also enforce available disk space. Unknown versions, malformed records, duplicate
accounts/blobs/drafts, invalid account identities, unsafe paths, and bad checksums fail before
replacement.

Restore writes a complete private journal before changing storage. Cache changes transact in
Room; draft files are atomically replaced; preferences and credentials publish last. The journal
is replayed idempotently if the app stops between stores. Background writers are blocked after
a failed commit until recovery finishes. The journal is deleted only after the complete result
is durable. Cache databases, WorkManager state, audit logs, recovery files, and arbitrary app
files are never copied through Android backup rules.

## Verification

Unit tests exercise the codec, header versions, Unicode, encryption, tampering, corruption,
unsafe names, and archives exceeding the old 25 MiB limit. Instrumentation tests cover selected
replacement, account isolation, protected destination work, cache eviction, inert restored
edits, cloud-only contents, and recovery after an injected preferences failure.

For native transport testing use a **disposable emulator installation**, never an installation
with real user data. `NativeBackupIntegrationTest` skips unless `nativePhase` is explicitly set.

1. Install the app and instrumentation APK into the disposable package.
2. Run that test with `-e nativePhase seed` to create fake accounts and representative local data.
3. Enable backup and select `com.android.localtransport/.LocalTransport` using `bmgr`.
4. For cloud behavior, set `backup_local_transport_parameters` to `is_encrypted=true`.
   For device-transfer behavior, use `is_encrypted=true,is_device_to_device_transfer=true`.
5. Run `bmgr backupnow PACKAGE`, uninstall that disposable app, and reinstall its APK to restore.
6. Run the test with `-e nativePhase cloud` or `-e nativePhase device` to verify the restored state.
7. Restore the emulator's previous backup transport and local-transport parameters.

The local transport simulates encrypted cloud and device-transfer flags. A factory-reset
old-device/new-device setup remains the final check for manufacturer-specific migration behavior.
