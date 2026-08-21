# Device test — round 5

APK: **`portionspot-pos-2026-08-19-r7.apk`** (debug build, same key — installs over r6).
Both phones point at the throwaway project (`lvaxbbobmounfxskooiw`).

**SQL: already applied.** The `expenses` table gained seven columns on **both** projects
(throwaway and production) on 19 August. Nothing for you to run. Script kept at
`supabase/2026-08-19-expense-approval.sql`.

---

## Confirmed passing — not repeated

Round 4, your results:

- **§A the day close.** Closed on the cashier phone, synced both, closed on both, till and safe
  agreed. Three rounds to get there; it is done.
- **§B the refund cap.**
- **§C the scanner.** It decodes. Speed and steadiness are §C below, not correctness.

---

## A · The expense a cashier posts — this is the one that failed

What you saw: the cashier posted an expense, it arrived on the admin phone **already approved**,
net profit dropped, and the cashier's own copy still said pending. Two phones reporting different
profit for one shop.

The cause was not the sync. The shared `expenses` table had **no column for an approval**, so a
pushed expense arrived carrying no lifecycle at all, and the pull filled in the only reading that
made sense when the web was the only other writer: approved and posted. Correct for a browser
that has no approval step; wrong the moment a till could raise one. The table now has the columns
and the state travels.

| # | Do this | Expect |
|---|---|---|
| A1 | Cashier phone: post a cash expense | Shows as **pending** there |
| A2 | Sync cashier, then sync admin | — |
| A3 | Admin: open the expense list | **Pending** — NOT approved, and awaiting your decision |
| A4 | Admin: check Net profit and Cash on hand **before** approving | Neither has moved |
| A5 | Admin: approve it | Net profit drops. Cash on hand drops by the same amount |
| A6 | Sync admin, then sync cashier | — |
| A7 | Cashier phone: same expense | Now reads **approved**, and by whom |
| A8 | Cashier phone: till and cash on hand | Down by the expense, matching the admin phone |

**A4 is the test you skipped last time.** You checked net profit and forgot cash on hand — and
they move for different reasons, so a bug in one hides perfectly behind the other looking right.

| # | Do this | Expect |
|---|---|---|
| A9 | Admin: post an expense **yourself**, sync both | Approved immediately on both. An owner does not queue for his own permission |
| A10 | Admin: **reject** a pending cashier expense, sync both | Reads rejected on both. Neither profit nor cash moves on either phone |

## B · Cash on hand vs the till — read this, then check it

Not a bug, and worth knowing so you stop suspecting one. On a single phone:

> **Cash on hand = Till + Safe. Always.**

Same ledger; moving money between the two changes neither total. What you were comparing was cash
on hand against the **till alone**, and those differ by whatever is in the safe — which a day
close deliberately fills. The dashboard now spells it out under the figure.

| # | Do this | Expect |
|---|---|---|
| B1 | Dashboard: read the line under **Cash on hand** | "Till $X · Safe $Y — cash on hand is the two together" |
| B2 | Add them up | Exactly the cash-on-hand figure |
| B3 | Cash screen: compare Till and Safe against that line | Identical |
| B4 | Move money till→safe (top up float, or close the day), then re-read | Till and safe change; **cash on hand does not** |

★ **One real cross-device catch to check while you are here.** The **opening float is a
device-local setting and does not sync.** If the two phones were set up with different opening
floats, both will be internally consistent and disagree with each other by exactly that
difference, forever, on every cash figure. Compare the opening float on both phones (Settings)
and tell me if they differ — that is a separate fix and I would rather know than guess.

## C · The scanner, faster

Correctness passed in round 4; this is about how it feels.

The frame is now capped at 1280 wide instead of whatever the sensor offers, only the middle 42%
is decoded, and one orientation is tried per frame rather than both. Codes that carry no check
digit (Code 39, Code 128, ITF) must now read the same twice before being accepted — a smeared
scan of those can decode *cleanly* to the wrong number, and on a till that is the wrong product
at the wrong price.

| # | Do this | Expect |
|---|---|---|
| C1 | Scan an EAN-13 (most packaged goods) | Faster than r6, and inside the on-screen guide |
| C2 | Aim so the barcode is **outside** the guide band | Does not decode — that is the band working, not a fault |
| C3 | Scan the same item ten times | Same number every time |
| C4 | Scan in poor light | Tell me if it is worse than r6 — the smaller frame is the one thing that could cost you here |
| C5 | Turn the phone sideways and scan | Still decodes |

## D · Notifications and cashier requests — new, never tested

Both were entirely local until now: an alert raised on one phone stayed there, and a cashier
asking for permission was ringing a doorbell in her own hallway.

| # | Do this | Expect |
|---|---|---|
| D1 | Cause an alert on the cashier phone (drop an item to low stock by selling it) | Alert appears there |
| D2 | Sync both | Same alert on the admin phone |
| D3 | Mark it read on the admin phone, sync both | **Stays unread on the cashier phone** — read state is per person, by design |
| D4 | Cashier: attempt a discount over the cap, raise the request | Sits pending on her phone |
| D5 | Sync both | The request appears on the admin phone |
| D6 | Admin: approve it, sync both | Cashier's phone shows approved and lets her take the discount |
| D7 | Take the discount, then sync both again | Stays applied — it does not un-approve or re-offer |

**If D2 or D5 shows a sync error**, send me the wording. Nothing has ever been written to either
of those tables, so this is the first real traffic they have carried.

---

## Known and not in this build

- Scanning a **box** barcode so it prices as a box rather than a piece. Needs a second barcode
  column on the item. Check first whether your suppliers put the same EAN on the box and the
  piece — if they do, the idea does not work and it is better to know now.
- Per-person read state (`notification_reads`) exists on the database and has no local table yet.
- Round 2 §E3 and §E4 — cosmetic, still skipped.
