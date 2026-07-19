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

@Database(
    entities = [
        Business::class,
        Item::class,
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
        AuditEntry::class
    ],
    version = 25,
    exportSchema = false
)
abstract class PosDatabase : RoomDatabase() {
    abstract fun businessDao(): BusinessDao
    abstract fun itemDao(): ItemDao
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
                        MIGRATION_23_24, MIGRATION_24_25
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
