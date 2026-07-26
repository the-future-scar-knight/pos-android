# Web → Android Parity Audit (gap list before code)

Reference: web POS (`Reference/POS-main.zip`, live at portionspot-v11-5.vercel.app)
Target: this Android app, whose entire UI is in `app/.../ui/PosUi.kt` (8,602 lines).

**Method:** compared the web `src/pages` + `src/components` against the Android composables.
Two screens deep-verified field-by-field (the ones Ryan screenshotted) + the data model.
The rest structurally scanned (presence confirmed, fidelity flagged for a deep pass).

**Confidence legend:** [v] verified in code · [~] structurally scanned, needs deep pass · [i] inferred.

---

## 0. Root cause (data model, not styling)

The web product model is **three-way**: `productType ∈ {box, set, piece}` (`Inventory.jsx:13,519-521`),
and the form + POS adapt to it (box → box price/units-per-box/loose-unit stock; set → "per set" pricing,
"Sets in Stock"; piece → "each" pricing, "Pieces in Stock").

Android's `Item` entity (`data/PosModels.kt:93`) **has no `productType` field.** ✔
It only infers box-vs-not from `boxSize > 1`. `productType` exists *only* in the sync DTOs
(`sync/Dtos.kt:412`) where it's synthesised as `if (bs > 1) "box" else "unit"` — so **"set" and
"piece" don't exist anywhere in the Android domain or UI.** Every product is silently a box-or-unit.

This is the concrete shape of the "you missed the little things" complaint: the concept was dropped at
the model layer, so no UI could show it. Fixing the UI without adding `productType` (DB migration +
model + sync round-trip) would be lipstick.

---

## 1. Inventory / Product form — HIGH priority ✔

Web `Inventory.jsx` ProductModal vs Android `ItemDialog` (`PosUi.kt:4368`).

| Web has | Android | Gap |
|---|---|---|
| Product-Type selector (3 tappable cards: Box/Units, Set, Piece + descriptions) | none | **Missing entirely** |
| Fields that adapt to type ("per set"/"each" labels; box-only rows hidden for set/piece) | fixed field set | Missing |
| "Stock Quantities" grouped box: Boxes ×N + Loose Units, live "Total: N units" | boxes+loose exist but ungrouped, less legible | Partial |
| Sets/Pieces-in-Stock variant for non-box | none (no set/piece) | Missing |
| Proper large modal, labeled field groups, distinct inputs | Material `AlertDialog` + scrolling Column (cramped, generic) | **Structural** |

## 2. Customer detail — HIGH priority ✔

Web `Customers.jsx` detail page vs Android `CustomerDetailDialog` (`PosUi.kt:5151`).

| Web has | Android | Gap |
|---|---|---|
| Full detail **page** w/ header (name · Retail/Trade · Edit · Statement) | Material `AlertDialog` popup | **Structural** |
| Time filters: All Time / This Month / This Year | none | Missing |
| Stats dashboard: **Total Spent** (+N purchases), **Avg Purchase** (+Last date) | none | **Missing** |
| Conditional "Change We Owe" / "Credit Owed" cards | balance line only | Partial |
| **Purchases ⇄ Credit & Change** tab toggle, rich purchase rows | credit ledger only, no purchases tab | **Missing** |
| Statement print/PDF | share-PDF buttons present | ✔ (has it) |

The whole "customer dashboard" Ryan praised (spend analytics + purchase history) is absent; Android only
shows the credit/change ledger.

---

## 3. Parity matrix (all screens verified in code)

