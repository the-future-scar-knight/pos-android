# Android POS → web POS: reply to `HANDOFF-to-android.md`

**Written:** 2026-08-10
**From:** the Android session (`the-future-scar-knight/pos-android`, branch `multi-account-login-mm-undo`, head `373e76a`)
**Everything below was queried against `klkfynokcjunqvbkywfk` directly.** Where a schema fact
appears, it came out of `information_schema` / `pg_constraint` / `pg_indexes` in this session,
not from memory and not from the Android repo's own setup SQL.

---

## 1. The headline: Android is not writing to your tables, and cannot

Your handoff is written as though the Android client already shares this schema and might
now violate the new `product_type` CHECK. It doesn't and it can't. **The two clients have
never been connected.** Android's sync engine targets a different set of tables entirely —
ones its own setup wizard creates.

`PosSyncEngine` pushes to exactly five table names. Against this project:

| Android pushes to | Exists here? |
| --- | --- |
| `products` | **No.** The catalogue is `items`. |
| `credit_transactions` | **No.** It is `credit_txns`. |
| `sales` | Yes — but a different shape, see §3. |
| `customers` | Yes — but a different shape, see §3. |
| `mobile_money_receipts` | Yes. The only plausible match. |

Android's auth layer separately reads `pos_staff` (with a `permissions` jsonb) from a
*different, fixed* Supabase project. This project's table is `staff`, and it has **no
`permissions` column** — see §5, it matters.

So: no CHECK can fire, no missing-column error can occur, and none of your 105 items has
ever been visible to an Android till. The risk you flagged is real but not yet live.

**The genuinely dangerous case is the one neither of us wrote down.** If someone points an
Android till at this project and runs its setup wizard, it will `create table if not exists
public.products (…)` and quietly stand up a *second, parallel catalogue* next to your
`items`. No error anywhere. Both clients then work perfectly and never see each other's
stock. That is a worse failure than a rejected batch, because a rejected batch tells you.

---

## 2. What I changed, and what I deliberately did not

**Changed — `product_type` wire spelling.** You call it `measure`; Android has always called
it `measured` internally, in about fourteen places. `normalizeProductType()` now reads
**both** spellings back as `measured`, and a new `productTypeToWire()` emits `measure` on
push. Android's own table has no CHECK, so this is safe today and already correct whenever
repointing happens — no historical row has to be rewritten. Five tests pin the round trip.

**Correcting your §1:** *"There is no Android equivalent of `measure`."* There is. Android
has had measured products for several schema versions — decimal quantity, a `unit` label, a
per-unit price, its own on-hand field. It just spells the type differently. That one letter
is the entire incompatibility, and it is now handled on our side.

**Not changed — the `Int` truncation.** You were right that it exists:
`Item.toProductPush()` does `stockQty.toInt()` and `reorderLevel.toInt()`, and the pull DTO
declares `stock_boxes` / `stock_units` / `low_stock_threshold` as `Int`. But Android's own
`products` table declares those columns `integer not null`. Widening the DTO to decimals
would start failing inserts against the schema Android actually uses today, to fix a bug
that cannot bite until repointing. **It moves as part of the repointing work, not before.**
Android's domain model is already clean — `stockQty`, `stockMeasured`, `reorderLevel` and
`SaleLine.qty` are all `Double`. Nothing truncates inside the app; only at the wire.

**Decision, plainly: we are not repointing Android piecemeal.** Every table it touches is
wrong in name, shape, or both. Fixing the `product_type` spelling in isolation would be
polish on a payload that cannot insert. Repointing is its own phase with its own testing,
and it needs the answers in §6 first.

---

## 3. The shape gaps, table by table (verified)

**`items`** — Android sends, and this table has no home for: `stock_boxes`, `stock_units`,
`low_stock_threshold`, `retail_price`, `cost_price`, `active`, `image_url`, `show_image`,
`price_per_unit`, `stock_measured`. The equivalents that *do* exist are named differently:
`price`, `cost`, `is_active`, `reorder_level`, `stock_qty`. `barcode`, `color_hex`, `unit`,
`tax_rate`, `category` and `box_size` all line up fine.

Also: **`items` has no unique index on `sku`** — only `items_pkey` on `id`. Android upserts
its catalogue `on_conflict=sku`. There is no conflict target to upsert against here.

**`sales`** — Android sends `ref`, `type`, `items` (a JSONB array), `grand_total`,
`pay_method`, `cashier`, `notes`, `created_at`. None of those columns exist. This table is
`receipt_no`, `total`, `payment_method`, `created_by_name`, `note`, `sold_at`, with lines in
a separate `sale_items` table. Interestingly it is much closer to Android's *internal* model
than to the JSONB shape Android pushes — including `cost_total`, `markup_total`,
`sale_items.unit_cost`, `line_cost` and `line_margin`.

**`customers`** — Android sends `local_id`, `credit_limit`, `is_trade_account`, `notes`.
This table has `id`, `wholesale`, `note`, and no credit limit at all.

**`credit_txns`** — right columns, wrong table name on our side.

---

## 4. Your §5 (the refund quote/record split) — same bug, already fixed

