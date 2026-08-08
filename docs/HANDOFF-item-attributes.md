# Handoff: custom stock attributes (car / brand / part number / etc.)

**Status: IN PROGRESS — code does not build yet.** Local Room + repo + ViewModel +
search wiring is done. The item add/edit dialog UI (the actual place a user types
in attributes) is NOT done yet. Do that next, in `PosUi.kt`. Cloud/Supabase side
(table + sync) is blocked pending the real production schema — see the
"Supabase — status as of 2026-08-08 (session 2)" section below before doing
anything there.

## Context / decisions already made with the user
Business: a motor-spares shop. They want to tag stock items with free-form
attributes — e.g. `car="Honda Fit"`, `brand="NewBlu"`, `part_number="A111K"` —
and search/filter existing screens by those attributes. One item can match
**multiple** cars (same key, several rows), since one part can fit several cars.

User's explicit answers (don't re-ask these):
1. Attribute types are **fully custom/flexible** — user can type any attribute
   name when adding stock (not a fixed dropdown of "Car"/"Brand"/etc).
2. "Car" is recorded as **free text** (e.g. "Honda Fit"), not structured
   make/model/year fields.
3. Filtering should work **everywhere search already exists** in the app (POS
   screen AND the Items/inventory management screen) — not a separate
   "advanced search" screen.

## Data model chosen
A generic key/value side table rather than fixed columns, because it's
flexible per decision #1 and naturally supports "fits multiple cars" (just
insert multiple rows with `key="car"` and different `value`s for one item):

```
item_attributes
- id            (client UUID, PK)
- businessId
- itemId        (FK -> items.id)
- key           (free text, user-typed, e.g. "car", "brand", "part_number")
- value         (free text, user-typed, e.g. "Honda Fit", "NewBlu", "A111K")
- updatedAt
- deleted       (tombstone)
- pendingSync   (local-only, mirrors the pattern every other entity uses)
```

## What's DONE (uncommitted on this branch until you commit them)

- **`app/src/main/java/com/portionspot/pos/data/PosModels.kt`**
  Added `ItemAttribute` entity (see doc comment in the file for design notes),
  right after the `Item` entity.

- **`app/src/main/java/com/portionspot/pos/data/PosDao.kt`**
  Added `ItemAttributeDao` (right before `ItemDao`): `observeForBusiness`,
  `forItemOnce`, `observeKeysForBusiness` (distinct keys, for autocomplete),
  `upsertAll`, `replaceForItem` (tombstones the old set + inserts the new set —
  simplest correct way to save "current attributes for this item" from an
  editor UI without diffing row by row), plus the standard `pending()` /
  `markSynced()` / `wipe()` sync-scaffold methods every other DAO has.

- **`app/src/main/java/com/portionspot/pos/data/PosDatabase.kt`**
  - Bumped `version` 15 → **16**.
  - Added `ItemAttribute::class` to `entities = [...]`.
  - Added `abstract fun itemAttributeDao(): ItemAttributeDao`.
  - Added `MIGRATION_15_16` (creates `item_attributes` table + 3 indices) and
    registered it in `.addMigrations(...)`.

- **`app/src/main/java/com/portionspot/pos/data/PosRepository.kt`**
  - Wired `itemAttributeDao`.
  - Added `itemAttributesFlow(businessId): Flow<Map<itemId, List<ItemAttribute>>>`
    (grouped by item, ready for the UI to do `attributesByItem[item.id]`).
  - Added `itemAttributeKeysFlow(businessId): Flow<List<String>>` (distinct
    keys used so far — for autocomplete suggestions in the editor).
  - Added `saveAttributesForItem(itemId, businessId, attrs: List<Pair<String,String>>)`
    which trims/drops-blank and calls `replaceForItem`.
  - Added `import kotlinx.coroutines.flow.map`.

- **`app/src/main/java/com/portionspot/pos/ui/PosViewModel.kt`**
  - Imported `ItemAttribute`.
  - Added `val itemAttributes: StateFlow<Map<String, List<ItemAttribute>>>`
    (same `businessId.flatMapLatest` pattern as `items`).
  - Added `val itemAttributeKeys: StateFlow<List<String>>`.
  - Added `fun saveItemAttributes(itemId: String, attrs: List<Pair<String, String>>)`.

- **`app/src/main/java/com/portionspot/pos/ui/PosUi.kt`** (search wiring — DONE)
  - `SearchField` composable now takes an optional `placeholder` param
    (default unchanged: "Search or scan SKU…").
  - **POS screen** (`PosScreen`, around the `filtered = remember(...)` block
    near line ~1707 pre-edits): now collects `vm.itemAttributes` and matches
    the search query against attribute *values* too. Placeholder updated to
    "Search name, SKU, brand, part #, car…".
  - **Items/inventory screen** (`ItemsScreen`, around line ~3320 pre-edits):
    same treatment — collects `vm.itemAttributes`, matches query against both
    attribute keys and values, placeholder updated.

