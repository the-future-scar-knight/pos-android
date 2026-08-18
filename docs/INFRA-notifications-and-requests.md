# Infrastructure: notifications and cashier requests

Database design for the two features that are built, working, and stranded on one
handset. Applied and verified against the **throwaway** project
`lvaxbbobmounfxskooiw` ("pos-schema-validation") on 2026-08-17. **Not** applied to
production `klkfynokcjunqvbkywfk` ("Shop Data") — the replayable SQL for that is in
§7, to be run by hand after review.

No application code was written. Everything below is DDL plus the reasoning behind it.

---

## 1. What was already there, and what was not

The first surprise is that `public.notifications` and `public.staff_requests`
**already exist** on the throwaway. A previous session added them to
`sync/SupabaseSetupSql.kt` §7c and somebody ran that script. So this was not a
green field; it was an audit of a design that had been written but never
exercised, and the exercise found four things wrong with it.

What was already correct and was left alone:

- Both tables carry `business_id`, `created_at`, `updated_at`, `deleted`,
  `client_updated_at`.
- Both have the `set_updated_at` trigger (server clock + stale-write guard).
- Both have the uniform `*_tenant_rw` RLS policy for `anon, authenticated`.
- Both have a `pos2_*` view with `security_invoker = true`.
- `notifications` has `uq_notifications_dedupe` on `(business_id, dedupe_key)`,
  non-partial so PostgREST can name it as an `on_conflict` target.
- Neither table has CHECK constraints, deliberately.

### 1.1 `pullNotifications` is DEAD CODE

`PosSyncEngine.kt:1298` defines `private suspend fun pullNotifications(...)`. It is
**never called**. `pull()` (line ~747) lists every pull in order and
`pullNotifications` is not among them; a repo-wide grep for the name returns
exactly one hit, its own declaration. It is a leftover from the version of the
engine that asked for six tables the web schema did not have and collected 404s
the owner saw as sync errors. That cleanup deleted the call site and left the
function.

The push side is not merely dead, it is absent: there is no `pushNotifications`
at all, and `SyncConfig.LOCAL_ONLY_TABLES` names `notifications` and
`staff_requests` as having no cloud home. `SyncConfig.TABLES` — the list that
gets a pull cursor — contains neither.

So: **both features are local-only in the running app, and the one piece of
cloud plumbing that survives is unreachable.** The tables existing on the
database changes nothing until the client is rewired.

### 1.2 The four defects found

1. **No pull cursor index.** Every other synced table has
   `(business_id, updated_at)` — `sales_cursor_idx`, `idx_audit_cursor`,
   `idx_expenses_cursor`. These two had only `(business_id, client_updated_at)`,
   which the pull never queries. `SupabaseRest.selectSince` pages on
   `business_id=eq.X&updated_at=gt.<cursor>&order=updated_at.asc`, so every sync
   pass of every phone in the shop would sequentially scan the whole feed. It
   would work, which is why nobody would notice until the feed was big enough to
   make sync feel broken. **Fixed.**

2. **The push DTO names a column that does not exist.** `NotificationPushDto`
   (`sync/Dtos.kt:934`) serialises `local_id`. There was no `local_id` column, so
   PostgREST rejects the entire batch with PGRST204 before a single row lands —
   the whole-batch failure mode this schema keeps warning about, from a
   one-column omission. **Fixed by adding the column** rather than by changing
   Kotlin, because the column is genuinely wanted for tracing and adding it is
   free.

3. **Read state is shop-wide.** `notifications.read_at` is one column on one row
   that every phone shares. The owner opening his alert feed marks the alert read
   on the cashier's phone too, and the cashier never learns the till is short.
   This is the defect the brief called out and it is the substantial part of this
   design. **Fixed by a new `notification_reads` table** — see §4.

4. **A decision alert buzzes every cashier in the shop.**
   `PosRepository.decideStaffRequest` mints the outcome notification with
   `audience = "cashier"`. `audience` is a ROLE, so in a shop with three cashiers
   all three phones fire "Your credit_limit request was approved" and two of them
   are being told about somebody else's request. **Fixed by adding
   `target_staff_id`** — see §3.2.

---

## 2. Vocabularies, enumerated from the code

Read out of the Kotlin, not invented. None of these is enforced as a CHECK —
see §6 for why that is a decision and not laziness.

**`notifications.category`** — from `NotificationEngine.compute` plus the
direct-write call sites in `PosRepository`:

