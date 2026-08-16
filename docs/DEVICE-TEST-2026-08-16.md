# Device test — 2026-08-16 accounting pass

Build: commit `e616c94` on `sync-repoint-web-schema`. 510 unit tests green, never run on a phone.

**Test against the throwaway project `lvaxbbobmounfxskooiw`, not prod.** Prod has zero
transactional rows and should stay that way.

Two phones where it says two phones; one is fine for everything else. Write down anything that
disagrees with the "expect" line — a wrong number here is the whole point of the exercise.

---

## A. The headline change: a refund is no longer profit

This is the one that changes every profit figure in the app. Do it first and do it on a
sale you ring up fresh, so you know the numbers.

1. Note today's **Collected** and **Profit** on the Dashboard.
2. Ring up a cash sale of a costed item — something you know the cost price of. Say it sells
   for $100 and cost you $60.
3. Dashboard now: **expect** Collected up by $100, Profit up by $40.
4. Refund the whole sale, paying the customer back in cash.
5. Dashboard now: **expect** Profit back to where it started in step 1. Collected shows the
   $100 that arrived AND $100 refunded on its own line — it does not silently vanish.
6. Check the item is back on the shelf in Inventory.

**Then the part that used to be broken:** go back and look at the day the sale was rung up if
it was a previous day. **Expect it to be unchanged.** A refund lands on the day you did the
refund, never on the day of the sale. If a past day's figures moved, that is a bug — report it.

## B. A partly-paid credit sale, refunded

Tests the cap. The old code would have reversed money that never arrived.

1. Sell $100 of goods to an account customer, taking $40 cash now and $60 on credit.
2. Dashboard: **expect** Collected +$40, and profit on 40% of the sale, not all of it.
3. Refund the whole sale.
4. **Expect** the reversal to take back only the $40 that was actually collected — profit
   returns to where it was, and it does not go NEGATIVE.
5. Now the odd one, so you can tell me if you want it changed: the customer will show a debt
   AND a refund owed at the same time. Nets to zero, reads as two live balances.

## C. A credit sale that was never paid, refunded

1. Sell $100 entirely on credit. Dashboard Collected: **expect** no change (nothing arrived).
2. Refund it in full.
3. **Expect** no change to Collected or Profit at all — there was nothing to reverse.

## D. VAT adds up

Only meaningful if you have VAT switched on. Skip if you don't charge it.

1. Ring up a sale with VAT on it.
2. On the Reports card, read down: Collected → Refunded → VAT → Net revenue → Cost → Profit.
3. **Expect** the column to actually add up. Net revenue = Collected − Refunded − VAT.
4. Refund that sale, then look at a window containing ONLY the refund.
5. **Expect** the VAT line to read NEGATIVE and the wording to say the VAT was handed back.
   The old build hid this and left money unexplained on the card.

## E. Z-report expected drawer — the split-tender bug

The one most likely to have been costing you money.

1. Open the day with a known float, say $60.
2. Ring up a sale paid **half cash, half EcoCash** — e.g. $50 cash + $30 EcoCash.
3. Open the Z-report.
4. **Expect** the expected drawer to be float + $50. The old build counted **$0** from that
   sale, because the receipt's payment method just says "split".
5. Now add each of these and re-check the Z-report after each:
   - a refund paid out in **cash** — expect the drawer to go DOWN by it;
   - a refund paid out by **EcoCash** — expect the drawer NOT to move;
   - a **cash expense** — expect DOWN;
   - a **till → safe transfer** — expect DOWN.
6. If you can, put more than 100 receipts through a day. **Expect** the 101st to still count.
   The old build silently stopped at 100.

## F. Cashier can shut up shop

You asked whether this works. It should, without phoning yourself.

1. Sign in as a **cashier**, not admin.
2. **Expect** the Cash screen to be reachable, showing Till and Safe.
3. **Expect** to be able to: close the day, top up the float, record money in.
4. **Expect NOT** to see: take money out, the profit split, your own money in/out. Those are
   yours alone — a cashier closing the till has no business reading how much of the drawer is
   your profit.
5. Close the day: count the till, confirm the amount to move to the safe.
6. Try to close it a second time. **Expect** it to tell you the day is already counted and do
   nothing. One count per trading day, by your own ruling.

## G. The two expected figures agree (two phones)

Deliberately two independent calculations. This checks they land in the same place.

1. Phone A: ring up a cash sale.
2. Phone B: sync, then open the Z-report and note the **expected drawer**.
3. Phone B: start a day close and note the **expected** figure it shows before you count.
4. **Expect the two to match.** If they don't, the cash mirror hasn't caught up — tell me the
   two numbers and roughly how long after the sale you looked.

## H. Drawings are not a loss

1. Note the Profit figure.
2. Record the owner taking money out, enough to go past float + capital + loans put together.
3. **Expect** Profit NOT to fall. Cash held falls, and the float/capital row goes negative to
   show you are over-drawn — that is correct and it should say so, not hide it.

---

## Known gaps in this build — not bugs, not yet written

- **Credit repayments still don't say how they were paid.** A repayment on phone A reaches the
  web but not phone B's drawer. The cloud column exists and the web populates it; we don't yet.
- **`verifyMobileMoney` on a debt** skips the overpayment-becomes-change-owed split.
- **`recordChangePayment` assumes cash**, so paying a customer out by EcoCash still moves the till.
- **Markup is not yet on the Z-report or Dashboard.**
- Deferred audit items, unchanged: top products sums gross line totals so it inflates by the
  discounts given; Dashboard count/average ignore refunds; the trading-day edge goes stale past
  midnight on a phone left open.
