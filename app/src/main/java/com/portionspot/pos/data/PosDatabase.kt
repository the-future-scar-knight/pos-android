package com.portionspot.pos.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v5 → v6: wholesale catalog. Adds the per-unit wholesale price, the box price
 * and box size, and a free-text category to `items` (drives the POS category
 * chips and the Box/WS lines on the product card). Existing rows keep their
 * retail `price`; the new columns default to 0 / 1 / null. Real migration —
 * no data is dropped.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN wholesalePrice REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN boxPrice REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN boxSize INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE items ADD COLUMN category TEXT")
    }
}

/**
 * v6 → v7: expenses. Adds the local-only `expenses` table (rent, salaries,
 * fuel…) that mirrors the web's Dexie store. Not synced — there is no cloud
 * `expenses` table — so it carries no pendingSync column. Real migration:
 * existing data is untouched.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS expenses (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "businessId TEXT NOT NULL, " +
                "category TEXT NOT NULL, " +
                "amount REAL NOT NULL, " +
                "date TEXT NOT NULL, " +
                "description TEXT, " +
                "createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, " +
                "deleted INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_businessId ON expenses (businessId)")
    }
}

/**
 * v7 → v8: suppliers. Adds the local-only `suppliers` table (vendor contacts)
 * that mirrors the web's Dexie store. Not synced — there is no cloud `suppliers`
 * table — so it carries no pendingSync column. Real migration: existing data is
 * untouched.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS suppliers (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "businessId TEXT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "phone TEXT, " +
                "email TEXT, " +
                "address TEXT, " +
                "notes TEXT, " +
                "createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, " +
                "deleted INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_suppliers_businessId ON suppliers (businessId)")
    }
}

/**
 * v8 → v9: purchase orders. Adds the local-only `purchase_orders` header table
 * and its `purchase_order_items` line table (mirrors the web's Dexie store).
 * Not synced — the cloud schema has no PO tables — so no pendingSync column.
 * Receiving a PO bumps catalog stock (handled in the repository, not here).
 * Real migration: existing data is untouched.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS purchase_orders (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "businessId TEXT NOT NULL, " +
                "ref TEXT NOT NULL, " +
                "supplierId TEXT, " +
                "supplierName TEXT NOT NULL DEFAULT '', " +
                "status TEXT NOT NULL DEFAULT 'draft', " +
                "notes TEXT, " +
                "createdAt INTEGER NOT NULL, " +
                "sentAt INTEGER, " +
                "receivedAt INTEGER, " +
                "updatedAt INTEGER NOT NULL, " +
                "deleted INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_orders_businessId ON purchase_orders (businessId)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS purchase_order_items (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "poId TEXT NOT NULL, " +
                "itemId TEXT, " +
                "name TEXT NOT NULL DEFAULT '', " +
                "sku TEXT, " +
                "qty REAL NOT NULL DEFAULT 1, " +
                "unitCost REAL NOT NULL DEFAULT 0, " +
                "receivedQty REAL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_order_items_poId ON purchase_order_items (poId)")
    }
}

/**
 * v9 → v10: per-customer wholesale flag. Adds a local-only `wholesale` boolean to
 * `customers`. It is NOT pushed to the cloud (the shared `customers` table has no
 * such column) and is preserved across pulls in the sync engine. Real migration:
 * existing rows default to 0 (retail). No data is dropped.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE customers ADD COLUMN wholesale INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v11 → v12: dual-currency tender. Adds three NULLABLE columns to the local-only
 * `sale_payments` table so a tender can record the SECOND currency it was paid in
 * (e.g. ZiG), the amount handed over in that currency, and the exchange rate used.
 * The base `amount` column stays the source of truth, so every existing total and
 * report is unchanged. Not synced (sale_payments is local-only). Real migration:
 * existing tender rows keep NULLs (= base-currency cash) — no data is dropped.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sale_payments ADD COLUMN tenderCurrency TEXT")
        db.execSQL("ALTER TABLE sale_payments ADD COLUMN tenderAmount REAL")
        db.execSQL("ALTER TABLE sale_payments ADD COLUMN rate REAL")
    }
}

/**
 * v12 → v13: attribution/audit + refunds (Phase 2).
 *
 *  1. Adds NULLABLE `createdBy` / `createdByName` (and `serverCreatedAt` where the
 *     row can sync) to `sales`, `credit_transactions` and `stock_movements` so every
 *     financial record can carry which cashier created it. Nullable with no default,
 *     so existing rows keep NULL and nothing is rewritten.
 *  2. Creates the refund ledger (prompt §11): `refunds` (header), `refund_items`
 *     (returned lines) and `refund_payments` (money handed back, split/over-time).
 *
 * Real migration — additive only, no data dropped.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Attribution columns on the existing financial tables.
        db.execSQL("ALTER TABLE sales ADD COLUMN createdBy TEXT")
        db.execSQL("ALTER TABLE sales ADD COLUMN createdByName TEXT")
        db.execSQL("ALTER TABLE sales ADD COLUMN serverCreatedAt INTEGER")
        db.execSQL("ALTER TABLE credit_transactions ADD COLUMN createdBy TEXT")
        db.execSQL("ALTER TABLE credit_transactions ADD COLUMN createdByName TEXT")
        db.execSQL("ALTER TABLE credit_transactions ADD COLUMN serverCreatedAt INTEGER")
        db.execSQL("ALTER TABLE stock_movements ADD COLUMN createdBy TEXT")
        db.execSQL("ALTER TABLE stock_movements ADD COLUMN createdByName TEXT")

        // 2. Refund ledger: header + returned lines + payouts.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS refunds (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "businessId TEXT NOT NULL, " +
                "saleId TEXT NOT NULL, " +
                "saleReceiptNo TEXT, " +
                "customerId TEXT, " +
                "customerName TEXT, " +
                "reason TEXT, " +
                "refundTotal REAL NOT NULL DEFAULT 0, " +
                "status TEXT NOT NULL DEFAULT 'settled', " +
                "createdBy TEXT, " +
                "createdByName TEXT, " +
                "createdAt INTEGER NOT NULL, " +
                "serverCreatedAt INTEGER, " +
                "updatedAt INTEGER NOT NULL, " +
                "deleted INTEGER NOT NULL DEFAULT 0, " +
                "pendingSync INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_refunds_businessId ON refunds (businessId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_refunds_saleId ON refunds (saleId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_refunds_customerId ON refunds (customerId)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS refund_items (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "refundId TEXT NOT NULL, " +
                "businessId TEXT NOT NULL, " +
                "saleLineId TEXT, " +
                "itemId TEXT, " +
                "name TEXT NOT NULL, " +
                "qty REAL NOT NULL DEFAULT 1, " +
                "unitPrice REAL NOT NULL DEFAULT 0, " +
                "lineTotal REAL NOT NULL DEFAULT 0, " +
                "mode TEXT NOT NULL DEFAULT 'retail', " +
                "unitsPerLine INTEGER NOT NULL DEFAULT 1, " +
                "restock INTEGER NOT NULL DEFAULT 1, " +
                "createdAt INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_refund_items_refundId ON refund_items (refundId)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS refund_payments (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "refundId TEXT NOT NULL, " +
                "businessId TEXT NOT NULL, " +
                "method TEXT NOT NULL, " +
                "amount REAL NOT NULL DEFAULT 0, " +
                "reference TEXT, " +
                "tenderCurrency TEXT, " +
                "tenderAmount REAL, " +
                "rate REAL, " +
                "createdBy TEXT, " +
                "createdByName TEXT, " +
                "createdAt INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_refund_payments_refundId ON refund_payments (refundId)")
    }
}

/**
 * v13 → v14: mobile-money SMS reconciliation (Phase 5, prompt §6). Adds the
 * `mobile_money_receipts` table: one parsed payment SMS per row, with a UNIQUE
 * index on (businessId, txnCode) so the provider's transaction code enforces
 * idempotency at the DB level (the same SMS can never be stored twice). Local-
 * first (carries pendingSync); cloud push is deferred to the parity phase.
 * Real migration — additive only, no data dropped.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mobile_money_receipts (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "businessId TEXT NOT NULL, " +
                "provider TEXT NOT NULL DEFAULT 'unknown', " +
                "rawBody TEXT NOT NULL DEFAULT '', " +
                "sender TEXT, " +
                "senderName TEXT, " +
                "senderPhone TEXT, " +
                "amount REAL NOT NULL DEFAULT 0, " +
                "currency TEXT NOT NULL DEFAULT 'USD', " +
                "txnCode TEXT NOT NULL, " +
                "receivedAt INTEGER NOT NULL, " +
                "status TEXT NOT NULL DEFAULT 'unmatched', " +
                "matchedCustomerId TEXT, " +
                "matchedCustomerName TEXT, " +
                "purpose TEXT, " +
                "appliedCreditTxnId TEXT, " +
                "appliedSaleId TEXT, " +
                "note TEXT, " +
                "createdBy TEXT, " +
                "createdByName TEXT, " +
                "serverCreatedAt INTEGER, " +
                "updatedAt INTEGER NOT NULL, " +
                "deleted INTEGER NOT NULL DEFAULT 0, " +
                "pendingSync INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mobile_money_receipts_businessId ON mobile_money_receipts (businessId)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_mobile_money_receipts_businessId_txnCode " +
                "ON mobile_money_receipts (businessId, txnCode)"
        )
    }
}

/**
 * v14 → v15: admin notifications + audit log (Phase 7, prompt §8). Adds the
 * `notifications` table (persisted admin feed with read/push/escalation state, a
 * UNIQUE (businessId, dedupeKey) index so recomputes update one row) and the
 * `audit_log` table (append-only sensitive-action trail). Both local-only. Real
 * migration — additive, nothing dropped.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS notifications (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "businessId TEXT NOT NULL, " +
                "category TEXT NOT NULL, " +
                "severity TEXT NOT NULL DEFAULT 'info', " +
                "title TEXT NOT NULL, " +
                "body TEXT NOT NULL, " +
                "dedupeKey TEXT NOT NULL, " +
                "refType TEXT, " +
                "refId TEXT, " +
                "eventAt INTEGER NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "readAt INTEGER, " +
                "pushedAt INTEGER, " +
                "deleted INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_businessId ON notifications (businessId)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_notifications_businessId_dedupeKey " +
                "ON notifications (businessId, dedupeKey)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS audit_log (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "businessId TEXT NOT NULL, " +
                "action TEXT NOT NULL, " +
                "entityType TEXT, " +
                "entityId TEXT, " +
                "summary TEXT NOT NULL, " +
                "meta TEXT, " +
                "createdBy TEXT, " +
                "createdByName TEXT, " +
                "createdAt INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_log_businessId ON audit_log (businessId)")
    }
}

/** v15 → v16: quotes. A quote is a `sales` row with status='quote'; add its
 *  lapse date. Additive + nullable, so existing sales are untouched. */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sales ADD COLUMN validUntil INTEGER")
    }
}