| value | raised by |
|---|---|
| `inventory` | low stock, out of stock, below zero, purchase-order arrival |
| `sales` | large sale, customer over credit limit |
| `payments` | mobile-money receipt left unverified |
| `refunds` | refund left owing |
| `system` | aging debt, device not synced |
| `cash` | day closed, float top-up, safe opened (`notifyCashEvent`) |
| `expenses` | expense awaiting approval, recurring expense posted |
| `requests` | approval requested, request approved/denied |

The first five are the set `PosRepository.ENGINE_CATEGORIES` — the ones the sweep
owns and is allowed to tombstone. The last three are written directly by event
handlers and the sweep must never clear them. That distinction is client
behaviour; the database only needs to not get in its way, which is another reason
`category` is unconstrained.

**`notifications.severity`** — `info` | `warn` | `danger`. `Notifier.notifyAlert`
branches on `danger` alone: danger takes the high-importance channel, everything
else takes the default one.

**`notifications.audience`** — `admin` | `cashier` | `all`. From
`NotificationEngine.audienceMatches`: `all` reaches everyone, `cashier` reaches
non-admins, **anything else including unknown values reaches admins only**. That
default fails safe, which is why an unknown value here cannot hurt anyone and
needs no constraint.

**`notifications.ref_type`** — `item`, `sale`, `refund`, `customer`,
`mm_receipt`, `purchase_order`, `device`, `expense`, `staff_request`, `cash`,
`day_close`. Note that `Notifier.ROUTABLE_REFS` covers only the first seven; the
rest fall back to the alert feed or a plain launch. That is a client gap, listed
here so it is visible, not a schema one.

**`staff_requests.type`** — the shipping app raises exactly two:
`credit_limit` (`PosViewModel:1645`, `:1695`) and `safe_withdrawal`
(`PosRepository.requestSafeWithdrawal:1453`). The model comment and the setup SQL
also name `discount`, `void` and `price_override` as designed-for. Only
`credit_limit` and `safe_withdrawal` have an execute-the-answer branch in
`decideStaffRequest`; the others would be approved and then do nothing.

**`staff_requests.status`** — `pending` | `approved` | `denied`.

**`staff_requests.target_type`** — `customer` (credit-limit asks) and `cash`
(safe withdrawals) in the shipping app; `sale` and `item` in the design.

---

## 3. `notifications`

### 3.1 Who is a notification for?

**By role, shop-wide — that is what the code actually models, so that is what the
schema models.** There is no per-staff targeting in `AppNotification` today; the
only audience concept is the three-valued role.

That is right for most of the feed. A low-stock alert is genuinely for whoever is
an admin, not for a named person. Where it breaks is the request-decision alert,
so the fix is additive and narrow rather than a redesign: keep `audience` as the
role, and add an optional override for the one case that needs a name.

### 3.2 Columns added

```sql
alter table public.notifications add column if not exists local_id        text;
alter table public.notifications add column if not exists target_staff_id text;
alter table public.notifications add column if not exists occurrence      integer not null default 1;
create index if not exists idx_notifications_cursor on public.notifications (business_id, updated_at);
```

**`local_id`** — the row id on the device that first raised the alert. Tracing
only. Deliberately **not** unique and **not** a key: two phones computing the same
low-stock condition mint different local ids and the same `dedupe_key`, and
conflicting on `local_id` would leave one cloud row per device — the exact
duplication the natural key exists to prevent. The upsert observed in testing
does not overwrite it, so it records the first device to see the condition, which
is the useful answer.

**`target_staff_id`** — null means the whole `audience` role, which is today's
behaviour and stays the default. Non-null narrows to that one person. Text, not
uuid, and no foreign key: a till in local mode has no cloud staff row to point at
and a request must still be raisable there.

**`occurrence`** — which run of a recurring condition this row is on. See §4.2.

### 3.3 Dedupe across devices

`dedupe_key` was a device-local uniqueness trick; once several devices can raise
the same alert it becomes the **shop-wide identity of a condition**, and the row's
uuid stops being an identity at all.

This is why `notifications.id` defaults on the server and the client must not send
it — the one table in this schema that works that way. Two devices agree on the
dedupe key and disagree on the uuid; if the push named `id`, each device's upsert
would rewrite the primary key to its own, bump `updated_at`, and make every other
device re-pull a row whose content did not change. Verified: two inserts with
different `local_id` and no `id` converged onto one row (§5).

