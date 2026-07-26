package com.portionspot.pos.sync

/**
 * The one-time Supabase setup script the app hands the user when their project is
 * empty (see the "Set up your database" flow in CloudSyncSection). It creates EVERY
 * table this app syncs to — the shared web-POS contract for the till core, plus the
 * accounting spine, supplier orders, the admin alert feed, the audit trail, the
 * approval channel and the staff roster (see [SyncConfig.TABLES] and the DTOs in
 * Dtos.kt).
 *
 * Bundled in-app (not an asset) so it is available offline on the device. Fully
 * idempotent — every statement is `if not exists` / `add column if not exists` — so
 * pasting it into an ALREADY-configured project is a harmless no-op that never drops
 * or overwrites data, and re-running an OLDER setup upgrades it in place.
 *
 * Policies here are deliberately permissive (`anon`/`authenticated` may read and
 * write). A bring-your-own database starts with no staff rows, so the staff-gated
 * policies used by a managed shop database would lock the owner out of their own
 * data on day one.
 *
 * Keep this in lockstep with the repo `supabase-setup.sql` — this constant is
 * GENERATED from that file, which is the source of truth.
 */
const val SUPABASE_SETUP_SQL: String = """
-- ============================================================================
-- ON-SPOT POS — Supabase setup (bring-your-own-database)
-- ----------------------------------------------------------------------------
-- Run this ONCE in your own Supabase project: Dashboard -> SQL Editor -> New
-- query -> paste -> Run. It creates every table the app syncs to.
--
-- Safe to run more than once and safe on an existing project: every statement
-- uses "if not exists" / "add column if not exists", so it never drops or
-- overwrites your data. Re-running an OLDER setup upgrades it in place.
--
-- The app connects with your project's anon (public) key. Kept in lockstep with
-- the in-app copy at app/.../sync/SupabaseSetupSql.kt.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) Till core — products, customers, sales, credit/change, mobile money
-- ---------------------------------------------------------------------------

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
    product_type        text    not null default 'box',   -- box | set | piece | measured
    box_only            boolean not null default false,
    image_url           text,
    show_image          boolean not null default true,
    -- Measured goods, sold by weight/volume/length (e.g. 2.5 kg)
    unit                text,
    price_per_unit      numeric default 0,
    stock_measured      numeric default 0,
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
    credit_limit     numeric,
    is_trade_account boolean not null default false,
    source           text not null default 'pos',
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);

create table if not exists public.sales (
    id                  text primary key,          -- the receipt ref string
    ref                 text,
    type                text not null default 'sale',      -- sale | return | quote
    status              text not null default 'completed',
    customer_id         text,
    customer_name       text,
    items               jsonb not null default '[]'::jsonb,
    payments            jsonb not null default '[]'::jsonb,
    subtotal            numeric not null default 0,
    line_discount_total numeric not null default 0,
    sale_discount       numeric not null default 0,
    sale_discount_pct   numeric not null default 0,
    total_discount      numeric not null default 0,
    discount_pct        numeric not null default 0,
    markup_total        numeric not null default 0,
    vat_enabled         boolean not null default false,
    vat_amount          numeric not null default 0,
    grand_total         numeric not null default 0,
    amount_paid         numeric not null default 0,
    change_given        numeric not null default 0,
    change_owed         numeric not null default 0,
    amount_owing        numeric not null default 0,
    pay_method          text,
    cashier             text,
    cashier_id          text,
    notes               text,
    hold_name           text,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create table if not exists public.credit_transactions (
    id            bigint generated always as identity primary key,
    local_id      text unique,
    sale_id       text,
    customer_id   text,                        -- customers.id (bigint) as text
    customer_name text,
    type          text not null,               -- credit_owed | credit_paid | change_owed | change_paid
    amount        numeric not null default 0,
    note          text,
    cashier       text,
    settled       boolean not null default false,
    settled_at    timestamptz,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create table if not exists public.mobile_money_receipts (
    id                    bigint generated always as identity primary key,
    local_id              text unique,
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
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- 2) Accounting spine — expenses and the cash-on-hand ledger
-- ---------------------------------------------------------------------------

create table if not exists public.expenses (
    id                bigint generated always as identity primary key,
    local_id          text unique,
    business_id       text,
    category          text default 'Other',
    amount            numeric default 0,
    date              date,
    description       text,
    -- approval lifecycle
    status            text default 'pending',   -- pending | approved | rejected
    submitted_by      text,
    submitted_by_name text,
    approved_by       text,
    approved_by_name  text,
    approved_at       bigint,                   -- epoch millis
    posted_at         bigint,                   -- epoch millis
    -- how it was funded (these three sum to amount)
    cash_portion      numeric default 0,        -- paid from the drawer
    payable_portion   numeric default 0,        -- still owed to the payee
    capital_portion   numeric default 0,        -- the owner covered it
    -- recurring schedule
    recurring         boolean default false,
    recurrence_period text,                     -- daily | weekly | monthly
    recurrence_active boolean default true,
    is_template       boolean default false,
    template_id       text,
    next_run_at       bigint,
    last_run_at       bigint,
    period_start      date,
    period_end        date,
    created_at        timestamptz default now(),
    updated_at        timestamptz default now(),
    deleted           boolean default false
);

create table if not exists public.cash_txns (
    id              bigint generated always as identity primary key,
    local_id        text unique,
    business_id     text,
    type            text,                       -- sale | expense | purchase | payout | capital | adjust
    amount          numeric default 0,          -- signed: + into the drawer, - out of it
    source          text,
    note            text,
    ref_type        text,
    ref_id          text,
    created_by      text,
    created_by_name text,
    created_at      timestamptz default now(),
    updated_at      timestamptz default now(),
    deleted         boolean default false
);

-- ---------------------------------------------------------------------------
-- 3) Supply — suppliers and purchase orders
-- ---------------------------------------------------------------------------

create table if not exists public.suppliers (
    id          bigint generated always as identity primary key,
    local_id    text unique,
    business_id text,
    name        text,
    phone       text,
    email       text,
    address     text,
    notes       text,
    created_at  timestamptz default now(),
    updated_at  timestamptz default now(),
    deleted     boolean default false
);

create table if not exists public.purchase_orders (
    id                  bigint generated always as identity primary key,
    local_id            text unique,
    business_id         text,
    ref                 text,
    supplier_id         text,
    supplier_name       text default '',
    status              text default 'draft',   -- draft | placed | partial | received | cancelled
    notes               text,
    eta                 bigint,                 -- epoch millis
    cash_paid           numeric default 0,
    capital_paid        numeric default 0,
    payable_remainder   numeric default 0,
    arrival_prompted_at bigint,
    sent_at             bigint,
    received_at         bigint,
    created_at          timestamptz default now(),
    updated_at          timestamptz default now(),
    deleted             boolean default false
);

-- Lines link to their order by po_local_id (the device's own stable key) rather
-- than a hard foreign key, so an offline-first push can never wedge on
-- parent/child ordering.
create table if not exists public.purchase_order_items (
    id               bigint generated always as identity primary key,
    local_id         text unique,
    po_local_id      text,
    item_id          text,
    name             text default '',
    sku              text,
    qty              numeric default 1,
    unit_cost        numeric default 0,
    sell_price       numeric,
    stock_on_arrival boolean default true,
    product_type     text default 'piece',
    received_qty     numeric,
    created_at       timestamptz default now(),
    updated_at       timestamptz default now()
);

-- ---------------------------------------------------------------------------
-- 4) Team — alerts, audit trail, approval requests, staff roster
-- ---------------------------------------------------------------------------

-- Alerts converge on ONE row per condition per shop, so two phones noticing the
-- same thing update the same row instead of duplicating it.
create table if not exists public.notifications (
    id          bigint generated always as identity primary key,
    local_id    text,
    business_id text,
    category    text,
    severity    text default 'info',            -- info | warn | danger
    audience    text not null default 'admin',  -- admin | cashier | all
    title       text,
    body        text,
    dedupe_key  text,
    ref_type    text,
    ref_id      text,
    event_at    bigint,                         -- epoch millis
    read_at     bigint,                         -- epoch millis, null = unread
    created_at  timestamptz default now(),
    updated_at  timestamptz default now(),
    deleted     boolean default false,
    constraint notifications_business_dedupe_key unique (business_id, dedupe_key)
);

-- Append-only. Receipt edits, till shortages/overages and voids recorded on a
-- cashier phone become visible on the admin phone.
create table if not exists public.audit_log (
    id          bigint generated always as identity primary key,
    local_id    text unique,
    business_id text,
    action      text not null,
    entity_type text,
    entity_id   text,
    summary     text,
    meta        text,
    user_id     text,
    user_name   text,
    details     jsonb,
    created_at  timestamptz default now(),
    updated_at  timestamptz default now()
);

-- The admin-approval channel: a cashier files a request (a credit limit), the
-- admin decides, and the admin's device applies the change.
create table if not exists public.staff_requests (
    id                bigint generated always as identity primary key,
    local_id          text unique,
    business_id       text,
    type              text,                     -- credit_limit
    target_type       text,                     -- customer
    target_id         text,
    target_name       text,
    amount            numeric,
    note              text,
    requested_by      text,
    requested_by_name text,
    status            text default 'pending',   -- pending | approved | rejected
    decided_by        text,
    decided_by_name   text,
    decided_at        bigint,                   -- epoch millis
    applied           boolean default false,    -- guards against double-application
    created_at        timestamptz default now(),
    updated_at        timestamptz default now(),
    deleted           boolean default false
);

-- Staff roster: powers the lock-screen "Other staff" list and the per-person
-- permission switches. Creating cashier LOGINS additionally needs Supabase Auth
-- and the create-cashier Edge Function; the till works fully without that.
create table if not exists public.pos_staff (
    id           uuid primary key,                  -- matches the auth user id
    role         text not null default 'cashier',   -- admin | cashier
    display_name text not null default '',
    email        text,
    permissions  jsonb not null default '{}'::jsonb,
    active       boolean not null default true,
    created_by   uuid,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- 5) Upgrade an older setup in place (no-ops on a fresh database)
-- ---------------------------------------------------------------------------

alter table public.products add column if not exists box_only       boolean not null default false;
alter table public.products add column if not exists image_url      text;
alter table public.products add column if not exists show_image     boolean not null default true;
alter table public.products add column if not exists unit           text;
alter table public.products add column if not exists price_per_unit numeric default 0;
alter table public.products add column if not exists stock_measured numeric default 0;

alter table public.customers add column if not exists source     text not null default 'pos';
alter table public.customers add column if not exists created_at timestamptz not null default now();

alter table public.sales add column if not exists line_discount_total numeric not null default 0;
alter table public.sales add column if not exists sale_discount       numeric not null default 0;
alter table public.sales add column if not exists sale_discount_pct   numeric not null default 0;
alter table public.sales add column if not exists discount_pct        numeric not null default 0;
alter table public.sales add column if not exists markup_total        numeric not null default 0;
alter table public.sales add column if not exists vat_enabled         boolean not null default false;
alter table public.sales add column if not exists hold_name           text;

alter table public.credit_transactions add column if not exists sale_id    text;
alter table public.credit_transactions add column if not exists settled    boolean not null default false;
alter table public.credit_transactions add column if not exists settled_at timestamptz;

alter table public.mobile_money_receipts add column if not exists created_at timestamptz not null default now();

alter table public.notifications add column if not exists audience text not null default 'admin';

alter table public.pos_staff add column if not exists email       text;
alter table public.pos_staff add column if not exists permissions jsonb not null default '{}'::jsonb;

-- ---------------------------------------------------------------------------
-- 6) Indexes for the "pull rows changed since <cursor>" query
-- ---------------------------------------------------------------------------

create index if not exists idx_products_updated_at    on public.products (updated_at);
create index if not exists idx_customers_updated_at   on public.customers (updated_at);
create index if not exists idx_sales_updated_at       on public.sales (updated_at);
create index if not exists idx_credit_updated_at      on public.credit_transactions (updated_at);
create index if not exists idx_mmr_updated_at         on public.mobile_money_receipts (updated_at);
create index if not exists idx_expenses_updated_at    on public.expenses (updated_at);
create index if not exists idx_cash_txns_updated_at   on public.cash_txns (updated_at);
create index if not exists idx_suppliers_updated_at   on public.suppliers (updated_at);
create index if not exists idx_po_updated_at          on public.purchase_orders (updated_at);
create index if not exists idx_po_items_updated_at    on public.purchase_order_items (updated_at);
create index if not exists idx_notifications_updated  on public.notifications (updated_at);
create index if not exists idx_audit_log_updated_at   on public.audit_log (updated_at);
create index if not exists idx_staff_requests_updated on public.staff_requests (updated_at);

create index if not exists idx_expenses_business      on public.expenses (business_id);
create index if not exists idx_cash_txns_business     on public.cash_txns (business_id);
create index if not exists idx_suppliers_business     on public.suppliers (business_id);
create index if not exists idx_po_business            on public.purchase_orders (business_id);
create index if not exists idx_po_items_po            on public.purchase_order_items (po_local_id);
create index if not exists idx_notifications_business on public.notifications (business_id);
create index if not exists idx_audit_log_business     on public.audit_log (business_id);
create index if not exists idx_staff_requests_status  on public.staff_requests (status);

-- ---------------------------------------------------------------------------
-- 7) Access for the anon (public) key
-- ---------------------------------------------------------------------------
-- The app talks to these tables as the anon/authenticated role. RLS is enabled
-- with permissive policies so the access is explicit. Anyone holding BOTH your
-- URL and anon key can read/write this data — keep them private, use a project
-- dedicated to this shop, and tighten these policies later if you wish.

alter table public.products              enable row level security;
alter table public.customers             enable row level security;
alter table public.sales                 enable row level security;
alter table public.credit_transactions   enable row level security;
alter table public.mobile_money_receipts enable row level security;
alter table public.expenses              enable row level security;
alter table public.cash_txns             enable row level security;
alter table public.suppliers             enable row level security;
alter table public.purchase_orders       enable row level security;
alter table public.purchase_order_items  enable row level security;
alter table public.notifications         enable row level security;
alter table public.audit_log             enable row level security;
alter table public.staff_requests        enable row level security;
alter table public.pos_staff             enable row level security;

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

drop policy if exists pos_all on public.expenses;
create policy pos_all on public.expenses
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_all on public.cash_txns;
create policy pos_all on public.cash_txns
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_all on public.suppliers;
create policy pos_all on public.suppliers
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_all on public.purchase_orders;
create policy pos_all on public.purchase_orders
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_all on public.purchase_order_items;
create policy pos_all on public.purchase_order_items
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_all on public.notifications;
create policy pos_all on public.notifications
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_all on public.audit_log;
create policy pos_all on public.audit_log
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_all on public.staff_requests;
create policy pos_all on public.staff_requests
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_all on public.pos_staff;
create policy pos_all on public.pos_staff
    for all to anon, authenticated using (true) with check (true);

grant usage on schema public to anon, authenticated;
grant all on
    public.products,
    public.customers,
    public.sales,
    public.credit_transactions,
    public.mobile_money_receipts,
    public.expenses,
    public.cash_txns,
    public.suppliers,
    public.purchase_orders,
    public.purchase_order_items,
    public.notifications,
    public.audit_log,
    public.staff_requests,
    public.pos_staff
to anon, authenticated;

grant usage, select on all sequences in schema public to anon, authenticated;

-- Done. Copy your Project URL and anon (public) key from
-- Dashboard -> Project Settings -> API, then paste them into the app under
-- Settings -> Cloud sync, and tap "Connect & sync".
"""
