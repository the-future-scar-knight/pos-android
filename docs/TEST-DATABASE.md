# Throwaway test database

A second Supabase project that carries the **same shared schema as the shop** but no real
money in it. Point the app at this one whenever a change touches sync, and the worst a bug
can do is corrupt a database nobody is trading on.

| | |
|---|---|
| Project | `pos-schema-validation` (`lvaxbbobmounfxskooiw`) |
| URL | `https://lvaxbbobmounfxskooiw.supabase.co` |
| Cost | Free tier, $0 |
| Shop row | `TEST SHOP - throwaway` — deliberately named so it is obvious on the receipt if you ever connect to the wrong one |

Get the anon key from the Supabase dashboard: **Project settings → API keys → `anon` /
`publishable`**. The app takes either format.

## Connecting the app to it

1. Settings → Cloud sync → paste the URL and the key.
2. Sync now. Nothing needs a login: RLS on every table is
   `auth_org_id() is null or business_id = auth_org_id()`, and an anon key carries no
   `org_id` claim, so it reads and writes freely on a single-shop database.
3. Before switching **back** to the real shop, use Admin → Danger zone → **Reset local
   data**. Test items and test sales are otherwise still sitting on the device.

Push is off by default (`SyncConfig.pushEnabled`). Turn it on here freely — this is what
the project is for.

## What is seeded, and what each row is there to prove

8 items, 31 live fitments, 1 tombstone. The data is shaped to exercise every branch of
attribute handling rather than to look realistic:

| Item | Fitments | What it tests |
|---|---|---|
| Oil Filter 164 (`164`) | 13 — Navara, X-Trail, BMW 3 Series, Mahindra Pikup… | **The whole point.** The name contains no car. Search "Navara" must find it; before item tags were pulled this returned nothing. |
| Oil Filter Z217 (`Z217`) | 7 — Hilux, Hi-Ace, Fortuner, Prado, CX5… | Several fitments matching one query ("toyota") must ALL be named on the card, not just the first. |
| Caravan / Hiace Air Filter | 2 — Caravan, Hiace | The name says the cars. A search for "Caravan" must put THIS above the tag-only match on Oil Filter 164. |
| Airwave/Vezel Brake Pads | 2 | Same, plus a `set` product type. |
| Spark Plug BKR6E | 4 | A `box` product (12/box) that also carries fitments. |
| Centre Bearing Mazda | 3, incl. a `Size: Heavy duty` tag | A key that is **not** `car` — must read "Size: Heavy duty", not "Fits Heavy duty". |
| Wiper Blade 22 inch | none | Untagged control: must behave exactly as before. |
| Gear Oil 80W-90 | none | `measure` product, untagged. |

Two deliberately awkward rows:

- **`Ford  Maverick` with NULL `key_norm`/`value_norm`** (and a double space). Both columns
  are nullable on the shared schema; the till has to normalise them itself or that fitment
  vanishes from search with no error anywhere. Search "ford maverick" — one space — must
  still find Oil Filter 164.
- **`NISSAN PATROL`, `deleted = true`.** A fitment the web removed. Searching "PATROL"
  must find **nothing**. If it still matches, tombstones are not being applied and the till
  will keep selling parts for cars the shop has decided they do not fit.

## Checks worth running on the device

1. Search `navara` → Oil Filter 164, captioned **Fits NISSAN NAVARA**.
2. Search `toyota` → Oil Filter Z217, captioned with **every** matching Toyota, not one.
3. Search `caravan` → the Air Filter first (name), Oil Filter 164 second (tag).
4. Search `patrol` → nothing.
5. Search `a` → name matches only; the catalogue must not flash up in full.
6. Inventory → each tagged product shows its fitments under the name; Wiper Blade shows
   none.

## Re-seeding

The seed is three `execute_sql` batches (schema, items, fitments) applied through the
Supabase MCP; the schema half is just `supabase-setup.sql` from the repo root, which is
idempotent and safe to re-run. Wiping and re-seeding the data is `delete from
item_attributes; delete from items; delete from businesses;` followed by the inserts.