The engine's dedupe keys are already device-independent — `lowstock:<itemId>`,
`refundowed:<refundId>`, `mmpending:<receiptId>`, `debtage:<customerId>` — because
they are built from record ids the shop shares, not from anything about the phone.
Two exceptions are **not** device-independent and are worth naming:

- `"unsynced"` — the "device hasn't synced" alert has a constant key with no
  device in it. Two phones both behind on sync produce **one** shared row that
  says whatever the last writer said. Left as-is because the alert is advisory,
  but it means the feed cannot tell the owner *which* phone is behind. Fixing it
  needs a device id in the key, which is client work.
- `"floattopup:$stamp"` and `"safewithdrawal:$stamp"` — keyed on a millisecond
  timestamp, so they are unique per event by accident rather than by design. Two
  tills doing a float top-up in the same millisecond would collide. Vanishingly
  unlikely, recorded for honesty.

---

## 4. `notification_reads` — read state, per person

### 4.1 Why a separate table

Read state is not a property of the alert. It is a property of a person's
relationship to the alert, and one column on one shared row cannot hold three
people's answers. Keeping `read_at` on `notifications` means the owner clearing
his feed clears the cashier's, and the cashier is never told about the shortage.

`notifications.read_at` is **left in place** (dropping it would be a destructive
change to a live column) but is marked legacy in its column comment. Nothing new
should write it.

### 4.2 Keyed on `dedupe_key`, not on `notifications.id`

This is the whole design, and the reason is offline-first:

- The device that raises an alert knows its `dedupe_key` the instant it computes
  it. It does **not** learn the cloud-minted uuid until a pull tells it, which may
  be hours later or never.
- Keying reads on the cloud id would therefore make "I have seen this" an action
  that requires a network. On a till in a shop with bad signal that is the same as
  having no read state at all.
- `dedupe_key` is already the identity every client agrees on — it is the upsert
  target on `notifications` for exactly this reason.

The cost of keying on `dedupe_key` is that row identity survives the
tombstone/revive cycle, and so would a stale read. The engine tombstones an alert
when its condition clears (stock arrives) and revives the *same* row by
`dedupe_key` when it recurs (the shelf empties again next week). Without a
discriminator, the admin who read it in March is still "read" in August and
nobody is told the second time.

`occurrence` is that discriminator. A server-side trigger bumps it when a
tombstoned row is revived:

```
unread(person P, alert A)  ==  no live notification_reads row for (A.business_id, A.dedupe_key, P)
                               OR that row's occurrence < A.occurrence
```

The bump lives on the server, not in either client, for the same reason the clocks
do: two clients would have to agree on when to bump it, and the first disagreement
produces an alert that is read on one phone and unread on the other with nothing
to say which is right.

The trigger is named `a_notifications_bump_occurrence` on purpose. Postgres fires
same-timing triggers in **name order** and this must run before
`notifications_set_updated_at`, which returns `OLD` to drop a stale write from a
device whose clock is behind. Returning `OLD` discards `NEW` entirely, including
the bump — which is exactly what should happen to a write that is being ignored.

### 4.3 Shape

```sql
create table if not exists public.notification_reads (
    id                uuid primary key default gen_random_uuid(),
    business_id       uuid not null,
    dedupe_key        text not null,
    staff_id          text not null,
    staff_name        text,
    occurrence        integer not null default 1,
    read_at           timestamptz not null default now(),
    local_id          text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);
```

`id` defaults and the client must not send it, same rule and same reason as
`notifications`: the natural key `(business_id, dedupe_key, staff_id)` is what
converges, and a client-named `id` would let one person's two phones fight over
the primary key of their own read row.

`staff_id` is text, not uuid and not a foreign key, so that a person can clear
their own feed on a till that has never been connected.

`staff_name` is a display snapshot so "read by" survives a staff member being
deactivated — the same snapshot pattern `staff_requests.requested_by_name` already
uses.

---

## 5. `staff_requests`

The existing table is sound. Its state machine, its polymorphic ref and its
attribution were already modelled correctly; what it lacked were indexes and
written-down intent.

**State machine.** `pending → approved | denied`, one-way and terminal.
`decided_at` non-null *is* the decided flag, and the push partitions on it alone —
a pending row goes up insert-once, a decided row merge-upserts — so a device never
has to know its own role to push correctly. The client refuses to re-decide a row
whose status is not `pending`, which is what stops a re-pulled approval opening
the safe twice.

