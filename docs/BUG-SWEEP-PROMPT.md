# Whole-app bug sweep — prompt for a fresh session

Paste everything below the line into a new Claude Code session opened at
`C:\Users\User\Production\Projects\pos-android`.

---

You are auditing the On-Spot-POS Android app for bugs. This is a **real shop's till**
handling real money in Harare, Zimbabwe — a wrong figure here is a wrong drawer at closing
time, not a cosmetic defect. Read before you judge, and prefer one proven finding to five
speculative ones.

## The app

- Kotlin / Jetpack Compose / Room, single module at `app/`.
- **73 source files, ~45,000 lines.** 45 test files, 605 tests.
- `minSdk = 23`, `targetSdk = 34`, **no core-library desugaring**.
- Branch: `sync-repoint-web-schema`.
- Offline-first till that syncs to **Supabase** (PostgREST over OkHttp — no Supabase SDK).
- **There are TWO clients over ONE shared schema**: this Android app and a separate Next.js
  web POS. Neither owns the schema. A change that only makes sense to one client is a bug.
- The shop really runs a **Sunmi handheld on Android 6 (API 23)** alongside newer phones.
  Anything that behaves differently across Android versions is a live production risk, not
  a theoretical one.

Concentrate first on the files where the money and the sync live:

| file | lines |
|---|---|
| `ui/PosUi.kt` | 12,742 |
| `data/PosRepository.kt` | 5,082 |
| `ui/PosViewModel.kt` | 3,111 |
| `sync/wire/WireDtos.kt` | 2,244 |
| `sync/PosSyncEngine.kt` | 2,045 |
| `data/PosDao.kt` | 1,735 |
| `data/PosModels.kt` | 1,532 |

## Read the code's own comments — they are the design record

This codebase documents its reasoning in unusually long comments, and many of them mark
places where a previous bug was fixed and *why the obvious alternative is wrong*. A comment
starting `★` is load-bearing. **Do not propose a "simplification" that undoes one without
addressing its argument.** If you think a starred comment is wrong, say so explicitly and
argue it — that is allowed and sometimes correct — but never silently.

## Bug classes this codebase actually produces

These are not hypotheticals. Every one has bitten this app in production. Hunt these
patterns specifically rather than reading top to bottom.

**1. Platform-version landmines that lint and JVM tests both miss.**
The last one: `SimpleDateFormat`'s `X` pattern letter needs **API 24**, `minSdk` is 23, so on
the Android 6 handheld *every cloud timestamp parsed to 0* and a freshly stocked product read
"Out of stock" forever. Lint's `NewApi` did not fire because the offending token was a
**letter inside a format string, not an API call**. The unit tests passed the whole time
because JVM tests run on **desktop Java**, where that letter works. Look for anything whose
behaviour depends on API level: format-pattern strings, `java.time`, Java-8 collection
methods (`Map.getOrDefault`, `computeIfAbsent`, `removeIf`, streams, `Optional`,
`String.join`), `ByteBuffer.position(int)`, and any `@RequiresApi` reasoning.

**2. Silent fallbacks that manufacture plausible data.**
The dominant failure shape here. A `runCatching{}.getOrNull()`, a `catch` returning 0, an
`?: emptyList()` — the caller cannot distinguish "no data" from "the read failed", and a
zero flows into a money figure looking like a fact. Audit every swallowed exception and every
default-on-failure in `sync/`, `data/`, and the notification engine. Ask of each: *if this
silently returned the default forever, what would the owner see, and would it look like a
bug or like a number?*

**3. Position- and order-dependence in multi-step passes.**
`PosSyncEngine.pull()` was a bare sequence, so one throw abandoned every table behind it, and
a table's position decided whether it synced at all. That is fixed, but look for the same
shape elsewhere: long sequential passes, ordering comments ("parents before children"), and
recompute steps that assume an earlier step ran.

