# Android → Web POS: the repoint is done, and here is what it assumes

Written 2026-08-10, after implementing the money model, the permission vocabulary, the
cash-session merge rule and the full sync repoint.

Everything below about the live schema was **queried against `klkfynokcjunqvbkywfk`**, not
recalled. Everything about behaviour was **executed** on a throwaway project
(`lvaxbbobmounfxskooiw`, free tier) rather than assumed.

Status: compile- and unit-test-verified (212 tests). **Nothing has run on a device.**

---

## 1. Your handoff was right, and one cross-check now proves it

Every column the 2026-08-10 handoff named exists exactly as described. Android's three
column meanings were adopted unchanged and they genuinely match — verified at
`PosRepository.checkout`: `subtotal` gross, `discount_total` combined and clamped,
`markup_total` = Σ line markup.

**The refund finding needed no change on our side.** `createRefund` already divided by
`saleGoodsValue(lines)`. Your reasoning is now a rule here, not a habit.

**The cross-check worth having.** On a scratch database we inserted a box line —
2 boxes @ $120, `unit_cost` 10, `units_per_line` 12 — and your generated columns produced
`line_cost = 240`, `line_profit = 0`. Android's `saleMarginFromLines` produces exactly 0
for the same line (test: `profitTotal_liftsAPerUnitCostToTheLineUnitExactlyOnce`).

That is the case `SaleLine`'s own docs flag as historically wrong on our side, and it is
the first time the two clients' profit arithmetic has been *compared* rather than assumed
to agree. **It agrees.**

---

## 2. Three findings you should act on

Each is the same species as the refund-ratio bug you found: nothing errors, the number is
just wrong.

### 2.1 `sale_discount` can go NEGATIVE

`computeSaleTotals` clamps the COMBINED discount to the goods value. So a whole-sale
discount larger than the cart leaves `discount_total` **smaller** than the discounts the
lines actually carry:

```
one line, $100 goods, $90 line discount, $50 whole-sale discount requested
  requested combined = 140  ->  discount_total CLAMPS to 100
  line_discount_total       = 90
  sale_discount = 100 - 90  = 10     (correct)

but with a bigger line discount the raw sum overruns the clamp entirely:
  two lines, $100 goods, line discounts 80 + 50 = 130
  discount_total CLAMPS to 100, raw line sum = 130
  sale_discount = 100 - 130 = -30    (nonsense)
```

Android **clamps `line_discount_total` to `discount_total` on the way out**, so we will
never send you a row that does this. If the web computes the column as a plain
`Σ line_discount`, it will produce one. Suggest clamping on your side too, or making
`sale_discount` a floored subtraction.

### 2.2 The cash-session tie-break compares different precisions

`cash_sessions.opened_at` is `timestamptz` — **microseconds**. Android stores epoch
**milliseconds** and writes millisecond-truncated values.

Two sessions differing only below a millisecond are a **tie** on our side (so the id
breaks it) and an **ordering** on yours (so the timestamp breaks it). Each client then
picks a different survivor — the exact divergence the rule exists to prevent, and it never
stops or raises an error.

Only reachable when two clients open a shift inside the same millisecond, which is
precisely what a tie-break is for. **Proposed fix: agree to compare `opened_at` truncated
to milliseconds.** This belongs on the wire, not in a client guessing.

### 2.3 `items_sku_idx` is not unique

It is a plain btree on `(business_id, sku)`. There is therefore **no conflict target for a
sku-keyed upsert**. This is part of why Android now treats the catalogue as pull-only and
keys everything by uuid. Flagging in case the web assumes sku uniqueness anywhere.

### 2.4 Posture note (not a bug, your call)

Every table's RLS policy is `auth_org_id() IS NULL OR business_id = auth_org_id()` for
`anon` **and** `authenticated`, on ALL commands. `auth_org_id()` reads
`auth.jwt() ->> 'org_id'`; an anon key carries no such claim, so the NULL branch always
wins — **the anon key has full read/write on every table**. The staff-JWT requirement from
the old project is gone. Deliberate or not, you should know it is the current state.

---

## 3. What Android now does, so you can rely on it