**Who approved.** `decided_by` (auth uuid) + `decided_by_name` (snapshot) +
`decided_at`.

**What it is about.** `target_type` / `target_id` / `target_name`, the same
polymorphic-ref pattern as `notifications.ref_type` / `ref_id`. `target_id` is
text with no foreign key deliberately: a request can point at a cart line that has
no cloud row yet, and blocking the ask until the thing exists defeats the point of
asking. `target_name` is the display snapshot so the admin sees *what* they are
approving without the referenced row having reached the cloud.

**`amount` carries two meanings in sequence** — the figure asked for, and after a
decision the figure actually approved, because an admin may override the request
before confirming and the approved number is the one that gets executed. Only one
of those is ever needed at a time, so one column is right, but it is worth knowing
that the original ask is **not recoverable** after a decision. If the owner ever
wants "he asked for 250 and I gave him 200" in the audit trail, that needs a
second column (`requested_amount`) and it is not there today.

**`applied`** is an idempotency guard, not a status: the approved action was
actually consumed (the safe was opened, the limit was written).

Added:

```sql
create index if not exists idx_staff_requests_cursor
    on public.staff_requests (business_id, updated_at);
create index if not exists idx_staff_requests_requester
    on public.staff_requests (business_id, requested_by, created_at desc)
    where deleted = false;
alter table public.staff_requests add column if not exists local_id text;
```

`idx_staff_requests_pending` covers `(business_id, status, created_at)`, which is
the **admin queue** query. That is a different question from the sync cursor and
one index cannot answer both.

### 5.1 RLS is deliberately NOT tightened

The obvious tightening — staff may INSERT and SELECT, only an admin may UPDATE —
is exactly what the till's split-mode push is already built for. It is not
installed, and must not be, because `auth_org_role()` returns NULL for an anon-key
client and that is how every till on a single-shop database connects. The gate
would deny every approval in the shop rather than just the cashier's. It becomes
correct the day the tills carry a JWT with an `org_role` claim and not one day
sooner. The commented-out policy is preserved in `SupabaseSetupSql.kt` §8.

Until then, **the split-mode push is a correctness measure the client keeps for
itself, and the database does not enforce it.** Do not read the Kotlin doc
comments on `StaffRequest` as a description of this schema — they describe an RLS
regime that does not exist.

---

## 6. No CHECK constraints, on purpose

A CHECK violation fails the **whole push batch**, not the offending row. A till
that has been offline all morning comes back with two hundred rows, one of them
carrying a category the schema has not heard of, and none of the two hundred land
— repeatedly, every cycle, until someone notices. A row with an odd `category` is
a cosmetically wrong feed entry; a batch that never lands is a day of takings that
never arrives.

Every vocabulary on these three tables is still growing (the till already writes
`cash`, `expenses` and `requests`, which the original design never listed) or is
free-form by intent (`staff_requests.type`). And the one vocabulary that matters
most, `audience`, already **fails safe in the client** — `audienceMatches` treats
any unknown value as admin-only, so a typo makes an alert too quiet rather than
leaking it to a cashier phone.

The vocabularies are therefore recorded as **column comments** on the database
itself and in §2 above. `outside_funds` remains the one table in §7c that is
CHECK-constrained, and its reasoning does not transfer: there, a typo silently
moves money between what the shop owes its owner and what it owes a lender.

**No widened-constraint pattern (`drop constraint … / re-add`) was needed or used.
Nothing was dropped, retyped or renamed. Every statement in §7 is additive.**

---

## 7. Exact SQL for production

Replayable and idempotent. Run against `klkfynokcjunqvbkywfk` in the SQL Editor.
This is the identical set of statements applied to the throwaway, concatenated in
order.

> The last block **drops and recreates three `pos2_*` views**. That is the only
> `drop` here and it is required, not optional: `select *` in a view is expanded
> at creation, so the existing `pos2_notifications` has a frozen column list that
> does not include the new columns. `create or replace` cannot change a view's
> column list. Dropping and recreating a view does not touch data.

