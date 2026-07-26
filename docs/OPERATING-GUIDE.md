# PortionSpot POS — Operating Guide

> **Read this first.** This describes how the app is *built to behave*, not how it has
> been proven to behave. The code compiles and the logic holds together, but almost none
> of it has run on a real phone yet. Treat this as the checklist to test against —
> especially the receipt-edit math, the expense and order accounting, and whether two
> devices genuinely stay in step.

- **Branch:** `multi-account-login-mm-undo`
- **Schema:** v30
- **State:** compile-verified (not yet device-tested)
- **Cloud:** Portionspot Motors (Supabase)

Every feature of the point-of-sale, in the words you use at the counter — from ringing a
sale to what the admin sees on the second phone.

---

## 1. Getting in

The app runs on its own first, and gains team features when you connect it to the cloud.

- **Local first.** On a fresh install the till works immediately — no account needed, an
  optional device PIN, the POS as home with the admin panel one tap away.
- **Connect the cloud** (your own Supabase) to turn on syncing and multiple users. People
  sign in with an email and password; each is an admin or a cashier.
- **The lock screen** shows the accounts already on this phone for a quick PIN unlock.
  When you are online it adds an **Other staff** section pulled from the shop roster — tap
  any name, enter *their* password once, set a device PIN, and you are in. No one can step
  into another person's account without that password.

## 2. Who can do what

Permissions are set per person, not per role, so you tune each cashier to what you trust
them with.

Every cashier has eight switches: **void sales, process refunds, edit receipts, give
discounts, manage inventory, manage expenses & orders, view reports,** and **manage
staff**. An admin always has all of them.

A switch is enforced in two places at once — the button is hidden and the action itself is
blocked — so a stale screen can't slip something through. When you change a cashier's
permissions, their phone picks it up on its own the next time it comes to the front or
syncs.

> **Removing someone** deactivates them: they can no longer sign in and drop off the roster
> and the lock screen, but every sale they ever made stays in the books, attributed to
> them. Nothing is deleted, and you can reactivate them later.

## 3. Ringing a sale

Four kinds of product, one cart, and a checkout that copes with however the customer pays.

- **Box / Set / Piece** — the everyday goods, sold as whole units, priced the way you set
  them.
- **Measured** — liquids, weight, length. Enter a decimal amount like 2.5 kg; it prices per
  unit and draws stock down by the exact amount.
- **Any tender** — cash, card, mobile money, a split across several, or put on credit. A
  cashier without the discount permission simply can't discount.

## 4. Change & credit

The part that used to lose money quietly. Every imbalance is now tracked to a named
customer, in whichever direction it runs.

| At the counter | What the app records |
| --- | --- |
| Customer overpays | The shop owes them the difference (change owed). |
| Cashier hands back too much change | The customer owes the shop the excess (a debt). |
| Paying change out later, more than owed | The part owed is settled; the excess becomes the customer's debt. |
| Customer overpays a debt | The shop now owes them the surplus back. |
| Walk-in, no account, money left uneven | Attach a customer, or book it as a till shortage / overage in the audit log. |

Each customer shows a single **net position** — "owes you $2.00" — with the two underlying
balances kept beneath it so the ledger stays auditable.

## 5. Receipts

One receipt per sale, editable for a short window, with a history that can't be quietly
rewritten.

- **Tap any receipt** to see its line items, totals, payments, and any change or balance
  owing.
- **Edit in place** inside a window the admin sets (30 minutes by default): add or remove
  items and the maths, the stock, and any payment difference all recompute — the difference
  flows through the change and credit ledger, never vanishing.
- **The history stays honest.** Every edit writes a permanent audit entry — what changed, by
  whom, old total to new. You see one clean receipt; the trail lives underneath it. After
  the window, the receipt locks.

## 6. Customers & credit limits

Setting a figure that carries real risk isn't a switch you hand out — it's a request the
admin answers.

