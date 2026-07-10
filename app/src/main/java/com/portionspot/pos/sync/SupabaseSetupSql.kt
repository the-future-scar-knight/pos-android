package com.portionspot.pos.sync

/**
 * The one-time Supabase setup script the app hands the user when their project is
 * empty (see the "Set up your database" flow in CloudSyncSection). It creates the FIVE
 * tables this app syncs to — matching the shared web-POS contract the engine reads/writes
 * (see [SyncConfig.TABLES] and the DTOs in Dtos.kt), NOT the older items/sale_items shape.
 *
 * Bundled in-app (not an asset) so it is available offline on the device. Fully
 * idempotent — every statement is `if not exists` / `drop … if exists` then recreate —
 * so pasting it into an ALREADY-configured project is a harmless no-op that never drops
 * or overwrites data.
 *
 * Keep this in lockstep with the repo `supabase-setup.sql` (the two are one source of
 * truth) and with the column names in Dtos.kt.
 */
const val SUPABASE_SETUP_SQL: String = """-- ============================================================================
-- ON-SPOT POS — Supabase setup (bring-your-own-database)
-- Run ONCE: Supabase Dashboard -> SQL Editor -> New query -> paste -> Run.
-- Safe to re-run: every statement is "if not exists", so it never drops your data.
-- The app connects with your project's anon (public) key.
-- ============================================================================

-- 1) Tables ------------------------------------------------------------------

create table if not exists public.products (
    id                  bigint generated always as identity primary key,
    sku                 text not null unique,
    name                text not null,
    category            text,
    box_price           numeric not null default 0,
    box_size            integer not null default 1,
    wholesale_price     numeric not null default 0,
    retail_price        numeric not null default 0,
    cost_price          numeric not null default 0,
    stock_boxes         integer not null default 0,
    stock_units         integer not null default 0,
    low_stock_threshold integer not null default 5,
    active              boolean not null default true,
    product_type        text    not null default 'box',
    box_only            boolean not null default false,
    image_url           text,
    show_image          boolean not null default true,
    updated_at          timestamptz not null default now()
);

create table if not exists public.customers (
    id               bigint generated always as identity primary key,
    local_id         text unique,
    name             text not null,
    phone            text,
    email            text,
    address          text,
    notes            text,
    balance          numeric not null default 0,
    is_trade_account boolean not null default false,
    updated_at       timestamptz not null default now()
);

create table if not exists public.sales (
    id             text primary key,          -- the receipt ref string
    ref            text,
    type           text not null default 'sale',      -- sale | return | quote
    status         text not null default 'completed',
    customer_id    text,
    customer_name  text,
    items          jsonb not null default '[]'::jsonb,
    payments       jsonb not null default '[]'::jsonb,
    subtotal       numeric not null default 0,
    total_discount numeric not null default 0,
    vat_amount     numeric not null default 0,
    grand_total    numeric not null default 0,
    amount_paid    numeric not null default 0,
    change_given   numeric not null default 0,
    amount_owing   numeric not null default 0,
    pay_method     text,
    cashier        text,
    cashier_id     text,
    notes          text,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

create table if not exists public.credit_transactions (
    id            bigint generated always as identity primary key,
    local_id      text unique,
    customer_id   text,                        -- customers.id (bigint) as text
    customer_name text,
    type          text not null,               -- credit_owed | credit_paid
    amount        numeric not null default 0,
    note          text,
    cashier       text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create table if not exists public.mobile_money_receipts (
    id                    bigint generated always as identity primary key,
    local_id              text,
    provider              text not null default 'unknown',
    txn_code              text not null unique,
    amount                numeric not null default 0,
    currency              text not null default 'USD',
    sender                text,
    sender_name           text,
    sender_phone          text,
    raw_body              text,
    received_at           timestamptz,
    status                text not null default 'unmatched',
    matched_customer_id   text,
    matched_customer_name text,
    purpose               text,
    note                  text,
    cashier               text,
    cashier_id            text,
    updated_at            timestamptz not null default now()
);

-- 2) Indexes for the "pull rows changed since <cursor>" query ----------------

create index if not exists idx_products_updated_at   on public.products (updated_at);
create index if not exists idx_customers_updated_at  on public.customers (updated_at);
create index if not exists idx_sales_updated_at      on public.sales (updated_at);
create index if not exists idx_credit_updated_at     on public.credit_transactions (updated_at);
create index if not exists idx_mmr_updated_at        on public.mobile_money_receipts (updated_at);

-- 3) Access for the anon (public) key ----------------------------------------
-- The app talks to these tables as the anon/authenticated role. RLS is enabled
-- with permissive policies so the access is explicit. Anyone holding BOTH your
-- URL and anon key can read/write this data — keep them private, use a project
-- dedicated to this shop, and tighten these policies later if you wish.

alter table public.products               enable row level security;
alter table public.customers              enable row level security;
alter table public.sales                  enable row level security;
alter table public.credit_transactions    enable row level security;
alter table public.mobile_money_receipts  enable row level security;

drop policy if exists pos_all on public.products;
create policy pos_all on public.products
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_all on public.customers;
create policy pos_all on public.customers
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_all on public.sales;
create policy pos_all on public.sales
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_all on public.credit_transactions;
create policy pos_all on public.credit_transactions
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_all on public.mobile_money_receipts;
create policy pos_all on public.mobile_money_receipts
    for all to anon, authenticated using (true) with check (true);

grant usage on schema public to anon, authenticated;
grant all on
    public.products,
    public.customers,
    public.sales,
    public.credit_transactions,
    public.mobile_money_receipts
to anon, authenticated;

-- Done. Copy your Project URL and anon (public) key from
-- Dashboard -> Project Settings -> API, then paste them into the app under
-- Settings -> Cloud sync, and tap "Connect & sync".
"""