```sql
-- ============================================================================
-- INFRA: notifications + cashier requests. ADDITIVE ONLY.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) notifications: the pull cursor index, and three columns.
-- ---------------------------------------------------------------------------

-- Every other synced table has (business_id, updated_at); the pull is literally
-- ?business_id=eq.X&updated_at=gt.<cursor>&order=updated_at. Without it that is
-- a sequential scan of the whole feed on every sync pass of every phone.
create index if not exists idx_notifications_cursor
    on public.notifications (business_id, updated_at);

-- The Android push DTO already serialises local_id. With no column, PostgREST
-- rejects the WHOLE batch with PGRST204 before a single row lands. Not unique
-- and not a key: two phones computing one condition mint different local ids and
-- the same dedupe_key, and the upsert resolves on (business_id, dedupe_key).
alter table public.notifications add column if not exists local_id text;

-- One named person instead of a whole role. `audience` is right for a low-stock
-- alert and wrong for a request outcome: audience='cashier' makes all three
-- cashiers' phones buzz for one person's approval. Null keeps today's behaviour.
alter table public.notifications add column if not exists target_staff_id text;

-- Which RUN of a recurring condition this row is on. The engine tombstones an
-- alert when its condition clears and revives the SAME row by dedupe_key when it
-- recurs, so row identity cannot tell one occurrence from the next — and
-- per-person read state must, or March's "seen it" swallows August's alert.
alter table public.notifications add column if not exists occurrence integer not null default 1;

comment on column public.notifications.local_id is
    'Row id on the device that first raised this alert. Tracing only — NOT a key, NOT unique. The upsert key is (business_id, dedupe_key).';
comment on column public.notifications.target_staff_id is
    'Narrow the alert from the whole `audience` role to one person. Null = the whole audience. Text, not FK: a local-mode till has no cloud staff row.';
comment on column public.notifications.occurrence is
    'Which run of a recurring condition this is. Bumped when a tombstoned row is revived. Read state is per (person, occurrence).';
comment on column public.notifications.audience is
    'Role the alert is for: admin | cashier | all. Unknown values are treated as admin by the client, which fails safe.';
comment on column public.notifications.category is
    'inventory | sales | system | payments | refunds | cash | expenses | requests. Deliberately NOT check-constrained: the vocabulary is still growing and a CHECK violation kills the whole push batch.';
comment on column public.notifications.dedupe_key is
    'The natural key. Stable across devices and across recomputes: one standing condition is one row that updates, never a duplicate every sweep.';
comment on column public.notifications.event_at is
    'When the UNDERLYING event happened — what an ageing escalation is measured from. NOT when the engine noticed.';
comment on column public.notifications.read_at is
    'LEGACY shop-wide read state. Superseded by public.notification_reads, which is per person. Left in place so nothing breaks; do not add new writers.';

-- ---------------------------------------------------------------------------
-- 2) The occurrence bump.
--
-- Named a_* on purpose: Postgres fires same-timing triggers in NAME order and
-- this must run BEFORE notifications_set_updated_at, which returns OLD to drop a
-- stale write. Returning OLD discards NEW including this bump — which is exactly
-- right for a write that is being ignored.
-- ---------------------------------------------------------------------------
create or replace function public.bump_notification_occurrence()
returns trigger language plpgsql set search_path to '' as $$
begin
  if tg_op = 'UPDATE' and old.deleted = true and new.deleted = false then
    new.occurrence := coalesce(old.occurrence, 1) + 1;
  end if;
  return new;
end;
$$;

drop trigger if exists a_notifications_bump_occurrence on public.notifications;
create trigger a_notifications_bump_occurrence
    before update on public.notifications
    for each row execute function public.bump_notification_occurrence();

-- ---------------------------------------------------------------------------
-- 3) notification_reads — read state PER PERSON.
--
-- notifications.read_at is one column on a row every phone shares, so the owner
-- opening his feed marks it read on the cashier's too and the cashier never
-- learns the till is short. Read state is a property of a person's relationship
-- to the alert; one row cannot hold three people's answers.
--
-- KEYED ON dedupe_key, NOT notifications.id: the device that raises an alert
-- knows its dedupe_key immediately and does not learn the cloud uuid until a
-- pull tells it. Keying on the uuid would make "I have seen this" require a
-- network, which on an offline-first till is the same as having no read state.
-- ---------------------------------------------------------------------------
create table if not exists public.notification_reads (
    -- Defaults, and the client must NOT send it — same rule as `notifications`.
    id                uuid primary key default gen_random_uuid(),
    business_id       uuid not null,
    dedupe_key        text not null,
    -- WHO read it. Text, not uuid and not FK: a local-mode till has no cloud
    -- staff row, and a person must still clear their own feed before connecting.
    staff_id          text not null,
    staff_name        text,
    occurrence        integer not null default 1,
    read_at           timestamptz not null default now(),
    local_id          text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted           boolean not null default false,
    client_updated_at timestamptz
);

-- The upsert target. NOT partial: a partial unique index cannot be named as a
-- PostgREST on_conflict target, and this exists to BE that target — without it
-- every "mark read" inserts a second row instead of updating one.
create unique index if not exists uq_notification_reads_person
    on public.notification_reads (business_id, dedupe_key, staff_id);
create index if not exists idx_notification_reads_cursor
    on public.notification_reads (business_id, updated_at);
create index if not exists idx_notification_reads_client_updated
    on public.notification_reads (business_id, client_updated_at);
create index if not exists idx_notification_reads_staff
    on public.notification_reads (business_id, staff_id)
    where deleted = false;

comment on table public.notification_reads is
    'Per-person read state for public.notifications. One row per (person, alert). Joined on dedupe_key, never on notifications.id, so a phone can mark an alert read while offline.';
comment on column public.notification_reads.dedupe_key is
    'The alert''s natural key. Join to notifications on (business_id, dedupe_key).';
comment on column public.notification_reads.occurrence is
    'The notifications.occurrence this person read. Unread iff no live row here, or this value < the alert''s current occurrence.';

-- The two clocks, as every other table. A table added here and left out of this
-- is a table with no server clock, and the cursor then skips rows silently.
drop trigger if exists notification_reads_set_updated_at on public.notification_reads;
create trigger notification_reads_set_updated_at
    before insert or update on public.notification_reads
    for each row execute function public.set_updated_at();

alter table public.notification_reads enable row level security;
drop policy if exists notification_reads_tenant_rw on public.notification_reads;
create policy notification_reads_tenant_rw on public.notification_reads
    for all to anon, authenticated
    using (auth_org_id() is null or business_id = auth_org_id())
    with check (auth_org_id() is null or business_id = auth_org_id());

-- ---------------------------------------------------------------------------
-- 4) staff_requests: the missing cursor index, the requester index, local_id.
-- ---------------------------------------------------------------------------

-- idx_staff_requests_pending covers (business_id, status, created_at) which is
-- the ADMIN QUEUE query, not the sync cursor. Different questions; one index
-- cannot answer both.
create index if not exists idx_staff_requests_cursor
    on public.staff_requests (business_id, updated_at);

-- The cashier's own "what did I ask for" list. Without it that screen scans the
-- shop's whole request history to find one person's rows.
create index if not exists idx_staff_requests_requester
    on public.staff_requests (business_id, requested_by, created_at desc)
    where deleted = false;

alter table public.staff_requests add column if not exists local_id text;

comment on column public.staff_requests.type is
    'What is being asked for. Shipping app: credit_limit | safe_withdrawal. Designed for discount | void | price_override. Free-form and NOT check-constrained — a CHECK violation fails the whole push batch, and this vocabulary grows with every new admin gate.';
comment on column public.staff_requests.status is
    'pending | approved | denied. One-way and terminal: the client refuses to re-decide a non-pending row, which is what stops a re-pulled approval opening the safe twice.';
comment on column public.staff_requests.decided_at is
    'Non-null == decided, and what the push partitions on. Pending rows go up insert-once; only decided rows merge-upsert. So a device never has to know its own role to push correctly.';
comment on column public.staff_requests.target_type is
    'What the request is ABOUT — the polymorphic ref: customer | cash | sale | item. Paired with target_id.';
comment on column public.staff_requests.target_id is
    'Text, not uuid, no FK on purpose: a request can point at a cart line with no cloud row yet, and blocking the ask until the thing exists defeats the point of asking.';
comment on column public.staff_requests.applied is
    'The approved action was actually CONSUMED (safe opened, limit written). Idempotency guard, not a status. A cashier device sets it locally and never pushes it.';
comment on column public.staff_requests.amount is
    'The figure asked for, and after a decision the figure APPROVED — an admin may override before confirming. The original ask is not recoverable afterwards.';

-- ---------------------------------------------------------------------------
-- 5) pos2_* views — the web back-office's door.
--
-- `select *` is EXPANDED AT CREATION, so the existing pos2_notifications froze
-- its column list before this change. Leaving it gives the web a view that
-- silently lacks target_staff_id and occurrence: no error, just a back-office
-- that cannot see who an alert is for and shows every recurrence as read.
-- Dropped and recreated because `create or replace` cannot change a column list.
--
-- security_invoker = true is NOT optional: a view runs as its OWNER by default,
-- the owner is postgres, and postgres BYPASSES row-level security.
-- ---------------------------------------------------------------------------
drop view if exists public.pos2_notifications;
create view public.pos2_notifications with (security_invoker = true) as
    select * from public.notifications;
grant select, insert, update, delete on public.pos2_notifications to anon, authenticated;

drop view if exists public.pos2_staff_requests;
create view public.pos2_staff_requests with (security_invoker = true) as
    select * from public.staff_requests;
grant select, insert, update, delete on public.pos2_staff_requests to anon, authenticated;

drop view if exists public.pos2_notification_reads;
create view public.pos2_notification_reads with (security_invoker = true) as
    select * from public.notification_reads;
grant select, insert, update, delete on public.pos2_notification_reads to anon, authenticated;
```

