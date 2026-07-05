# PortionSpot Motors POS — Native Android App: Feature Build Prompt

> **Architecture, read first — do not get this wrong.** This project (`pos-android`) is a **native Kotlin + Jetpack Compose** app with its own Room database, its own Supabase-REST sync engine, and native ESC/POS/Sunmi/Bluetooth printing. It is **NOT** a WebView wrapper and it does **NOT** share a `src/` React codebase with the web POS. There is no `MainActivity` loading a URL, no `Login.jsx`, no `src/lib/sync.js`. The web repo below is a **reference for schema, business logic, and UI only** — you reimplement those natively in Kotlin, you do not load or embed the web app. (An earlier "corrected" prompt claiming this was a React PWA in a thin WebView was wrong for this repo and has been deleted.)

## Context

You are extending an existing **native Android app (Kotlin)** for PortionSpot Motors, a wholesale motor spares business in Harare, Zimbabwe. The app is mostly built — your job is to add the features below and iron out details. Do not rebuild what already works.

**Reference systems:**
- The existing web POS (React/Vite/Tailwind + Supabase + Dexie offline sync) lives at `github.com/ckachale14-hash/POS`. Use it as the source of truth for the database schema, business logic, and the **mobile UI**, which the Android app must visually match. A **local copy of that repo is on this machine** at `C:\Users\User\Production\Projects\Reference\POS-main.zip` (unzip to read it without network / the right GitHub account) — prefer this for schema/logic/UI reference so a session never stalls on repo access.
- Backend: Supabase (project ID `ucgvvxlhdooevngtraje`) — Postgres, Auth, Edge Functions, Storage.
- Distribution: **sideloaded APK only** (never Google Play), so restricted permissions like READ_SMS and READ_CALL_LOG are acceptable.
- Up to 4 staff use the system, across multiple devices including Sunmi handheld POS devices.

Before writing code, read the repo's schema and the mobile layout of the web POS (bottom navigation pinning Sync, POS, Sales, Inventory, with a "More" overflow sheet) and confirm your understanding of both back to me.

---

## 1. UI parity with the mobile web POS

Replicate the mobile UI of the web POS in the Android app: same navigation structure (bottom nav: Sync, POS, Sales, Inventory + "More" sheet), same screens, same visual language (colors, spacing, iconography). Use Jetpack Compose. Where a native pattern is clearly better (e.g., Material ripple, system back handling), prefer the native pattern but keep the layout and hierarchy identical.

### 1.1 Color & contrast — confirmed bug, fix this
Running the current build, **some text is unreadable because the colors are off** — foreground/background pairs don't have enough contrast (this shows up in both light and dark, and looks worst in `values-night`). Audit `ui/PosTheme.kt` and every screen in `ui/PosUi.kt`: no hardcoded colors that fight the theme, every text/icon uses an `onX` color that actually contrasts its container, and both light and dark themes are checked on-device. Target WCAG AA contrast (4.5:1 for body text) as the floor. Match the web POS palette rather than inventing new colors.

### 1.2 Settings & feature parity with the original POS — audit for gaps
The Settings screen and the feature set must match the **options of the original web POS in the repo** (`github.com/ckachale14-hash/POS`), not a reduced subset. **Known gap: the quote feature is missing** from the Android app. There are almost certainly others — the app hasn't been fully exercised yet. So: enumerate every screen, action, and setting in the web POS, diff it against the native app, produce a written parity checklist of what's missing, and confirm the list with me before building. Do not assume the native app is complete just because it runs.

## 2. Authentication & database security (Supabase Auth + RLS)

Do **not** ship the service-role key in the app. The correct architecture is:

- Keep the Supabase **anon key** in the app, but enable **Row Level Security on every table** so the anon key alone can do nothing.
- Users authenticate via **Supabase Auth** (email + password). Use the `supabase-kt` Kotlin client.
- A `profiles` table (or JWT custom claims) stores each user's role: `admin` or `cashier`.
- All read/write access flows through **RLS policies keyed to `auth.uid()` and role**.