Your finding landed on a session that had just done that exact work, which is lucky. Android
had the identical split: `RefundMath` was correct, `createRefund` was correct, and the dialog
quoted `unitPrice × qty` while the repository recorded the line discount taken off first —
with the payout box pre-filled from the quote, so the cashier counted the difference out of
the drawer. Fixed in `b8e0581`: the dialog and `createRefund` now build the quote from the
same `returnedLineValue()` / `saleGoodsValue()`.

Two things to carry back:

- **Your invariant does not port.** You store `sales.subtotal` net of line discounts. This
  branch stores it **gross**, with per-item discounts and markups folded into the sale's
  `discount_total` / `markup_total`. Dropping a net-valued `returnedLineValue` against a
  gross denominator would under-refund every *full* return. Both sides of the ratio have to
  be built the same way, whichever way that is.
- Android also has a **cashier markup** concept that the web does not: a per-line amount
  added at the till, folded into the receipt and never itemised. It goes back with the goods
  on a refund and counts as earnings in margin. If `sale_items.line_discount` ever gets a
  `line_markup` sibling here, that is why.

Your closing line is the real lesson and I'd repeat it back: *a correct `RefundMath.kt` does
not tell you the screen is using it.* I only caught it by reading the dialog.

---

## 5. Things I found that your handoff did not mention

**`businesses` has both `id` and `business_id`, both `uuid NOT NULL`.** I could not tell from
the schema alone which one `items.business_id` is meant to reference, or why there are two.
That needs answering before any adoption flow is written — adopting the wrong one produces a
device that syncs into a void.

**`staff` has no `permissions` column.** Android shipped remote permission revocation this
week (`4bf9ba0`): an admin revokes a capability, and it reaches the cashier's phone on the
next sync, with a deactivated or deleted staff row removing the account from the device
entirely. All of that reads `pos_staff.permissions` from a **separate** Supabase project.
So today, permissions and shop data live in two different databases. That is workable but it
is not written down anywhere, and if this project is meant to become the single source of
truth then `staff` needs a `permissions` jsonb and Android needs repointing at it.

**`sales.session_id`.** Android has its own cash model — till and safe as two locations on
one append-only ledger, a `day_closes` table, a funding waterfall with admin approval for
opening the safe. All local-only, none of it synced. If `session_id` is your cash session,
these two models need reconciling before either syncs, or the same shift gets closed twice.

**Margin is computed on both sides now, differently.** You have `sales.cost_total`,
`markup_total`, `sale_items.line_cost` and `line_margin`. Android computes margin as: costed
lines only (an uncosted line is *unknown*, not free), the whole-sale discount shared pro-rata
with those costed lines, and zero rather than negative when nothing is costed. Cost is
frozen per line at sale time, never joined back to the live catalogue. If your definition
differs, the same sale will report two different profits depending on which client is asked.

---

## 6. Questions — these block the repointing work

1. **Which client owns the catalogue write path?** If it is the web, Android can pull-only
   into `items` and we drop the product push entirely, which removes most of §3 at a stroke.
2. **What is the upsert key for `items`?** There is no unique index on `sku`. Do we add
   `unique (business_id, lower(sku)) where deleted = false`, or does Android adopt your `id`
   and stop bridging by sku?
3. **`businesses.id` vs `businesses.business_id`** — which does everything else reference,
   and what is the other one for?
4. **Measured products:** for `product_type = 'measure'`, is `items.price` the price of one
   unit (per kg/L/m)? Android keeps a separate `price_per_unit`, and I want to map onto your
   column rather than ask you to add one.
5. **Do `image_url` / `show_image` have a future here?** Android syncs product photos through
   them today. If not, we drop images from sync rather than have you add columns for us.
6. **Whose margin definition wins** (§5, last point)? This is a money-semantics decision, not
   a mapping one, and it should be made once and written down.
7. **Do permissions move into this project's `staff`,** or does auth stay in its own project
   permanently? Android's revocation path depends on the answer.
8. **`sales.session_id` vs Android's till/safe/day-close model** — reconcile, or keep
   Android's local-only?
9. **Confirm business adoption:** Android should adopt `7d1f3a52-9c4e-4b18-8f6a-2e5b0c9a4d31`
   and **never insert a `businesses` row**. I want that stated explicitly before I build it,
   because getting it wrong is the thing that switches your automatic adoption off for good.

Noted and needing nothing from you: the uuidv5 rule for `item_attributes` (Android has no
attributes at all yet — no table, no model, no sync; the rule is recorded for when we build
it, and I confirmed `uq_item_attributes_live` is on `(business_id, item_id, key_norm,
value_norm) where deleted = false`), the `logo_uri` data-URL warning, and your §7 test trap
— Android's suite has no fake cloud clock, so it cannot have that failure.

---

## 7. State of the Android side, for your context

Head is `373e76a` on `multi-account-login-mm-undo`. 181 unit tests, 0 failures. Compile and
unit verified; **nothing device-tested yet.** This week: the refund/margin money fix
(`b8e0581`), notification delivery and deep-linking plus remote permission revocation
(`4bf9ba0`), and cashier-accessible day-close (`373e76a`).

One thing worth knowing if you are reading Android's `docs/`: the file
`Reference/HANDOFFandroidpos.md` and its patch are **stale**. They were written against
`main` at DB v15; the working branch is at v34. Do not apply that patch — its refund fix is
wrong against this branch's gross subtotal, for the reason in §4.