/**
 * v16 → v17: product images. Adds the remote image URL (mirrors the cloud
 * `products.image_url`), a local cached-copy path + pending-upload flag (both
 * local-only), and the `show_image` toggle to `items`. Additive + nullable/defaulted,
 * so existing items keep NULL image / show=on and their cards are unchanged.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN imageUrl TEXT")
        db.execSQL("ALTER TABLE items ADD COLUMN imageLocalPath TEXT")
        db.execSQL("ALTER TABLE items ADD COLUMN imagePending INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN showImage INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * v17 → v18: product type (Box / Set / Piece). Adds `productType` to `items` so the
 * catalog carries the web's three-way product model instead of inferring box-vs-unit
 * from box size. Existing rows are backfilled by their box size — a real box item
 * (boxSize > 1) becomes "box"; a single-unit item becomes "piece" (sold individually).
 * Additive + defaulted, so nothing is dropped and every existing card is unchanged.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN productType TEXT NOT NULL DEFAULT 'box'")
        db.execSQL("UPDATE items SET productType = CASE WHEN boxSize > 1 THEN 'box' ELSE 'piece' END")
    }
}

/**
 * v18 → v19: per-customer credit limit. Adds a nullable `creditLimit` (REAL) to
 * `customers` — a local-only credit ceiling the cashier can set when editing a
 * customer. Additive + nullable, so existing rows keep NULL (no limit set) and
 * nothing is dropped.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE customers ADD COLUMN creditLimit REAL")
    }
}

/**
 * v19 → v20: per-item markup. Mirrors the per-item discount plumbing but ADDS to the
 * line price instead of subtracting. Adds `lineMarkup` to `sale_items` (per-line
 * markup snapshot) and `markupTotal` to `sales` (sum of per-item markups, the mirror
 * of `discountTotal`). Local-only — the shared cloud schema has no markup column, so
 * these are never pushed. Additive + defaulted, so existing rows keep 0 and nothing
 * is dropped.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sale_items ADD COLUMN lineMarkup REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sales ADD COLUMN markupTotal REAL NOT NULL DEFAULT 0")
    }
}

// Records the change the shop still owes the customer (change due minus change
// handed over now). Nullable; local-only (the cloud has no such column).
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sales ADD COLUMN changeOwed REAL")
    }
}

// v21 → v22: measured (unit-priced) products. Adds the two decimal LOCAL-ONLY
// columns backing productType='measured' — price for one unit and the decimal
// on-hand quantity. The existing `unit` TEXT column doubles as the measured unit
// label (kg/L/m/…), so no new unit column is needed. Cloud has no matching
// columns; these stay on-device.
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN pricePerUnit REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN stockMeasured REAL NOT NULL DEFAULT 0")
    }
}

/**
 * v22 → v23: expenses become the accounting spine + a cash ledger (B3).
 *
 *  1. Extends `expenses` with the approval lifecycle (status/approver/postedAt), the
 *     double-entry-lite funding split (cash/payable/capital portions), the recurring
 *     schedule (period/active/template/next-run) and sync-ready columns (localId +
 *     pendingSync). Every column is additive with a default, so existing expenses are
 *     untouched. Existing rows are backfilled: localId = id, status = 'approved' (they
 *     were already real spent costs, so they keep counting toward derived profit),
 *     postedAt = createdAt. They deliberately get NO funding-portion backfill and NO
 *     cash-ledger row — the cash balance starts fresh from this version, so a history
 *     of expenses with no matching historical cash-in can't drive it negative.
 *  2. Creates the `cash_txns` cash-on-hand ledger (append-only, sync-ready).
 *
 * Real migration — additive only, no data dropped.
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Expense: approval + funding split + recurring + sync-ready.
        db.execSQL("ALTER TABLE expenses ADD COLUMN localId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE expenses ADD COLUMN status TEXT NOT NULL DEFAULT 'approved'")
        db.execSQL("ALTER TABLE expenses ADD COLUMN submittedBy TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN submittedByName TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN approvedBy TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN approvedByName TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN approvedAt INTEGER")
        db.execSQL("ALTER TABLE expenses ADD COLUMN postedAt INTEGER")
        db.execSQL("ALTER TABLE expenses ADD COLUMN cashPortion REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE expenses ADD COLUMN payablePortion REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE expenses ADD COLUMN capitalPortion REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE expenses ADD COLUMN recurring INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE expenses ADD COLUMN recurrencePeriod TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN recurrenceActive INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE expenses ADD COLUMN isTemplate INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE expenses ADD COLUMN templateId TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN nextRunAt INTEGER")
        db.execSQL("ALTER TABLE expenses ADD COLUMN lastRunAt INTEGER")
        db.execSQL("ALTER TABLE expenses ADD COLUMN periodStart TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN periodEnd TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 1")
        db.execSQL("UPDATE expenses SET localId = id, postedAt = createdAt")

        // 2. Cash-on-hand ledger (append-only, sync-ready).
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS cash_txns (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "localId TEXT NOT NULL, " +
                "businessId TEXT NOT NULL, " +
                "type TEXT NOT NULL, " +
                "amount REAL NOT NULL DEFAULT 0, " +
                "source TEXT, " +
                "note TEXT, " +
                "refType TEXT, " +
                "refId TEXT, " +
                "createdBy TEXT, " +
                "createdByName TEXT, " +
                "createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, " +
                "deleted INTEGER NOT NULL DEFAULT 0, " +
                "pendingSync INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_txns_businessId ON cash_txns (businessId)")
    }
}

/**
 * v23 → v24: supplier orders + pending stock (B4).
 *
 *  1. `items` gains two LOCAL-ONLY columns backing incoming (pending) stock: `pendingQty`
 *     (ordered-but-not-arrived units, shown as a "+N pending" badge and NOT sellable) and
 *     `pendingNew` (a product created by a PO whose first arrival hasn't been confirmed —
 *     fully blocked from sale until arrival). The cloud `products` table has no matching
 *     columns, so these stay on-device.
 *  2. `purchase_orders` gains the ETA + payment split: `eta`, `cashPaid`, `capitalPaid`,
 *     `payableRemainder`, `arrivalPromptedAt`. Paying for a PO drains cash via a
 *     `cash_txns` "purchase" row (NOT an expense — a cash→inventory asset purchase), and
 *     any unpaid balance is accounts payable to the supplier.
 *  3. `purchase_order_items` gains `sellPrice`, `stockOnArrival`, `productType` so a line
 *     can seed a brand-new product and be flagged to auto-stock on arrival.
 *
 * Real migration — additive with defaults, no data dropped.
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN pendingQty REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN pendingNew INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE purchase_orders ADD COLUMN eta INTEGER")
        db.execSQL("ALTER TABLE purchase_orders ADD COLUMN cashPaid REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE purchase_orders ADD COLUMN capitalPaid REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE purchase_orders ADD COLUMN payableRemainder REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE purchase_orders ADD COLUMN arrivalPromptedAt INTEGER")
        db.execSQL("ALTER TABLE purchase_order_items ADD COLUMN sellPrice REAL")
        db.execSQL("ALTER TABLE purchase_order_items ADD COLUMN stockOnArrival INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE purchase_order_items ADD COLUMN productType TEXT NOT NULL DEFAULT 'piece'")
    }
}

/**
 * B5 — view + edit receipt. A receipt edited inside the admin window is rewritten IN
 * PLACE (same id, same receiptNo), so the only new state is the marker saying it
 * happened. The actual what-changed history is append-only rows in `audit_log`.
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sales ADD COLUMN editedAt INTEGER")
        db.execSQL("ALTER TABLE sales ADD COLUMN editCount INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v25 → v26: cloud sync for the accounting/supplier tables.
 *
 * The owner approved pushing the previously LOCAL-ONLY features to the shared Supabase,
 * and the cloud tables (`expenses`, `cash_txns`, `suppliers`, `purchase_orders`,
 * `purchase_order_items`, plus `products.unit/price_per_unit/stock_measured`) now exist.
 * `expenses` and `cash_txns` were already built sync-ready (they carry localId +
 * pendingSync); the three supplier-order tables were not, so they gain both here.
 *
 * BACKFILL matters: every row already on the device is stamped `localId = id` and
 * `pendingSync = 1` so the whole existing history uploads on the FIRST sync pass rather
 * than only rows touched from now on. expenses/cash_txns are re-stamped pending for the
 * same reason — nothing ever marked them synced, but this makes it explicit.
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // suppliers / purchase_orders / purchase_order_items become sync-ready.
        db.execSQL("ALTER TABLE suppliers ADD COLUMN localId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE suppliers ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 1")
        db.execSQL("UPDATE suppliers SET localId = id, pendingSync = 1")

        db.execSQL("ALTER TABLE purchase_orders ADD COLUMN localId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE purchase_orders ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 1")
        db.execSQL("UPDATE purchase_orders SET localId = id, pendingSync = 1")

        db.execSQL("ALTER TABLE purchase_order_items ADD COLUMN localId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE purchase_order_items ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 1")
        db.execSQL("UPDATE purchase_order_items SET localId = id, pendingSync = 1")

        // Already-recorded expenses / cash movements: make sure they queue for upload.
        db.execSQL("UPDATE expenses SET localId = id WHERE localId IS NULL OR localId = ''")
        db.execSQL("UPDATE expenses SET pendingSync = 1")
        db.execSQL("UPDATE cash_txns SET localId = id WHERE localId IS NULL OR localId = ''")
        db.execSQL("UPDATE cash_txns SET pendingSync = 1")

        // Measured products now have cloud columns — re-queue the catalogue so the
        // unit / price_per_unit / stock_measured this device holds reaches the cloud.
        db.execSQL("UPDATE items SET pendingSync = 1 WHERE productType = 'measured'")
    }
}

/**
 * v26 → v27: admin notifications become CLOUD-SYNCED (multi-device).
 *
 * Alerts were local-only, so a condition a cashier's phone noticed never reached the
 * owner's admin phone. The cloud `notifications` table upserts on the composite key
 * `(business_id, dedupe_key)`, so the row needs the two sync columns every other synced
 * entity carries: `updatedAt` (last-write-wins + the `updated_at=gt.<cursor>` pull) and
 * `pendingSync` (the upload queue).
 *
 * BACKFILL: existing rows get `updatedAt = createdAt` and `pendingSync = 1` so the whole
 * feed this device already holds uploads once on the next pass instead of staying
 * invisible to the other phones. `pushedAt` is deliberately untouched — it is
 * device-local state about THIS phone's heads-up notifications.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notifications ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE notifications ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 1")
        db.execSQL("UPDATE notifications SET updatedAt = createdAt WHERE updatedAt = 0")
        db.execSQL("UPDATE notifications SET pendingSync = 1")
    }
}

/**
 * v27 → v28 — AUDIT LOG SYNC. The append-only trail gains the two sync columns every
 * other synced table already has: `updatedAt` (feeds the `updated_at=gt.<cursor>` pull
 * cursor; for an immutable row it simply mirrors `createdAt`) and `pendingSync` (the
 * upload queue).
 *
 * BACKFILL: existing rows get `updatedAt = createdAt` and `pendingSync = 1`, so the
 * history this device already holds uploads ONCE on the next pass instead of staying
 * invisible to the admin phone. The cloud side has no UPDATE policy, so that one upload
 * is insert-or-skip and re-running it can never rewrite a shared row.
 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE audit_log ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE audit_log ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 1")
        db.execSQL("UPDATE audit_log SET updatedAt = createdAt WHERE updatedAt = 0")
        db.execSQL("UPDATE audit_log SET pendingSync = 1")
    }
}

/**
 * v28 → v29 — NOTIFICATION AUDIENCE (admin⇄cashier targeting). Adds `audience` to
 * `notifications` so a row can be aimed at the admin phone, the cashier phones, or
 * everyone. It drives which device raises a system heads-up (a cashier must not buzz
 * for an admin-only alert) and where the notification deep-links.
 *
 * DEFAULT 'admin' backfills the whole existing feed to the historical behaviour — every
 * alert built so far (engine conditions, expense submissions, recurring postings) was
 * admin-facing — so no data is dropped and old rows keep targeting the admin. `audience`
 * is SYNCED (it is content, not device-local state), so pushing it re-queues nothing on
 * its own; the cloud column `notifications.audience text not null default 'admin'` is
 * added in parallel and a push that beats it fails per-table (surfaced + retried) — the
 * engine already tolerates that.
 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notifications ADD COLUMN audience TEXT NOT NULL DEFAULT 'admin'")
    }
}

/**
 * v29 → v30 — STAFF REQUESTS (admin⇄cashier approval channel, Phase 3). Adds the
 * `staff_requests` table: a cashier RAISES a request (over-threshold discount, void…)
 * that lands in the admin's Alerts feed on the other phone; the admin approves/denies it
 * and the decision syncs back.
 *
 * SYNC-READY from birth: carries `localId` (= id) + `pendingSync`, and the cloud table
 * `public.staff_requests` already exists (see the Phase-3 brief). RLS shapes the push
 * (staff INSERT/SELECT, admin-only UPDATE/DELETE), which is handled in PosSyncEngine.
 *
 * Additive only — a brand-new table, so no existing data is touched. Indices on
 * businessId / status / requestedBy back the admin-pending, my-requests and pending-count
 * queries.
 */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS staff_requests (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "localId TEXT NOT NULL DEFAULT '', " +
                "businessId TEXT NOT NULL, " +
                "type TEXT NOT NULL, " +
                "targetType TEXT, " +
                "targetId TEXT, " +
                "targetName TEXT, " +
                "amount REAL, " +
                "note TEXT, " +
                "requestedBy TEXT, " +
                "requestedByName TEXT, " +
                "status TEXT NOT NULL DEFAULT 'pending', " +
                "decidedBy TEXT, " +
                "decidedByName TEXT, " +
                "decidedAt INTEGER, " +
                "applied INTEGER NOT NULL DEFAULT 0, " +
                "createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, " +
                "deleted INTEGER NOT NULL DEFAULT 0, " +
                "pendingSync INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_staff_requests_businessId ON staff_requests (businessId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_staff_requests_status ON staff_requests (status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_staff_requests_requestedBy ON staff_requests (requestedBy)")
    }
}

