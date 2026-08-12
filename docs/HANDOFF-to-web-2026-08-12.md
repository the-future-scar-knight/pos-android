# Android → Web, 2026-08-12: what changed on our side, and the one place we now disagree

Supersedes the 2026-08-11 handoff, which is stale in three places. **Everything below was
checked today** — the deployed bundle was fetched and read, the web repo was fetched at
`origin/main`, and both databases were queried. Nothing here is recalled.

---

## 0. Verified state, so nobody works from assumptions

| | |
|---|---|
| Web `origin/main` | `3ed0036` *"Stock: a sale that shares a millisecond with a count is still a sale"* |
| Web deployed | Current — bundle is `index-VH1zmyd3.js` and **does** contain `stockBaseAt`, so both of yesterday's missing fixes are live |
| Web syncs | 21 `pos2_*` tables, including all five of the "accounting spine" ones |
| **Prod** `klkfynokcjunqvbkywfk` | **21 tables, 21 `pos2_` views**, `set_updated_at` on all 21, **172 items** |
| **Throwaway** `lvaxbbobmounfxskooiw` | **24 tables, 24 views** — has the three new tables and the two new `cash_sessions` columns |

**The redeploy landed and it fixed both shop-floor symptoms.** Nothing further is needed
there.

---

## 1. THE ONE REAL DISAGREEMENT — the stock tie-break

Both clients hit the same bug within hours of each other and fixed it differently. **Your
fix is the correct one.** Ours is narrower and will be changed to match.

**Yours** (`3ed0036`): at a tie, exclude only the movement that *produced* the baseline —
identified by what it is (an absolute type, `adjust`/`restock`, carrying the counted figure
as its `balanceAfter`) rather than by when it happened. Everything else stamped at the same
instant is another till's independent work and is counted.

**Ours**: `createdAt > stockBaseAt`, strictly. The whole tie is excluded regardless of type.

**Where that differs, and it is exactly the case you described.** Two tills in the same
millisecond: A takes a count of 25, B rings up two. Your client reads 23. Ours reads 25 —
we drop B's sale. Same rows, two answers, no error either side.

**Action: none for you.** We are aligning Android to your rule. Flagged so that if you see
a till disagree by exactly one movement, this is why, and it is ours.

Worth stating for both sides: this works on Android-authored counts today for a different
reason. Our restock stamps its movement one millisecond *after* the item row, so it is
never in the tie at all — and we never push `stock_qty`, so the baseline the tie is measured
against does not move when we edit a product.

---

## 2. What Android now does that it did not before

### 2.1 We push `items`. The catalogue is no longer yours alone.

A product added, renamed or repriced on a till now reaches the cloud. Last-write-wins on
`client_updated_at`, which is the rule your pull already applies in the other direction.

**★ We still never push `stock_qty`, and never will.** Your `items` field list includes
`stockQty` and ours deliberately omits it, so **you remain the single writer of the shop's
figure** and the ledger remains the authority for movement. That is the contract; nothing
on our side writes the other half.

Consequence you will see: **a product created on a till arrives with `stock_qty` at its
default of 0.** Its opening count rides on a `restock` movement in `stock_movements`. If a
screen reads `stock_qty` directly instead of reconciling the ledger, a till-created product
looks empty until someone sets a figure.

### 2.2 We push the accounting spine

`expenses`, `suppliers`, `purchase_orders`, `purchase_order_items`, `audit_entries` — all
five now push and pull from Android. You already sync all five, so we have caught up to you
rather than the reverse.

Two things to expect:

- **`purchase_orders.status`** is CHECK-constrained to `draft|sent|received|cancelled`. Our
  internal model also has `placed` and `partial`, which we translate to `sent` on the way
  out and restore on the way back. If you see a PO whose status looks coarser than the one
  you set, that is the translation, not a loss.
- **`audit_entries.meta`** is jsonb, but our local column is free text (`"$12.50"`,
  `"qty 2 → 3"`). We send object- and array-shaped text as real jsonb and everything else
  as a **JSON string**. Expect both shapes in that column.

