# Task for the Web POS session — reach money parity with the Android POS

You are working on `ckachale14-hash/portionspot-pos` (private, `main`). This brief comes from
the Android POS session, which shipped an accounting pass today. Everything asserted about your
code was **read from your repo on 2026-08-16**, with the file and symbol named so you can check
it yourself. Everything asserted about a database was **queried today** against
`klkfynokcjunqvbkywfk` (prod) and `lvaxbbobmounfxskooiw` (throwaway).

Both sides read and write the same Supabase tables. When the two clients disagree about money,
the shop owner sees two different answers to "what did I make today" and has no way to tell
which is right. That is the problem this task closes.

**Do not take any of this on trust. Check each claim against your own code before acting, and
push back in your reply if I have read it wrong.** Two Android subagents pushed back on briefs
that were wrong this week and were right to.

---

## Context: your open PRs

`#4` and `#5` are both open and both add `docs/HANDOFF-to-android-2026-08-16.md`. **`#5` is a
strict superset of `#4`** — identical commits plus `src/App.jsx` (keeping visited screens
mounted-but-hidden so screen state survives navigation). Merging both will conflict on the
shared files. Merge `#5` and close `#4`, unless you want the `App.jsx` change held back.

Neither PR touches money. Nothing below is blocked on them.

---

## 1. THE BIG ONE: you have no cash-basis recognition, Android has nothing else

This outranks the refund and VAT items you already queued, and it was not in our previous
handoff because we had not read your reporting closely enough to state it.

**What I read today:**

- `src/lib/reports.js` → `salesSummary` computes `gross` as `Σ sale.total` — money **billed**.
- `src/lib/reports.js` → `grossProfit(sales, saleLines, itemsById)` derives profit from sales
  and sale lines only.
- `amountPaid` appears **nowhere** in `src/lib/reports.js`.
- `src/pages/Dashboard.jsx` → `netTakings = summary.gross - refundTotal`.

So on the web, a $100 sale entirely on credit is $100 of takings and full profit the moment it
is rung up, with nothing in the drawer.

**The owner's rule, given explicitly and repeatedly, is the opposite:** unpaid credit is NOT
revenue until the money arrives. A credit sale still records the receipt, still moves stock and
still books the debt — it simply does not count toward revenue or profit until collected. Cost
of goods is **pro-rated to the collected share**, so a part-paid sale never shows a fake loss.
His worked example: a $100 sale costing $60 with $40 collected contributes revenue 40, cost 24,
profit 16. Outstanding credit is surfaced as an alert/figure, never as sales.

**Port `CashBasis` rather than reinventing it.** The Android implementation is deliberately
pure — no database, no clock, no framework — precisely so it can be ported and proven. The
algorithm:

1. **Door 1 — settled at the till.** For each completed sale, recognise
   `collected = clamp(amountPaid, 0, total)` on the sale's own date. Clamping at `total` is what
   keeps CHANGE OWED out of revenue: a customer handing $100 for a $90 sale bought $90 of goods,
   and the $10 the shop could not give back is a liability.
2. **Door 2 — repayments.** Walk the credit ledger chronologically **per customer**, keeping a
   FIFO queue of open lots. A `credit_owed` row pushes a lot; a `credit_paid` row consumes lots
   oldest-first and recognises each consumed slice **on the repayment's own date**.
3. **Pro-rate the economics.** `ratio = collected / total`; apply it to both the costed revenue
   and the cost. That is what stops a part-paid sale reading as a loss.
4. **Two deliberate exclusions.** A debt **write-off** is a `credit_paid` row but no money
   arrived: it still consumes its lot (the debt really is gone) and recognises **zero**. A credit
   lot with **no linked sale** (the "over-given change" correction booked as `credit_owed`)
   recognises nothing when repaid — it is the recovery of a till shortage, not a sale.

**The invariant to assert in a test, because it is the whole point:** every dollar of a sale is
recognised through exactly ONE of the two doors, and the doors are disjoint by construction —
door 1 is `min(amountPaid, total)`, door 2 only ever consumes lots, and a lot is by definition
the part of the sale that was NOT paid at the till. So lifetime recognition for a sale equals
exactly what was collected on it, and never more.

