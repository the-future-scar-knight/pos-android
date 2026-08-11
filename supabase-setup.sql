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
returns uuid language sql stable set search_path to '' as $$
  select nullif(auth.jwt() ->> 'org_id', '')::uuid
$$;

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

do $$ begin
    alter table public.staff add constraint staff_role_check
        check (role = any (array['admin','manager','cashier']));
exception when duplicate_object then null; end $$;

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

do $$ begin
    alter table public.items add constraint items_product_type_check
        check (product_type = any (array['box','set','piece','measure']));
exception when duplicate_object then null; end $$;

create index if not exists items_cursor_idx on public.items (business_id, updated_at);
create index if not exists items_sku_idx on public.items (business_id, sku);
create index if not exists items_barcode_idx on public.items (business_id, barcode);

-- Item tags: key/value pairs on a product. In the live shop every row is
-- key = 'car' and the value is a vehicle the part fits, which is how a cashier
-- finds a filter for a Hiace whose product name never says Hiace.
--
-- The unique index is PARTIAL (live rows only) so re-tagging a part that was
-- previously untagged and tombstoned does not collide with the tombstone.
create table if not exists public.item_attributes (
    id                uuid primary key,
    business_id       uuid not null,
    item_id           uuid not null,
    key               text not null,
    value             text not null,
    key_norm          text,
    value_norm        text,
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

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
do $$ begin
    alter table public.cash_sessions add constraint cash_sessions_status_check
        check (status = any (array['open','closed']));
exception when duplicate_object then null; end $$;

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

do $$ begin
    alter table public.cash_movements add constraint cash_movements_type_check
        check (type = any (array['pay_in','pay_out','drop','petty','float_topup','safe_in','bank_deposit']));
exception when duplicate_object then null; end $$;

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
-- 8) Row-level security — one tenant policy per table, matching the live shop.
-- ---------------------------------------------------------------------------
do $$
declare t text;
begin
  foreach t in array array[
    'businesses','staff','items','item_attributes','stock_movements','customers','credit_txns',
    'cash_sessions','cash_movements','sales','sale_items','sale_payments',
    'refunds','refund_items','refund_payments','mobile_money_receipts',
    'audit_entries','expenses','suppliers','purchase_orders','purchase_order_items'
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
end $$;
