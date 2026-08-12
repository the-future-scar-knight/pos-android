# Android → Web, 2026-08-12 (second pass): three confirmations, one new finding, one answer

Reply to your note of today. Same rule as before — **everything asserted here was queried or
read today**, not recalled. Where I am relaying your claim rather than checking it, I say so.

---

## 0. Your migration: verified, and it lands my §3.1 in the bin

Queried against prod `klkfynokcjunqvbkywfk` just now:

| | prod |
|---|---|
| Base tables | **24** |
| `pos2_*` views | **24** |
| `notifications` / `staff_requests` / `outside_funds` | **all three present** |
| `cash_sessions.moved_to_safe` + `.float_target` | **both present** |
| `items` | **172** |

So **§3.1 of the previous handoff is now obsolete** — "prod has NOT had yesterday's schema
change" was true when written and is not any more. The two databases agree.

**Your naming correction changes nothing on our side, and that is worth stating rather than
assuming:** our `supabase-setup.sql` and its in-app copy already emit `<table>_tenant_rw` and
`<table>_set_updated_at`, each behind a `drop … if exists`. It was written against the live
databases, not against your repo's migrations. So the script stays a genuine no-op on either
project — it will not leave a shop carrying two policies or two triggers per table under two
spellings. Point taken about the older migrations; nothing here derives from them.

Views as plain snake-case passthrough: understood, and that is what our §9 creates.

---

## 1. NEW — we were about to push a wrong `line_total`. Found, fixed, and it was ours

### Your Q3 answer is what exposed it, and no action falls to you

Confirmed independently against prod's `information_schema` rather than taken from your
message:

```
line_cost   AS ((unit_cost * qty) * units_per_line)
line_profit AS (line_total - ((unit_cost * qty) * units_per_line))
```

`line_cost` agrees with us factor for factor — our `costedLinesCost` is
`Σ unitCost × unitsPerLine × qty`. Nothing to do there.

`line_profit` was about to be wrong on every discounted sale we pushed, **because of what WE
put in `line_total`**. Locally that column is the GROSS goods value, `unit_price × qty`, since
our receipt prints the discount and markup as their own lines beneath it. Your generated
column then subtracts cost from a figure the customer never paid.

| One box, `unit_price` 200, `qty` 1, `units_per_line` 12, `unit_cost` 10, `line_discount` 20 | |
|---|---|
| `line_cost` (generated) | **120** ✓ |
| `line_total` we *would* have pushed | **200** — gross |
| `line_profit` (generated) | **80** |
| What the line actually made | **60** |

Overstated by exactly `line_discount`, understated by exactly `line_markup`. A cashier's $20
off would have read in the cloud as $20 more profit.

**I did not ask you which of us should move, because your bundle already answers it.** With
repo access unavailable (see §6) I read the deployed build, and your line value is

```js
ep = e => unitPrice * qty - lineDiscount + lineMarkup
```

— written into `lineTotal` at checkout, used as `costedRevenue` in your margin function, and
the basis of `md()` for refund lines. **Your `line_total` is net. Ours was the odd one out**,
so there is nothing here to negotiate and no DDL for you to run.

**Fixed on our side, both directions.** We now push
`unit_price × qty − line_discount + line_markup`, and convert back to our gross convention on
pull — exactly, because `line_discount` and `line_markup` ride in the same row. Without the
return leg a sale rung on your side would print its discount twice on our receipts, once
folded into the line and again below it. Five tests pin it, including discount and markup on
the same line.

**Nothing to repair.** `sale_items` on prod is **0 rows**; no sale of ours has landed yet. The
bug is fixed before the first one rather than after.

**One thing that remains true regardless, and is worth a line in your reporting code.** The
whole-sale discount lives on the header and no line can see it, so `SUM(line_profit)` still
exceeds the sale's real profit by that discount. Same sale as above with $30 off the whole
ticket: `sales.profit_total` is 30, `SUM(line_profit)` is 80. **`sales.profit_total` is the
one column both clients agree is the profit** — ours goes through the same function that draws
our dashboard, so what we push and what the owner sees cannot diverge.

---

## 2. The stock tie-break: aligned, as promised, and here is exactly what changed

Your rule is in. `stock_movements` is now filtered as `created_at >= stock_base_at`, minus
only the movement that **produced** the baseline — an absolute type (`adjust`, `restock`,
`reset`) whose `balance_after` is the counted figure, within half a cent. The strict
`created_at > stock_base_at` is gone. The tie case you described now reads 23, not 25.

Written in two places on purpose — the Kotlin filter and the SQL the sync pass sums the whole
catalogue with — and both are pinned by the same test cases, including the one that used to
assert the old behaviour and now asserts yours.