| Area | Behaviour |
|---|---|
| **Primary keys** | Android's `newId()` is `UUID.randomUUID()`. Rows go up under their own id. **No `local_id` anywhere.** |
| **Timestamps** | We push **`client_updated_at`, never `updated_at`** — a skewed device clock stamping the server cursor column would make every other device silently skip everything behind it. |
| **Generated columns** | Never named in an insert: `sale_items.line_cost`, `line_profit`, `cash_sessions.variance`. |
| **Sales** | Three rows: `sales` + `sale_items` + `sale_payments`. Line tombstones push with `deleted = true` so removals propagate; the **header's money derives from LIVE lines only**, so totals match the printed receipt. |
| **Refunds** | First-class `refunds` / `refund_items` / `refund_payments`. Never `type='return'` negative sales. |
| **Duplicates** | `ignoreDuplicates` is **opt-in per table and exactly one table opts in** — `mobile_money_receipts`, where `(business_id, txn_code)` is a real idempotency key. Everywhere else merge-duplicates on our own uuid. |
| **Catalogue** | **Pull-only.** The web owns it. A till never edits a product. |
| **Stock** | Pushed as `stock_movements`; on-hand recomputed as **`SUM(delta)`**, never the newest `balance_after` (two offline tills each snapshot a balance from the stock *they* could see, so the later snapshot discards the other's sale). |
| **Business id** | Adopts the single `businesses.id`. **Never inserts a businesses row.** Push refuses entirely until adopted rather than orphaning rows under a device-invented id. |
| **Permissions** | Written as an **explicit full map under BOTH spellings** (`refunds` and `process_refunds`). We keep deny-by-default for cashiers; you treat absence as allowed. Stating every key removes the disagreement instead of resolving it. Effective rule implemented as `isAdmin OR (NOT shopLocked AND staffPermitted)`. |
| **Session merge** | `winner = oldest opened_at, ties by id ascending`. Losers CLOSED (never deleted) with a note; their sales/refunds repointed in the same transaction. |

---

## 4. Questions I need answered

### BLOCKING — I cannot finish the cash side without these

**Q1. How does the web compute a shift's cash-up — by `session_id`, or by time window?**

This is the big one. Android has no shift UI yet, so every sale we push carries
`session_id = NULL`. If your cash-up sums takings by `session_id`, **every Android sale is
excluded from it** — the shop counts the drawer and the number is short by whatever the
tills sold, with nothing saying why. If you sum by time window (as Android's own local
day-close does), NULL is harmless for now.

The answer decides whether the shift open/close flow is blocking or cosmetic.

**Q2. Who maintains `items.stock_qty`, and does the web write `stock_movements` for every
sale?**

We treat the ledger as the authority and recompute on-hand from `SUM(delta)` after each
pull. If the web instead mutates `stock_qty` directly without writing a movement, our
recompute will **overwrite your figure with a number derived from an incomplete ledger**.
Both clients must agree which is the cache.

**Q3. What are the exact generated expressions for `line_cost` and `line_profit`?**

I wrote our bring-your-own-database setup script from the formula your handoff stated
(`line_total − unit_cost × qty × units_per_line`) and confirmed it reproduces your
behaviour on the box-line case. Please confirm the literal expressions so a
bring-your-own database cannot compute different profits from the shop's.

**Q4. Vocabularies — please confirm the accepted values for each:**

| Column | Android uses |
|---|---|
| `sales.status` | `completed` · `parked` · `refunded` · `void` · `quote` |
| `sales.payment_status` | `paid` · `unpaid` · `pending` |
| `refunds.status` | `settled` · `owed` (your default is `owed`) |
| `credit_txns.type` | `credit_owed` · `credit_paid` · `change_owed` · `change_paid` |
| `stock_movements.type` | `sale` · `restock` · `adjust` · `return` · `reset` |

Any of these that is CHECK-constrained on your side will fail the **whole batch**, not the
offending row — so a single unexpected value takes a day's sales down with it. If any are
constrained, tell me the exact list.

### NON-BLOCKING but needed before we enable push on a second till

**Q5.** Does the web tolerate `sales.session_id` / `refunds.session_id` being NULL, or does
anything join on it assuming presence?

**Q6.** `cash_movements.session_id` — required, or nullable in practice? We do not push cash
movements yet, partly because pointing them at a session you have never heard of would
silently omit them from the cash-up.

**Q7.** Is `sales.receipt_no` expected to be unique per business? We generate device-coded
refs (`K7Q-0013`) to avoid collisions, but there is no unique index on it.

**Q8.** Should Android ever push `items`? Currently never. If the shop wants to add products
from a till, we need a rule for which side wins — and given `items_sku_idx` is not unique,
a conflict target too.

**Q9.** Will you adopt the millisecond-truncated `opened_at` comparison from §2.2?

---

## 5. What is still open on our side (so you are not waiting on us for these)

- **No shift UI.** Nothing opens or closes a `CashSession`, so `session_id` is NULL on every
  sale. The merge rule is built and tested; it has nothing to merge yet. Gated on **Q1**.
- **Not pushed yet:** `cash_movements`, `audit_entries`, `expenses`, `suppliers`,
  `purchase_orders`, `purchase_order_items`. All exist on your side; each needs a
  uuid-keyed DTO. None blocks sales/stock/customer sync.
- **No cloud home at all** for `notifications`, `staff_requests`, `day_closes`,
  `outside_funds` — device-local by necessity, not by choice. Say if any should get one.
- **`DayClose` vs a closed `cash_session`** are unreconciled shapes. Ours records a count
  event; yours is a period. Related, not the same.
- **Nothing device-tested.** Four Room migrations (34→35, 35→36, 36→37) have never executed
  against a real database. Push stays gated OFF until the pull is verified against live
  shop data.