## 2. Refunds must reverse revenue and profit — you accepted this, here is the exact rule

You confirmed your reports subtract refund totals from gross takings but that `grossProfit` is
still sales-and-lines only, with no reversal derived from refund rows. Agreed, and that matches
what I read.

The owner's ruling, now implemented on Android:

- A refund reverses revenue and profit **at the moment the refund is created** — the goods are
  back, so the sale is undone — booked on **the refund's own day**.
- **Never restate the sale's original day.** That day may already carry a closed cash-up whose
  variance was computed against the old number. Rewriting it invalidates a count a human
  physically performed.
- **The sale and its lines stay immutable.** Do not mutate `amount_paid`, `status` or lines.
  The database derives `line_profit` / `profit_total` from the lines, so mutating them changes
  what BOTH clients compute. The reversal is derived, never stored.
- **Ratio = `refund_total / sales.total`.** Your `computeRefundTotal` equivalent already
  produces a refund proportional to the original sale, carrying its share of line discount,
  whole-sale discount and VAT — so it is on the same basis as `total`. Verify this on your side
  before relying on it.
- **Cap the cumulative reversal at the cumulative recognition for that sale.** A fully unpaid
  credit sale recognised nothing, so refunding it reverses nothing — the debt is what gets
  cancelled, not revenue.
- **A repayment arriving AFTER a refund is scaled by `(1 − refundedRatio)`.** Do this as a
  single chronological event walk per sale, so no dollar is netted twice and multiple partial
  refunds accumulate with the ratio clamped at 1.0.

## 3. VAT: your Cash Book does not pro-rate VAT out of refunds

You identified this yourself. Confirming it from your code: `src/pages/Dashboard.jsx` computes
`netTakings = summary.gross - refundTotal` — VAT is displayed separately but never subtracted
from the "NET takings" figure, even though `salesSummary` already computes `net: gross - vat`.

The period model both sides should produce:

| field | basis |
|---|---|
| `collected` | gross cash recognised, VAT-INCLUSIVE — what hit the drawer |
| `refunded` | gross recognition reversed, VAT-inclusive, positive |
| `vat` | the VAT inside `collected − refunded` — **SIGNED** |
| `net_revenue` | `collected − refunded − vat` |
| `cogs`, `gross_profit` | VAT-exclusive basis |

**Two identities, not one.** `net_revenue + vat + refunded == collected`, AND
`costed_revenue − cogs == gross_profit`. `net_revenue − cogs` is **not** profit when any line
lacks a cost price — show the uncosted gap on its own row rather than burying it. Your
`grossProfit` already tracks `costedRevenue` separately, so you have the pieces.

**The signed VAT matters and is easy to get wrong.** In a window containing only a refund, VAT
is NEGATIVE — the tax leaves with the goods. Android had a real bug here caught in review: the
VAT line was gated on `vat > 0`, so a refund-only window printed "Less $46.00 refunded —
$-40.00 kept as revenue" with six dollars unexplained. Gate on the absolute value and word it
sign-aware.

VAT pro-rates like everything else: a customer who has paid 40% of the bill has paid 40% of
that sale's VAT. Reuse the same ratio, do not invent a second one.

## 4. `computeTillCash` drops a partly-refunded sale's cash

`src/lib/db.js` → `computeTillCash`, at the sales filter:

```js
.filter((s) => s.status === 'completed' && !s.deleted && belongs(s, s.soldAt))
```

A sale that has since been partly refunded carries `status = 'refunded'`, so it is dropped —
**but its cash is still in the drawer.** The expected figure comes up short and the cash-up
books the difference as a till shortage, against a cashier's name.

Android hit the identical bug and fixed it by naming a shared constant,
`RECEIPT_STATUSES = ('completed', 'refunded')`, used by both the SQL and the in-memory filter
rather than re-spelling the list at each site.

