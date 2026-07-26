# Session handoff — admin⇄cashier notifications & requests (2026-07-25)

Branch: `session-2026-07-25-ui-parity` (off `82e0d68`, the true latest source).
Goal: seamless notifications + request/approval round-trips between the admin
phone and cashier phones, on the existing poll-based Supabase REST sync
(no websockets, local-first preserved).

## State at handoff

### ✅ DONE, pushed, build-verified — commit `49c5715` (Phases 1+2)
Compile + full unit-test suite green (`:app:compileDebugKotlin :app:testDebugUnitTest`).

1. **Cross-device heads-ups**: `PosRepository.fireUnpushedHeadsUps(isAdmin)` fires a
   system notification for every pulled-in, live, un-pushed alert row matching the
   device role, stamping device-local `pushedAt`. Wired into BOTH pull paths:
   `SyncManager.afterPull` (foreground poll/manual) and `SyncWorker` (15-min background).
   Both also kick `AdminNotificationWorker.runNow` so engine conditions recompute within
   the ~45s poll cadence instead of the 30-min sweep.
2. **Audience targeting**: `AppNotification.audience` (`admin`|`cashier`|`all`, default
   admin), Room migration v28→v29, DTO field, pure rule
   `NotificationEngine.audienceMatches`, `Notifier.notifyAlert` (audience-aware
   deep-link), `AuthManager.isDeviceAdmin()` for background role resolution.
3. Fixed pre-existing upstream test breakage (`NotificationEngineTest.snap()` positional
   args vs `openPurchaseOrders` added mid-signature) — named args now.

### ✅ DONE, cloud-side (Supabase project `ucgvvxlhdooevngtraje`, applied via MCP)
- `notifications.audience text not null default 'admin'` + index — **already applied**.
- `staff_requests` table **already existed** (someone created it earlier; the app had
  zero wiring): local_id unique, type/target_type/target_id/target_name/amount/note,
  requested_by(_name), status, decided_by(_name), decided_at bigint, applied bool,
  deleted, timestamps, `idx_staff_requests_updated_at`.
- RLS (verified): staff INSERT/SELECT; **admin-only UPDATE/DELETE** — this shapes the
  push design below.

### 🟡 WIP — Phase 3 `staff_requests` wiring (uncommitted → committed as WIP on top of
`49c5715`; **build NOT verified**, an Opus agent was interrupted mid-task)
Data/sync/VM layers are in (~540 lines across 8 files):
- `StaffRequest` entity (PosModels.kt), `StaffRequestDao` (PosDao.kt), Room **v29→v30**
  migration (PosDatabase.kt), AppContainer/repo plumbing (PosApp.kt).
- DTOs (Dtos.kt) + engine push/pull (PosSyncEngine.kt): push splits
  **pending rows → insert-once (`ignoreDuplicates=true` upsert on local_id;
  cashiers can't UPDATE cloud rows per RLS)** vs
  **decided rows → merge upsert (admin passes the UPDATE policy)** — mirror of the
  sales fresh/edited split. Pull = last-write-wins by updated_at, cursor
  `staff_requests`, shaped like pullExpenses.
- PosViewModel additions (~71 lines) — request submit/decide entry points.

**NOT done for Phase 3** (the remaining work):
1. **UI, cashier side** (PosUi.kt): "Ask admin" alternative in the discount
   admin-PIN dialog (find `discountNeedsApproval` / `verifyAdminPin` call sites in the
   checkout flow); pending/approved/denied status surface on checkout; tap-to-apply an
   approved discount when the sale is still open (mark `applied` LOCALLY ONLY — a
   cashier device must never dirty a decided row for push, RLS would reject it every
   cycle as a per-table error).
2. **UI, admin side** (PosUi.kt `AdminAlertsScreen` ~line 1043): "Requests" section
   above the alert feed — requester, type, amount, note, age, Approve/Deny buttons.
3. **Notification glue** (check what the WIP repo layer already has —
   `submitStaffRequest` should create an audience="admin" alert
   (dedupe `reqpending:<id>`, category "requests", `pushedAt=stamp` so the originating
   phone doesn't buzz itself); `decideStaffRequest` an audience="cashier" alert
   (`reqdecided:<id>`) and clears/reads the pending one. Both `nudgeSync` +
   `requestPullNow`.
4. **Unit tests** for the pending-vs-decided push partition (pure function) and
   audience mapping.
5. **BUILD + fix**: `:app:compileDebugKotlin :app:testDebugUnitTest` (command below) —
   the WIP has never compiled; expect loose ends.

### ⬜ Phase 4 — not started
- **Hot-poll**: while a request I submitted (cashier) or just decided (admin) is
  in flight, temporarily poll every ~5–8s (cap ~2–3 min) instead of 45s so the
  round-trip feels live. Hook into `SyncManager` (`POLL_MS` loop) via a
  "hot until <timestamp>" flag set from submit/decide.
- **Bundled setup SQL refresh**: `sync/SupabaseSetupSql.kt` + repo `supabase-setup.sql`
  only create 5 of the now-14 synced tables — stale as a BYO-database contract
  (harmless for the shared prod DB, which has everything).

## How to build / verify
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\pc\AppData\Local\Android\Sdk"
Set-Location "<repo>"
& .\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --console=plain *> build.log
```
~7–10 min. PowerShell exit codes lie for gradlew — read the `BUILD SUCCESSFUL|FAILED`
line in the log (it's UTF-16; use `Get-Content`/`Select-String`, e.g.
`Select-String -Pattern "^e: " -Context 0,2` for compile errors).
Debug APK: `:app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Push access (gotcha)
The Windows credential manager's GitHub identity (`ckachale14-hash`) has NO write
access to `the-future-scar-knight/pos-android` (403). Pushes in this session used a
fine-grained PAT from the owner account, passed one-shot on the push URL
(`https://x-access-token:<PAT>@github.com/...`) — deliberately NOT stored on disk.
Ask the user for the PAT (or fix collaborator access) before pushing.

## Cloud facts (verified this session)
- Supabase project `ucgvvxlhdooevngtraje`; all 13 previously-synced tables exist +
  `staff_requests`; `notifications` has `UNIQUE (business_id, dedupe_key)` and 48 rows
  actively syncing.
- Sync cadence today: push-on-change 2.5s debounce · foreground poll 45s ·
  background worker 15 min · reconnect flush · `MIN_GAP_MS` 20s coalescing.