/**
 * v30 → v31 — FREEZE COST OF GOODS AT SALE TIME. Adds `sale_items.unitCost`: the
 * item's cost price captured the moment the line was rung up.
 *
 * Why: gross profit joined `items i` and used `i.cost` — the LIVE catalog cost — so
 * editing a product's cost price silently rewrote the profit of every past sale. With
 * the cost frozen on the line, history stops moving.
 *
 * Additive and nullable, so nothing is lost: the profit query prefers `li.unitCost`
 * and falls back to `i.cost` only where the line has none (rows pulled from the cloud,
 * which carries no cost column, keep reporting exactly as before).
 *
 * BACKFILL: existing lines take the item's CURRENT cost. That is an approximation, not
 * the truth — the real cost at the time of those old sales was never recorded, and this
 * is the best available stand-in. It is also exactly what those rows were already
 * reporting, so the backfill moves no existing number; it only stops them moving again.
 */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sale_items ADD COLUMN unitCost REAL")
        db.execSQL(
            "UPDATE sale_items SET unitCost = " +
                "(SELECT i.cost FROM items i WHERE i.id = sale_items.itemId) " +
                "WHERE itemId IS NOT NULL"
        )
    }
}

/**
 * v31 → v32 — TWO CASH LOCATIONS (till + safe). Adds `cash_txns.location`.
 *
 * The shop has no bank: its cash sits on-site in exactly two places, a working TILL float
 * and a SAFE holding the day's takings. Rather than a second ledger (which would drift),
 * the existing append-only ledger gains a location per movement. Cash-on-hand keeps its
 * meaning — the SUM over both locations — because a move between them is written as a
 * matching `transfer_out`/`transfer_in` PAIR that nets to zero.
 *
 * BACKFILL: every existing row defaults to 'till'. That is not a guess — before this
 * version there was only the drawer, so all historical cash was till cash. The safe
 * starts empty and fills on the first close of day.
 *
 * LOCAL-ONLY: the cloud `cash_txns` table has no `location`, so the column is absent from
 * CashTxnDto and never pushed; a pulled row keeps whatever this device recorded.
 */
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cash_txns ADD COLUMN location TEXT NOT NULL DEFAULT 'till'")
    }
}