## What's NOT done yet — DO THIS NEXT

**The attribute editor UI inside `ItemDialog` (add/edit item), in `PosUi.kt`.**
Search for `private fun ItemDialog(` — as of this branch it starts around
line ~3414. This is the add/edit-item `AlertDialog`. It currently has no
attribute fields at all.

Plan (not yet implemented):
1. Add local state: `var attributeRows by remember { mutableStateOf(...) }`
   seeded from `vm.itemAttributes.value[existing?.id]` mapped to
   `List<Pair<String, String>>` (key, value), defaulting to one empty row
   `listOf("" to "")` for a brand-new item so the "+ add attribute" affordance
   is visible immediately.
2. Render a small repeatable list of rows in the dialog's `text = { Column { ... } }`
   block (after the existing category/sku/barcode fields is a natural spot):
   each row = two `OutlinedTextField`s side by side (key, value) + a delete
   icon button; below the rows, a "+ Add attribute" `TextButton` that appends
   `"" to ""`. Consider surfacing `vm.itemAttributeKeys.collectAsState()` as
   tap-to-fill suggestion chips above the key field (autocomplete for
   previously-used keys like "car"/"brand"/"part_number") — nice-to-have, not
   required for v1.
3. On Save (both the `existing == null` add-branch and the update branch in
   the `confirmButton`'s `onClick`): after `vm.addItem(...)` / `vm.updateItem(...)`,
   call `vm.saveItemAttributes(itemId, attributeRows.filter { it.first.isNotBlank() && it.second.isNotBlank() })`.
   - **Gotcha**: `vm.addItem(...)` is fire-and-forget (`viewModelScope.launch`
     inside the VM) and doesn't return the new item's id synchronously. Either:
     (a) generate the `id` client-side here (`com.portionspot.pos.data.newId()`)
     and pass it through a new optional `id: String? = null` param on
     `vm.addItem(...)` → `Item(...)` construction so we know it up front, or
     (b) change `addItem` to `suspend fun addItem(...): String` returning the
     id and call it from a `LaunchedEffect`/coroutine scope in the dialog.
     Option (a) is less invasive — recommended.
4. Double check `ItemDialog`'s `Column(Modifier.verticalScroll(...))` — new
   rows should go inside that scrollable column so the dialog doesn't overflow
   on small screens.

## Not started / deliberately deferred (told to the user)

- **Supabase schema**: see the detailed "Supabase — status as of 2026-08-08
  (session 2)" section above — this is now the current plan, superseding this
  bullet. Short version: bridge on `sku`, table name `product_attributes`,
  blocked on seeing the real production schema first.
- **Sync engine** (`app/src/main/java/com/portionspot/pos/sync/PosSyncEngine.kt`):
  NOT wired at all. Push is currently hand-listed per table (`products`,
  `customers`, `credit_transactions`, `mobile_money_receipts`, ...) and push is
  globally gated behind `config.pushEnabled()` (default OFF) per the file's
  own doc comments — read those comments before touching it. `item_attributes`
  push/pull needs to follow the same upsert-by-conflict-key pattern once
  Supabase access exists. Natural conflict key: there isn't a great natural
  unique key other than the local `id` itself (unlike `sku` for products), so
  upsert on `id` server-side.
- No SQL migration test / instrumented test added for `MIGRATION_15_16`.
  Existing migration tests live in the `app/src/test/java/com/portionspot/pos/`
  tree (check `app/src/test/java/com/portionspot/pos/sync` and sibling dirs for
  the pattern used by `MIGRATION_14_15` if one exists) — worth adding one.

## Supabase — status as of 2026-08-08 (session 2)

The user gave project details: URL `https://ucgvvxlhdooevngtraje.supabase.co`,
anon/publishable key `sb_publishable_yRhr2E9xuAvoGX7_8pyy-g_1WskC6w3` (both in
`Supabase.txt` they uploaded — treat as sensitive, same as any credential).

**Important finding: this repo has TWO different possible cloud schemas, and
they do NOT match each other.**

1. `supabase-setup.sql` in the repo root defines a self-contained schema:
   `businesses`, `items` (text PK, one row per product), `sales`, `customers`,
   `credit_transactions`, `sale_items`, `payment_intents`. This looks like a
   standalone "just this Android app" setup path.

2. `app/src/main/java/com/portionspot/pos/sync/PosSyncEngine.kt` (the code
   that actually runs) instead pushes/pulls tables named `products` (bridged
   by **`sku`**, not id — see `itemDao.pending().filter { sku not blank }` and
   `api.upsert("products", ..., "sku")`), plus `customers`, `sales`,
   `credit_transactions`, `mobile_money_receipts`. See `Dtos.kt`'s
   `ProductDto`/`ProductPushDto` for the exact column names it expects
   (`stock_boxes`, `stock_units`, `low_stock_threshold`, `retail_price`,
   `cost_price`, etc. — snake_case, some money fields as strings). **This
   schema is NOT defined anywhere in this repo** — it's presumed to already
   exist in whatever Supabase project gets connected.

**Confirmed with the user: their real project (`ucgvvxlhdooevngtraje`) already
has an existing, separate web POS's live tables** — so it's schema #2 above
(or something close to it), not the standalone `supabase-setup.sql` one. Do
NOT run `supabase-setup.sql` against this project without checking first —
it could clash with or be redundant against tables that already exist.

**Blocked on: reading the real schema.** This sandbox's bash network egress
is allowlisted (github/npm/pypi-type domains only) and does NOT include
`*.supabase.co`, so `curl`/`web_fetch` against the project fail with
`host_not_allowed`. Confirmed via:
```
curl -s -D - "https://ucgvvxlhdooevngtraje.supabase.co/rest/v1/" -H "apikey: ..."
→ HTTP 403, x-deny-reason: host_not_allowed
```
**Next session should either:**
- Ask the user to add `ucgvvxlhdooevngtraje.supabase.co` to network egress
  settings so it can query directly, or
- Ask the user to run this in the Supabase SQL editor and paste the result:
  ```sql
  select table_name, column_name, data_type, is_nullable
  from information_schema.columns
  where table_schema = 'public'
  order by table_name, ordinal_position;
  ```
  Specifically need to confirm: does `products.sku` have a `unique`
  constraint? (Required for it to safely be the bridge key for the new
  attributes table below.)

### Proposed cloud table design for the new attributes feature

Discussed with the user and they liked this direction — **not yet created in
the real database, and not yet reflected in any `.sql` file in the repo.**
Do both once the real schema is confirmized.

Bridge by **`sku`**, matching how `products` already syncs (by sku, not a
UUID/bigint id either side owns) — no new id-resolution logic needed, and it
matches how the sync engine already thinks about products.

```sql
create table if not exists public.product_attributes (
    id          bigint generated always as identity primary key,
    business_id text not null,
    sku         text not null,        -- bridges to products.sku
    key         text not null,        -- "car", "brand", "part_number", ...
    value       text not null,        -- "Honda Fit", "NewBlu", "A111K", ...
    updated_at  timestamptz not null default now(),
    deleted     boolean not null default false,
    unique (sku, key, value)
);

create index idx_product_attributes_sku       on public.product_attributes (sku);
create index idx_product_attributes_key_value on public.product_attributes (business_id, key, value);
```

Rationale (for whoever picks this up — don't relitigate unless new info shows
up from the real schema dump):
- **Not JSONB on `products`**: an `attributes jsonb` column would need an
  array-of-objects to support "fits multiple cars", can't cleanly express a
  uniqueness constraint on one (attribute, value) pair, and every edit is a
  read-modify-write of the whole blob (race-prone with two writers: web POS +
  Android). A side table gives row-level upserts for free.
- **Not a normalized `vehicles` + join table**: would only fit the "car"
  attribute; the user explicitly wants fully custom attribute names (decision
  #1, above), so a fixed vehicles schema is too narrow.
- **Multi-value per key is just multiple rows**: a part fitting a Honda Fit
  AND a Toyota Vitz is two rows, `(sku,'car','Honda Fit')` and
  `(sku,'car','Toyota Vitz')`. No special-casing for "some attributes are
  single-valued, some aren't."
- **Search** becomes `select distinct sku from product_attributes where
  business_id = ? and value ilike '%query%'`, joined back to `products`. If
  search volume gets heavy, a `pg_trgm` GIN index on `value` would speed up
  `ilike '%...%'`; not needed for v1.

**Local Room side note:** the local `ItemAttribute` entity (already built,
see below) currently keys by `itemId` (Android's local UUID), not `sku`,
because that's the natural key on-device. When wiring the actual sync
push/pull for this table, follow the same pattern `PosSyncEngine` already
uses for products: resolve `itemId ↔ sku` via `itemDao.allForBusinessOnce(bid)`
(there's already a `skuById` map built for this exact purpose in `push()`)
rather than trying to sync raw `itemId`s to the cloud.



```
git clone https://github.com/the-future-scar-knight/pos-android.git
git checkout session-2026-08-08-item-attributes
```

Everything above is committed on that branch (not on `main`). Diff against
`main` to see the full changeset in context:

```
git diff main...session-2026-08-08-item-attributes
```
