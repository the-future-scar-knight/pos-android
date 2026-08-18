# Device test — round 2, 17 August 2026

Supersedes `DEVICE-TEST-2026-08-16.md` and `DEVICE-TEST-2026-08-17.md`. Everything you already
passed has been stripped out; what is left is untested, changed, or new.

**Point both clients at the throwaway Supabase project (`lvaxbbobmounfxskooiw`), not production.**

---

## Already passed — not repeated here

Confirmed working on 16–17 August. Do not re-run unless something else looks wrong.

- Refund of a same-day cash sale reverses profit correctly, and the goods go back on the shelf.
- A sale on account does not move revenue; the debt appears in Change & Credit.
- Z-report expected drawer counts a **split tender** correctly ($50 cash + $30 EcoCash moves the
  drawer by $50), and reacts correctly to cash refunds, EcoCash refunds, cash expenses and
  till→safe transfers.
- A **cashier** can reach the Cash screen, close the day, top up the float and record money in, and
  cannot see take-money-out, the profit split, or owner money in/out.
- Owner drawings do not reduce profit **on the phone that made them**.
- A sale rung up on one phone reaches the other phone after a sync.

VAT (old section D / A8) is parked — you are not charging it.

---

## A · The refund rewrite — behaviour has CHANGED

This is the biggest change and it is the one that had your till at **−$60**. A refund now settles
**debt before it pays money**: the unpaid part of a sale is cancelled off the customer's account,
and only what the shop actually collected can be handed back.

One phone, no cloud. Product at **price $100, cost $60**.

| # | Do this | Expect exactly |
|---|---|---|
| A1 | Sell $100 on account, take **$40** cash now, $60 on credit. Read the Dashboard | Revenue **40**, cost **24**, profit **16**. Not a loss |
| A2 | Refund that whole sale | The dialog offers **$40 to hand back**, and says **$60 cleared off their account**. Not $100 |
| A3 | After it: check the till, the profit and the customer | Till goes down by **40 only, never negative**. Profit returns to where it started. Customer owes **nothing** and is owed **nothing** — no more two live balances netting to zero |
| A4 | Sell $100 entirely on credit, nothing paid. Refund it in full | **Nothing to hand back** — the dialog says so in words. Stock returns, the debt clears, revenue and profit do not move |
| A5 | Sell $100 on account with $40 paid. Refund **half** the goods ($50 worth) | It comes **off the debt**, and pays out **nothing**. Debt drops to $10. Cash does not cross the counter to someone who still owes for that receipt |
| A6 | Then refund the other half | Clears the last $10 of debt and hands back the **$40** they actually paid |
| A7 | Fully-paid $100 cash sale, refunded in three goes: 40, 40, then try 40 again | Third gives back **$20 only**. Never more than the sale |

**A8 — the one you skipped.** Ring up a cash sale **yesterday** (change the device date if that is
faster), then refund it **today**. Expect today to read collected 0, refunded 100, profit −40, and
**yesterday to be completely unchanged**. A refund lands on the day the goods came back, never on
the day of the sale. If a past day's figures move, stop and screenshot.

## B · Split refunds and non-cash payouts — new

| # | Do this | Expect |
|---|---|---|
| B1 | Refund a sale paid $50 cash + $30 EcoCash. In the refund dialog add **two** payouts — some cash, some EcoCash | Both are listed, each removable. The refund records both tenders |
| B2 | Check the till after B1 | It drops by the **cash** part only. The EcoCash part never opened the drawer |
| B3 | On a customer you owe change, tap **Pay out change** | There is now a **tender picker**. Choose EcoCash |
| B4 | Check the till after B3 | It does **not** move. Before this, an EcoCash payout took money out of a drawer it had never been in, and the day close booked the difference as a loss |
| B5 | Ring up a sale paid entirely by **card or EcoCash**, exact amount | There is **no "Record change given" button**. It only appears when cash was tendered |

## C · Two phones, one shop — the fix for what you found

**This is the section that failed last time.** Cash movements were pushed to the cloud and never
pulled back, so day closes, drawings and expenses reached Supabase and never reached the second
phone. Attack this hardest.

Sign both phones into the same project. Sync after each step, on both.

| # | Do this | Expect |
|---|---|---|
| C1 | Phone A: record an owner **drawing** from the till. Sync both | Phone B's till goes **down by it**. This is your test H, which did nothing across devices before |
| C2 | Phone A: record a **cash expense**. Sync both | Phone B's till goes down by it, **once**. Not twice — the expense record and its cash movement are separate rows and only one of them moves the drawer |
| C3 | Phone A: **top up the float** from the safe. Sync both | Phone B's till goes **up** and its safe goes **down**, by the same amount. **Run the SQL below first** |
| C4 | Phone A (cashier): **close the day** — count the till, move the excess to the safe. Sync phone B | Phone B's till and safe both match phone A's, to the cent |
| C5 | Phone B: open the Cash screen | The button reads **"Day closed"**, not "Close the day" |
| C6 | Phone B: tap it | It shows the record — counted, expected, short/over, moved to safe — and says **who counted it**. There is no count field and no confirm button |
| C7 | Phone B: ring up a sale, then sync both | Both phones agree on expected cash again. This is the exact sequence that gave you two different numbers |
| C8 | Compare the **Z-report expected drawer** with the figure the close dialog shows before you count | The two agree. They are calculated independently on purpose |