### 7.1 The unread predicate, for both clients

Neither client should invent its own. This is the definition:

```sql
select n.*,
       (r.id is null or r.deleted or r.occurrence < n.occurrence) as unread
from public.notifications n
left join public.notification_reads r
       on  r.business_id = n.business_id
       and r.dedupe_key  = n.dedupe_key
       and r.staff_id    = :staff_id
where n.business_id = :business_id
  and n.deleted = false
order by n.event_at desc;
```

Not shipped as a view because a view cannot take `:staff_id` as a parameter.

---

## 8. Sync direction

| table | direction | notes |
|---|---|---|
| `notifications` | **two-way** | Push upserts on `on_conflict=business_id,dedupe_key` and must **omit `id`**. Pull cursors on `updated_at`. Tombstones (`deleted=true`) sync, so a cleared condition clears on every phone. |
| `notification_reads` | **two-way** | Push upserts on `on_conflict=business_id,dedupe_key,staff_id`, omitting `id`. Small and append-mostly. |
| `staff_requests` | **two-way, split-mode** | Pending rows push **insert-once** (`on_conflict=id`, ignore-duplicates); decided rows (`decided_at` non-null) push **merge-upsert**. The split is client-side discipline, not RLS — see §5.1. |

`pushed_at` is **device-local and has no column, and must not get one.** It records
whether *this* handset already fired its own heads-up and is meaningless on any
other one. A pull must preserve the local value, which `NotificationDto.toNotification`
already does correctly.