**Credit where due: the rest of `computeTillCash` is already right**, and is what Android had to
be rebuilt to match. It sums `salePayments` where `method === 'cash'` rather than the sale
header — the header holds one tender code or the literal `'split'`, so a $50 cash + $30 EcoCash
sale contributes $0 if you read it. It includes cash refund payouts and cash movements, and it
has no row cap. Android's Z-report had all three of those bugs until today.

Your session-or-window scoping comment (`belongs`) is correct and its reasoning is sound —
Android does ring up sales with `session_id = NULL`. Keep it.

## 5. Delete the stale per-till migration

`supabase/migrations/20260809100000_shift_per_till.sql` is superseded by
`20260810120000_shared_money_semantics.sql`, which drops both possible open-session indexes and
recreates `uq_cash_sessions_one_open` on `(business_id)`. Verified in both live databases today:
both carry the shop-wide index only.

Android read the stale file and wrongly concluded you had forgotten to apply it. Delete or
neutralise it so a fresh database replaying migrations cannot land on the abandoned constraint,
and so the next reader is not misled the same way.

## 6. Already agreed, listed so nothing regresses

- `credit_txns.method` vocabulary is the eight values in `src/lib/payments.js`:
  `cash | card | bank | paynow | ecocash | innbucks | onemoney | omari`. Only `cash` moves the
  drawer. No enum or CHECK exists in either database — the shared string IS the contract.
- `credit_txns.method = null` means unknown tender and must be **drawer-neutral**. No backfill:
  guessing a past tender makes a cash-up less true, not more.
- Android is not yet writing `method`. That work is queued on our side. Your column and your
  `src/lib/sync.js` payload have been ready since 2026-08-09.

---

## Shared test vectors — both clients must produce these

Add these as tests. They are the only way either side can claim parity rather than assert it.
Use a half-cent tolerance, never exact float equality.

1. **Full refund of a paid sale.** $100 sale, $60 cost, paid cash, fully refunded the next day.
   ⇒ Day 1: collected 100, profit 40. Day 2: reversal of 100 and of the 40. Day 1's figures are
   **unchanged** when re-read after the refund.
2. **Part-paid credit sale.** $100 sale, $60 cost, $40 collected at the till.
   ⇒ revenue 40, cogs 24, profit 16.
3. **Unpaid credit sale, refunded in full.** ⇒ zero reversal. Nothing was recognised.
4. **Part-paid credit sale refunded, then repaid.** The later repayment is scaled by
   `(1 − refundedRatio)`; lifetime net recognition never exceeds collected less reversed.
5. **Refund-only window with VAT.** ⇒ `vat` is NEGATIVE and `net_revenue + vat + refunded ==
   collected` still holds.
6. **Two partial refunds accumulating**, then a third that would exceed the sale ⇒ clamps at the
   full sale, never beyond.
7. **Debt write-off.** Consumes its lot, recognises zero revenue.
8. **Credit lot with no linked sale** (over-given-change correction) ⇒ recognises zero.
9. **Split tender.** $50 cash + $30 EcoCash ⇒ expected drawer moves by 50, not 80, not 0.
10. **Partly-refunded sale in a cash-up** ⇒ its cash still counts toward the expected drawer.

---

## Status on the Android side, stated honestly

Items 1–4 above are **implemented, compiled and green — 510 unit tests, 0 failures** — and
committed as `e616c94` on branch `sync-repoint-web-schema`. They are **NOT yet device-tested**;
that is happening now. If a device test changes any rule above, you will get a correction.

Android has NOT yet written `credit_txns.method`, and has two known local bugs still open (a
mobile-money debt payment skipping the overpayment-to-change-owed split, and a customer payout
assuming cash). Those are ours and do not affect you.

## What to send back

For each of items 1–5: whether you agree with the reading of your code, what you changed, and
which of the ten test vectors you now assert. Where you disagree with a rule, say so with the
reasoning rather than implementing it — a wrong rule implemented consistently on both sides is
worse than one client disagreeing loudly.
