package com.portionspot.pos.sync

/**
 * The one-time Supabase setup script the app hands the user when their project is empty
 * (the "Set up your database" flow in CloudSyncSection).
 *
 * ★ REGENERATED AGAINST THE REAL WEB-POS SCHEMA. The previous version created a
 * `public.products` table with `retail_price` / `stock_boxes` / `stock_units`, plus
 * `credit_transactions`, `cash_txns`, `audit_log`, `notifications` and `staff_requests` —
 * a schema that exists nowhere. Pasting it into the SHOP's project would have stood up a
 * second, parallel catalogue beside `items`: no error, both clients working, neither
 * seeing the other's stock. That is worse than a rejected script, because a rejected
 * script tells you.
 *
 * What it creates now is the shape Android actually syncs against (see [SyncConfig.TABLES]
 * and sync/wire/WireDtos.kt), so a bring-your-own database ends up a peer of the shop's
 * rather than a lookalike.
 *
 * Fully idempotent — `if not exists` / `add column if not exists` throughout — so running
 * it on an already-configured project is a harmless no-op and re-running an older setup
 * upgrades it in place.
 *
 * Policies are deliberately permissive (`anon`/`authenticated` may read and write) and
 * mirror the live shop's: a bring-your-own database starts with no staff rows, so
 * staff-gated policies would lock the owner out of their own data on day one.
 *
 * Keep in lockstep with the repo `supabase-setup.sql`.
 */