An admin sets a customer's credit limit directly. When a **cashier** tries to raise one,
the app doesn't apply it — it files a **request**. The admin gets a prompt notification,
approves it (confirming or changing the amount), and the **admin's own phone carries out
the change**, which then syncs to every device. The cashier is told the outcome, and while
a request is in flight the phones poll faster so the answer feels immediate.

> **Discounts work the opposite way on purpose.** They aren't approved — anyone with the
> discount permission gives them freely, they're simply recorded, and the admin has a
> **Discounts given** view to review later. No one is interrupted for every markdown.

## 7. Suppliers & orders

Restocking is money moving into inventory — handled as such, so it never distorts your
profit.

- **Place an order** against a saved or new supplier: quantities, unit cost, an optional
  sell price, and a rough arrival date. It's paid from cash on hand, with the same options
  if cash is short.
- **Incoming goods show a pending mark and can't be sold** until you confirm they've arrived
  — the app prompts you near the date, or you mark it by hand.
- **Buying stock is cash becoming inventory, not an expense,** so it doesn't hit profit
  twice — the goods only affect profit later, as cost of goods, when they sell.

## 8. Expenses & the accounting

A cash drawer you can trust and a profit figure that's always derived, never a pot someone
subtracts from.

**Cash on hand** is the opening float plus cash sales, less cash paid out. **Profit is
calculated** — revenue, minus cost of goods, minus expenses — so it can never drift out of
sync with reality.

Expenses need the admin's approval; a recurring one like rent is approved once and posts
itself each period. If the drawer can't cover an expense, you choose: **take what's there**
(the rest becomes money owed), **cover it yourself** (recorded as your own contribution),
or **abort**. Every entry is recorded both sides, so the books balance and each shortfall
is explained.

## 9. What the admin sees

The alerts that reach the admin's phone — now aimed at the right person and firing across
devices, not just stored.

| Severity | Alert | When |
| --- | --- | --- |
| Critical | Out of stock | an item has hit zero |
| Warning | Low stock | an item dropped below its threshold |
| Warning | Refund still owed | escalates the longer it goes unpaid |
| Warning | Payment to verify | a mobile-money receipt awaiting a match |
| Note | Large sale | a sale above the amount you set |
| Warning | Aging debt | a customer balance sitting unpaid |
| Critical | Over credit limit | a customer has gone past their ceiling |
| Warning | Order arriving | a supplier order at or past its date |
| Request | Credit-limit request | a cashier needs a limit approved |
| Critical | Not synced | local data hasn't reached the cloud |

Alerts are **addressed** — admin matters go to the admin phone, cashier matters to cashiers
— and read state follows you, so clearing one on either phone clears it on both. Each
device still buzzes its own heads-up.

## 10. Sync & running two devices

Instant on the way up, thrifty on the way down, and built so two phones can't collide.

- **Saving is instant** on the device. Uploads fire within a couple of seconds of any money
  action; if you're offline they queue and flush the moment you reconnect.
- **Downloads are paced to save data** — every 45 seconds while the app is open, immediately
  when you open an admin screen, and every 15 minutes in the background. No always-on
  connection.
- **Receipt numbers carry a per-phone code** (like `K7Q-0013`) so two tills never mint the
  same one, with a check that warns you if the cloud ever refuses a sale.
- **Everything travels:** sales, customers, credit and change, refunds, mobile-money,
  products, expenses, the cash ledger, suppliers, orders, notifications, the audit trail,
  and staff requests.

> One thing to confirm on each phone when you set up the second one: open the sync panel
> from the Wi-Fi icon and check it shows a recent **last upload** and a waiting count that
> falls to zero. A count that never clears means that phone's upload switch is off.

---

*The honest footnote.* This guide reflects the build as merged: two work streams combined
— permissions, roster switching, cross-device notifications, and the staff-request
round-trip — on top of the earlier run of fixes and features. It is verified to compile
and, where tests exist, to pass them. It is not yet verified against a real counter, a real
customer, or a real second phone. That part is the next step, and it's yours.
