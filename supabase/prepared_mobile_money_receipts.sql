-- PREPARED, NOT YET APPLIED — review then run in the Supabase SQL editor (or via
-- apply_migration). Purely ADDITIVE: a new table the web POS never touches, so it
-- cannot affect the website or the existing shared tables.
--
-- Backs the Android mobile-money SMS reconciliation feature (prompt §6) across
-- devices. Keyed for idempotency on txn_code (the provider's unique reference) so
-- the same payment SMS can never be stored twice; bridged to the Android row by
-- local_id (the UUID), matching the customers/credit convention. RLS mirrors the
-- other POS tables: only signed-in staff can read/write.

create table if not exists public.mobile_money_receipts (
  id                     bigint generated always as identity primary key,
  local_id               text unique,                 -- Android UUID (sync bridge)
  provider               text not null default 'unknown',
  txn_code               text not null,               -- provider reference = idempotency key
  amount                 numeric not null default 0,
  currency               text not null default 'USD',
  sender                 text,                         -- SMS originating address
  sender_name            text,
  sender_phone           text,
  raw_body               text,                         -- original SMS (audit / re-parse)
  received_at            timestamptz,
  status                 text not null default 'unmatched', -- unmatched|needs_verification|verified|ignored
  matched_customer_id    text,
  matched_customer_name  text,
  purpose                text,                         -- debt | sale
  note                   text,
  cashier                text,                         -- attribution (display name)
  cashier_id             text,                         -- attribution (auth uid)
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),
  constraint mobile_money_receipts_txn_code_key unique (txn_code)
);

create index if not exists mobile_money_receipts_status_idx
  on public.mobile_money_receipts (status);

alter table public.mobile_money_receipts enable row level security;

-- Staff-only access (same is_pos_staff() helper the products/sales/customers
-- policies use). No anon policy: mobile-money data is never public.
create policy pos_mm_staff_select on public.mobile_money_receipts
  for select using (is_pos_staff());
create policy pos_mm_staff_insert on public.mobile_money_receipts
  for insert with check (is_pos_staff());
create policy pos_mm_staff_update on public.mobile_money_receipts
  for update using (is_pos_staff());
create policy pos_mm_admin_delete on public.mobile_money_receipts
  for delete using (is_pos_admin());

-- After this exists, the Android side (deferred until the table is live so it can
-- be tested): add a MobileMoneyDto + pull/push in PosSyncEngine, upserting on
-- txn_code (idempotent) under the same pushEnabled gate as sales/refunds.
