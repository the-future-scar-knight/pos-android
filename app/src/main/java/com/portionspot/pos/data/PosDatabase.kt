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
        Supplier::class,
        PurchaseOrder::class,
        PurchaseOrderLine::class
    ],
    version = 12,
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
    abstract fun supplierDao(): SupplierDao
    abstract fun purchaseOrderDao(): PurchaseOrderDao

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
                        MIGRATION_9_10, MIGRATION_11_12
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