const val SUPABASE_SETUP_SQL: String = """
-- ============================================================================
-- ON-SPOT POS — Supabase setup (bring-your-own-database)
-- ----------------------------------------------------------------------------
-- Run ONCE: Dashboard -> SQL Editor -> New query -> paste -> Run.
-- Safe to run again: every statement is "if not exists" / "add column if not
-- exists", so it never drops or overwrites data.
--
-- This creates the SHARED PortionSpot POS schema — the same shape the web POS
-- uses. Do NOT hand-edit table or column names: both clients read them.
-- ============================================================================

create extension if not exists "pgcrypto";

-- Tenant scoping. Returns NULL when the JWT carries no org claim, which is how
-- an anon-key client is allowed through on a single-shop database.
create or replace function public.auth_org_id()
returns uuid language sql stable set search_path to '' as ${'$'}${'$'}
  select nullif(auth.jwt() ->> 'org_id', '')::uuid
${'$'}${'$'};

-- ---------------------------------------------------------------------------
-- 1) Shop profile + staff
-- ---------------------------------------------------------------------------
create table if not exists public.businesses (
    id                  uuid primary key,
    business_id         uuid not null,
    name                text default '',
    currency            text default 'USD',
    tagline             text,
    address             text,
    phone               text,
    email               text,
    website             text,
    receipt_header      text,
    receipt_footer      text,
    paper_width         text default '80mm',
    receipt_large_text  boolean default false,
    vat_enabled         boolean default false,
    vat_number          text,
    vat_percent         numeric default 15,
    total_rounding      numeric default 0,
    discount_threshold  numeric default 5,
    quote_validity_days integer default 7,
    -- Shop-wide capability locks. A lock switches a capability off for every
    -- cashier at once; a per-staff permission can only ever subtract further.
    lock_refunds        boolean default false,
    lock_discounts      boolean default false,
    lock_credit         boolean default false,
    lock_price_override boolean default false,
    lock_parking        boolean default false,
    lock_quotes         boolean default false,
    lock_stock_adjust   boolean default false,
    updated_at          timestamptz not null default now(),
    deleted             boolean not null default false,
    client_updated_at   timestamptz
);

create table if not exists public.staff (
    id                uuid primary key,
    business_id       uuid not null,
    name              text not null,
    username          text not null,
    role              text not null default 'cashier',
    active            boolean not null default true,
    pin_hash          text,
    -- Positive keys; an explicit false revokes. Android writes every key
    -- explicitly (both spellings) so neither client has to infer from absence.
    permissions       jsonb,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

do ${'$'}${'$'} begin
    alter table public.staff add constraint staff_role_check
        check (role = any (array['admin','manager','cashier']));
exception when duplicate_object then null; end ${'$'}${'$'};

-- ---------------------------------------------------------------------------
-- 2) Catalogue. `items` — NOT `products`.
-- ---------------------------------------------------------------------------
create table if not exists public.items (
    id                uuid primary key,
    business_id       uuid not null,
    category_id       uuid,
    name              text default '',
    barcode           text,
    sku               text,
    category          text,
    -- The price of ONE STOCK UNIT. For a 'measure' product that unit is the
    -- kg / L / m — there is no second per-unit price column.
    price             numeric default 0,
    wholesale_price   numeric default 0,
    box_price         numeric default 0,
    box_size          numeric(14,3) default 1,
    cost              numeric,
    tax_rate          numeric default 0,
    track_stock       boolean default true,
    -- On-hand in stock units. A 'measure' product carries FRACTIONAL quantities
    -- here; measured stock deliberately has no column of its own.
    stock_qty         numeric(14,3) default 0,
    reorder_level     numeric default 0,
    unit              text default 'pc',
    color_hex         text,
    is_active         boolean default true,
    product_type      text not null default 'piece',
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

do ${'$'}${'$'} begin
    alter table public.items add constraint items_product_type_check
        check (product_type = any (array['box','set','piece','measure']));
exception when duplicate_object then null; end ${'$'}${'$'};

create index if not exists items_cursor_idx on public.items (business_id, updated_at);
create index if not exists items_sku_idx on public.items (business_id, sku);
create index if not exists items_barcode_idx on public.items (business_id, barcode);

-- Item tags: key/value pairs on a product. In the live shop every row is
-- key = 'car' and the value is a vehicle the part fits, which is how a cashier
-- finds a filter for a Hiace whose product name never says Hiace.
--
-- The unique index is PARTIAL (live rows only) so re-tagging a part that was
-- previously untagged and tombstoned does not collide with the tombstone.
--
-- key_norm / value_norm are GENERATED. The DATABASE owns the canonical form so
-- the two clients can never disagree about what counts as "the same tag" — the
-- unique index below is built on them, and as plain columns nothing fills them:
-- every live row indexes (null, null), the constraint stops meaning anything and
-- a search for "hiace" matches nothing.
create table if not exists public.item_attributes (
    id                uuid primary key,
    business_id       uuid not null,
    item_id           uuid not null,
    key               text not null,
    value             text not null,
    key_norm          text generated always as (lower(btrim(regexp_replace(key, '\s+', ' ', 'g')))) stored,
    value_norm        text generated always as (lower(btrim(regexp_replace(value, '\s+', ' ', 'g')))) stored,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

-- Upgrade a database created by an earlier run of this script, where the two
-- columns were plain text. Dropping them loses nothing: every value is derived.
do ${'$'}${'$'}
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'item_attributes'
      and column_name = 'key_norm' and is_generated <> 'ALWAYS'
  ) then
    -- The view and the indexes depend on the columns, so they go first; both are
    -- recreated further down by this same script.
    drop view if exists public.pos2_item_attributes;
    drop index if exists public.uq_item_attributes_live;
    drop index if exists public.idx_item_attributes_lookup;
    drop index if exists public.idx_item_attributes_value_trgm;
    alter table public.item_attributes drop column key_norm, drop column value_norm;
    alter table public.item_attributes
      add column key_norm text generated always as (lower(btrim(regexp_replace(key, '\s+', ' ', 'g')))) stored,
      add column value_norm text generated always as (lower(btrim(regexp_replace(value, '\s+', ' ', 'g')))) stored;
  end if;
end ${'$'}${'$'};

create unique index if not exists uq_item_attributes_live
    on public.item_attributes (business_id, item_id, key_norm, value_norm)
    where deleted = false;
create index if not exists idx_item_attributes_cursor
    on public.item_attributes (business_id, updated_at);
create index if not exists idx_item_attributes_lookup
    on public.item_attributes (business_id, key_norm, value_norm);
create index if not exists idx_item_attributes_item
    on public.item_attributes (item_id);

-- Stock ledger. THIS is the authority; items.stock_qty is a derived cache that
-- every device recomputes from these rows after a pull.
create table if not exists public.stock_movements (
    id                uuid primary key,
    business_id       uuid not null,
    item_id           uuid,
    type              text default 'adjust',
    delta             numeric default 0,
    balance_after     numeric default 0,
    note              text,
    created_by        text,
    created_by_name   text,
    created_at        timestamptz,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

-- ---------------------------------------------------------------------------
-- 3) Customers + credit ledger
-- ---------------------------------------------------------------------------
create table if not exists public.customers (
    id                uuid primary key,
    business_id       uuid not null,
    name              text default '',
    phone             text,
    email             text,
    address           text,
    note              text,
    wholesale         boolean default false,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);
create index if not exists customers_cursor_idx on public.customers (business_id, updated_at);

-- Balance is DERIVED from these rows, never stored.
create table if not exists public.credit_txns (
    id                uuid primary key,
    business_id       uuid not null,
    customer_id       uuid,
    sale_id           uuid,
    type              text not null,
    amount            numeric default 0,
    note              text,
    method            text,
    created_by        text,
    created_by_name   text,
    created_at        timestamptz,
    server_created_at timestamptz,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

-- ---------------------------------------------------------------------------
-- 4) Cash: one drawer, one shift
--
-- This is also where a DAY-CLOSE lands: a close is the event that ends a shift,
-- not a separate record. Two more columns for it are added in section 7b, which
-- is where the reasoning lives.
-- ---------------------------------------------------------------------------
create table if not exists public.cash_sessions (
    id                uuid primary key,
    business_id       uuid not null,
    status            text not null default 'open',
    opened_at         timestamptz not null default now(),
    opened_by         text,
    opened_by_name    text,
    opening_float     numeric not null default 0,
    closed_at         timestamptz,
    closed_by         text,
    closed_by_name    text,
    counted_cash      numeric,
    expected_cash     numeric,
    -- GENERATED: never send this column, an insert naming it fails the batch.
    -- COALESCE, verbatim from the live schema. Without it a shift counted before its
    -- expected figure is known yields NULL variance instead of the counted amount.
    variance          numeric generated always as
                          (coalesce(counted_cash, 0::numeric) - coalesce(expected_cash, 0::numeric)) stored,
    note              text,
    -- Which DEVICE opened the shift. Recorded, but NOT part of its identity —
    -- any device may close it, and closing it closes it for the shop.
    till_code         text,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

-- ONE open shift per SHOP, not per till: the shop has one physical drawer, so
-- it has exactly one thing to count. Two offline tills can still each open one
-- (both correctly see none open); the clients settle that afterwards with a
-- shared rule — oldest opened_at wins, ties broken by id.
-- CHECK-constrained on the live schema. Only four columns in the whole database are,
-- and this is one: the merge writes 'closed' on a loser, and anything else fails the
-- WHOLE batch rather than the row.
do ${'$'}${'$'} begin
    alter table public.cash_sessions add constraint cash_sessions_status_check
        check (status = any (array['open','closed']));
exception when duplicate_object then null; end ${'$'}${'$'};

create unique index if not exists uq_cash_sessions_one_open
    on public.cash_sessions (business_id)
    where status = 'open' and deleted = false;

create index if not exists idx_cash_sessions_cursor on public.cash_sessions (business_id, updated_at);

-- No `location` column: TILL / SAFE / OUTSIDE is DERIVED from `type`.
create table if not exists public.cash_movements (
    id                uuid primary key,
    business_id       uuid not null,
    session_id        uuid,
    type              text not null,
    amount            numeric not null,
    reason            text,
    created_by        text,
    created_by_name   text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

-- `safe_out` is the newest word here and it was added to close a real hole: the
-- vocabulary could say money went INTO the safe and had no way to say it came out,
-- so a float top-up or a safe-funded expense went up as a bare `pay_out` and landed
-- against the TILL on every other device. Cash on hand still agreed to the cent,
-- which is what made it hard to spot — only the till/safe split drifted.
--
-- DROP THEN ADD, rather than `exception when duplicate_object`: an existing database
-- already carries this constraint under this name with the OLD seven-word list, so
-- the add would be swallowed as a duplicate and the constraint would keep rejecting
-- `safe_out` for ever. A client that emits a word the CHECK refuses does not lose one
-- row — the whole batch fails, and cash stops syncing entirely.
alter table public.cash_movements drop constraint if exists cash_movements_type_check;
alter table public.cash_movements add constraint cash_movements_type_check
    check (type = any (array['pay_in','pay_out','drop','petty','float_topup','safe_in','safe_out','bank_deposit']));

-- ---------------------------------------------------------------------------
-- 5) Sales. A sale is THREE rows: header + lines + tenders.
-- ---------------------------------------------------------------------------
create table if not exists public.sales (
    id                  uuid primary key,
    business_id         uuid not null,
    receipt_no          text,
    status              text default 'completed',
    -- subtotal            = Sum(unit_price * qty)                    GROSS
    -- discount_total      = whole-sale discount + Sum(line_discount) COMBINED
    -- line_discount_total = Sum(line_discount)                       so a reader
    --   can recover the whole-sale half by SUBTRACTION rather than reconstructing
    --   it. Note it is clamped to discount_total: discount_total itself is capped
    --   at the goods value, so an over-large whole-sale discount would otherwise
    --   make (discount_total - line_discount_total) go negative.
    subtotal            numeric default 0,
    discount_total      numeric default 0,
    line_discount_total numeric not null default 0,
    markup_total        numeric not null default 0,
    cost_total          numeric not null default 0,
    -- Gross profit: costed revenue - discount share - cost, COSTED LINES ONLY.
    -- A line with no cost is unknown-cost, not zero-cost.
    profit_total        numeric not null default 0,
    tax_total           numeric default 0,
    total               numeric default 0,
    payment_method      text,
    tendered            numeric,
    amount_paid         numeric default 0,
    change_due          numeric,
    payment_ref         text,
    payment_status      text default 'unpaid',
    note                text,
    customer_id         uuid,
    customer_name       text,
    sold_at             timestamptz,
    session_id          uuid,
    created_by          text,
    created_by_name     text,
    server_created_at   timestamptz,
    updated_at          timestamptz not null default now(),
    deleted             boolean not null default false,
    client_updated_at   timestamptz
);
create index if not exists sales_cursor_idx on public.sales (business_id, updated_at);
create index if not exists sales_sold_at_idx on public.sales (business_id, sold_at desc);
create index if not exists idx_sales_session on public.sales (session_id);

create table if not exists public.sale_items (
    id                uuid primary key,
    business_id       uuid not null,
    sale_id           uuid not null,
    item_id           uuid,
    name              text default '',
    qty               numeric default 0,
    unit_price        numeric default 0,
    unit_cost         numeric,
    line_discount     numeric default 0,
    line_markup       numeric not null default 0,
    line_tax          numeric default 0,
    line_total        numeric default 0,
    mode              text default 'retail',
    units_per_line    numeric default 1,
    -- GENERATED. Naming either in an INSERT fails the WHOLE batch, not the row.
    -- Verbatim from the live schema. Null unit_cost yields NULL for both, which is the
    -- "cost not recorded" case and must stay distinct from a zero cost.
    line_cost         numeric generated always as ((unit_cost * qty) * units_per_line) stored,
    line_profit       numeric generated always as (line_total - ((unit_cost * qty) * units_per_line)) stored,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);
create index if not exists sale_items_sale_idx on public.sale_items (sale_id);

create table if not exists public.sale_payments (
    id                uuid primary key,
    business_id       uuid not null,
    sale_id           uuid not null,
    method            text default 'cash',
    amount            numeric default 0,
    reference         text,
    tender_currency   text,
    tender_amount     numeric,
    rate              numeric,
    created_at        timestamptz,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);
create index if not exists sale_payments_sale_idx on public.sale_payments (sale_id);

-- ---------------------------------------------------------------------------
-- 6) Refunds — first-class rows, NOT negative-total sales.
--    sale_id / sale_line_id are what let a returned line be traced back to the
--    receipt it came off.
-- ---------------------------------------------------------------------------
create table if not exists public.refunds (
    id                uuid primary key,
    business_id       uuid not null,
    sale_id           uuid,
    sale_receipt_no   text,
    customer_id       uuid,
    customer_name     text,
    reason            text,
    refund_total      numeric default 0,
    status            text default 'owed',
    created_by        text,
    created_by_name   text,
    created_at        timestamptz,
    server_created_at timestamptz,
    session_id        uuid,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

create table if not exists public.refund_items (
    id                uuid primary key,
    business_id       uuid not null,
    refund_id         uuid not null,
    sale_line_id      uuid,
    item_id           uuid,
    name              text default '',
    qty               numeric default 0,
    unit_price        numeric default 0,
    line_total        numeric default 0,
    mode              text default 'retail',
    units_per_line    numeric default 1,
    restock           boolean default true,
    created_at        timestamptz,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

create table if not exists public.refund_payments (
    id                uuid primary key,
    business_id       uuid not null,
    refund_id         uuid not null,
    method            text default 'cash',
    amount            numeric default 0,
    reference         text,
    tender_currency   text,
    tender_amount     numeric,
    rate              numeric,
    created_by        text,
    created_by_name   text,
    created_at        timestamptz,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

-- ---------------------------------------------------------------------------
-- 7) Mobile money, audit, accounting spine
-- ---------------------------------------------------------------------------
create table if not exists public.mobile_money_receipts (
    id                    uuid primary key,
    business_id           uuid not null,
    provider              text default 'unknown',
    raw_body              text,
    sender                text,
    sender_name           text,
    sender_phone          text,
    amount                numeric default 0,
    currency              text default 'USD',
    txn_code              text not null,
    received_at           timestamptz,
    status                text default 'unmatched',
    matched_customer_id   uuid,
    matched_customer_name text,
    purpose               text,
    applied_credit_txn_id uuid,
    applied_sale_id       uuid,
    note                  text,
    created_by            text,
    created_by_name       text,
    server_created_at     timestamptz,
    updated_at            timestamptz not null default now(),
    deleted               boolean not null default false,
    client_updated_at     timestamptz
);

-- The ONE genuine idempotency key in this schema: the same provider SMS read
-- twice is the same receipt, so a duplicate here may safely be ignored. Nowhere
-- else may a push silently skip a row.
create unique index if not exists mobile_money_receipts_txn_key
    on public.mobile_money_receipts (business_id, txn_code);

create table if not exists public.audit_entries (
    id                uuid primary key,
    business_id       uuid not null,
    action            text not null,
    entity_type       text,
    entity_id         text,
    summary           text,
    meta              jsonb,
    created_by        text,
    created_by_name   text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

create table if not exists public.expenses (
    id                uuid primary key,
    business_id       uuid not null,
    category          text,
    amount            numeric not null default 0,
    date              date,
    description       text,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

create table if not exists public.suppliers (
    id                uuid primary key,
    business_id       uuid not null,
    name              text not null,
    phone             text,
    email             text,
    address           text,
    notes             text,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

create table if not exists public.purchase_orders (
    id                uuid primary key,
    business_id       uuid not null,
    ref               text,
    supplier_id       uuid,
    supplier_name     text,
    status            text not null default 'draft',
    notes             text,
    created_at        timestamptz not null default now(),
    sent_at           timestamptz,
    received_at       timestamptz,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

create table if not exists public.purchase_order_items (
    id                uuid primary key,
    business_id       uuid not null,
    po_id             uuid not null,
    item_id           uuid,
    name              text,
    sku               text,
    qty               numeric not null default 0,
    unit_cost         numeric not null default 0,
    received_qty      numeric,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

-- ---------------------------------------------------------------------------
-- 7b) Columns the WEB back-office reads that the till does not.
--
-- Added here rather than in the create-table above so a database stood up by an
-- older copy of this script gains them on a re-run. The pos2_* views in section
-- 9 select them BY NAME and cannot be created without them.
-- ---------------------------------------------------------------------------
alter table public.businesses add column if not exists logo_uri text;
alter table public.businesses add column if not exists cash_enabled boolean default true;
alter table public.businesses add column if not exists card_enabled boolean default false;
alter table public.businesses add column if not exists bank_enabled boolean default false;
alter table public.businesses add column if not exists paynow_enabled boolean default false;
alter table public.businesses add column if not exists ecocash_enabled boolean default false;
alter table public.businesses add column if not exists innbucks_enabled boolean default false;
alter table public.businesses add column if not exists onemoney_enabled boolean default false;
alter table public.businesses add column if not exists omari_enabled boolean default false;
alter table public.businesses add column if not exists bank_name text;
alter table public.businesses add column if not exists bank_branch text;
alter table public.businesses add column if not exists bank_account_name text;
alter table public.businesses add column if not exists bank_account_number text;
alter table public.businesses add column if not exists ecocash_account_name text;
alter table public.businesses add column if not exists ecocash_phone text;
alter table public.businesses add column if not exists ecocash_merchant_code text;
alter table public.businesses add column if not exists innbucks_account_name text;
alter table public.businesses add column if not exists innbucks_phone text;
alter table public.businesses add column if not exists onemoney_account_name text;
alter table public.businesses add column if not exists onemoney_phone text;
alter table public.businesses add column if not exists omari_account_name text;
alter table public.businesses add column if not exists omari_phone text;
alter table public.businesses add column if not exists paynow_integration_id text;
alter table public.businesses add column if not exists second_currency text;
alter table public.businesses add column if not exists second_currency_rate numeric default 0;

-- The other two thirds of the shop's money POLICY. `discount_threshold` is on the
-- create-table above and has been readable since the schema was written; these two
-- were only ever kept in one phone's local settings, which meant the shop's discount
-- cap was really whatever the handset in your hand was last told. The owner raising
-- the cap on his phone left the cashier's till enforcing the old one, and lowering it
-- left her till allowing the larger discount on every line of every sale, with nothing
-- anywhere reporting a disagreement.
--
-- ★ NULL is "not stated", NOT zero, and the till reads it that way. A zero cap means NO
-- LIMIT in this app, so defaulting these would take the ceiling off every cashier's
-- discount on any database that had simply not set one yet. No defaults, deliberately.
alter table public.businesses add column if not exists max_item_discount numeric;
alter table public.businesses add column if not exists variance_note_threshold numeric;

-- A DAY-CLOSE IS THE CLOSING HALF OF A SHIFT, NOT A SECOND KIND OF THING.
--
-- The till keeps a local `day_closes` table: expected / counted / variance /
-- who / when, plus how much was moved to the safe and what float was left. Six
-- of those eight are already columns on `cash_sessions` — and its `variance` is
-- GENERATED as exactly `counted_cash - expected_cash`, which is the same
-- arithmetic the device does. Giving day-closes their own cloud table would put
-- two answers to "what was the till short on the 8th?" in one database, and
-- nothing would say which one the web should believe.
--
-- So the close FOLDS INTO `cash_sessions` and only the two genuinely missing
-- facts get columns. A close writes (or completes) a session with
-- status='closed', `opened_at` = the start of the trading day being closed —
-- which is what the device's `day_start` grouping key means — and `closed_at`
-- the moment of the count.
--
--   moved_to_safe  the excess physically carried from the drawer to the safe as
--                  part of the same confirmation. Recoverable in theory from the
--                  transfer PAIR in `cash_movements`, but only by pairing rows
--                  by amount and timestamp: `cash_movements` has no ref_type /
--                  ref_id, so nothing there says "this drop belongs to that
--                  close". Snapshotting it is one column against a join nobody
--                  can write correctly.
--   float_target   what was deliberately LEFT in the till. It is a shop setting
--                  that changes over time, so reading today's value tells you
--                  nothing about a close from March. Snapshot, not lookup.
--
-- Not added: a `day_start` column. `opened_at` carries it.
alter table public.cash_sessions add column if not exists moved_to_safe numeric not null default 0;
alter table public.cash_sessions add column if not exists float_target numeric;

-- ---------------------------------------------------------------------------
-- 7c) Tables the till needs that the web schema never grew.
--
-- These three are the multi-device half of the app. Every one of them is
-- useless on a single device: an alert nobody else sees, an approval the admin
-- is never asked for, an owner's cash injection visible only on the phone it
-- was typed into. They are added here — not as a private Android schema, but in
-- the same shape and with the same mechanisms as everything above — so the web
-- back-office can adopt them by reading the matching pos2_* views in section 9.
--
-- Deliberately NOT check-constrained. A CHECK that a client trips fails the
-- WHOLE push batch rather than the offending row (see cash_movements above), and
-- these carry vocabularies that are still growing: the till already writes
-- notification categories the original design never listed ('cash', 'expenses',
-- 'requests'), and `staff_requests.type` is free-form by design. `outside_funds`
-- is the exception and is constrained — see below.
-- ---------------------------------------------------------------------------

-- The shared alert feed. The engine recomputes the shop's alert state on a
-- schedule and upserts on the NATURAL key (business_id, dedupe_key), so a
-- standing condition — a low-stock item, an owed refund — is ONE row that gets
-- updated, never a fresh duplicate every cycle.
--
-- ★ `id` DEFAULTS here, unlike every other table in this schema, and the client
-- must NOT send it. Two devices computing the same condition mint different
-- local ids but the same dedupe_key; if the push named `id`, each device's
-- upsert would rewrite the row's primary key to its own, bumping `updated_at`
-- and making every other device re-pull a row that did not change. The cloud
-- owns the id; the clients match on dedupe_key.
--
-- `read_at` is SHARED (read on one phone is read everywhere). There is no
-- `pushed_at` column and there must not be: that flag records whether THIS
-- handset already fired its own heads-up notification and is meaningless on any
-- other one.
create table if not exists public.notifications (
    id                uuid primary key default gen_random_uuid(),
    business_id       uuid not null,
    category          text not null default 'system',
    severity          text not null default 'info',
    title             text not null default '',
    body              text not null default '',
    -- The natural key. Stable across devices and across recomputes.
    dedupe_key        text not null,
    -- Who the alert is FOR: 'admin' | 'cashier' | 'all'. Decides which handset
    -- buzzes, so a cashier phone does not fire for an admin-only alert.
    audience          text not null default 'admin',
    ref_type          text,
    ref_id            text,
    -- When the UNDERLYING event happened, which is what an ageing escalation is
    -- measured from — not when the engine noticed it.
    event_at          timestamptz not null default now(),
    created_at        timestamptz not null default now(),
    read_at           timestamptz,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

-- NOT partial. A partial unique index cannot be inferred as a PostgREST
-- `on_conflict` target, and this one exists to BE that target: without it the
-- upsert has nothing to resolve against and every recompute inserts again.
create unique index if not exists uq_notifications_dedupe
    on public.notifications (business_id, dedupe_key);
create index if not exists idx_notifications_unread
    on public.notifications (business_id, read_at)
    where deleted = false;

-- The admin⇄cashier approval channel: a cashier who hits an admin-gated action
-- raises a request instead of hunting for the owner's PIN, and it lands in the
-- admin's feed on the OTHER phone.
--
-- This is the one table here with no local-only fallback worth having. An
-- approval request that cannot leave the device is a dialog asking a person who
-- is not in the room.
--
-- Keyed on the Android uuid like everything else. `target_id` / `ref` fields are
-- text, not uuid: a request can point at a cart line that has no cloud row yet.
create table if not exists public.staff_requests (
    id                uuid primary key,
    business_id       uuid not null,
    -- Free-form in v1: 'discount' | 'void' | 'price_override' | 'credit_limit' | …
    type              text not null,
    target_type       text,
    target_id         text,
    -- Snapshot for display, so the admin sees WHAT they are approving without
    -- the referenced row having reached the cloud yet.
    target_name       text,
    amount            numeric,
    note              text,
    requested_by      text,
    requested_by_name text,
    status            text not null default 'pending',
    decided_by        text,
    decided_by_name   text,
    -- Non-null == decided. The push partitions on this alone (a pending row goes
    -- up insert-once, a decided row merge-upserts), so a device never has to
    -- know its own role to push correctly.
    decided_at        timestamptz,
    -- The approved action was actually consumed by the requester. Device-local
    -- on the cashier side today; a convenience, never a correctness field.
    applied           boolean not null default false,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

create index if not exists idx_staff_requests_pending
    on public.staff_requests (business_id, status, created_at desc);

-- Money from OUTSIDE the shop, or out of it to the owner. Neither takings nor
-- expense — this is the equity/liability side of the till-and-safe model, and it
-- is its own ledger for a reason the cash tables cannot accommodate:
--
--   A row is written WHETHER OR NOT SHOP CASH MOVED. Paying a supplier straight
--   from the owner's pocket never touches the drawer and writes no cash movement
--   at all. Folding these into `cash_movements` would therefore post non-cash
--   events into the ledger every till balance is derived from, and the drawer
--   would read wrong on every device in the shop.
--
-- The split the schema had no way to say, said in two columns:
--   kind='capital'  the OWNER's own money. 'in' raises what the shop owes the
--                   owner; 'out' is a drawing that pays some of it back. NEVER
--                   an expense — it must not touch profit.
--   kind='loan'     money BORROWED from outside. A liability; 'out' is a
--                   repayment.
-- `amount` is always POSITIVE and `direction` carries the sign, so "put in" and
-- "taken out" total separately without a SIGN() in every query.
create table if not exists public.outside_funds (
    id                uuid primary key,
    business_id       uuid not null,
    kind              text not null default 'capital',
    direction         text not null default 'in',
    amount            numeric not null default 0,
    -- 'Owner', a lender's name, …
    source            text,
    note              text,
    -- What it funded: 'expense' | 'purchase_order' | 'cash'.
    ref_type          text,
    ref_id            text,
    created_by        text,
    created_by_name   text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

-- The ONE place in section 7c that IS constrained, batch-kill risk accepted.
-- These two columns are the entire semantic content of the table: a row whose
-- `kind` is a typo is not a slightly-wrong row, it is money that has silently
-- moved between what the shop owes its owner and what it owes a lender. A push
-- that fails loudly is recoverable; that is not.
do ${'$'}${'$'} begin
    alter table public.outside_funds add constraint outside_funds_kind_check
        check (kind = any (array['capital','loan']));
exception when duplicate_object then null; end ${'$'}${'$'};

do ${'$'}${'$'} begin
    alter table public.outside_funds add constraint outside_funds_direction_check
        check (direction = any (array['in','out']));
exception when duplicate_object then null; end ${'$'}${'$'};

create index if not exists idx_outside_funds_created
    on public.outside_funds (business_id, created_at desc);

-- ---------------------------------------------------------------------------
-- 7.5) TWO CLOCKS, TWO JOBS — the trigger every table needs.
--
-- Leaving this out does not break anything loudly. It breaks sync quietly, which
-- is worse, and it is exactly what happened to the project this section was
-- written for.
--
--   `updated_at` is the SERVER's, and it is what every pull cursor pages on. It
--   has to be monotonic and immune to a device with a wrong clock. With only a
--   DEFAULT, that holds for a client which OMITS the column — but a client that
--   SENDS it writes its own browser clock straight into the cursor column. Two
--   clients then order the same log by two different clocks: one stamps a row
--   slightly ahead, the other's cursor jumps past it, and every row behind it is
--   never pulled again. No error, no retry; a day's takings simply never arrive.
--
--   `client_updated_at` is the DEVICE's, and it decides conflicts. Without it
--   "last write wins" is really "last PUSH wins": a till offline since morning
--   overwrites an afternoon edit made elsewhere the moment it reconnects,
--   because its rows get a fresh server timestamp on arrival.
--
-- The stale-write guard lives here rather than in either client so that it also
-- covers direct SQL and any future client.
-- ---------------------------------------------------------------------------
create or replace function public.auth_org_role()
returns text language sql stable set search_path to '' as ${'$'}${'$'}
  select nullif(auth.jwt() ->> 'org_role', '')
${'$'}${'$'};

create or replace function public.set_updated_at()
returns trigger language plpgsql set search_path to '' as ${'$'}${'$'}
begin
  -- An update carrying an OLDER client timestamp than the row already has is a
  -- stale write from a device that has been out of touch. Returning OLD leaves
  -- the row exactly as it is: the write is IGNORED rather than rejected, so a
  -- catching-up device is not stuck retrying a batch it can never land.
  if tg_op = 'UPDATE'
     and new.client_updated_at is not null
     and old.client_updated_at is not null
     and new.client_updated_at < old.client_updated_at then
    return old;
  end if;

  new.updated_at := now();
  -- Defaulted when absent (a client that knows nothing about the column) and
  -- capped a day ahead, so a clock set to 2049 cannot win every conflict on this
  -- shop forever.
  new.client_updated_at := least(coalesce(new.client_updated_at, now()), now() + interval '1 day');
  return new;
end;
${'$'}${'$'};

do ${'$'}${'$'}
declare t text;
begin
  foreach t in array array[
    'businesses','staff','items','item_attributes','stock_movements','customers','credit_txns',
    'cash_sessions','cash_movements','sales','sale_items','sale_payments',
    'refunds','refund_items','refund_payments','mobile_money_receipts',
    'audit_entries','expenses','suppliers','purchase_orders','purchase_order_items',
    -- Section 7c. New tables get the trigger, the tenant policy and the pos2_
    -- view on exactly the same terms as the originals — a table added to this
    -- schema and left out of these three loops is a table with no server clock,
    -- no tenant isolation and no back-office door.
    'notifications','staff_requests','outside_funds'
  ] loop
    execute format('drop trigger if exists %I on public.%I', t || '_set_updated_at', t);
    execute format(
      'create trigger %I before insert or update on public.%I ' ||
      'for each row execute function public.set_updated_at()',
      t || '_set_updated_at', t
    );
  end loop;
end ${'$'}${'$'};

-- Fuzzy matching for the fitment search ("hiace" finding "Toyota HiAce"). Kept
-- optional: a project where the extension cannot be installed should still end
-- up with a working till rather than a failed script.
do ${'$'}${'$'}
begin
  create extension if not exists pg_trgm with schema extensions;
  create index if not exists idx_item_attributes_value_trgm
    on public.item_attributes using gin (value_norm extensions.gin_trgm_ops);
exception when others then
  raise notice 'pg_trgm not available, skipping the fuzzy tag index: %', sqlerrm;
end ${'$'}${'$'};

-- ---------------------------------------------------------------------------
-- 7d) Paynow payment intents — the ONE table the tills may not read.
--
-- Written ONLY by the paynow-* Edge Functions with the service-role key. It
-- holds the merchant reference, Paynow's poll URL and the settled status of
-- every online payment, which is precisely the data that must not be reachable
-- with the anon key every till carries: anyone who can read `paynow_poll_url`
-- can poll a stranger's transaction, and anyone who can write `status` can mark
-- an unpaid sale paid.
--
-- So this table is the deliberate exception to section 8 — RLS is ON and there
-- is NO anon/authenticated policy at all. Service role bypasses RLS, so the
-- functions keep working and nothing else can touch it. Do NOT add it to the
-- table-name lists in sections 8 or 9; being absent from them is the point.
-- ---------------------------------------------------------------------------
create table if not exists public.payment_intents (
    -- OUR merchant reference — what we send Paynow as `reference`, and the id
    -- the webhook matches an incoming status update back to.
    id                text primary key,
    business_id       uuid,
    -- Set once a paid intent has been turned into a sale; null while pending.
    sale_id           uuid,
    amount            numeric not null default 0,
    currency          text default 'USD',
    -- Our own small vocabulary: created | sent | paid | cancelled | failed.
    status            text not null default 'created',
    -- Paynow's own reference. Null until Paynow first reports one, which for a
    -- mobile transaction is the first status update, NOT the initiate reply.
    paynow_reference  text,
    paynow_poll_url   text,
    -- The Paynow page rendered as a QR at the counter (web/initiatetransaction).
    browser_url       text,
    -- Express checkout (remotetransaction) only.
    method            text,
    phone             text,
    -- Unique per merchant, max 32 chars. Lets /interface/trace find a
    -- transaction whose initiate reply we never received.
    merchant_trace    text,
    -- Paynow's status string verbatim, before it is folded into `status`.
    -- "Awaiting Delivery" and "Delivered" both mean the money is in.
    raw_status        text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create unique index if not exists idx_payment_intents_trace
    on public.payment_intents (merchant_trace)
    where merchant_trace is not null;

-- The "payments that never settled" list an admin screen reads.
create index if not exists idx_payment_intents_unsettled
    on public.payment_intents (business_id, created_at desc)
    where status not in ('paid', 'cancelled', 'failed');

alter table public.payment_intents enable row level security;
revoke all on public.payment_intents from anon, authenticated;
grant all on public.payment_intents to service_role;

-- ---------------------------------------------------------------------------
-- 8) Row-level security — one tenant policy per table, matching the live shop.
--
-- ★ `staff_requests` is on the SAME uniform policy as every other table, and
-- that is a decision, not an omission. The obvious tightening — staff may
-- INSERT and SELECT, only an admin may UPDATE — is exactly what the till's push
-- is already built for (a pending row goes up insert-once so it cannot trip an
-- UPDATE policy; only a decided row merge-upserts). But `auth_org_role()`
-- returns NULL for an anon-key client, which is how every till on a single-shop
-- database connects, so the gate below would deny every approval in the shop
-- rather than just the cashier's. It becomes correct the day the tills carry a
-- JWT with an `org_role` claim, and not one day sooner:
--
--   create policy staff_requests_decide on public.staff_requests for update
--     to authenticated
--     using (business_id = auth_org_id() and auth_org_role() = 'admin')
--     with check (business_id = auth_org_id() and auth_org_role() = 'admin');
--
-- Until then the split-mode push is a correctness measure the client keeps for
-- itself. Do not read the till's comments as a description of this schema.
-- ---------------------------------------------------------------------------
do ${'$'}${'$'}
declare t text;
begin
  foreach t in array array[
    'businesses','staff','items','item_attributes','stock_movements','customers','credit_txns',
    'cash_sessions','cash_movements','sales','sale_items','sale_payments',
    'refunds','refund_items','refund_payments','mobile_money_receipts',
    'audit_entries','expenses','suppliers','purchase_orders','purchase_order_items',
    -- Section 7c. New tables get the trigger, the tenant policy and the pos2_
    -- view on exactly the same terms as the originals — a table added to this
    -- schema and left out of these three loops is a table with no server clock,
    -- no tenant isolation and no back-office door.
    'notifications','staff_requests','outside_funds'
  ] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists %I on public.%I', t || '_tenant_rw', t);
    execute format(
      'create policy %I on public.%I for all to anon, authenticated ' ||
      'using (auth_org_id() is null or business_id = auth_org_id()) ' ||
      'with check (auth_org_id() is null or business_id = auth_org_id())',
      t || '_tenant_rw', t
    );
    execute format(
      'create index if not exists %I on public.%I (business_id, client_updated_at)',
      'idx_' || t || '_client_updated', t
    );
  end loop;
end ${'$'}${'$'};

-- ---------------------------------------------------------------------------
-- 9) pos2_* views — how the WEB back-office addresses this same data.
--
-- The two clients are not on two schemas. The web reads and writes `pos2_items`,
-- `pos2_sales` and the rest, which are plain views over the very tables above;
-- the Android till reads and writes the tables directly. Skip this section and
-- you get a working till and a back-office that reports "the POS tables are
-- missing", against a database that has every one of them.
--
-- `security_invoker = true` is NOT optional. A view runs as its OWNER by
-- default, the owner here is `postgres`, and postgres BYPASSES row-level
-- security — so without it every view below is an unrestricted door around the
-- tenant policy just installed on the table beneath it. (Requires PG15+; both
-- Supabase projects this targets are PG17.)
--
-- `select *` is expanded at creation, so these freeze the column list as it
-- stands. They are dropped and recreated rather than `create or replace`d,
-- because replace cannot change a view's column list and re-running this script
-- after a schema change would otherwise fail.
-- ---------------------------------------------------------------------------
do ${'$'}${'$'}
declare t text;
declare v text;
begin
  foreach t in array array[
    'businesses','staff','items','item_attributes','stock_movements','customers','credit_txns',
    'cash_sessions','cash_movements','sales','sale_items','sale_payments',
    'refunds','refund_items','refund_payments','mobile_money_receipts',
    'audit_entries','expenses','suppliers','purchase_orders','purchase_order_items',
    -- Section 7c. New tables get the trigger, the tenant policy and the pos2_
    -- view on exactly the same terms as the originals — a table added to this
    -- schema and left out of these three loops is a table with no server clock,
    -- no tenant isolation and no back-office door.
    'notifications','staff_requests','outside_funds'
  ] loop
    v := 'pos2_' || t;
    execute format('drop view if exists public.%I', v);
    execute format('create view public.%I with (security_invoker = true) as select * from public.%I', v, t);
    execute format('grant select, insert, update, delete on public.%I to anon, authenticated', v);
  end loop;
end ${'$'}${'$'};
"""
