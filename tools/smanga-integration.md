# smanga integration

## Compatibility

This native provider targets the smanga 4.3 API contract inspected at:

- Frontend: [`e18b39c`](https://github.com/lkw199711/smanga/tree/e18b39cef5ef51f58ed99f8ae00a7fc15ee14bdf)
- Backend: [`97f5477`](https://github.com/lkw199711/smanga-adonis/tree/97f54771f9d1bb83c0725e5e08769706503a8856)

The upstream image does not pin the backend revision. Saving a connection probes JSON authentication and the OPDS feed; a version number alone does not establish compatibility. Use an ordinary account with access to the intended media libraries, and enable OPDS. Reverse-proxy subpaths and addresses ending in `/api` are accepted. Automatic redirects are disabled; enter the final server address.

Connection settings reuse LANraragi's extracted `ConnectionAddressSetting`, including its public-address field, optional LAN address under Advanced settings, validation and existing localized help. The shared `ConnectionAddressRouter` prefers the LAN endpoint on Wi-Fi and falls back to the public endpoint for failed reads. smanga probes `/api/deploy/status` without credentials. Canonical resource URLs and account/cache identities continue to use the public address; adding or changing the LAN address does not change them. Saving both addresses validates a fresh public login token at the LAN `/user/me` endpoint without reauthentication, compares the account, and checks OPDS on both endpoints. This requires both addresses to reach the same database and prevents identical credentials on unrelated servers from passing verification. History writes are not replayed across endpoints.

The connection icon is the unchanged compact logo used by the [current upstream sidebar](https://github.com/lkw199711/smanga/blob/e18b39cef5ef51f58ed99f8ae00a7fc15ee14bdf/src/layout/components/sidebar.vue), from [`src/assets/logo2.png`](https://github.com/lkw199711/smanga/blob/e18b39cef5ef51f58ed99f8ae00a7fc15ee14bdf/src/assets/logo2.png). Attribution is in `NOTICE`; the upstream license is retained under `app/src/main/assets/licenses/`.

Supported scope: media libraries, title search, server sorting, native details/chapters, comic pages, physical PDF pages, offline downloads, chapter progress and reading visits. Uploads, remote deletion, metadata editing, EPUB, administrative functions and unmounted cloud drives are outside this integration.

## Implementation boundaries

- `koharia.source.smanga`: connection settings, private credentials, source and capability adapters.
- `koharia.smanga`: authenticated protocol, page mapping, catalogue, PDF cache and reading coordinator.
- `koharia.smanga.ui`: thin Voyager/Paging models over the existing source shelf components.
- `domain/.../smanga`, `data/.../smanga`: account-scoped persistence; SQLDelight migration 20.

The toolbar and network availability observer are shared with LANraragi. Details, chapter actions, comic/PDF rendering and download scheduling use the existing app pipelines. Source API ABI and shared app/reader preference scope are unchanged.

## Cache and network rules

Successful media and shelf responses, including empty results, persist without TTL. Startup, resume and connectivity recovery do not invalidate them. Pages are fetched on demand. Refresh stages every previously cached page in the selected query, then atomically replaces that query's generation; failure or cancellation retains the previous generation. An unavailable uncached query explicitly reports incomplete coverage. Downloaded content is selected from this account's local records.

The media tabs default to All. The new account-scoped selection preference starts at All rather than inheriting the first library automatically saved by older builds; subsequent user selections are remembered. All queries merge ordered pages from the authorized `/media` IDs, with at most two concurrent page fetches. The unscoped `/manga` route in the pinned backend does not filter library permissions and is never used. Merged pages and per-library cursors have a separate cache keyed by the authorized ID set, search, order, connection and account. A single authorized library reuses its existing shelf cache. Refresh replaces merged pages and their supporting per-library pages together only after success.

When a refresh discovers added libraries, the media list is published after the new shelf is ready so failures cannot strand the previous warm cache. Revoked libraries are removed immediately, even if the subsequent shelf refresh fails or is cancelled; that narrowed permission scope must not fall back to the older combined shelf.

Name merging preserves the server's order within each library and uses binary UTF-8 over raw `mangaName` to choose between library heads. The backend does not expose its database collation, so All cannot promise the exact global locale ordering of PostgreSQL or MySQL across libraries. Name sequences that differ from binary ordering are accepted; they are not evidence of missing data. Page lengths, totals, IDs, authorization scope and cursor offsets remain validated, as do numeric/date sequences. No unscoped endpoint or full-catalogue download is used as a fallback.

JSON calls use the token header. Covers, images and PDF originals use the permission-checked OPDS paths and HTTP Basic. The unauthenticated `/image` and `/file` routes are never used. Credentials are absent from Source headers, URLs, database cache payloads and diagnostic output. OPDS checks both origin/path and account namespace, has no shared HTTP cache and follows no redirects. History POST is one-shot, without transport or authentication replay.

Comic manifests preserve JSON display order and map it to OPDS lexical page numbers. Cache URLs include the manifest digest. Extraction polling uses `reTry <= 9`, a bounded attempt count and a 90-second overall deadline. RAR/7z still require a functioning server extraction worker.

PDF originals are prepared only for the actively opened chapter. Adjacent preloading can use an already complete file. Originals live under the app's disposable `smanga-pdf` cache, separate from manual downloads; temporary-cache cleanup includes them. Explicit chapter refresh invalidates cached originals. Downloads restart from byte zero because OPDS does not support Range, validate byte length and PDF readability, then publish the completed file. No incomplete PDF becomes a completed cache or download.

## Progress and history

Reading state uses stable remote chapter IDs and local revisions. The coordinator reads exact per-chapter `latest` values, never aggregate `MAX(chapterId)` as a resume position. Completion comes from `latest.finish`; a history visit is not completion. Network requests run outside local state locks and acknowledgments compare the revision. Indexed queries select only pending operations or the requested manga, rather than loading the account's entire reading history on each upload.

Successful progress writes validate and retain the server's returned position, completion and timestamp as the new remote baseline. This allows a subsequent explicit read/unread action and avoids treating the same client's prior write as a conflict when clocks differ. Per-chapter baseline revisions reject stale overlapping pulls, while an actual changed remote state still requires confirmation.

Unconfirmed position/page-mapping conflicts pause uploads durably. The existing reader dialog handles explicit local/remote selection, without percentage-based position guessing. Reader-session start does not create a visit; an actually displayed page does. History operations are marked attempted before sending and ambiguous outcomes are not replayed. Backup restoration drops stale pending operations and does not synthesize remote visits. Network restoration only retries the reading outbox.

The server history API returns manga aggregates, not a complete event stream. These aggregates discover visited manga only. Import uses that manga's unique, most recent exact chapter reading state and timestamp; missing or tied states are skipped instead of guessing from the aggregate chapter ID. Imported history remains a visit summary and never marks chapters read. The history list, resume actions and local history deletion are restricted to the active account.

## Validation

Tests cover authentication renewal and boundaries, account isolation, page ordering/extraction, warm/empty/cold catalogue caches, refresh rollback and cancellation, reading conflicts/revisions/history replay, per-chapter download strategy and PDF preloading. Device tests use generated two-page PDFs and a loopback HTTP fixture; database tests create isolated temporary databases.

Validated on 2026-09-22:

| Check | Result |
| --- | --- |
| `spotlessApply`, `spotlessCheck` | Passed |
| `:data:generateDebugDatabaseInterface`, `:app:compileDebugKotlin` | Passed |
| Selected app unit regressions | 382 distinct cases passed, including the focused history rerun after fixing test teardown |
| Database instrumentation | 13 passed: 8 smanga and 5 LANraragi, including migration retention and index query plans |
| PDF instrumentation | 6 passed on `emulator-5554`, including rendering, physical page count, offline reuse, corrupt/truncated responses and cancellation |
| Isolated app/test APK assembly | Passed with `-PdeviceTestFixture=true` |
| Dedicated live smanga server | 3 protocol/cache tests and 1 native ZIP/PDF reading/download test passed; details and limitations in the live validation report |
| Live-sync fix regression | All 90 current smanga unit cases passed, including six new cases for write acknowledgments and overlapping pulls |

Unit selection included `koharia.smanga.*`, `koharia.connection.*`, `koharia.lanraragi.*`, `*Komga*`, `*LocalFolder*`, `eu.kanade.tachiyomi.ui.reader.*`, `*RawDownloadPolicyTest` and `eu.kanade.tachiyomi.ui.history.*`. Diagnostic evidence is in `.test-artifacts/smanga/final-verify-2.log`, `history-verify-2.log`, `device-final-build.log`, `data-device-final.log` and `pdf-device-final.log`. The broad regression log includes four history test teardown failures; all eight history tests subsequently passed in the focused rerun. No production change was needed for those test failures.

Use `-PdeviceTestFixture=true` for app instrumentation and explicitly target the emulator. Retain installed packages and existing app data. Do not use Gradle connected tests that automatically uninstall packages; build/install retained fixture APKs and invoke instrumentation through ADB. Raw logs and generated fixtures belong in `.test-artifacts/smanga/`.

Live deployment results and remaining coverage limits are recorded in [smanga-live-validation.md](smanga-live-validation.md). The supplied dedicated server's backend commit was not independently pinned, so these results do not certify every smanga 4.3 image.

The opt-in `SmangaLiveProtocolTest` and `SmangaLiveReaderTest` require `runSmangaLive=true`, the isolated fixture package, and a private `files/smanga-live.json` configuration containing `address`, `username` and `password`. Supply it through standard input to `run-as`, not instrumentation arguments or committed files. Remove the private configuration and its host copy after testing. The reader test requires an idle download/deletion queue, creates its own connection and download directory, restores preferences, and only modifies chapters whose account progress and history were initially absent. It deletes those test-created remote reading records on exit, without deleting or editing server files.