### ★ C3 needs one SQL statement run FIRST, on every project both clients point at

The shared database could say "money went **into** the safe" and had no word for "money came
**out** of it". So a float top-up or a safe-funded expense went up as a plain payout and landed
against the **till** on every other device — that phone's till too low and its safe too high by the
same amount, with cash on hand still adding up to the cent, which is exactly why it was invisible.

The fix is the missing word. Run this on the throwaway project (and on production before this build
ever points at it):

```sql
alter table public.cash_movements drop constraint if exists cash_movements_type_check;
alter table public.cash_movements add constraint cash_movements_type_check
    check (type = any (array['pay_in','pay_out','drop','petty','float_topup','safe_in','safe_out','bank_deposit']));
```

**Drop then add, not just add** — the constraint already exists under that name with the old
seven-word list, so a bare `add` is swallowed as a duplicate and `safe_out` keeps being rejected.

**Order matters and the failure is loud.** A CHECK violation fails the **entire push batch**, not
the offending row. Install this build against a project that has not had the statement run and
**cash stops syncing altogether**. Schema first, then the app.

**The web needs telling too.** It will start seeing a movement type it has never met. Android
treats an unrecognised type as money moving at the till in the direction it was written, rather
than dropping it; the web should do something equally forgiving, or better, learn the word.

## D · Phone and browser together — still untested

Everything here is untested against the web. Lower priority than C.

| # | Do this | Expect |
|---|---|---|
| D1 | Same date window on the phone and the web Dashboard | The **same collected, revenue and profit, to the cent** |
| D2 | Ring up a sale on the phone, sync, open the web | The sale, its lines, its tenders and its stock movement all present |
| D3 | Take a **cash** debt repayment on the phone, sync | The expected drawer moves by that amount on **both**, and **once**. A double count here looks exactly like an honest till being over |
| D4 | Refund a sale in the **web**, sync to the phone | The phone's revenue reverses **on the refund's day**, not the sale's |
| D5 | Post a **cash expense** on the phone, sync, read the web's expected drawer | It moves by it **once**. Worth checking specifically — if the web derives an expense's drawer effect from the expense record rather than from the cash movement, it will count twice |
| D6 | Edit a product's price in the web, sync. Then try editing it on the phone | It reaches the phone. The catalogue is **pull-only** — the till is not supposed to publish it |
| D7 | Open a shift on the phone while one is open in the web | You should **not** get two. One shop, one drawer — they merge |
| D8 | Revoke a cashier's refund permission in the web, sync the phone | The refund button goes away on the phone |
| D9 | Take an **EcoCash** debt repayment on the phone, sync, read the customer's ledger in the web | The row says **EcoCash**, not blank. The phone never wrote a tender against a credit row before, so this was blank on the web every time |
| D10 | Raise a refund in the **web** that still owes the customer money. Sync the phone, open it, tap **Record payout** | You can actually pay it out. A refund raised in the browser used to arrive on the phone with **nothing payable**, so the phone silently refused to hand over a cent |

## E · Older bugs — confirm the fixed ones stayed fixed

| # | Check | Should be |
|---|---|---|
| E1 | Raise a quote, find it in Receipts, try to refund it | **No refund possible.** Refunding a quote would restock goods that never left |
| E2 | On a day with a credit sale, compare the Receipts "Today" figure with the Dashboard's collected | They **agree**. They used to disagree by the whole credit sale |
| E3 | Leave the app open across midnight, then look at "Today" | **Known wrong, still open.** Expect it to be sticky |
| E4 | Top products, on a day with discounts | **Known wrong, still open.** Bars are inflated by the discounts given — confirm it is only cosmetic |

## F · Will look broken. Is not.

- **Alerts do not cross devices.** No shared notification table on either client. An alert raised on
  a cashier's phone stays on that phone.
- **The day-close detail and the owner-money-vs-loan split stay on the phone.** No cloud column
  expresses them. The counted cash, expected cash, float target and amount moved to the safe **do**
  travel — which is what makes C5 and C6 work.
- **A synced movement loses its fine category.** A drawing, a petty spend and a safe withdrawal all
  arrive on the other phone as "money out". The amount, the direction and the reason text are exact;
  the heading is not.
- **Cashier requests only work on one device.** Built, but local-only.

---

## What to write down

For anything that fails: the screen, the window (today / yesterday / this week), the numbers you
expected, the numbers you got, and whether it was **before or after a sync, and on which phone**.
"The profit looks wrong" cannot be chased. "Phone B shows till 140 and phone A shows 100 after
syncing both" can.