/**
 * v32 → v33 — CLOSE THE DAY. Adds the `day_closes` table: one permanent row per counted
 * close, holding what the ledger expected, what was physically counted, the variance
 * between them, how much was moved into the safe and who closed.
 *
 * The variance is kept here as well as in the ledger (as a `variance` cash row) because
 * the two answer different questions: the ledger keeps the BALANCE right, this keeps the
 * HISTORY — including per-cashier attribution, so a repeat offender is visible.
 *
 * Brand-new table, so nothing existing is touched. Sync-ready (localId / updatedAt /
 * pendingSync) but deliberately NOT wired to push or pull: the cloud schema has no
 * `day_closes` and this phase does not change it.
 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS day_closes (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "localId TEXT NOT NULL DEFAULT '', " +
                "businessId TEXT NOT NULL, " +
                "dayStart INTEGER NOT NULL, " +
                "expectedCash REAL NOT NULL DEFAULT 0, " +
                "countedCash REAL NOT NULL DEFAULT 0, " +
                "variance REAL NOT NULL DEFAULT 0, " +
                "movedToSafe REAL NOT NULL DEFAULT 0, " +
                "floatTarget REAL NOT NULL DEFAULT 0, " +
                "note TEXT, " +
                "closedBy TEXT, " +
                "closedByName TEXT, " +
                "closedAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, " +
                "deleted INTEGER NOT NULL DEFAULT 0, " +
                "pendingSync INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_day_closes_businessId ON day_closes (businessId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_day_closes_closedAt ON day_closes (closedAt)")
    }
}

/**
 * v33 → v34 — OUTSIDE FUNDS (owner capital, loans, drawings). Adds `outside_funds`: the
 * equity/liability ledger that sits beside the cash ledger, so money from outside the
 * shop can never be mistaken for takings.
 *
 * BACKFILL matters here. Owner-funded money was already being recorded, just scattered:
 * `expenses.capitalPortion` (a bill the owner covered out of pocket) and
 * `purchase_orders.capitalPaid` (stock the owner paid for). Both are copied in as
 * `kind='capital', direction='in'` rows carrying their source ref, so the owner's running
 * "put in" total is CONTINUOUS across the upgrade instead of resetting to zero on a
 * number they have been watching. Nothing is deleted or moved — the source columns stay
 * exactly as they are; this is the one ledger that now totals them.
 *
 * `pendingSync = 0` on the backfilled rows: they are reconstructions of history, not new
 * facts, so they should never queue for an upload that (deliberately) does not exist.
 *
 * Sync-ready but NOT wired to push/pull — the cloud schema has no `outside_funds`.
 */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS outside_funds (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "localId TEXT NOT NULL DEFAULT '', " +
                "businessId TEXT NOT NULL, " +
                "kind TEXT NOT NULL DEFAULT 'capital', " +
                "direction TEXT NOT NULL DEFAULT 'in', " +
                "amount REAL NOT NULL DEFAULT 0, " +
                "source TEXT, " +
                "note TEXT, " +
                "refType TEXT, " +
                "refId TEXT, " +
                "createdBy TEXT, " +
                "createdByName TEXT, " +
                "createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, " +
                "deleted INTEGER NOT NULL DEFAULT 0, " +
                "pendingSync INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outside_funds_businessId ON outside_funds (businessId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outside_funds_createdAt ON outside_funds (createdAt)")

        // Backfill 1: bills the owner covered out of pocket.
        db.execSQL(
            "INSERT INTO outside_funds (" +
                "id, localId, businessId, kind, direction, amount, source, note, " +
                "refType, refId, createdBy, createdByName, createdAt, updatedAt, deleted, pendingSync) " +
                "SELECT 'of-exp-' || e.id, 'of-exp-' || e.id, e.businessId, 'capital', 'in', " +
                "e.capitalPortion, 'Owner', 'Covered ' || e.category, 'expense', e.id, " +
                "e.approvedBy, e.approvedByName, COALESCE(e.postedAt, e.createdAt), " +
                "COALESCE(e.postedAt, e.createdAt), 0, 0 " +
                "FROM expenses e WHERE e.deleted = 0 AND e.isTemplate = 0 " +
                "AND e.status = 'approved' AND e.capitalPortion > 0"
        )
        // Backfill 2: stock the owner paid for.
        db.execSQL(
            "INSERT INTO outside_funds (" +
                "id, localId, businessId, kind, direction, amount, source, note, " +
                "refType, refId, createdBy, createdByName, createdAt, updatedAt, deleted, pendingSync) " +
                "SELECT 'of-po-' || p.id, 'of-po-' || p.id, p.businessId, 'capital', 'in', " +
                "p.capitalPaid, 'Owner', 'Covered ' || p.ref, 'purchase_order', p.id, " +
                "NULL, NULL, p.createdAt, p.createdAt, 0, 0 " +
                "FROM purchase_orders p WHERE p.deleted = 0 AND p.status != 'cancelled' " +
                "AND p.capitalPaid > 0"
        )
    }
}

