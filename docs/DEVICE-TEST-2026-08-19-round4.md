# Device test — round 4, 19 August 2026

APK: **`portionspot-pos-2026-08-19-r6.apk`** (debug build, same key as r5 — installs over the top).
Both phones point at the throwaway project (`lvaxbbobmounfxskooiw`). No SQL to run.

---

## Confirmed passing — not repeated

Your results, 18–19 August:

- **Voiding a refund** (round 3 §B). Both balances read correctly.
- **Round 3 §D.** Worked.
- **Grid view / list view.** Fixed.

Still not reached: the expense check, and everything browser-side.

---

## Read this before §A

Your database settled what happened, and it is worth knowing what you are re-testing.

`cash_movements` still holds the real day close from **17 August** — $61.00 out of the till, into
the safe, "Day close — takings to safe", by Ryan. The `cash_sessions` row for that same trading
day says `counted_cash = NULL`, note *"Closed automatically at end of trading day"*. Same story on
18 August. **The shop counted the drawer and the shared row claims nobody did.**

What did it: the pull is last-writer-wins on `updated_at`, and the phone holding the money lost.
Phone A counts → writes the count. Before that reaches the database, phone B — which has not
pulled and still sees the day as open — hits midnight, rolls the day over, and publishes the
*same* row with `counted_cash = null` and a newer stamp. Phone A pulls it, sees it is newer, and
overwrites its own count with the null. That is why syncing twice changed nothing: the second
sync just re-confirmed an erased row.

Two things changed:

1. A wire row **with no count can no longer un-count a local one**, and the rescued count is
   re-published in the same pass — so the phone that kept the figure repairs the database for the
   other one.
2. A day is now resolved by its **counted** session, not the oldest one. A day can hold two
   sessions; the merge only ranks *open* ones, so once a count closed one, the leftover uncounted
   session outranked it forever.

★ **The 17 and 18 August rows in the database are still wrong and will NOT repair themselves** —
the count was already wiped off the phone that took it. Fixing those two rows means a manual
`UPDATE`, and `counted_cash` cannot be reconstructed from the movements (only `moved_to_safe` = 61
survives). Say the word and I will write it; I have not touched your data.

---

## A · The day close — third attempt

Do this on a **fresh day** if you can. If you are testing on a day already closed, close it on
neither phone first and tell me.

| # | Do this | Expect |
|---|---|---|
| A1 | Ring up a sale on **each** phone, sync both | Both agree on till and safe |
| A2 | Phone A (cashier): close the day — count, move the excess to the safe | Records as before |
| A3 | Sync phone A, **then** sync phone B | — |
| A4 | Phone B: open Cash | Button reads **"Day closed"**, not "Close the day" |
| A5 | **Phone A: open Cash** | Also reads **"Day closed"** — this is the half that failed last time |
| A6 | Both phones: tap it | Counted, expected, short/over, moved to safe, **who counted it** |
| A7 | Sync both again, twice | Still "Day closed" on both. Nothing flips back |

**A5 is the new test.** Last time the phone that *did* the closing was the one offering to close
again, which is the reverse of what everyone assumes goes wrong.

**If it fails**, before doing anything else tell me: which phone shows what, the till and safe
figure on each, and whether the day rolled over midnight between the close and the check.

## B · The refund amount is now capped

The till always clamped this, so no money was ever over-paid — what was wrong is that the screen
let you type a number it had no intention of honouring.

| # | Do this | Expect |
|---|---|---|
| B1 | Sell $100 on account, take $40 cash | — |
| B2 | Refund in full. Look at the payout field | Label reads **"Paying back now (max $40.00)"** |
| B3 | Try to type **500** | It stops at **40.00**. You cannot get past it |
| B4 | Type 25, press Add, then look at the field again | Max now reads **$15.00** — what is left, not the total |
| B5 | Confirm | Button says "Pay back $40.00" and hands back exactly that |
| B6 | Refunds screen → a refund the shop still owes → **Record payout** | Field is capped at what is **still owed**, and says so |
| B7 | On a refund already half paid, check B6's ceiling | It is the remainder, not the original amount |

## C · The barcode scanner — was completely broken

It was failing two ways at once, and both were fatal.

The camera hands over a row-**padded** image (a 1280-wide frame can arrive 1536 bytes per row).
It was being passed to the decoder as if the rows were tight, so every row after the first was
read at an offset — a sheared picture, which never decodes. The short final row also threw, and
the error handler reported it as "no barcode in this frame", every frame, forever.

Second: analysis frames come out in the **sensor's** orientation, a quarter turn from what you
see. A barcode you hold horizontally lies *vertically* in the buffer, across the scan lines. 1D
readers only scan across. That is exactly why QR seemed to be the only thing it ever caught.

| # | Do this | Expect |
|---|---|---|
| C1 | Inventory → scan → point at any product barcode (EAN-13 on a tin, packet, bottle) | Decodes within a second or two |
| C2 | Turn the phone sideways and scan the same barcode | Still decodes |
| C3 | Scan a **narrow** barcode — a small item, a printed label | Decodes; tell me if this is the one that struggles |
| C4 | Scan a QR code | Still works (it always did) |
| C5 | POS screen → scan an item that is in the catalogue | Adds it to the cart |
| C6 | Scan an item that is **not** in the catalogue | Says so — does not silently do nothing |

**If C1 still does nothing**, tell me whether the camera preview itself appears. A black box and a
live preview that never decodes are two entirely different faults.

## D · The expense you have not checked yet

| # | Do this | Expect |
|---|---|---|
| D1 | Record a cash expense on phone A | Till drops by that amount |
| D2 | Sync both | Phone B's till drops by the same amount |
| D3 | Dashboard on both | Net profit drops by it. Gross profit does **not** |

---

## Not in this build

The **notifications** and **cashier requests** wiring is written but deliberately left out of r6,
so it cannot muddy §A. It ships in r7.

## Known and skipped

- Round 2 §E3 (midnight rollover display) and §E4 (top products inflated by discounts) — cosmetic.
- Scanning a **box** barcode and having it price as a box rather than a piece: not built. It needs
  a second barcode column on the item and a cloud column to match. See the progress note.