### Roles and sub-accounts

- One main **admin** account. Admin creates **cashier sub-accounts** (name, email/username, password, PIN for quick unlock).
- Cashier accounts are created by anyone logged in with valid credentials that permit it, but can be **deleted or deactivated only by the admin**. Enforce this server-side (RLS policy or a Supabase Edge Function that verifies the caller's admin role via their JWT before using the service role to create/delete auth users) — never enforce it client-side only.
- Cashiers can log in on **any device**. Sessions are per-device; the same cashier account works identically on a phone, tablet, or Sunmi terminal.

### Offline login

- **First login requires internet** (to fetch the Supabase session and profile).
- After first login, the app caches the session (encrypted with Android Keystore / EncryptedSharedPreferences) plus a locally-verifiable PIN/password hash, so the cashier can **unlock and work fully offline**.
- Handle refresh-token expiry gracefully: if the session can't refresh when back online, queue data safely and prompt re-login without losing unsynced records.

## 3. Per-cashier attribution (critical)

Every transaction, sale, credit, payment, refund, stock movement, and order **must record which cashier created it**:

- Every relevant table gets a `created_by` (UUID → auth user) column.
- Records created **offline** must stamp `created_by` from the locally cached session **at creation time**, not at sync time, so attribution survives device sharing and network drops.
- Use client-generated UUIDs for all offline records so sync never collides or reassigns ownership.
- RLS: cashiers can insert and read; edits/voids/deletes of financial records require admin (or create an immutable ledger with reversal entries instead of edits — prefer this).

## 4. Timestamps & audit trail on everything

Every record gets `created_at` (device time, stamped at creation, offline included) and `server_created_at` (set on sync, server time). Additionally:

- **Debts (customer owes us):** date incurred, every part-payment dated, running balance, so debt age is always computable.
- **Change/credit owed (we owe customer):** same treatment.
- **Orders:** the exact date/time the order was **placed** is first-class and immutable, separate from fulfilment/payment timestamps.
- Status changes (order placed → fulfilled, debt opened → settled) get their own timestamped audit entries.
- Show ages in the UI in human terms ("owed for 34 days") and exact datetimes on tap.

## 5. Device permissions (sideload — full access is fine)

Request at runtime, with graceful degradation if denied: `READ_CONTACTS`, `READ_CALL_LOG`, `RECEIVE_SMS` + `READ_SMS`, `POST_NOTIFICATIONS`, Bluetooth (`BLUETOOTH_CONNECT`/`SCAN`), and storage via **MediaStore/Storage Access Framework** (avoid legacy `WRITE_EXTERNAL_STORAGE` where possible).

### Contacts
- When adding/editing a customer, offer a **contact picker** to pull name + number from the phone instead of retyping.
- Optionally match existing DB customers to contacts by phone number so their saved name/photo appears.

### Call log (be clever here — propose your best design, but at minimum)
- "Recent calls" quick-add: show recent callers so a customer who just phoned in an order can be added or selected in one tap.
- If an incoming/last-call number matches a customer in the DB, surface a shortcut: "Ashley called 5 min ago — open her account (owes $42, 12 days)."

### Storage
- Generate, save, and re-open **PDFs**: receipts, quotes, credit statements, change statements, debt statements. Save to a visible app folder the user can browse; also share via WhatsApp/other apps with a share sheet.

## 6. Ecocash / mobile-money SMS reconciliation (flagship feature)

Build an SMS listener that parses incoming mobile-money confirmation messages (Ecocash primarily; design the parser to be **rule-based and extensible** so OneMoney, InnBucks, Omari, or bank alerts can be added by adding a pattern, not rewriting code).

For each payment SMS, extract and persist: **sender name, sender number (if present), amount, currency, unique transaction code, and received datetime**. Store every parsed payment in a `mobile_money_receipts` table (offline-first, synced).

Then run the matching flow:
1. If the sender's number/name matches a customer in the DB, link the payment to that customer as **pending verification**.
2. Fire a **notification**: "Received $X from [name] via Ecocash — verify." Tapping opens a verification screen.
3. The cashier confirms **what the money is for**: a specific order, settling debt, or a new sale.
4. The system then computes automatically:
   - **Debt payment:** apply amount, show remaining debt (or overpayment → change/credit owed).
   - **Order payment:** compare against order total — fully paid, underpaid (balance becomes debt/credit), or overpaid (change owed).
5. Unmatched payments stay in an "Unmatched payments" list for manual assignment later. Nothing is ever silently discarded.
6. Duplicate protection: the unique transaction code is the idempotency key — the same SMS can never be recorded twice.

## 7. Split payments — ALREADY IMPLEMENTED, verify don't rebuild

This is **already built natively**: a sale is one transaction with a `sale_payments` child table (`data/PosModels.kt`), each sub-record carrying method, amount, currency, and its own timestamp, plus a `PaymentMethod` enum. **Do not rebuild it.** Verify the existing checkout flow populates it correctly, that totals/receipts/reports treat the transaction as one unit while still reporting per-method breakdowns, and only extend if a gap is found. Design intent (kept for reference): methods include cash USD, cash ZWG, Ecocash, InnBucks, card, credit/on-account, etc.

## 8. Admin capabilities & notifications

The admin login unlocks an admin mode with (implement all of these; propose anything else that fits a motor-spares wholesaler):

**Controls**
- Create cashier accounts; deactivate/delete cashiers (sole authority).
- View transactions filtered by **any date range**, by cashier, by payment method, by product.
- **Force-disable payment methods** globally (e.g., lock out Ecocash during network problems or rate volatility) — disabled methods disappear/grey out on all cashier devices on next sync, with offline devices honoring the lock once they sync.
- Approve/perform voids, price overrides, and discounts beyond a set threshold. **Refunds themselves are cashier-performed** (see §11) — but the admin sees every refund and can void a wrongful one.
- Set low-stock thresholds per product; adjust stock with reasons (audit-logged).
- Debt management: aging report (30/60/90 days), per-customer statements, write-offs (admin-only).
- End-of-day / shift summary per cashier: sales count, totals per method, expected cash in drawer vs recorded.
- Device & sync health: last-sync time per device, pending unsynced record counts.
- Full audit log viewer.

**Notifications to admin**
- Low stock (per thresholds).
- Large transactions above a configurable amount.
- Voids/manual stock adjustments by cashiers.
- **Refunds issued by cashiers:** which sale, goods returned (and whether restocked), amount refunded, method(s), and whether the money was **fully returned or is still owed** to the customer (a refund left partially unpaid for more than N hours escalates like an unverified payment).
- Mobile-money payments left **unverified** for more than N hours.
- Debts crossing aging thresholds (e.g., newly older than 30 days).
- A device that hasn't synced in more than N hours while records are pending.

## 9. Admin UI

Admin mode gets its own UI, distinct from the cashier POS view. **Do not design it yet** — I will provide reference designs. Build the cashier features first with the role plumbing in place so the admin UI can slot in.

## 10. Printing (Bluetooth + Sunmi + RawBT) — EXTEND, don't rebuild

The native printing stack is **already built**: `print/EscPos.kt` (ESC/POS driver), `print/BluetoothPrinter.kt`, `print/SunmiPrinter.kt`, and `print/ReceiptPrinter.kt`. Treat this section as "verify and add the missing options," not "build from scratch."

- **Bluetooth thermal printers:** ESC/POS over Bluetooth exists — confirm 58mm and 80mm paper widths are both configurable.
- **Sunmi devices:** Sunmi internal-printer path exists — confirm hardware detection and SDK use.
- **RawBT fallback:** integrate with the RawBT print service app via its Android intent API (`rawbt:` scheme / share intents), so any printer RawBT supports works too. The RawBT APK for on-device testing is at `Reference\Rawbtprinter_7.0.3-185.apk` in my Projects folder — this is for installing on test devices, not something you bundle into the build.
- Printer selection, paper width, and default printer are per-device settings.

### Receipt design — you have creative freedom
- You decide layout, fonts, font sizes, and spacing for receipts, quotes, and statements — make them clean, legible on thermal paper, and professional. Provide 2–3 style presets I can choose between in settings.
- Logo printing options: header logo (raster, dithered for thermal), size options, and — if feasible on the target printers — a **light watermark of the logo** behind the receipt body (implement via low-density raster; if a given printer can't render it acceptably, degrade gracefully to no watermark rather than a black smear).
- Receipts must show: business details, receipt number, date/time, cashier name, line items, per-method payment breakdown for split payments, change/credit/debt resulting, and a footer message (configurable).

## 11. Refunds & returns

Refunds are a **first-class, cashier-performed** flow, built on the **immutable reversal-ledger** principle: the original sale is never edited or deleted — a refund is an append-only reversal linked back to it, so the full history (sold → refunded → repaid) is always reconstructable.

- **Who:** any signed-in cashier can issue a refund; every refund records `created_by` (the cashier) and its timestamps (device time at creation, offline-safe; server time on sync). The admin is notified of every refund and can void a wrongful one (§8).
- **Against a sale:** a refund references the original sale. It can be **full or partial** — the cashier selects which line items / quantities are being returned, so a customer can bring back 2 of 5 items.
- **Goods returned:** the cashier marks whether the goods came back. Returned goods are **restocked** by default (a `return` stock movement, which already exists), with a per-line option to **not** restock damaged/faulty goods.
- **Money redistributed:** the refund records how the money was given back — one or more methods/currencies, exactly like split payments (cash USD, cash ZWG, EcoCash reversal, store credit, etc.).
- **Partial money over time:** if the full refund amount isn't handed back at once, the outstanding amount becomes a tracked **"shop owes customer"** balance and each later payout is a dated entry — the **same mechanism as change-owed and credit**, so debt/credit/refund all age and settle the same way.
- **Time:** every refund event and every payout carries its own timestamp; the UI shows ages in human terms ("refund owed for 3 days") and exact datetimes on tap.
- **Receipts:** a refund can print/share a refund receipt showing the original sale ref, items returned, amount refunded per method, and any balance still owed.

---

## Working rules

1. **Ask before assuming.** If the repo schema conflicts with anything above, or a requirement is ambiguous, stop and ask.
2. **Schema changes** go through Supabase migrations — list every migration before applying, and never drop or rewrite existing tables with live data.
3. **Offline-first is non-negotiable:** every feature above must work offline (except first login and SMS arrival, which are inherently online events) and sync cleanly, preserving original timestamps and cashier attribution.
4. **Order of work:** (1) Auth + roles + RLS, (2) attribution + timestamps + audit, (3) split payments, (4) permissions + contacts/call log, (5) SMS reconciliation, (6) PDFs + printing, (7) admin features, (8) admin UI once references are provided.
5. After each phase, give me a short summary of what changed, what to test, and any decisions you made on my behalf.
6. **Skills to use for the whole rebuild:**
   - **`/targeted-read` on every step** — read only the files a given change touches, never scan the whole codebase. I want to save tokens; blanket reads of `app/src` are not allowed.
   - **`/android-ninja`** for the native Kotlin/Compose engineering (architecture, Room, sync, coroutines, native bridges, printing, SMS/permissions plumbing).
   - **`/mobile-android-design`** wherever UI is involved — the color/contrast fix (1.1), settings/feature parity (1.2), receipt layout presets, and any new screens. Apply it for look-and-feel decisions, not for backend work.