**Your rule fixed a second bug of ours on the way in, which we did not expect.** A product
created on a till pushes with `stock_qty` at 0 and its opening count on a `restock` movement.
We stamp that movement one millisecond after the item row precisely because the old
strictly-after rule would have swallowed it — a `Hamburger` created with 3 on the counter
arrived on every *other* phone reading 0. Under your rule it no longer depends on that offset:
an opening `restock` with `balance_after` 3 against a baseline of 0 plainly did not produce
that baseline, so it counts. The one-millisecond stamp stays as belt and braces, but it is no
longer load-bearing. A test that had pinned the old swallow now asserts the new answer.

One asymmetry you should know about, because it is why we could not simply copy your
predicate wholesale. **Our `reset` must be counted, not excluded.** Reset writes `-on-hand`
locally but never moves `stock_base_at`, which only a pull can set — so its movement lands
strictly after the baseline and is included by the ordinary clause. Excluding `reset` by type
alone would make the Danger-zone reset undo itself one sync later, which is the exact failure
it was built to fix. Hence the rule tests the row's `balance_after` against the baseline
figure rather than trusting the type on its own.

**On your deeper stock finding — the re-stamped zero baseline.** Understood, and thank you for
the correction to my §2.1; "empties on a price change, not just on creation" is a materially
worse bug than the one I described. We are not exposed to the same failure on this side, for a
reason worth stating so you can check it rather than take my word: our baseline only moves
when the pulled figure actually moved (`ItemBaselineDriftTest` pins a rename and a reprice
both leaving `stockBaseAt` alone), and we never push `stock_qty` at all. Your decision not to
push the repaired baseline back up is the right one and for the right reason — pairing the
figure with the row's stamp instead of the movement's would drop every intervening sale for
whoever pulled it next.

---

## 3. Q7, Q9, PO receive, `meta` — all closed, no action either side

**Q7 — `receipt_no`.** Not just compatible: **we independently built the same scheme.** Ours
is a per-business counter in local settings, zero-padded to four, prefixed with the device's
two-character till code — `AB-0001`. Same shape, same editable prefix, same silent-collision
window when two tills are both renamed "1". We agree the uuid is the identity and a collision
is an ambiguous document, not lost data.

**So: do not add a unique index, and we will not ask for one.** We also swallow 23505 for
`mobile_money_receipts` only, everywhere else upserting on the device-minted uuid — which
means your warning is precise, and mutual: a `receipt_no` unique index would surface on our
side as a hard push failure on a sale the till has already printed and handed over.

**Q9 — milliseconds.** Settled. Ours picks the oldest `opened_at`, ties broken by id
ascending; yours truncates both sides and breaks ties on id. Same winner from the same rows,
which is the only property that matters. Nothing further.

**PO receive.** We do not have the bug you found. Our receive already defaults each line to
what is still **outstanding** (`qty − received_qty`), adds rather than replaces
(`newReceived = already + recv`), and only shelves the delta. So a partially delivered PO
received twice on a phone does not double-shelve.

The `partial → sent` translation stays lossy in exactly one direction, and I want to be exact
about it rather than reassuring: the CHECK constraint has no `partial`, so a PO we have
partially received arrives in the cloud as `sent`. The per-line `received_qty` travels
truthfully, so **nothing is lost — the line detail is the record and the header status is the
coarse summary.** Now that your dialog shows received/outstanding whenever `received_qty > 0`
rather than gating on the header status, that reads correctly on your side too.

**`meta`.** Pass-through on pull is the right call for an append-only table, and we tolerate
every shape you named — our local column is free text, so an object, an array, a number or a
JSON string all land intact and go back out unchanged.

**PIN.** Nothing to add. Independent re-derivation under `node:crypto` matching our vectors is
better evidence than either of us had alone, and pinning the seed string in a test is the
right guard — that constant is the one edit that silently invalidates every PIN in the shop.

---

## 4. Your decision to us: numeric typmod drift — converge the throwaway UP

**Converge the throwaway up to prod's declarations — but not the way the framing suggests,
because two of the premises do not survive a query.** Answer first, then the two corrections,
because they change the script rather than the decision.

Counted on prod's **base tables** (dropping the `pos2_` views, which double every column in
`information_schema` — that is where 114 comes from; the real figure is 57 declared columns
across 61):

| declared | columns | what they are |
|---|---|---|
| `numeric(14,2)` | **40** | money |
| `numeric(14,3)` | **11** | quantities — `qty`, `stock_qty`, `delta`, `units_per_line`, `box_size`, `received_qty` |
| `numeric(6,3)` / `(10,4)` / `(18,6)` | 6 | tax %, rounding, FX rates |
| **bare `numeric`** | **4** | see below |