/**
 * Shop-wide capability locks on [Business] — the coarse half of the permission model,
 * mirroring the web's `businesses.lock_*` columns.
 *
 * All seven default to 0 (unlocked), which is the only safe backfill: a shop that has
 * never had these switches has never used them, and defaulting any of them to LOCKED
 * would silently take a capability away from every cashier on upgrade — for a shop that
 * never asked for it. The owner turns a lock on deliberately or it stays off.
 */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "lockRefunds", "lockDiscounts", "lockCredit", "lockPriceOverride",
            "lockParking", "lockQuotes", "lockStockAdjust"
        ).forEach { col ->
            db.execSQL("ALTER TABLE businesses ADD COLUMN $col INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/**
 * The trading SHIFT — [CashSession] — plus the `sessionId` that ties a sale or a refund
 * to the shift it happened in.
 *
 * Nothing is backfilled. Every existing sale keeps `sessionId = null`, which reads as
 * "rung up before the shop tracked shifts" and is the truth. Inventing a session to hang
 * history off would put real money into a shift that never existed and that nobody ever
 * counted — a cash-up is a claim about what was in a drawer at a moment, and it cannot be
 * reconstructed after the fact.
 */
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS cash_sessions (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "businessId TEXT NOT NULL, " +
                "status TEXT NOT NULL DEFAULT 'open', " +
                "openedAt INTEGER NOT NULL, " +
                "openedBy TEXT, " +
                "openedByName TEXT, " +
                "openingFloat REAL NOT NULL DEFAULT 0, " +
                "closedAt INTEGER, " +
                "closedBy TEXT, " +
                "closedByName TEXT, " +
                "countedCash REAL, " +
                "expectedCash REAL, " +
                "note TEXT, " +
                "tillCode TEXT, " +
                "updatedAt INTEGER NOT NULL, " +
                "deleted INTEGER NOT NULL DEFAULT 0, " +
                "pendingSync INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_sessions_businessId ON cash_sessions (businessId)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_cash_sessions_businessId_status " +
                "ON cash_sessions (businessId, status)"
        )
        db.execSQL("ALTER TABLE sales ADD COLUMN sessionId TEXT")
        db.execSQL("ALTER TABLE refunds ADD COLUMN sessionId TEXT")
    }
}

