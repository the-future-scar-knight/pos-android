# Device test — the money release, 17 August 2026

APK: `portionspot-pos-2026-08-16.apk` (commit `4f55718`). Web: live on `main` (`e91c5f9`).

**Point both at the throwaway Supabase project (`lvaxbbobmounfxskooiw`), not production.** Section B
writes cash movements and refunds across two clients; that is exactly the kind of test you do not
want against a real day's takings.

Everything in section A is new code that has never run on hardware. Work down in order — A is worth
more than B, and B is worth more than C.

---

## A · The money model, one phone, no cloud

Set a product at **price $100, cost $60**. VAT off unless a test says otherwise. Do each on its own
day where the test says "next day" — change the device date if you have to, it is faster than
waiting and the code reads the clock the same way.

| # | Do this | Expect exactly |
|---|---|---|
| A1 | Cash sale of one, paid in full | Dashboard today: collected **100**, revenue **100**, profit **40** |
| A2 | Sale of one on a customer's account, nothing paid | Revenue **does not move**. "Left unpaid at the till" = **100**. Stock still drops, receipt still prints, debt appears in Change & Credit |
| A3 | Sale of one, customer pays **$40** at the counter, rest on account | Revenue **40**, cost **24**, profit **16**. Not a loss |
| A4 | Next day, customer pays the remaining **$60** | Today: revenue **60**. Yesterday, re-read: **still 40**, unchanged |
| A5 | Sale of one, customer hands **$120**, book the $20 change to their account | Recognised **100**, not 120. The $20 is a liability, not takings |
| A6 | Next day, refund A1's sale in full | Today: collected 0, refunded **100**, profit **−40**. **Yesterday still reads 100 / 40** |
| A7 | Refund A2's unpaid credit sale in full | **Nothing reverses.** Revenue and profit unmoved. The debt clears |
| A8 | Turn VAT on. Sell $100 + VAT, paid. Next day refund it in full | Refund day: VAT line reads **returned**, not collected, and `collected − refunded − VAT = revenue` still holds on the card |
| A9 | Sale of $80 paid $50 cash + $30 EcoCash | Expected drawer moves by **50**. Not 80, not 0 |
| A10 | Refund A1's sale in three goes: 40, 40, then try 40 again | Third one gives back **20** only. Never more than the sale |

**The two sums are printed in words under the figures on both clients. Read them.** If the words do
not add up to the number above them, stop and screenshot — that is the whole point of this release.

## B · The two of them together

Sign the phone in against the same Supabase project as the browser, sync, then:

| # | Do this | Expect |
|---|---|---|
| B1 | Run the same window on the phone and in the web Dashboard | The **same collected, revenue and profit, to the cent** |
| B2 | Ring up a sale on the phone, sync, open the web | The sale, its lines, its tenders and its stock movement all present |
| B3 | Take a **cash** debt repayment on the phone, sync | The expected drawer moves by that amount on **both** clients, **once**. This is the one I would attack hardest — each client writes its own cash movement, and a double count here would look exactly like an honest till being over |
| B4 | Refund a sale in the **web**, sync to the phone | The phone's revenue reverses **on the refund's day**, not the sale's |
| B5 | Edit a product's price in the web, sync | It reaches the phone. Now try editing it on the phone — the catalogue is **pull-only**, the till is not supposed to publish it |
| B6 | Open a shift on the phone while one is open in the web | You should **not** get two. One shop, one drawer — they merge |
| B7 | Revoke a cashier's refund permission in the web, sync the phone | The button goes away on the phone |

## C · Older bugs — confirm the fixed ones stayed fixed

| # | Check | Should be |
|---|---|---|
| C1 | Raise a quote, then look for it in Receipts and try to refund it | **No refund possible.** Refunding a quote would restock goods that never left |
| C2 | On a day with a credit sale, compare the Receipts "Today" figure with the Dashboard's collected | They **agree**. They used to disagree by the whole credit sale |
| C3 | Leave the app open across midnight, then look at "Today" | It **rolls over**. Known to have been sticky — this one is still open, so expect it to be wrong |
| C4 | Top products, on a day with discounts | Bars are inflated by the discounts given. **Known wrong, still open** — confirm it is only cosmetic |

## D · Will look broken. Is not.

- **Alerts do not cross devices.** There is no shared notification table on either client. An alert
  raised on a cashier's phone stays on that phone.
- **The day-close detail and the owner-money-vs-loan split stay on the phone.** No cloud column
  expresses them. The day's counted cash and float target *do* go up, on the shift row.
- **A repayment synced from the phone shows no tender in the web's ledger.** The Android credit row
  has no `method` column yet. The money still moves the drawer correctly on both sides, through the
  cash movement — it is the label that is missing, not the cash.
- **Cashier requests only work on one device.** Built, but local-only. See the notes below.

---

## What to write down

For anything that fails: the screen, the window (today / yesterday / this week), the numbers you
expected, the numbers you got, and whether it was before or after a sync. "The profit looks wrong"
cannot be chased; "Tuesday shows 40 on the phone and 55 in the browser after syncing" can.
