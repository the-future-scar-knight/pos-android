# Device test — round 3, 18 August 2026

Supersedes round 2. Everything you have confirmed passing is stripped out; what is left is the
fixes made since, plus what round 2 never reached.

**Both clients point at the throwaway project (`lvaxbbobmounfxskooiw`).** The schema work is
already applied to both projects — no SQL to run this time.

---

## Confirmed passing — not repeated

Your results, 17–18 August:

- **The refund rewrite (round 2 §A).** Debt-first settlement, on every case you tried.
- **Split refunds and non-cash payouts (round 2 §B).**
- **Cash movements across two phones.** An expense and a float top-up both reached the second
  phone. A sale with a new customer, $2 of change left on it, paid out **on the other phone**,
  and both agreed. That was the whole of G and H.
- **Quote not refundable; Receipts and Dashboard agree (round 2 §E1, §E2).**
- Round 2 §E3 (midnight rollover) and §E4 (top products inflated by discounts) are known broken
  and cosmetic. Skip them.

---

## A · The day close, again — this failed last time

Closing on one phone still let you close on the other. Not sync lag: the count is written onto
that day's **shift** row, and when the day had no shift the step was skipped without a word. The
close still recorded locally and still moved cash to the safe, but `day_closes` never syncs, so
the other phone had nothing to read. The shift is now created when it is missing.

| # | Do this | Expect |
|---|---|---|
| A1 | Phone A (cashier): close the day — count, move the excess to the safe | Records as before |
| A2 | Sync phone A, **then** sync phone B | — |
| A3 | Phone B: open the Cash screen | The button reads **"Day closed"**, not "Close the day" |
| A4 | Phone B: tap it | Counted, expected, short/over, moved to safe, and **who counted it**. No count field, no confirm button |
| A5 | Phone B: ring up a sale, sync both | Both phones still agree on till and safe |
| A6 | Z-report expected drawer vs the figure the close dialog shows before you count | They agree — calculated independently on purpose |

**If A3 still offers to close**, tell me the till and safe figures on each phone, and whether you
synced phone A *after* closing. That distinguishes two different causes.

## B · Voiding a refund — new, never tested

A void un-restocks the goods, so the customer has them again and owes for them again.

| # | Do this | Expect |
|---|---|---|
| B1 | Sell $100 on account, take $40 cash. Refund in full | $60 clears the debt, $40 hands back |
| B2 | **Void** that refund | Stock comes back off the shelf |
| B3 | Check the customer's two balances separately | They owe **$60 again**, and the shop owes them **nothing** |
| B4 | Check their overall balance | $60 owing |

**B3 is the real test, not B4.** The two old errors cancelled to the cent, so the *net* balance
looked perfectly right while both halves were wrong. Read the two balances separately.

## C · Money moved in the BROWSER reaching the phone — new

The web keeps a safe too and writes a transfer as one row. The phone read all of these against
the till, so a bank deposit made in the browser left the phone's drawer looking short by the
whole deposit, and a drop or top-up broke the phone's **total** cash on hand.

Do each in the **web**, then sync the phone.

| # | In the browser | Expect on the phone |
|---|---|---|
| C1 | A **bank drop** (drawer into the safe) | Till **down**, safe **up**, total unchanged |
| C2 | A **float top-up** (safe into the drawer) | Till **up**, safe **down**, total unchanged |
| C3 | A **bank deposit** | **Safe down. The till does not move at all** |
| C4 | Total cash on hand, both clients | The same figure |

## D · The refund dialog — the two things you reported

| # | Do this | Expect |
|---|---|---|
| D1 | Refund $100 of goods, type **50** in "Paying back now", do NOT press Add | Button reads **"Pay back $50.00"** |
| D2 | Press it | The $50 is recorded, not silently dropped |
| D3 | Refund a fully-unpaid credit sale | Button reads **"Record refund"** — nothing crosses the counter |
| D4 | Refund $100: add $40 cash, then add $30 EcoCash | Both listed and removable; the button tracks the running total |

## E · The Z-report — card and mobile money now adds up

| # | Do this | Expect |
|---|---|---|
| E1 | Sale part cash part EcoCash, refund part of it to EcoCash, open the Z-report | A **"Card and mobile money"** card: taken, reversed, **net** |
| E2 | Check the net against your actual wallet | They agree. The old screen showed gross only, and mentioned the reversal in a sentence that was never subtracted from anything |

## F · Shop policy follows the shop, not the phone — new

The discount cap and variance threshold were device-local, so two phones enforced different
rules and the browser's setting never reached the till at all.

| # | Do this | Expect |
|---|---|---|
| F1 | Set the **max item discount** on phone A, sync both | Phone B enforces the same cap |
| F2 | Set it in the **browser**, sync the phone | The phone picks it up |
| F3 | After F1 and F2, check bank details, VAT number, receipt header | **All untouched.** Only the three policy fields travel |
| F4 | Change the **variance note threshold**, close a day past it on the other phone | It asks for a note |

## G · A capped refund crossing devices — new

| # | Do this | Expect |
|---|---|---|
| G1 | On the phone, refund a $100 sale that only collected $40, paying back nothing yet | $60 clears the debt, $40 payable |
| G2 | Sync, then record a payout against it on the **other phone** or the web | It offers **$40**, not $100 |

## H · Small things you asked for

| # | Check | Expect |
|---|---|---|
| H1 | POS: switch to list view, go to Inventory, come back | **Still list view** |
| H2 | Expenses list | Rows read **"Pending"** or **"Approved"** — it used to say "Posted" |

## I · Still untested — the browser

Round 2 §D was never reached. Lower priority than A–C, but none of it has ever been checked.

| # | Do this | Expect |
|---|---|---|
| I1 | Same window on phone and web Dashboard | Same collected, revenue and profit, to the cent |
| I2 | A **cash** debt repayment on the phone, sync | The drawer moves by it on **both**, and **once** |
| I3 | Refund a sale in the web, sync the phone | Reverses on the **refund's** day, not the sale's |
| I4 | A cash expense on the phone, then the web's expected drawer | Moves by it **once**, not twice |
| I5 | An **EcoCash** debt repayment on the phone, sync, read the customer's ledger in the web | Says **EcoCash**, not blank |
| I6 | Revoke a cashier's refund permission in the web, sync | The button goes away on the phone |

## J · Will look broken. Is not.

- **Alerts do not cross devices, and cashier requests are still one device.** The tables now
  exist on both databases, but no client code reads them yet — infrastructure only, by your
  instruction.
- **A synced movement loses its fine category.** A drawing and a petty spend both arrive on the
  other phone as "money out". The amount, direction and reason text are exact; the heading is not.

---

## What to write down

The screen, the window, the numbers you expected, the numbers you got, and **which phone, and
whether it was before or after a sync**. "Phone B shows till 140, phone A shows 100, both synced"
can be chased. "The cash looks wrong" cannot.