/**
 * Make the stock ledger syncable.
 *
 * `stock_movements` was device-local, so a sale drew down the till that rang it up and no
 * other till ever heard. Since `items.stock_qty` is only a CACHE of these rows, that left
 * a shop with as many different stock figures as it had tills, none of them wrong from
 * where it was standing.
 *
 * Existing rows are backfilled `pendingSync = 0` — NOT 1. They are this device's own
 * history of draw-downs it already applied locally; pushing them now would replay every
 * sale the till has ever made into the shared ledger and take the shop's stock down twice.
 * History stays where it is; only movements made from here travel.
 */
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE stock_movements ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE stock_movements ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE stock_movements ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 1")
        db.execSQL("UPDATE stock_movements SET pendingSync = 0, updatedAt = createdAt WHERE deleted = 0")
    }
}

/**
 * Item key/value tags — the web's `item_attributes`, in this shop the cars each part fits.
 *
 * Purely additive: a new table, nothing touched on `items`. Every row arrives from the
 * pull, so an upgraded device starts empty and fills on its next sync rather than needing
 * anything backfilled here. There is no `pendingSync` column by design — the web owns the
 * catalogue, so these travel one way only.
 *
 * Index names are Room's own (`index_<table>_<cols>`); a hand-rolled name here passes the
 * migration and then fails Room's schema identity check on the next open.
 */
