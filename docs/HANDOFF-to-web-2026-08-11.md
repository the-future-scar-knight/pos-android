# Android → Web POS, 2026-08-11: the first two-way device test, and what changed

Written after the first time the Android till and the web back-office ran against the
same database with real data in it. Everything about the live schema below was **queried**
against `lvaxbbobmounfxskooiw`, and everything about the web's behaviour was **read out of
the deployed bundle or the repo**, not recalled.

Read §1 first. It is the only part that changes what you should do today.

---

## 0. Which repo is which — this cost a whole session

- The deployed back-office (`portionspot-pos-v1.vercel.app`) is built from
  **`ckachale14-hash/portionspot-pos`**, which is **PRIVATE**. A clone attempt without
  access returns *"Could not resolve to a Repository"*, which reads exactly like a wrong
  repo name. It is not.
- **`ckachale14-hash/POS` is a different, stale repo.** It still syncs `products`,
  `credit_transactions`, `settings`, `onConflict: 'sku'`, `stock_boxes`/`stock_units` —
  the dead contract. `pos2_` appears nowhere in it, and it has no `src/lib/__tests__/` and
  no `pin.js`. A session diagnosed the live app from it and was wrong on every point.

The deployed bundle is public and is the fastest ground truth when repo access is missing:

```bash
curl -s https://portionspot-pos-v1.vercel.app/ | grep -o 'src="/assets/[^"]*\.js"'
curl -s https://portionspot-pos-v1.vercel.app/assets/<file>.js -o bundle.js
grep -c "stockBaseAt" bundle.js     # 0 => predates the stock-baseline fix
```

---

## 1. THE DEPLOYED WEB BUILD IS STALE, AND THAT IS TODAY'S ACTION

Two fixes are on `main` and are **not live**:

| commit | what it fixes |
|---|---|
| `d0f5610` (08-10) | *Count the takings that arrived without a shift* |
| `3a447bd` (08-11) | *Stock: five on the shelf is still five after selling one and refunding it* |

The deployed bundle contains **zero** occurrences of `stockBaseAt`, so it predates the
second. Both symptoms reported from the shop floor today were this, not new bugs:

**Symptom A — "I sold 3 of 24 and the web says out of stock."** The deployed reconcile is

```js
const onHand = Math.max(round(sum_of_all_deltas), 0);
if (onHand !== item.stockQty) await db.items.put({ ...item, stockQty: onHand });
```

It overwrites on-hand with **Σ delta**, ignoring the shop's own figure. ATF's only movement
is the −3, so `max(-3, 0)` = 0. The database was right throughout: `stock_qty` 24, one
movement −3, `balance_after` 21.

Blast radius is currently contained — the reconcile does not set `pendingSync`, so the zero
stays in the browser and does not reach the cloud. **But editing a zeroed product on the web
pushes `stock_qty = 0`, and Android then adopts that as a fresh baseline.** At that point
the shelf empties for real, on every device. Until the redeploy: do not edit products on
the web, and do not reorder from its stock figures.

**Symptom B — "the cash-up didn't see the $24."** Partly the stale build, partly a bad
test on our side: the drawer was opened at 12:38 and the sale was rung at 12:18. It
predated the shift and is correctly excluded either way. **Check the clock before drawing
conclusions from a cash-up.**

**Redeploying `main` is the fix for both.** The Vercel project is not under the
`PortionSpot's projects` team, so it has to come from the other account.

---

## 2. The cash-up question from the 2026-08-10 handoff is ANSWERED

Q1 was marked BLOCKING. The answer is in your own source:

```js
const belongs = (row, at) => row.sessionId === mine || (row.sessionId == null && within(at));
```

**Unstamped sales count, by time window.** Android has no shift UI, so every sale it pushes
carries `session_id = NULL` — and that is safe. Thank you; this is the good answer and it
unblocks the shift work on our side rather than forcing it.

The remaining constraint is that an unstamped sale only counts if it falls inside an **open**
shift. Anything the till sells while no shift is open is in no cash-up anywhere. That is on
us to fix (see §5).

---

## 3. Schema changes APPLIED to the throwaway, not to prod

Applied to `lvaxbbobmounfxskooiw` only. `klkfynokcjunqvbkywfk` (prod) is **untouched** and
still has 21 tables. The full DDL is in `supabase-setup.sql` §7b and §7c and is idempotent —
running the whole script again is safe.