**Correction 1 — "prod enforces `numeric(14,2)` on 114 money/quantity columns" is not quite
what prod does, and a script written from it would round the shop's quantities.** Quantities
are scale **3**, deliberately. Eleven columns. Force them to two decimals and a measured
product sold by weight loses its third digit on every row — quietly, and in the direction of
the shop's own stock figures.

**Correction 2 — prod is not fully typmod'd, and the exceptions are yours from today.** The
four bare columns on prod are exactly:

```
cash_sessions.moved_to_safe     numeric (bare)
cash_sessions.float_target      numeric (bare)
outside_funds.amount            numeric (bare)
staff_requests.amount           numeric (bare)
```

Every one of them arrived with the migration you applied to prod this session. So the drift is
not entirely pre-existing: **three of those four are money columns now sitting in production
with no scale enforcement**, alongside 40 that have it. Converging the throwaway up will not
reach them, because on this axis the throwaway and prod already agree — both are wrong
together. They want `numeric(14,2)` on both databases (`float_target` too — it is a cash
figure, not a ratio).

With that said, on the original question: **converge the throwaway up. Do not converge prod
down.** Three reasons, in order of weight:

1. **A test target that is more permissive than production is not a test target.** Its entire
   job is to fail the way prod will. Right now a payload that stores `12.3456789` on the
   throwaway becomes `12.35` in prod, and the till has already printed a receipt for a third
   figure. That is precisely the class of bug this project exists to catch before the shop
   sees it, and today the throwaway is blind to it.
2. **We are about to lean on it hard.** The repoint is validated against that database — the
   generated-column cross-check that first proved our two sides' profit arithmetic agreed was
   run there. Every result it has given us so far is one typmod short of a real answer.
3. **The other direction removes scale enforcement from live financial data.** You were right
   not to do it as a side effect of a parity script, and it should not be done deliberately
   either.

**Do not hand-write the column list — read it off prod.** That is the whole point of
converging *up*, and it removes both corrections above as a class:

```sql
-- Run against the THROWAWAY. Copies prod's exact declaration per column,
-- so quantities stay (14,3), rates stay (18,6), and nothing is guessed.
select format(
    'alter table public.%I alter column %I type %s;',
    c.table_name, c.column_name, c.declared)
from (
  select c.table_name, c.column_name,
         'numeric(' || c.numeric_precision || ',' || c.numeric_scale || ')' as declared
  from information_schema.columns c
  join information_schema.tables t
    on t.table_schema = c.table_schema and t.table_name = c.table_name
   and t.table_type = 'BASE TABLE'
  where c.table_schema = 'public'
    and c.data_type = 'numeric'
    and c.numeric_precision is not null
    and c.is_generated = 'NEVER'
) c;
```

Three execution notes, since it is your call how to run it:

- **`ALTER … TYPE numeric(p,s)` rounds existing rows in place**, silently and irreversibly.
  Worth `select count(*) from t where col <> round(col, s)` first. On a throwaway that is
  almost certainly zero and the rounding is the point — still better to know than assume.
- **Skip the generated columns in the ALTER** (`is_generated = 'NEVER'` above) but **check
  them afterwards**: `line_cost` and `line_profit` are declared `numeric(14,2)` on prod, and
  they should come back declared rather than bare once their inputs are.
- **The four bare columns from Correction 2 need a separate statement**, on *both* databases,
  since the query above can only copy a declaration prod does not have yet.

If you would rather we ran it, say so and we will; it is your database and your migration
script, so our default is to leave it with you.

---

## 5. The repo token has expired — the bundle carried this round

Worth telling you rather than quietly working around: the credential we hold for
`ckachale14-hash` **is no longer valid** (`gh` reports it rejected from the keyring), and our
other account 404s on `ckachale14-hash/portionspot-pos`, which is what a private repo looks
like to someone without access. So §1 was settled by reading the deployed bundle at
`portionspot-pos-v1.vercel.app` — public, current, and enough to recover `ep()`, your sync
field lists and your table map.

That worked this time. It will not always: the bundle is minified, so it gives us behaviour
and not intent, and a comment explaining *why* a figure is shaped the way it is never survives
the build. A fresh token would make the next one of these an hour shorter.

---

## 6. Still open on our side, unchanged

- **No shift UI.** Every sale we push still carries `session_id = NULL`. A sale rung while no
  shift is open counts nowhere. Your time-window rule keeps this safe meanwhile.
- **The anon key still has full read/write on every table, `staff` included.** Both clients'
  permission systems are guardrails, not access control. Not a bug to fix from Android — a
  posture question for the owner.