val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `item_attributes` (" +
                "`id` TEXT NOT NULL, " +
                "`businessId` TEXT NOT NULL, " +
                "`itemId` TEXT NOT NULL, " +
                "`key` TEXT NOT NULL, " +
                "`value` TEXT NOT NULL, " +
                "`keyNorm` TEXT NOT NULL, " +
                "`valueNorm` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_item_attributes_businessId ON item_attributes (businessId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_item_attributes_itemId ON item_attributes (itemId)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_item_attributes_businessId_keyNorm_valueNorm " +
                "ON item_attributes (businessId, keyNorm, valueNorm)"
        )
    }
}

/**
 * Give every item a stock BASELINE, and repair the shelves the ledger emptied.
 *
 * `stock_movements` records CHANGES and nothing has ever written an opening entry, so
 * `SUM(delta)` is how much an item has moved, not what is on the shelf. The sync pass read
 * it as an on-hand: an item pulled with 2, sold once, held exactly one movement of -1, and
 * came back from the recompute as -1 — out of stock, with two of them on the shelf.
 *
 * The columns start at 0, which reads as "no baseline known" and makes the recompute skip
 * the item entirely — so the damage stops on upgrade, before any sync runs. The repair
 * needs the shop's own figure, which is not on the device, so the items cursor is dropped
 * as well: the next pull re-reads the whole catalogue, stamps each item with the cloud's
 * `stock_qty` and `updated_at`, and the recompute replays only the movements made after
 * that instant. A sale rung up offline before this upgrade is therefore still subtracted,
 * exactly once.
 */
val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN stockBaseQty REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN stockBaseAt INTEGER NOT NULL DEFAULT 0")
        // Force a full catalogue re-read so every item gets a baseline from the shop.
        db.execSQL("DELETE FROM settings WHERE `key` = 'cursor_items'")
    }
}

/**
 * v39 → v40 — THE SHOP ROSTER LANDS ON THE DEVICE. Adds the `staff` table: the local
 * mirror of the shared cloud `staff` row (id, business_id, name, username, role, active,
 * pin_hash, permissions, updated_at, deleted).
 *
 * This is what makes staff sign-in work at all, and specifically what makes it work with
 * no network. The credential is `username` + a PIN checked against `pin_hash`, and the
 * hash is byte-compatible with the web's (see [com.portionspot.pos.auth.StaffPin]) — so
 * once a device has pulled the roster ONCE, every cashier on it can open the till through
 * a power cut, a dead cell, or a cloud outage. Nothing secret is added to the device by
 * doing this: a hash is a hash, and the plain PIN was never stored anywhere.
 *
 * It replaces a sign-in that could not work at ALL: the old path authenticated against
 * Supabase GoTrue at a hard-coded URL belonging to a different project, then read a table
 * called `pos_staff` that does not exist in the shared schema, then created cashiers
 * through an Edge Function that was never deployed. Three independent breaks, one
 * replacement.
 *
 * Brand-new table, so no existing data is touched and nothing can be lost. Sync-ready
 * from birth (`updatedAt` / `deleted` / `pendingSync`), and `staff` is already in
 * [com.portionspot.pos.sync.SyncConfig.TABLES], so it gets a pull cursor for free.
 *
 * `(businessId, username)` is a PLAIN index, not unique — the cloud table has no unique
 * constraint on it either, and inventing one here would turn a merely-untidy shop into a
 * pull that throws.
 */