---

## 9. What each client has to do to adopt this

### Android (`pos-android`) — the blocking item first

1. **`event_at` and `read_at` are `timestamptz` on the database and `Long` epoch
   milliseconds in `NotificationDto` / `NotificationPushDto`.** This is a hard
   blocker: the push will be rejected on type. It was **not** papered over with
   `event_at_ms` columns, because two answers to "when did this happen" in one row
   is the failure this schema's own comments warn about repeatedly. The DTOs must
   convert via `IsoTime`, like `created_at` / `updated_at` already do.
2. Remove `notifications` and `staff_requests` from `SyncConfig.LOCAL_ONLY_TABLES`
   and add them to `SyncConfig.TABLES` so they get pull cursors. They are already
   in `LEGACY_CURSOR_TABLES`; that entry becomes wrong and should go.
3. Wire `pullNotifications` into `pull()` — it is currently dead (§1.1). Its
   dedupe-key matching and `pushedAt` preservation are already correct.
4. Write the pushes: `pushNotifications` (upsert on `business_id,dedupe_key`,
   **omitting `id`**) and `pushStaffRequests` (split-mode per §8). Neither exists.
5. Write `pullStaffRequests` and its DTO. `sync/Dtos.kt` records that the old ones
   were deleted because they aimed at a 404.
6. Move read state off `AppNotification.readAt` onto a new local `notification_reads`
   entity keyed `(businessId, dedupeKey, staffId)`, and change
   `NotificationDao.observeUnreadCount` / `markRead` / `markAllRead` to the
   predicate in §7.1. This needs a Room migration.
7. Populate `target_staff_id` when minting the `reqdecided:` alert in
   `decideStaffRequest` — set it to `req.requestedBy` so only the requester's
   phone buzzes.
