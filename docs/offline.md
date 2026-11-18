# Offline data lifecycle

Room is the source of rendered memo data. Network responses update Room; screens observe Room through Paging or Flow. A memo has a stable account-local identity and a separate server resource name. Feed, map, search and comment memberships reference the same canonical memo body.

## Reads

`memoPages` and `attachmentPages` use Room PagingSources and Paging 3 RemoteMediators. Query records exist independently of items, so a successful empty response differs from a query never loaded. Loading and retry state belong to Paging; connectivity belongs to the account connection state.

Each refresh reserves a generation before requesting the network. A response must match its generation and page token before ingestion. Partial refreshes retain prior cached memberships. Only an exhausted successful snapshot can prune missing entries, and pending changes remain protected.

`PreDownloadWorker` performs exhaustive history pulls using the same ingestion transactions. History queries have separate pagination tokens from interactive queries. Incremental cursors and full-snapshot timestamps are independent and scoped to each collection. A new Explore selection establishes its own baseline. Download failures never advance the completion cooldown.

## Writes

Every memo, comment, reaction and attachment write is local first. User actions commit an immutable mutation and the visible projection in one transaction. An editor reports success only after that commit.

`OutboxSyncWorker` is the sole network mutation entrypoint. Immediate requests and background retries use separate WorkManager chains, so retry backoff cannot delay a new write or reconnect. Requests do not require Android internet validation: a reachable private LAN server is sufficient. `drainOutbox` processes ordered mutations, waiting for parent creation and attachment uploads while allowing unrelated entities to progress. WorkManager owns execution and retries; Room owns pending work, errors and conflicts. A successful worker run can still leave work awaiting user review.

Network calls execute outside the storage lock. Acknowledgement binds the actual server identity, updates the server snapshot, removes exactly the sent mutation and reapplies later mutations by field mask, all transactionally. Pending deletes are tombstones. Account and restore fences prevent obsolete responses from committing.

Conflicts retain server and local versions for keep-local, keep-server, merge or defer. Deferred conflicts can be reopened from the sync panel; explicit retry resets rejected writes and uploads while preserving unresolved conflicts. Revision checks precede updates; the current Memos adapters do not provide an atomic conditional update, so a concurrent server write between that check and update remains a protocol limitation. Authentication failures and rejected writes retain user content and staged files. Ambiguous creates retry automatically; older servers that discard client IDs can produce duplicates.

## Files, eviction and recovery

Attachments are staged in app-private storage before being queued. New inline and restored upload rows commit in the same transaction as their memo; failed preparation removes unowned files. Media authentication is restricted to the server origin, including playback subrequests and redirects. Upload responses retain the actual server resource identity. Failed uploads are never retired by an attempt ceiling, and missing uploads never silently remove attachments from a memo.

Completed, unreferenced staged uploads are collected at application startup after strict checks of drafts, queued mutations, restored edits and cached references. Cache eviction preserves pending projections and their dependencies. Backup exports translate stable local identities to server identities; restored queued edits remain inert review records.

The v8-to-v9 Room migration normalizes duplicate memo bodies and preserves existing pending work and staged files. Legacy optimistic rows have unverified server bases until refreshed. Database migration never uses destructive fallback.

## Validation

Regular JVM tests cover mutation projection, transport and account behavior. Robolectric runs actual Room transaction, migration, paging and sync tests without an emulator. Instrumented tests remain available for Android integration; running an emulator requires the permission described in `AGENTS.md`.

The implementation follows [Android's offline-first data guidance](https://developer.android.com/topic/architecture/data-layer/offline-first), [Paging's network/database integration](https://developer.android.com/topic/libraries/architecture/paging/v3-network-db), and [WorkManager's unique-work policies](https://developer.android.com/reference/androidx/work/ExistingWorkPolicy).