val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `staff` (" +
                "`id` TEXT NOT NULL, " +
                "`businessId` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`username` TEXT NOT NULL, " +
                "`role` TEXT NOT NULL, " +
                "`active` INTEGER NOT NULL, " +
                "`pinHash` TEXT, " +
                // The business id the hash was salted with — see StaffMember.pinShopId.
                // Empty string means "the same as businessId".
                "`pinShopId` TEXT NOT NULL DEFAULT '', " +
                "`permissions` TEXT, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, " +
                "`pendingSync` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_staff_businessId ON staff (businessId)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_staff_businessId_username " +
                "ON staff (businessId, username)"
        )
    }
}

/**
 * v40 → v41: a shift becomes a TRADING DAY, and the day-close writes its figures onto it.
 *
 * Two columns are added to `cash_sessions` — `movedToSafe` and `floatTarget` — so the
 * closing half of a day close has somewhere to live on the row the cloud actually shares.
 * They are the last two of the shape `public.cash_sessions` already carries; `variance`
 * stays GENERATED on that side and DERIVED here, and is deliberately not a column in
 * either place.
 *
 * DEFAULT 0 and NOT NULL: a session that predates this migration was never counted out
 * through the new path, so zero is not a guess — it is the truthful statement that nothing
 * was recorded as moved and no float target was recorded as left. Any other default would
 * put money in the safe that never went there.
 *
 * No data migration for the sessions themselves. Attaching historical sales to day
 * sessions is done by [PosRepository.backfillDaySessions] at runtime, not here: it has to
 * MINT session ids and decide which day each sale belongs to in the device's own timezone,
 * and neither is something SQL in a migration can do honestly.
 */
val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cash_sessions ADD COLUMN movedToSafe REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cash_sessions ADD COLUMN floatTarget REAL NOT NULL DEFAULT 0")
    }
}

/**
 * v41 → v42 — ITEM TAGS BECOME TWO-WAY. Adds `item_attributes.pendingSync`, the flag the
 * new push block selects on, so a fitment added at the counter reaches the shop instead of
 * living and dying on one phone.
 *
 * ★ DEFAULT 0, not 1, and that is the whole point of writing this by hand. Every existing
 * row in this table CAME FROM the cloud — 671 of them in the live shop — so marking them
 * dirty would upload the shop's entire tag list back to the row it was read from on the
 * very next pass. Nothing would break; it would just be several hundred rows of pointless
 * traffic on a phone paying for its own data, once per device.
 *
 * Additive column on an existing table, so no data moves and nothing can be lost.
 */
val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE item_attributes ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        Business::class,
        Item::class,
        ItemAttribute::class,
        SaleEntity::class,
        SaleLine::class,
        SalePayment::class,
        StockMovement::class,
        Customer::class,
        CreditTxn::class,
        Setting::class,
        Expense::class,
        CashTxn::class,
        Supplier::class,
        PurchaseOrder::class,
        PurchaseOrderLine::class,
        Refund::class,
        RefundLine::class,
        RefundPayment::class,
        MobileMoneyReceipt::class,
        AppNotification::class,
        AuditEntry::class,
        StaffRequest::class,
        DayClose::class,
        OutsideFund::class,
        CashSession::class,
        StaffMember::class
    ],
    version = 42,
    exportSchema = false
)
abstract class PosDatabase : RoomDatabase() {
    abstract fun businessDao(): BusinessDao
    abstract fun itemDao(): ItemDao
    abstract fun itemAttributeDao(): ItemAttributeDao
    abstract fun saleDao(): SaleDao
    abstract fun salePaymentDao(): SalePaymentDao
    abstract fun stockMovementDao(): StockMovementDao
    abstract fun customerDao(): CustomerDao
    abstract fun creditDao(): CreditDao
    abstract fun settingDao(): SettingDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun cashTxnDao(): CashTxnDao
    abstract fun supplierDao(): SupplierDao
    abstract fun purchaseOrderDao(): PurchaseOrderDao
    abstract fun refundDao(): RefundDao
    abstract fun mobileMoneyDao(): MobileMoneyDao
    abstract fun notificationDao(): NotificationDao
    abstract fun auditDao(): AuditDao
    abstract fun staffRequestDao(): StaffRequestDao
    abstract fun dayCloseDao(): DayCloseDao
    abstract fun outsideFundDao(): OutsideFundDao
    abstract fun cashSessionDao(): CashSessionDao
    abstract fun staffDao(): StaffDao
    abstract fun syncArmDao(): SyncArmDao

    companion object {
        @Volatile
        private var INSTANCE: PosDatabase? = null

        fun get(context: Context): PosDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PosDatabase::class.java,
                    "spot_pos.db"
                )
                    // v10 → v11 deliberately has NO migration: the parity rebuild
                    // (split payments, stock ledger, reorder levels, fresh empty
                    // catalog) is a clean slate, so the destructive fallback wipes
                    // any old local DB and recreates it empty. v11 → v12 IS a real
                    // migration (additive dual-currency tender columns), so a v11
                    // install keeps its data. The destructive fallback remains the
                    // net for any version with no path.
                    .addMigrations(
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                        MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                        MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
                        MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
                        MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26,
                        MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29,
                        MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32,
                        MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35,
                        MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38,
                        MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41,
                        MIGRATION_41_42
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
