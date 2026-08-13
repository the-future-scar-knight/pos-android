# Handoff — 2026-08-13, branch `sync-repoint-web-schema`

Read this first. Then [`docs/HANDOFF-to-web-2026-08-12b.md`](HANDOFF-to-web-2026-08-12b.md) for what
the web POS was last told.

**Not** `SESSION-HANDOFF.md` in the repo root — that one is from 2026-07-25 and describes a different
phase, but do not delete it: its **Phase 3 `staff_requests` UI is still genuinely unbuilt**, and
`staff_requests` is still on `SyncConfig.LOCAL_ONLY_TABLES`. It is the record of that outstanding work.

---

## State

Everything below is **compile-and-test green and NOT device-tested**:
`BUILD SUCCESSFUL`, 0 compile errors, **479 tests / 0 failures** (41 test files, up from 384).
Room **40 → 42**. Debug APK built and handed over.

The previous build — the one Ryan *did* device-test, on two phones against the throwaway project
`lvaxbbobmounfxskooiw` — passed cleanly: sales, a new customer, change-to-account and a stock
increase all crossed between devices. That is verified in the cloud data, not just reported.

### 1. A shift is a trading day

`data/DaySession.kt` (+28 tests). Ryan's decision: **no open/close shift UI**. One `cash_sessions`
row per SHOP per day, rolling at local midnight.

- Sessions are created **lazily by a real sale** — never on app start, never by a sync pass. The
  cloud allows one open session per business, so every idle phone minting the morning's row is a
  race where all but one push is rejected.
- `openedAt` is the day's **own midnight**, not `now`. That makes it a correct bucketing key and
  makes two offline phones produce rows differing only by id, so the oldest-wins tie-break is
  deterministic rather than a coin flip.
- **Rollover runs immediately before `planSessionMerge`.** Without it the merge — which keeps the
  OLDEST open session — would have kept *yesterday* and dragged today's sales into it.
- Day close writes through to `cash_sessions` (`counted_cash`, `expected_cash`, `moved_to_safe`,
  `float_target`; `variance` is GENERATED, never pushed). `day_closes` stays local as the detailed
  record — the cloud row is the shared summary.
- **One count per trading day, by owner ruling.** A second close is a no-op returning the first
  record, and the UI now says so instead of silently accepting a count. Rationale is written at the
  guard in `PosRepository.closeDay`; a second-count path was considered and deliberately not built.

### 2. Both tills count the same drawer

`data/CashMirror.kt` (+18 tests). Pulled sales and refunds now create local cash rows, so phone B's
till balance includes phone A's takings. Before this, a day close on the wrong phone produced a fake
"over" equal to the other phone's cash — and by Ryan's rule a variance hits profit.

Reconciles **sums** (expected − already held), not row existence: a refund legitimately accumulates
one payout row per instalment and `voidRefund` writes a reversing row, so existence tells you nothing
about amount. A side benefit is that a voided refund converges on the other phone by itself.

### 3. Item attributes have a UI

`data/AttributeId.kt`, `data/AttributeVocabulary.kt` (+27 tests). 671 fitments were invisible and
uneditable. Now: view/add/edit/remove on the item, with typeahead over existing keys and values.

**Ids are derived, never random** — UUID **v5** over
`businessId ⑟ itemId ⑟ canonical(key) ⑟ canonical(value)` (U+001F separator, namespace
`6f1c0b3e-8a2d-5f47-9c31-2b7a4d6e8f10`), matching the web character for character so two offline
phones tagging the same part converge instead of creating duplicates that `uq_item_attributes_live`
would 23505 — failing the whole push batch. `java.util.UUID.nameUUIDFromBytes` is v3/MD5 and there is
a test asserting the result is **not** that value.

`rekeyPendingAttributes` re-mints unsent tags under the SHOP's business id before upload: the app is
local-first, so tags typed before a cloud connect carry the device's invented business id.

### 4. Three audited money fixes

- **A refund could be issued against a QUOTE.** `observeRecent` was the only sales query with no
  `status` filter, so quotes rendered in Receipts with a Refund button — restocking goods that never
  left and booking a real debt. Now `IN ('completed','refunded')`, plus `SaleEntity.isRefundable()`
  guarding the action itself. `void` is excluded deliberately: a voided sale has already been
  reversed, and showing it offers a second reversal of the same transaction.
- **The Receipts "Today" hero counted unpaid credit as takings.** Now reads collected, from the SAME
  `cashBasisInputs` flow the Dashboard uses. The old `SUM(total)` query was **deleted**, not left
  unused — a query named "takings" returning billed totals invites the bug back.
- **A cash debt repayment never moved the till.** Now does, behind a tender picker (cash / mobile
  money / card); only cash moves the drawer.

---

## Next session: the deferred accounting pass