### 2.3 We write `staff` rows directly — you are not the only writer any more

Android has dropped Supabase Auth for staff entirely. Sign-in is now `staff.username` +
`staff.pin_hash`, and the admin console creates cashiers and sets PINs by writing `staff`
over PostgREST.

**The hash is a port of your `src/lib/pin.js`, verified rather than intended.** We generated
vectors by running your algorithm under WebCrypto and pinned them in a test, so a cashier
created in your UI unlocks the phone and one created on the phone works in yours.
PBKDF2-HMAC-SHA256, 210,000 iterations, 256 bits, lowercase hex, salt
`SHA-256("PortionSpot POS pin v1:" + businessId)`, stored `pbkdf2$<iters>$<hex>`.

**The salt uses the CLOUD business id.** If you ever change how that seed is built, say so
before deploying — it silently invalidates every PIN on both clients at once.

---

## 3. What you may want to DO

### 3.1 Prod has NOT had yesterday's schema change — that is deliberate

The three new tables (`notifications`, `staff_requests`, `outside_funds`) and the two new
`cash_sessions` columns (`moved_to_safe`, `float_target`) exist **only on the throwaway**.
Prod is still 21 tables. The DDL is in `supabase-setup.sql` §7b/§7c and is idempotent, so
running the whole script against prod is safe whenever the owner decides.

### 3.2 If you adopt the day-close fold, add two fields

Your `cashSessions` sync spec is
`[status, tillCode, openedAt, openedBy, openedByName, openingFloat, closedAt, closedBy, closedByName, countedCash, expectedCash, note]`
— it does not carry `movedToSafe` or `floatTarget`. That is harmless today (PostgREST only
updates the columns a payload names, so you cannot blank them), but you will not see them.

A day-close is now modelled as the closing half of a shift rather than a separate record:
`status='closed'`, `opened_at` = the start of the trading day, `closed_at` = the count.
`variance` is GENERATED — never name it in an insert.

### 3.3 Three tables you have zero references to

`notifications`, `staff_requests`, `outside_funds` are in the shared schema with `pos2_`
views, on the same trigger/RLS terms as everything else. Nothing on either client reads
them yet. `staff_requests` is the one that is useless without a second device — it is a
cashier asking an admin who is not in the room.

Two details that are decisions, not accidents:
- **`notifications.id` defaults server-side and clients must not send it.** The natural key
  is `(business_id, dedupe_key)` and its unique index is deliberately non-partial, because
  PostgREST cannot infer a partial index as an `on_conflict` target.
- **`staff_requests` is on the same permissive tenant policy as every other table.** The
  obvious tightening would deny every approval in the shop, because `auth_org_role()` is
  null for an anon-key client and that is how every till connects.

---

## 4. Still open, and still ours

- **No shift UI on Android.** Every sale we push carries `session_id = NULL`. Your
  time-window rule makes that safe, which is the good answer — thank you. The remaining
  edge is that a sale rung while no shift is open counts nowhere.
- **Overselling** now warns rather than blocks, and the cached figure is no longer clamped
  at zero — so a genuinely oversold item reads negative on our side instead of pretending.

## 5. Questions from 2026-08-10 still unanswered

**Q3** the literal generated expressions for `line_cost` / `line_profit`; **Q7** whether
`sales.receipt_no` is meant to be unique per business (there is no unique index); **Q9**
whether you will compare `opened_at` truncated to milliseconds for the session tie-break.

And still true, still worth knowing: every table's policy is
`auth_org_id() IS NULL OR business_id = auth_org_id()` granted to `anon`, and an anon key
carries no such claim — so **the anon key has full read/write on every table, `staff`
included.** Both clients' permission systems are guardrails, not access control.

---

## 6. Data note

67 products from the owner's stock lists were inserted directly into **both** databases on
2026-08-11 (prod now 172 items), with `client_updated_at` set. Six new categories appeared:
Engine Rings, Water Pumps, Electrical, Accessories, Brakes, Cooling.
