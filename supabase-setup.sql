-- ============================================================================
-- PortionSpot POS — Supabase setup (bring-your-own-database)
-- ----------------------------------------------------------------------------
-- Run this ONCE in your own Supabase project: Dashboard -> SQL Editor -> New
-- query -> paste -> Run. It creates the six tables the app syncs to.
--
-- Safe to run more than once and safe to run on an existing project: every
-- statement uses "if not exists", so it never drops or overwrites your data.
-- (A brand-new, empty project is still recommended — see SUPABASE_SETUP.md.)
--
-- No login is used: the app connects with your project's anon (public) key.
-- Timestamps are stored as TEXT in fixed-width UTC ISO format so the sync
-- cursor can compare them as plain strings.
-- ============================================================================

-- 1) Tables ------------------------------------------------------------------

create table if not exists public.businesses (
    id              text primary key,
    name            text not null,
    currency        text not null default 'USD',
    tagline         text,
    address         text,
    phone           text,
    email           text,
    website         text,
    receipt_header  text,
    receipt_footer  text,
    -- VAT / ZIMRA
    vat_enabled      boolean not null default false,
    vat_number       text,
    vat_percent      double precision not null default 15,
    -- which payment methods the cashier may offer
    cash_enabled     boolean not null default true,
    card_enabled     boolean not null default false,
    bank_enabled     boolean not null default false,
    paynow_enabled   boolean not null default false,
    ecocash_enabled  boolean not null default false,
    innbucks_enabled boolean not null default false,
    onemoney_enabled boolean not null default false,
    omari_enabled    boolean not null default false,
    -- bank transfer
    bank_name            text,
    bank_branch          text,
    bank_account_name    text,
    bank_account_number  text,
    -- mobile money (the account the buyer pays into)
    ecocash_account_name  text,
    ecocash_phone         text,
    ecocash_merchant_code text,
    innbucks_account_name text,
    innbucks_phone        text,
    onemoney_account_name text,
    onemoney_phone        text,
    omari_account_name    text,
    omari_phone           text,
    -- Paynow: ID only. The secret Integration KEY is NEVER stored in the cloud;
    -- it stays on the cashier's device and (Stage 3) in an Edge Function secret.
    paynow_integration_id text,
    updated_at      text not null,
    deleted         boolean not null default false
);

-- If you ran an earlier version of this script, add the payment/VAT columns to
-- an existing businesses table (no-op when they already exist).
alter table public.businesses add column if not exists vat_enabled      boolean not null default false;
alter table public.businesses add column if not exists vat_number       text;
alter table public.businesses add column if not exists vat_percent      double precision not null default 15;
alter table public.businesses add column if not exists cash_enabled     boolean not null default true;
alter table public.businesses add column if not exists card_enabled     boolean not null default false;
alter table public.businesses add column if not exists bank_enabled     boolean not null default false;
alter table public.businesses add column if not exists paynow_enabled   boolean not null default false;
alter table public.businesses add column if not exists ecocash_enabled  boolean not null default false;
alter table public.businesses add column if not exists innbucks_enabled boolean not null default false;
alter table public.businesses add column if not exists onemoney_enabled boolean not null default false;
alter table public.businesses add column if not exists omari_enabled    boolean not null default false;
alter table public.businesses add column if not exists bank_name            text;
alter table public.businesses add column if not exists bank_branch          text;
alter table public.businesses add column if not exists bank_account_name    text;
alter table public.businesses add column if not exists bank_account_number  text;
alter table public.businesses add column if not exists ecocash_account_name  text;
alter table public.businesses add column if not exists ecocash_phone         text;
alter table public.businesses add column if not exists ecocash_merchant_code text;
alter table public.businesses add column if not exists innbucks_account_name text;
alter table public.businesses add column if not exists innbucks_phone        text;
alter table public.businesses add column if not exists onemoney_account_name text;
alter table public.businesses add column if not exists onemoney_phone        text;
alter table public.businesses add column if not exists omari_account_name    text;
alter table public.businesses add column if not exists omari_phone           text;
alter table public.businesses add column if not exists paynow_integration_id text;

create table if not exists public.items (
    id           text primary key,
    business_id  text not null,
    category_id  text,
    name         text not null,
    barcode      text,
    sku          text,
    category     text,
    price        double precision not null default 0,
    wholesale_price double precision not null default 0,
    box_price       double precision not null default 0,
    box_size        integer          not null default 1,
    cost         double precision,
    tax_rate     double precision not null default 0,
    track_stock  boolean not null default false,
    stock_qty    double precision not null default 0,
    unit         text not null default 'pc',
    color        text,
    is_active    boolean not null default true,
    updated_at   text not null,
    deleted      boolean not null default false
);

-- Wholesale catalog columns (added after first release). Safe to re-run.
alter table public.items add column if not exists category        text;
alter table public.items add column if not exists wholesale_price double precision not null default 0;
alter table public.items add column if not exists box_price       double precision not null default 0;
alter table public.items add column if not exists box_size        integer          not null default 1;