8. Optional: put a device id into the `"unsynced"` dedupe key so the feed can say
   *which* phone is behind (§3.3).

### Web (`portionspot-pos`)

Reads and writes the `pos2_*` views, which now exist for all three tables. It
needs the same three rules the till does: never send `notifications.id` or
`notification_reads.id`; upsert on the natural keys, not the primary key; use the
§7.1 predicate for unread rather than `notifications.read_at`.

---

## 10. Assumptions and open questions

Stated because a stated assumption is useful and a confident guess is not.

**Assumptions made:**

1. **The bundled `SupabaseSetupSql.kt` §7c is the intended shape**, and the two
   tables found on the throwaway came from running it rather than from some other
   source. Their columns match that script exactly, which is strong but not proof.
2. **`staff_id` in `notification_reads` will hold the same identifier as
   `staff_requests.requested_by`** — described in the Kotlin as the "cashier auth
   uuid". I did not verify what that value actually is at runtime on a local-mode
   till, where there may be no auth uuid at all. If local mode uses a different
   identifier, read state written offline will not match read state written after
   connecting. **This is the assumption most likely to be wrong.**
3. **A tombstone-then-revive is the only way a condition recurs.** If the client
   ever hard-deletes and re-inserts a notification instead, `occurrence` resets to
   1 and a stale read row would suppress the new alert. The current
   `upsertNotificationByKey` reuses tombstoned rows, so this holds today.
4. **`local_id` should record the FIRST device to see a condition, not the last.**
   The upsert as tested does not overwrite it. If the owner would rather know the
   most recent reporter, the client's `do update` clause must set it.
5. **`business_id` on these tables is the shop uuid**, matching every other table.
   Not separately verified for `notifications` since the table was empty.

**Open questions I could not settle from the code:**

1. **Should a notification ever be addressed to a specific person *instead of* a
   role, or only as a narrowing of one?** I implemented narrowing — `audience`
   still applies and `target_staff_id` filters within it. If the owner wants
   "message this one person regardless of role", that is a different semantic and
   `audience` would need an `individual` value.
2. **Should the original requested amount survive a decision?** Today `amount` is
   overwritten with the approved figure, so "he asked for 250 and I gave him 200"
   is unrecoverable. Adding `requested_amount` is trivial and additive; I did not,
   because nothing in the code asks for it and I would be guessing at an audit
   requirement.
3. **How long should the feed be kept?** There is no retention policy and no
   purge. A shop running for two years accumulates every low-stock alert it ever
   raised, and `notification_reads` grows as (alerts × staff). Neither will hurt
   soon; both will eventually. Nothing in the code expresses an intent.
4. **Should `staff_requests` expire?** A pending request that the owner never
   sees stays pending forever, and the app has no remote cancel — a cashier
   cannot withdraw a pushed row. There is no `expires_at` and I did not invent
   one.
5. **Whether the web already has its own notion of alerts.** I did not read the
   `portionspot-pos` codebase for this pass. If it has an existing feed, the
   `audience` vocabulary and the dedupe-key convention need reconciling with it
   before either client goes live on these tables.

---

## 11. Verification performed

Applied to `lvaxbbobmounfxskooiw` as five `apply_migration` calls, then read back
from `information_schema` / `pg_indexes` / `pg_policy` / `pg_trigger`. Round-trip
tests were run **as the `anon` role** (`begin; set local role anon; …`), which is
the role every till actually connects as — running them as the privileged MCP role
would have bypassed RLS and proved nothing about whether a client can write.

Proven:

- `anon` can insert into all three tables. RLS does not lock the till out.
- Two devices raising the same condition with different `local_id` and no `id`
  converge onto **one** row, keeping the first device's `local_id`.
- Two people's read state on the same alert is independent: owner `unread=false`,
  cashier `unread=true`, simultaneously.
- Tombstone then revive bumps `occurrence` 1 → 2, and the owner's occurrence-1
  read correctly goes stale (`unread=true` again).
- `staff_requests` round-trips pending → approved, carrying the admin's overridden
  amount (250 asked, 200 approved) and preserving `requested_by_name` through the
  merge.
- The `set_updated_at` stale-write guard fires on the new table: an update
  carrying a `client_updated_at` three days old was **ignored**, not rejected, and
  the row kept its value.
- All three `pos2_*` views expose the new columns.
- `get_advisors(security)` returns zero lints.
- All test rows deleted; the three tables are back to 0 rows.
