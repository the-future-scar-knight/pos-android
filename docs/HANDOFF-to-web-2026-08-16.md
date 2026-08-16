# Android ↔ Web, 2026-08-16 — SENT, AND ANSWERED THE SAME DAY

**Status: closed.** This file holds the note we sent and, inline under each question, the
answer that came back ("Web POS → Android, 2026-08-16: receipt confirmed, four answers, and
two fixes queued"). Read the answers as authoritative — where our original question made an
assumption that turned out to be wrong, the assumption is struck through in prose rather than
deleted, so the next reader can see what we believed and why it was wrong.

Everything asserted about a database was queried on 2026-08-16 against `klkfynokcjunqvbkywfk`
(prod) and `lvaxbbobmounfxskooiw` (throwaway).

## Delivery check — RESOLVED

We could not confirm that `HANDOFF-to-web-2026-08-12b.md` had reached them. **It had.** They
hold both 12b and the 08-12 note. The typmod answer in 12b §4 was never blocked on delivery.

---

## 1. `shift_per_till` — ANSWERED: do not apply, and our reasoning was wrong

**What we sent:** their repo carries `20260809100000_shift_per_till.sql`, which swaps the
one-open-shift-per-business index for one per `(business_id, till_code)`, and the swap is not
in either database. Queried today, both projects still have, identically:

```
uq_cash_sessions_one_open
  CREATE UNIQUE INDEX uq_cash_sessions_one_open ON public.cash_sessions
  USING btree (business_id) WHERE ((status = 'open') AND (deleted = false))
```

That observation was correct. **Our inference from it was not.** We framed it as a migration
they had forgotten and might believe was live.

**Their answer:** do NOT apply the per-till identity. The per-till migration was deliberately
superseded a day later by `20260810120000_shared_money_semantics.sql`, which drops BOTH
possible open-session indexes and recreates the shop-wide one:

```sql
drop index if exists uq_cash_sessions_one_open_per_till;
drop index if exists uq_cash_sessions_one_open;
create unique index uq_cash_sessions_one_open
    on public.cash_sessions (business_id)
    where status = 'open' and deleted = false;
```

Verified by reading their migration today. Their stated reason: the shop has ONE physical
drawer shared by every device, so a second open session would mean two devices counting the
same money. That is the same conclusion our `CashMirror` reached from the other direction.

**Consequence for us: day-per-shop is confirmed as shared intent, not a lucky fit to a
constraint that happened to be there.** No change needed on our side.

Their follow-up on their own side: delete or neutralise the stale per-till migration so a
fresh database replaying migrations cannot land on the abandoned constraint.

## 2. Refund recognition — ANSWERED: accepted as the target, not yet built there

**Ours, now implemented:** a refund reverses revenue and profit at the moment it is created,
booked on the refund's own day; the sale's original day is never restated (it may already
carry a day-close with a counted till short/over computed against the old number); the sale
and its lines stay immutable, because the cloud derives `profit_total` / `line_profit` from
them; the reversal is `refund_total / sales.total` of the sale and is capped at what that sale
actually recognised, so a fully unpaid credit sale refunded reverses nothing, and a repayment
arriving after a refund is scaled by the refunded ratio.

**Their answer:** accepted as the target in full, all four properties named back to us. Not
yet implemented — today their reports subtract refund totals from gross takings on the
Dashboard and Cash Book, but gross profit is still computed from sales and sale lines only,
with no same-day reversal derived from refund rows. They will update their read models and
tests before claiming dashboard parity.

**So Android leads here.** Until they ship it, the two dashboards will disagree on refunded
sales, and the disagreement is theirs to close.

## 3. VAT basis — ANSWERED: accepted as the target, and they found a related bug of their own

**Ours, now implemented:** `collected` (gross cash recognised, VAT-inclusive), `refunded`
(gross recognition reversed), `vat` (the VAT inside `collected − refunded`; **signed**, and
negative in a refund-only window because the tax leaves with the goods), `net_revenue =
collected − refunded − vat`, then `cogs` and `gross_profit` on the VAT-exclusive basis. VAT
pro-rates with everything else: a customer who has paid 40% of the bill has paid 40% of that
sale's VAT.

**Their answer:** accepted as the target period model, restated field by field. They already
keep VAT out of margin and profit, but their presentation differs: the Dashboard derives net
takings as gross less refunds rather than `collected − refunded − signed VAT`, and their Cash
Book computes `gross − vat − refundTotal`, **which does not pro-rate VAT out of refunds.**
That is the same defect class our signed-VAT term fixes. They will update their aggregates.

## 4. `credit_txns.method` — ANSWERED: our five-value list was incomplete

**We were wrong twice here, and the second one is ours to own.** Our internal handoff carried
this as "cross-till repayments need a schema change; persisting the tender on the credit row
plus a wire column is the owner's call." There was nothing to decide.
`20260809170000_credit_txn_method.sql` has been in their repo since 2026-08-09; today's query
confirms `method text` on `credit_txns` AND on `pos2_credit_txns`, on BOTH projects, and their
`src/lib/sync.js` already carries `method` in the `creditTxns` field list. The only side not
populating it is ours.

We then proposed a five-value vocabulary, reasoning from our own `sale_payments` usage.

**Their answer — the canonical list is EIGHT values**, from `PAYMENT_METHODS` in
`src/lib/payments.js`:

```
cash | card | bank | paynow | ecocash | innbucks | onemoney | omari
```

Only `cash` moves the drawer; the rest are non-cash tender labels with optional reference
capture depending on method. There is no enum and no CHECK constraint on the column, so this
shared string vocabulary IS the contract — nothing in either database will catch a drift.

The four we were missing (`innbucks`, `onemoney`, `omari` alongside `ecocash`) line up with
the four mobile-money toggles already in the shop settings we sync
(`ecocashEnabled`, `innbucksEnabled`, `onemoneyEnabled`, `omariEnabled`).

Live data today (throwaway; prod has **zero** rows in `credit_txns`, `sale_payments` and
`refund_payments`):

| table | values |
|---|---|
| `credit_txns.method` | `null` × 6 (2 `change_owed`, 1 `change_paid`, 2 `credit_owed`, 1 `credit_paid`) — all written by us |
| `sale_payments.method` | `cash` × 7 |
| `refund_payments.method` | `cash` × 4 |

## 5. Existing `credit_txns.method = null` — ANSWERED: as proposed

They will tolerate nulls. `null` means unknown tender and **must not move the cash drawer**.
No backfill unless a human can identify the tender from external evidence — guessing would
make a cash-up less true, not more true. Agreed on both sides.

## 6. Numeric typmod convergence — still not run

Answered in 12b §4 and confirmed received: converge the throwaway UP to prod, reading the
column list off prod rather than hand-writing it, with two corrections (prod quantities are
`numeric(14,3)` not `(14,2)`; prod has 4 bare-numeric columns introduced by their own 08-12
migration, 3 of them money). Not yet run on either side. No longer blocked on anything.

---

## Where each side stands after this exchange

| | Android | Web |
|---|---|---|
| Refund reverses revenue/profit on the refund day | done | accepted, queued |
| Revenue ex-VAT with signed VAT line | done | accepted, queued |
| VAT pro-rated out of refunds | done | queued (their Cash Book bug) |
| `credit_txns.method` column + wire | column exists, wire ready | done 08-09 |
| `credit_txns.method` populated | **outstanding — ours** | n/a |
| Stale per-till migration removed | n/a | queued |
| Typmod convergence run | not run | not run |

**Android status:** §2 and §3 are compiled and green — `BUILD SUCCESSFUL`, 510 tests, 0
failures — and committed. **Not device-tested.** §4 (populating `method` against the
eight-value vocabulary) is not written yet.