create table if not exists public.sales (
    id              text primary key,
    business_id     text not null,
    receipt_no      text,
    status          text not null default 'completed',
    subtotal        double precision not null default 0,
    discount_total  double precision not null default 0,
    tax_total       double precision not null default 0,
    total           double precision not null default 0,
    payment_method  text not null default 'cash',
    tendered        double precision,
    change_due      double precision,
    payment_ref     text,
    payment_status  text not null default 'paid',
    note            text,
    customer_id     text,
    customer_name   text,
    sold_at         text not null,
    updated_at      text not null,
    deleted         boolean not null default false
);

-- If you ran an earlier version of this script, add the customer columns to an
-- existing sales table (no-op when they already exist).
alter table public.sales add column if not exists customer_id   text;
alter table public.sales add column if not exists customer_name text;
-- Payment reference + status (mobile money / Paynow).
alter table public.sales add column if not exists payment_ref    text;
alter table public.sales add column if not exists payment_status text not null default 'paid';

create table if not exists public.customers (
    id           text primary key,
    business_id  text not null,
    name         text not null,
    phone        text,
    email        text,
    address      text,
    note         text,
    updated_at   text not null,
    deleted      boolean not null default false
);

create table if not exists public.credit_transactions (
    id           text primary key,
    business_id  text not null,
    customer_id  text not null,
    sale_id      text,
    type         text not null,                 -- credit_owed | credit_paid
    amount       double precision not null default 0,
    note         text,
    created_at   text not null,
    updated_at   text not null,
    deleted      boolean not null default false
);

create table if not exists public.sale_items (
    id            text primary key,
    sale_id       text not null,
    business_id   text not null,
    item_id       text,
    name          text not null,
    qty           double precision not null default 1,
    unit_price    double precision not null default 0,
    line_discount double precision not null default 0,
    line_tax      double precision not null default 0,
    line_total    double precision not null default 0,
    updated_at    text not null,
    deleted       boolean not null default false
);

-- Paynow online payments (Stage 3). This table is written ONLY by the Edge
-- Functions using the service-role key; the app never reads or writes it
-- directly, and it is deliberately NOT exposed to the anon key (so payment
-- amounts/references stay private). It exists for idempotency ("prevent
-- duplicate transactions") and so the webhook has somewhere to record results.
create table if not exists public.payment_intents (
    id                text primary key,        -- our merchant reference (a UUID)
    business_id       text,
    sale_id           text,                    -- set once the sale is committed
    amount            double precision not null default 0,
    status            text not null default 'created', -- created|sent|paid|cancelled|failed
    paynow_reference  text,                    -- Paynow's own reference, when known
    paynow_poll_url   text,                    -- where paynow-status polls
    browser_url       text,                    -- the page we render as a QR
    created_at        text not null,
    updated_at        text not null
);

-- 2) Indexes for the "pull rows changed since <cursor>" query ----------------

create index if not exists idx_businesses_updated_at on public.businesses (updated_at);
create index if not exists idx_items_updated_at      on public.items (updated_at);
create index if not exists idx_sales_updated_at      on public.sales (updated_at);
create index if not exists idx_sale_items_updated_at on public.sale_items (updated_at);
create index if not exists idx_customers_updated_at  on public.customers (updated_at);
create index if not exists idx_credit_txn_updated_at on public.credit_transactions (updated_at);

-- 3) Access for the anon (public) key ----------------------------------------
-- The app uses no login, so PostgREST talks to these tables as the `anon`
-- role. We enable Row Level Security and add permissive policies so the
-- access is explicit and Supabase's security linter stays quiet.
--
-- NOTE: anyone holding BOTH your project URL and anon key can read/write this
-- data. That is the trade-off for a no-login setup. Keep them private, and use
-- a project dedicated to this shop. You can tighten these policies later.

alter table public.businesses          enable row level security;
alter table public.items               enable row level security;
alter table public.sales               enable row level security;
alter table public.sale_items          enable row level security;
alter table public.customers           enable row level security;
alter table public.credit_transactions enable row level security;

drop policy if exists pos_anon_all on public.businesses;
create policy pos_anon_all on public.businesses
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_anon_all on public.items;
create policy pos_anon_all on public.items
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_anon_all on public.sales;
create policy pos_anon_all on public.sales
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_anon_all on public.sale_items;
create policy pos_anon_all on public.sale_items
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_anon_all on public.customers;
create policy pos_anon_all on public.customers
    for all to anon, authenticated using (true) with check (true);

drop policy if exists pos_anon_all on public.credit_transactions;
create policy pos_anon_all on public.credit_transactions
    for all to anon, authenticated using (true) with check (true);

grant usage on schema public to anon, authenticated;
grant all on
    public.businesses,
    public.items,
    public.sales,
    public.sale_items,
    public.customers,
    public.credit_transactions
to anon, authenticated;

-- payment_intents is intentionally locked down: RLS on, NO anon/authenticated
-- policy or grant. Only the Edge Functions (service-role key) can touch it, so
-- the anon key that the app holds cannot read other shops' payment data.
alter table public.payment_intents enable row level security;
grant all on public.payment_intents to service_role;

-- Done. Copy your Project URL and anon (public) key from
-- Dashboard -> Project Settings -> API, then paste them into the app:
-- Settings -> Cloud sync.
--
-- For Paynow ONLINE (QR + auto-verify) you also deploy the Edge Functions in
-- supabase/functions and set your Integration ID + Key as function secrets —
-- see PAYNOW_SETUP.md. The secret Key is NEVER stored in any table above.
