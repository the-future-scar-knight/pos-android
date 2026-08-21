-- ═══════════════════════════════════════════════════════════════════════════════
-- Expense approval, on the shared schema
-- 19 August 2026 · additive only · safe to run twice
-- ═══════════════════════════════════════════════════════════════════════════════
--
-- WHY
--
-- The cashier phone can now post expenses, and the approval lifecycle that governs them
-- lived only on the device that raised them. `public.expenses` has five columns and the
-- Android `Expense` has thirty-one, so a pushed expense arrived at the owner's phone
-- carrying no lifecycle at all — and the pull, having nothing to read, synthesised the
-- only reading that made sense for a WEB-authored row: approved and posted.
--
-- That was right for the web and wrong the moment a till could raise one. Observed on two
-- phones, 19 August: a cashier posted an expense; it landed on the owner's phone already
-- approved, dropped the owner's net profit, and stayed `pending` on the cashier's. Two
-- phones reporting different profit for the same shop, with nothing on either saying why.
--
-- The state has to travel, so it needs somewhere to travel in.
--
-- WHAT THIS DOES NOT DO
--
-- No column for the funding split (cash / payable / capital) or the recurrence engine.
-- Those are genuinely device-side bookkeeping — a cash portion means "out of THIS drawer",
-- and there is no `cash_txns` row on another device for money this one spent. Only the
-- lifecycle travels, because only the lifecycle is a shared fact about the expense.
--
-- ★ `status` DEFAULTS TO 'approved', WHICH IS DELIBERATE AND IS ONLY ABOUT WRITERS THAT
-- DO NOT KNOW ABOUT IT. Every existing row was authored by the web or by an older build,
-- and every one of them is a cost that was actually incurred; defaulting them to 'pending'
-- would silently withdraw them from the books and drop them into an approval queue for
-- decisions nobody remembers. The web keeps inserting without the column and keeps getting
-- 'approved', which is the honest reading of a client that has no approval step. The
-- Android client always sends an explicit value, so the default never applies to it.

alter table public.expenses
    add column if not exists status            text        not null default 'approved',
    add column if not exists submitted_by      text,
    add column if not exists submitted_by_name text,
    add column if not exists approved_by       text,
    add column if not exists approved_by_name  text,
    add column if not exists approved_at       timestamptz,
    add column if not exists posted_at         timestamptz;

-- No CHECK on `status`. The vocabulary is pending | approved | rejected today and a CHECK
-- violation fails the WHOLE upsert batch, not the offending row — one unrecognised value
-- would strand every other expense in the same push. The same call was made for the
-- notification and staff-request vocabularies, for the same reason.

comment on column public.expenses.status is
    'pending | approved | rejected. Defaults to approved for writers with no approval step (the web).';
comment on column public.expenses.approved_at is
    'When the owner approved it. Null while pending.';
comment on column public.expenses.posted_at is
    'When it hit the books. Equals approved_at for one-off expenses.';

-- The pull pages on (business_id, updated_at); nothing here changes that, and the index
-- for it already exists. No new index is needed: the approval queue is read from the
-- DEVICE, never by asking the cloud for pending rows.