### 3a. `cash_sessions` gains two columns — the day-close fold

```sql
alter table public.cash_sessions add column if not exists moved_to_safe numeric not null default 0;
alter table public.cash_sessions add column if not exists float_target  numeric;
```

The till keeps a local `day_closes` table (expected / counted / variance / who / when, plus
how much went to the safe and what float was left). **Six of those eight are already
`cash_sessions` columns**, and `variance` is `GENERATED ALWAYS AS (COALESCE(counted_cash,0)
- COALESCE(expected_cash,0))` — the same arithmetic the device does. Giving day-closes their
own cloud table would put two answers to *"what was the till short on the 8th?"* in one
database with nothing to arbitrate.

So **a day-close is the closing half of a shift**: `status='closed'`, `opened_at` = the start
of the trading day being closed, `closed_at` = the moment of the count. Only the two facts
with no home got columns:

- `moved_to_safe` — recoverable in theory from the transfer pair in `cash_movements`, but
  that table has no `ref_type`/`ref_id`, so nothing says which drop belongs to which close.
- `float_target` — a shop setting that drifts, so today's value says nothing about a close
  from March. Snapshot, not lookup.

**What this means for you:** `pos2_cash_sessions` was dropped and recreated so the two
columns are visible through it — a `select *` view freezes its column list at creation, so a
view left alone would never have shown them. If your cash-up reads `cash_sessions`, it will
start seeing rows whose `opened_at` is a day boundary rather than a real shift open. That is
intended.

### 3b. Three new tables: `notifications`, `staff_requests`, `outside_funds`

All three are wired into the same three mechanisms as every existing table — the
`set_updated_at` trigger, the tenant RLS policy, and a `pos2_*` view with
`security_invoker = true`. Each carries `business_id`, `updated_at`, `client_updated_at`,
`deleted`. **They are yours to read too** — that is why they are in the shared schema and
behind `pos2_` views rather than in a private Android schema.

Three details that are decisions, not accidents:

- **`notifications.id` DEFAULTS server-side and clients must not send it.** The natural key
  is `(business_id, dedupe_key)`, with a **non-partial** unique index because PostgREST
  cannot infer a partial index as an `on_conflict` target. Two devices computing the same
  condition mint different local ids but the same dedupe key; if the push named `id`, each
  device's upsert would rewrite the PK to its own and every other device would re-pull an
  unchanged row forever.
- **`outside_funds` is the only one CHECK-constrained** (`kind ∈ {capital, loan}`,
  `direction ∈ {in, out}`). A tripped CHECK fails the whole batch, so we constrain almost
  nothing — but those two columns are the table's entire meaning, and a wrong `kind` moves
  money between what the shop owes its owner and what it owes a lender. Loud failure beats
  quiet corruption there.
- **`staff_requests` is on the same permissive tenant policy as everything else.** The
  obvious tightening (staff INSERT+SELECT, admin-only UPDATE) is written out as a comment in
  §8 and deliberately not enabled: `auth_org_role()` returns NULL for an anon-key client,
  which is how every till connects, so that gate would deny *every* approval in the shop.
  It becomes correct the day tills carry an `org_role` claim. **The till's own KDoc claims
  this policy is already in force — it is not. Do not read the till's comments as a
  description of this schema.**

Nothing on the Kotlin side pushes or pulls these three yet.

---

## 4. What Android does now that it did not on 2026-08-10

Four commits on `sync-repoint-web-schema`. 271 unit tests, 0 failures. **Not device-tested
beyond today's session.**

### `6b102c1` — the catalogue is no longer web-only

Android now **pushes `items`**, uuid-keyed, last-write-wins on `client_updated_at` (the same
rule the pull already applied in the other direction). This is what stops the web being
mandatory daily rather than a back office.

**★ `stock_qty` is NOT in the payload, and never will be.** The ledger is the authority for
what is on the shelf; `items.stock_qty` is the shop's baseline figure and stays yours. A
till writing its own computed on-hand into it would re-baseline every other device from
whatever movements *that phone* happened to have seen — two tills offline from each other
would each discard the other's sales, silently. A unit test serialises the DTO and fails if
`stock_qty` ever reappears in it.

Consequences you may see:

- **A product created on a till arrives with `stock_qty` at its default of 0.** Its opening
  count rides on a `restock` movement in `stock_movements`. If your inventory screen reads
  `stock_qty` directly rather than reconciling the ledger, a till-created product will look
  empty until you set a figure.
- `price_per_unit`, the image columns and the PO `pending` fields **have no column on the
  shared schema at all**, so a measured product's per-unit price does not round-trip in
  either direction. This needs a column from you; nothing on our side can fix it.
- `item_attributes` (the 671 car fitments) stays **pull-only** from Android.

### `6b102c1` also fixes a bug that was live on both sides

`toItem` re-adopted the stock baseline on **every** accepted pull, stamped with
`client_updated_at` — which is the row's *edit* clock, not a stock clock. So a rename or a
reprice moved the baseline past every earlier movement and dropped them. Rename ATF and the
till went back to reporting 24 with a −3 sale on file. The baseline now moves **only when
the figure itself moves**; a real stock-take still supersedes the ledger before it.

**This is the same class of bug as §1's `Math.max(Σ delta, 0)`, and it is worth checking
whether your `stockBaseAt` adoption has it too.**

### `bf01b4a` — stock typed into the till's form reaches the ledger

`items.stockQty` is a cache the sync pass rebuilds, so a figure written onto the row with no
movement behind it survived until the next pull and was then computed away. Restocking from
the till silently did not work. It now logs the difference as an `adjust` movement (or a
`restock` for a new product's opening count). `resetAllStock` had the same fault plus one of
its own — it only cleared `stockQty`, so "reset all stock" emptied everything except
products sold by weight.

### `fec6b3c` — a repointed till uploads what it already held

A dirty flag recorded that a row was *sent*, never *where*. Point a till at a second project
and everything it pushed to the first stayed marked clean and was never offered to the new
database — the pass reported success because nothing was pending. Found live: customer
`Ryan` stayed behind while `Tim` went up, and two sales plus a `credit_owed` of $15 landed
in the cloud referencing a customer present in neither database. **A debt with no debtor,
and nothing anywhere raised it, because no foreign key refuses one.**

---

## 5. Still open on our side

- **No shift UI.** Nothing creates a `CashSession`, so `session_id` is NULL on every pushed
  sale. Given §2 that is tolerable, but anything sold while no shift is open counts nowhere.
  Being built next, folding `day_closes` into `cash_sessions` per §3a.
- **No oversell guard.** The till clamps its display at 0 but writes the full negative delta
  to the ledger, so an oversold item goes negative on every device after a sync.
- **Not pushed yet:** `expenses`, `suppliers`, `purchase_orders`, `purchase_order_items`,
  `audit_entries`. In progress. Note `purchase_orders.status` is CHECK-constrained to
  `draft|sent|received|cancelled` while the till writes `placed` and `partial` — a
  translation is being written, since a CHECK violation fails the whole batch.
- **`notifications` / `staff_requests` / `outside_funds`** have tables now but no Kotlin.

## 6. Questions still unanswered from 2026-08-10

Q1 is answered (§2). Still outstanding: **Q3** the literal generated expressions for
`line_cost`/`line_profit`; **Q7** whether `sales.receipt_no` is expected unique per business
(there is no unique index); **Q8** the conflict rule now that Android *does* push `items` —
we assume last-write-wins on `client_updated_at`, tell us if you assume otherwise; **Q9**
whether you will adopt the millisecond-truncated `opened_at` comparison for the session
tie-break.

Also flagged 2026-08-10 and still true: **the anon key has full read/write on every table.**
Every policy is `auth_org_id() IS NULL OR business_id = auth_org_id()`, `auth_org_id()` reads
`auth.jwt() ->> 'org_id'`, and an anon key carries no such claim, so the NULL branch always
wins. Deliberate or not, you should know it is the current state.

---

## 7. To be on the same page, we are assuming

1. You redeploy `main`, and stock stops reading as out-of-stock for anything the till sells.
2. `items.stock_qty` is **yours**; the movement ledger is **ours**; on-hand is
   `stock_qty + Σ deltas stamped strictly after the row's client_updated_at`. Neither client
   writes the other's half.
3. A day-close is a closed `cash_session`, not a separate record.
4. Nobody adds a CHECK constraint to a shared table without saying so — one unexpected value
   fails an entire push batch, not the offending row.
5. `client_updated_at` is what both clients write and compare; `updated_at` is the server's
   and is only ever read as a cursor.