**Read `pos-reporting-maths-audit` in memory first.** Ryan approved this split; these are the four
that were deferred, and they need their own tests and their own device test because #1 changes what
every profit figure in the app says.

1. **Refunds never reduce collected revenue or profit.** `createRefund` deliberately never touches
   the sale, its lines, `amountPaid` or `status`, and `CashBasis.contributions` skips refund rows.
   A $100 sale costing $60, fully refunded, still reads $100 collected / $40 profit with the goods
   back on the shelf. **This is NOT the earlier "refund/profit fix"** — that one was about how a
   refund is *valued* (`returnedLineValue`). Verified; do not re-conflate them.
2. **VAT basis mismatch in one card.** Revenue comes from `total` (VAT-inclusive); cost and profit
   from `SaleMargin` (`total − taxTotal`, VAT-exclusive by design). Reports renders them as a
   subtraction that does not hold: "$115 collected − $60 cost = $40 profit".
3. **Drawings cut reported profit** once cumulative drawings exceed float + capital + loans —
   `floatCapital = (float + equityCash).coerceAtLeast(0.0)` clamps at zero while the till keeps
   falling. Breaks the rule that a drawing must not reduce profit.
4. **Z-Report expected drawer** reads the sale HEADER `paymentMethod` (a split sale contributes $0
   cash), is capped at `observeRecent(100)`, and ignores refund payouts, cash-funded expenses and
   safe transfers.

Lower priority, same audit: top-products sums GROSS `lineTotal` so it inflates by exactly the
discounts given; Dashboard count/average ignore refunds while Reports subtracts them; the trading-day
edge is computed once per `businessId` and goes stale past midnight; two live gross-profit
definitions with one orphaned; `SaleDetailDialog` itemises the cashier markup that print and PDF fold
into the subtotal.

Found later and left alone, worth folding into the same pass:

- `verifyMobileMoney(purpose = "debt")` writes `credit_paid` directly instead of going through
  `recordRepayment`, **skipping the overpayment → `change_owed` split**.
- `recordChangePayment` assumes cash out unconditionally — the mirror image of the repayment bug.
- Latent: if a sale ever got `status = "refunded"`, `ZReportDialog` would silently drop it.

## Open decisions

- **Cross-till repayments need a schema change.** `cash_movements` is push-only, and `CashMirror`
  cannot cover credit rows because `CreditTxn` has **no `method` column** — a pulled `credit_paid`
  cannot tell the receiving till whether it was cash or EcoCash. Persisting the tender on the credit
  row plus a wire column is Ryan's call, not an agent's. Today a repayment reaches the web cash-up
  but not the second phone.
- **Numeric typmod convergence** — answered yes to the web with two corrections (prod quantities are
  `numeric(14,3)` not `(14,2)`; prod has 4 bare-numeric columns its own 08-12 migration added, 3 of
  them money). Ryan is holding this until the Android side settles. Not yet run.
- **The web's `shift_per_till` migration was never applied.** `20260809100000_shift_per_till.sql`
  exists in their repo; I queried BOTH projects and prod still has `uq_cash_sessions_one_open` on
  `(business_id)`. The ALTERs landed (`till_code`, `refunds.session_id`), only the index swap did
  not. Day-per-shop was chosen partly because it fits the index that is actually there. **The web
  may believe this is live** — tell them.

## Gotchas that cost real time

- **Never trust a Gradle exit code here.** It returned 0 on a session that also produced a broken
  commit. Grep the log text for `BUILD SUCCESSFUL` / `BUILD FAILED`, and tally
  `app/build/test-results/testDebugUnitTest/*.xml` rather than believing a summary line.
- **`$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`** — not on PATH; gradlew fails
  without it.
- **One Gradle run at a time.** 8GB host; two concurrent runs fail.
- **Subagents worked well and should be reused: sequential, and forbidden from running Gradle.**
  All four workstreams touch `PosRepository`/`PosUi`/`PosDao`/`PosDatabase`, so parallel would have
  been a merge mess. Denying them the compiler forced them to re-read their own edits; one combined
  10-minute build then came back clean first time. Two of them also pushed back correctly on briefs
  that were wrong — that is the behaviour to encourage, not suppress.
- **The GitHub PAT for `ckachale14-hash/portionspot-pos` was pasted in a chat transcript on
  2026-08-13 and should be rotated.** Used transiently as `GH_TOKEN=…` per command; never written to
  disk or the gh keyring.

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& .\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --console=plain *> build.log
& .\gradlew.bat :app:assembleDebug --console=plain *> apk.log
```
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Device test not yet done

Cross-till drawer agreement (ring a cash sale on A, check the till on B); day close reaching the
other phone and refusing a second count; the repayment tender picker moving the till for cash and not
for EcoCash; quotes absent from Receipts and unrefundable; attributes crossing between phones
(**needs push enabled — it defaults off**); and the midnight rollover, which only proves itself
across a real midnight.