**4. Cache vs authority confusion.**
`items.stock_qty` is a **cache**; `stock_movements` is the authority. On-hand is *the shop's
baseline plus movements stamped strictly after it* — `SUM(delta)` is NOT an on-hand, and
reading it as one once emptied a real shelf. `data/StockLedger.kt` holds the rule, and
`PosDao.deltaSinceBaselineByItem` is a **hand-written SQL twin of the Kotlin version** — if
you change one and not the other, stock breaks silently. Look for other derived-vs-stored
pairs that can drift.

**5. Local-only fields mistaken for synced ones.**
Not everything travels. The **opening float**, `day_closes`, `outside_funds`, per-item
**markup**, images, and most device preferences are local-only, while the shared
`businesses` row is mostly owned by the web. Two phones that disagree forever, with no error
anywhere, is the signature. Check `sync/SyncConfig.kt` for what is genuinely local and find
code that assumes otherwise. Related: `businesses` must be PATCHed on named columns, never
upserted — an upsert nulls every column it was not given.

**6. Money semantics that differ by one word.**
Gross vs net, billed vs collected, VAT-inclusive vs exclusive, whole-sale discount vs line
discount, refund on the sale's day vs the return's day. `sale_items.line_total` means **NET
on the wire and GROSS locally**, and `line_cost`/`line_profit` are **GENERATED columns in
Postgres** — naming either in an insert fails the whole batch. Verify every arithmetic
identity actually holds on screen: if a card shows `a − b = c`, compute it.

**7. Dirty-flag and idempotency traps.**
Rows push only when `pendingSync = 1`, and `markSynced` clears it after a successful upload.
Look for: rows that can never become dirty (so never upload), rows re-dirtied in a loop,
upserts that could double-count, and **wipe/reset paths that miss tables**. One did exactly
that last week and uploaded a week of a test database's cash into the real books.

**8. Enforcement that exists only in the UI.**
Capability checks belong in the handler as well as the screen — a stale composable can send
an action a revoked cashier should not have. Some paths already gate on both sides
(discounts do); find the ones that do not.

**9. Clock confusion.**
Three clocks: the device's, the server's `updated_at`, and the `client_updated_at` a device
stamps. Pull cursors page on the **server** clock and are compared as **strings**; stock
baselines compare on the **client** clock. Mixing them is how a movement gets discarded or a
device parks a cursor in the future and skips rows permanently.

## How to verify anything

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest
```

- **Never trust a piped Gradle exit code.** In Git Bash `... | tail` reports *tail's* status
  and masks `BUILD FAILED`. Run without a pipe, then grep the captured output text for
  `BUILD SUCCESSFUL` / `BUILD FAILED` / `^e: `. This has already caused one broken commit.
- Do **not** run two Gradle invocations at once — they contend on locks.
- Full builds take **15–20 minutes**; run them in the background, and use
  `compileDebugKotlin` or a single `--tests` filter when you only need a compile check.

## Rules

- **Do not touch the production Supabase project** (`klkfynokcjunqvbkywfk`). Read-only queries
  are fine and often the fastest way to confirm a claim; writes are not. A throwaway project
  (`lvaxbbobmounfxskooiw`) exists for anything destructive.
- Do not commit or push unless asked.
- Do not refactor. This is a bug hunt; a large diff buries the findings.

## What to give back

A **ranked list**, worst first. For each finding:

1. `file.kt:line` and what the code does.
2. **A concrete failure scenario** — real inputs, real state, and the wrong number or
   behaviour that results. "This could be confusing" is not a finding; "a sale of 3 against a
   shelf of 24 reports 24 after a rename" is.
3. Whether it is **PROVEN** (you traced or ran it) or **SUSPECTED** (it looks wrong but you
   could not confirm). Label every one. Do not inflate a suspicion into a certainty.
4. The smallest fix you would make, and what it might break.

Say plainly if a whole area looks sound — a clean bill on the refund path is a useful result,
not a wasted pass. Do not pad the list to look thorough.
