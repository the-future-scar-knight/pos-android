# Money parity: the three open items, closed on both clients — 2026-08-16

The web session replied to `PROMPT-to-web-2026-08-16.md` and pushed back on three things. All
three are now resolved **in both codebases in one pass**, rather than being documented and left
for whoever next opens the file. This is the Android-side record of what changed here and why.

Web side: `ckachale14-hash/portionspot-pos`, branch `money-cash-basis`, PR #6, and
`docs/HANDOFF-to-android-5.md` in that repo for the full reply.

---

## 1. `costedRevenue` meant two different things — no Android change needed

`SaleMargin.costedRevenue` here is the costed lines' take AFTER their share of the whole-sale
discount, so `costedRevenue − lineProfit` resolves to the cost of goods and nothing else. That is
what `CashBasis.contributionOf` depends on when it computes `saleCogs`.

The web's `computeSaleMargin` returned the figure GROSS of that share and took it off inside
`profitTotal` instead. Same profit to the cent, different `costedRevenue` — so its `cogs` came out
as `cost + discountShare`, booking every discount the shop gave away as stock it had bought.

**The trap worth remembering: `costedRevenue − cogs == grossProfit` holds on BOTH bases.** The
identity written into this object specifically to catch a wrong-basis subtraction is blind to this
one. It needs a test that names the two figures apart, and the web now has it.

**Ours was the correct definition and is unchanged.** The web adopted it: `costedRevenue` is now
discount-net there too, with the gross figure surviving as `costedGoodsValue` for the coverage
comparison only.

## 2. `status = 'refunded'` — the premise was wrong, the gap it pointed at was real

The brief asserted that a partly-refunded sale carries `status = 'refunded'`. **Neither client
writes that status onto a sale.** `createRefund` here is a linked reversal that deliberately never
edits the sale — `CashBasis`'s own header says so — and the web's `createRefund` does not either.
`RECEIPT_STATUSES` and `ExpectedDrawer`'s comment both describe a status nothing produces. If this
went to Ryan as money currently vanishing from cash-ups, that needs walking back.

**But the two filters really were inconsistent, and that is now fixed here:**

`SaleDao.observeAllSaleMargins` — the input to `CashBasis` — filtered `status = 'completed'`, while
`SaleDao.drawerReceipts` filtered `RECEIPT_STATUSES`. Two filters answering the same question about
the same row, disagreeing. A sale carrying `refunded` would have had its CASH counted toward the
expected drawer while its REVENUE AND PROFIT vanished from the books: the drawer would balance
*perfectly*, against takings that appeared in no revenue figure anywhere. A shortage announces
itself; that would not have.

Changed:

- `observeAllSaleMargins(businessId, statuses)` now takes the status set.
- `PosRepository.cashBasisSalesFlow` passes the same `RECEIPT_STATUSES` constant `expectedDrawer`
  already passes — one constant, both sites.
- The web made the matching change (`isReceipt` in `AppContext.completedSales`, `salesInRange`, and
  every `cashBasisFor` input) in the same pass, so neither client is ever alone on it.

**Deliberately NOT changed:** the other fifteen `status = 'completed'` queries in `PosDao.kt` — the
7-day chart, the discount list, the summary rollups. They are billed-basis read models, not the
pair that was inconsistent, and rewriting sixteen SQL strings without a device in hand is how a fix
becomes an incident. Worth a sweep when someone is next in that file with a phone.

## 3. The stale per-till migration — already right here, nearly broken there

The brief asked the web to delete `20260809100000_shift_per_till.sql`. **It must not be deleted.**
It is the only migration in the history that creates `cash_sessions.till_code` and
`refunds.session_id`; `20260810120000_shared_money_semantics.sql` drops the indexes but re-adds
neither column. Both were confirmed present in prod (`klkfynokcjunqvbkywfk`) by querying
`information_schema` on 2026-08-16. A fresh database replaying a history without that file would
have no column telling a cash refund which drawer paid it out.

Only the abandoned `uq_cash_sessions_one_open_per_till` index was removed there, with a header
explaining the supersession so the next reader does not repeat this session's conclusion.

**Nothing to change on this side.** `CashSessionMerge.kt` and `DaySession.kt` already document and
assume the shop-wide `uq_cash_sessions_one_open` index; `till_code` is only ever recorded as which
device opened a shift, never as part of a session's identity. The Android code was never wrong
about this — only the reading of the migration file was.

## 4. `uncollected` was worded as though it were a debt — fixed here

`Period.uncollected` is windowed on the sale's day and reads `amountPaid` alone, so it is reduced
by neither a later refund nor a later repayment. That is correct and matches the never-restate rule
everywhere else. But three sites in `PosUi.kt` called it "sold on credit and not yet paid", which
is false for any past window the moment the customer settles up — yesterday's figure stays whole
forever.

They now read **"left the till unpaid — it counts on the day the money comes in"**, and the
`Period.uncollected` KDoc states outright that the field is not "still owed", that no screen may
word it that way, and that the customer ledger is what answers that question. The web uses the
same wording.

---

## What is NOT done

- None of this is device-tested. It is compile-green and unit-green on both sides.
- Android still does not write `credit_txns.method`. Unchanged, still queued.