| Web page | Android | Status | Verified finding |
|---|---|---|---|
| `POS.jsx` | `SellScreen`+`CartBar`+`QuoteDialog`+`ParkedSalesDialog`+`PriceModeDialog` | [v] good | Faithful: responsive product grid (`LocalPosDimens.productColumns`), search, category chips, barcode scan, held sales, quote mode, box/wholesale/retail price modal. Minor: verify "Quick fill" qty entry exists in `CartDialog`. |
| `Inventory.jsx` | `ItemsScreen`+`ItemDialog` | [v] **HIGH gap** | §1 — no Box/Set/Piece; `AlertDialog` form. + Wholesale price list. |
| `Customers.jsx` | `CustomersScreen`+`CustomerDetailDialog` | [v] **HIGH gap** | §2 — no spend dashboard / purchases tab; `AlertDialog` popup. |
| `Dashboard.jsx` | `DashboardScreen` | [v] MED gap | **Strong port** (range chips, revenue hero, KPI incl. gross profit + margin, 7-day chart, payment breakdown, top products, health, recent, Z-Report). Missing: **Cash Reconciliation / Expected-in-drawer** and **Net Profit** (after expenses). |
| `SalesHistory.jsx` | `ReportsScreen`+`ReceiptsScreen`+`RefundsScreen` | [v] MED-HIGH gap | Split 3 ways (reasonable). `ReportsScreen` is **aggregate-only** (totals/VAT/discount/payment split) — no profit/margin here. `ReceiptsScreen` is the txn list (reprint, share, refund, quotes toggle, refunded badges) but **no filters** (cashier/category/payment/date) and **no per-sale line-item expansion or per-sale cost/profit/margin**. |
| `Settings.jsx` | `SettingsScreen` (+`SettingsCategoryBar`) | [v] LOW-MED gap | Good coverage: logo, printer (58/80mm, Bluetooth, RawBT, Sunmi), receipt preset + large text, tax, discount, categories, Danger Zone (reset stock / wipe sales). Verify: session auto-hold timeout + audit-log viewer (may live in `AdminManageScreen`). |
| `Receipts.jsx` | `ReceiptsScreen` | [v] good | Present w/ today-takings header, receipts/quotes toggle, reprint, share PDF, refund, refunded/line-through states. |
| `ChangeCredit.jsx` | `ChangeCreditScreen` | [v] present | Standard CRUD matches web (types filter, record payment, settled). Field-level polish pass pending. |
| `Expenses.jsx` | `ExpensesScreen`+`ExpenseModal` | [v] present | CRUD (category, description, edit/delete) matches. Polish pass pending. |
| `Suppliers.jsx` | `SuppliersScreen`+`SupplierModal` | [v] present | CRUD (phone/email/address/notes) matches. Polish pass pending. |
| `PurchaseOrders.jsx` | `PurchaseOrdersScreen`+create/detail/receive dialogs | [v] present | Add-products/order-total/receive flow matches. Polish pass pending. |
| `AppShell.jsx` | `MobileTopBar`+`AdminBottomNav`+`MoreSheet` | [v] present | Nav shell present (online/offline, More sheet). |
| `SessionHUD.jsx` | `CartBar` (floating) | [v] present | Floating cart + held-session access present. |
| `Login.jsx` | `LocalAuthScreens`/`AuthScreens` | [v] diverged (intentional) | Multi-account local-first login — deliberately different from web. |

**Verdict:** presence is near-complete; only **2 screens have HIGH gaps** (Inventory form, Customer
detail), **2 have MEDIUM gaps** (Dashboard reconciliation/net-profit, Sales-history filters/line-items/
profit), and the rest are present and need only a per-form fidelity/token polish — not a rebuild.

---

## 4. Cross-cutting issues (hit every screen)

1. **`AlertDialog` used as the form container** for big forms (ItemDialog, CustomerDetailDialog, etc.).
   It's cramped, caps width, and on a small phone the scrolling content truncates; on a tablet/POS it
   wastes the screen. Web uses full modals / pages. This is both the fidelity AND the responsiveness
   problem in one. → move heavy forms to full-screen/adaptive containers.
2. **No design-token coherence pass.** Web colours are balanced/coordinated; Android grew screen-by-
   screen. `AppearanceSection` + Pine theme exist, but spacing/type/field styling isn't unified.
3. **Field styling** — web inputs are visually distinct, labeled, grouped. Android leans on default
   `OutlinedTextField` stacks. Distinct, grouped fields are a per-form change.

## 5. Responsiveness (Ryan's explicit constraint — must hold on every fix)

- `PosDimens` responsive sizing already exists — **build within it, don't fork new magic numbers.**
- Ledger (2026-07-08): the Sunmi (API 23) and other tills rescale via **system Font/Display size**;
  the app pins `fontScale`/density in `attachBaseContext`. Any new `sp`-driven layout must be tested
  against that, not just the emulator.
- Replacing `AlertDialog` forms with adaptive layouts is the single biggest responsiveness win: use a
  width-aware container (compact phone = full-screen sheet; medium/expanded = centered modal) so the
  same screen works on a phone, a tablet, and the Sunmi.

## 6. Recommended fix order (verified; each ends verified on-device, per the Done bar)

**HIGH — real feature/model gaps:**
1. [DONE] **`productType` added to the model** — DB v17→v18 migration (backfilled by box size),
   `Item.productType`, sync round-trip both ways (+ `normalizeProductType` heals legacy "unit"),
   `ItemDialog` Box/Set/Piece selector with adaptive fields, type-aware inventory notifications,
   and colour-coded Set/Piece badges on POS cards + inventory rows. Both compiles green.
2. [PARTIAL — see #1] **Inventory product form**: Box/Set/Piece selector + adaptive fields done.
   Still open: move the form off `AlertDialog` into an adaptive container (cross-cutting §4/#6);
   Wholesale price list already exists. Guard held: box/loose ↔ total-units math unchanged.
3. **Customer detail** rebuilt as an adaptive screen with the spend dashboard (Total Spent / Avg /
   time filters) + Purchases ⇄ Credit tabs.

**MEDIUM — depth gaps on otherwise-good screens:**
4. **Sales history**: add filters (cashier/category/payment/date) + per-sale line-item expansion +
   cost/profit/margin to the `Receipts`/`Reports` pair.
5. **Dashboard**: add Cash Reconciliation (Expected-in-drawer) + Net Profit (after expenses).

**LOW — fidelity/token polish (no rebuild):**
6. Cross-cutting §4 pass: move heavy forms off `AlertDialog` into adaptive containers, unify tokens/
   field styling. Sweep ChangeCredit / Expenses / Suppliers / PurchaseOrders / Settings for field polish.

**Next step:** confirm this order, then execute. #1–#3 are the ones that answer the original complaint.
