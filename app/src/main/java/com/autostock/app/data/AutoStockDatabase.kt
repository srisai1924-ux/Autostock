package com.autostock.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Product::class, Sale::class, SaleItem::class, Purchase::class, PurchaseItem::class, PurchaseReturn::class, PurchaseReturnItem::class, Supplier::class, SupplierPayment::class, SupplierPaymentAllocation::class], version = 10, exportSchema = true)
abstract class AutoStockDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS products_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        oemNumber TEXT NOT NULL,
                        aftermarketNumbers TEXT NOT NULL,
                        brand TEXT NOT NULL,
                        vehicleCompatibility TEXT NOT NULL,
                        category TEXT NOT NULL,
                        barcode TEXT NOT NULL,
                        purchasePricePaise INTEGER NOT NULL,
                        sellingPricePaise INTEGER NOT NULL,
                        gstRate REAL NOT NULL,
                        quantity INTEGER NOT NULL,
                        lowStockAlert INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    INSERT INTO products_new (
                        id, name, oemNumber, aftermarketNumbers, brand,
                        vehicleCompatibility, category, barcode,
                        purchasePricePaise, sellingPricePaise, gstRate,
                        quantity, lowStockAlert
                    )
                    SELECT id, name, partNumber, '', brand, vehicleType, category,
                           barcode, purchasePricePaise, sellingPricePaise, gstRate,
                           quantity, lowStockAlert
                    FROM products
                """)
                db.execSQL("DROP TABLE products")
                db.execSQL("ALTER TABLE products_new RENAME TO products")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_products_oemNumber ON products(oemNumber)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_products_barcode ON products(barcode)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN vehicleMake TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE products ADD COLUMN vehicleModel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE products ADD COLUMN vehicleVariant TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE products ADD COLUMN yearFrom INTEGER")
                db.execSQL("ALTER TABLE products ADD COLUMN yearTo INTEGER")
                db.execSQL("ALTER TABLE products ADD COLUMN compatibilityNotes TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS sales (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    customerName TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    subtotalPaise INTEGER NOT NULL,
                    gstPaise INTEGER NOT NULL,
                    totalPaise INTEGER NOT NULL
                )""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS sale_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    saleId INTEGER NOT NULL,
                    productId INTEGER NOT NULL,
                    productName TEXT NOT NULL,
                    barcode TEXT NOT NULL,
                    quantity INTEGER NOT NULL,
                    unitPricePaise INTEGER NOT NULL,
                    gstRate REAL NOT NULL,
                    lineSubtotalPaise INTEGER NOT NULL,
                    gstPaise INTEGER NOT NULL,
                    lineTotalPaise INTEGER NOT NULL
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sale_items_saleId ON sale_items(saleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sale_items_productId ON sale_items(productId)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN hsnCode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sales ADD COLUMN invoiceNumber TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sales ADD COLUMN sellerName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sales ADD COLUMN sellerAddress TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sales ADD COLUMN customerAddress TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sales ADD COLUMN sellerGstin TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sales ADD COLUMN customerGstin TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sales ADD COLUMN sellerStateCode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sales ADD COLUMN customerStateCode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sales ADD COLUMN taxType TEXT NOT NULL DEFAULT 'LEGACY'")
                db.execSQL("ALTER TABLE sales ADD COLUMN cgstPaise INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sales ADD COLUMN sgstPaise INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sales ADD COLUMN igstPaise INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sale_items ADD COLUMN hsnCode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sale_items ADD COLUMN cgstPaise INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sale_items ADD COLUMN sgstPaise INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sale_items ADD COLUMN igstPaise INTEGER NOT NULL DEFAULT 0")
                val cursor = db.query("SELECT id FROM sales ORDER BY id")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    db.execSQL("UPDATE sales SET invoiceNumber = ? WHERE id = ?", arrayOf("LEGACY-${id}", id))
                }
                cursor.close()
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sales_invoiceNumber ON sales(invoiceNumber)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS purchases (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    supplierName TEXT NOT NULL,
                    supplierGstin TEXT NOT NULL DEFAULT '',
                    supplierInvoiceNumber TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    taxablePaise INTEGER NOT NULL,
                    cgstPaise INTEGER NOT NULL,
                    sgstPaise INTEGER NOT NULL,
                    igstPaise INTEGER NOT NULL,
                    gstPaise INTEGER NOT NULL,
                    totalPaise INTEGER NOT NULL,
                    itcEligible INTEGER NOT NULL
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchases_createdAt ON purchases(createdAt)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS purchase_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    purchaseId INTEGER NOT NULL,
                    productId INTEGER NOT NULL,
                    productName TEXT NOT NULL,
                    partNumber TEXT NOT NULL,
                    quantity INTEGER NOT NULL,
                    unitPricePaise INTEGER NOT NULL,
                    gstRate REAL NOT NULL,
                    taxablePaise INTEGER NOT NULL,
                    cgstPaise INTEGER NOT NULL,
                    sgstPaise INTEGER NOT NULL,
                    igstPaise INTEGER NOT NULL,
                    gstPaise INTEGER NOT NULL,
                    totalPaise INTEGER NOT NULL
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_items_purchaseId ON purchase_items(purchaseId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_items_productId ON purchase_items(productId)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS purchase_returns (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    purchaseId INTEGER NOT NULL,
                    supplierName TEXT NOT NULL,
                    creditNoteNumber TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    taxablePaise INTEGER NOT NULL,
                    cgstPaise INTEGER NOT NULL,
                    sgstPaise INTEGER NOT NULL,
                    igstPaise INTEGER NOT NULL,
                    gstPaise INTEGER NOT NULL,
                    totalPaise INTEGER NOT NULL,
                    itcReversalEligible INTEGER NOT NULL
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_returns_purchaseId ON purchase_returns(purchaseId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_returns_createdAt ON purchase_returns(createdAt)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS purchase_return_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    returnId INTEGER NOT NULL,
                    purchaseItemId INTEGER NOT NULL,
                    productId INTEGER NOT NULL,
                    productName TEXT NOT NULL,
                    quantity INTEGER NOT NULL,
                    taxablePaise INTEGER NOT NULL,
                    cgstPaise INTEGER NOT NULL,
                    sgstPaise INTEGER NOT NULL,
                    igstPaise INTEGER NOT NULL,
                    gstPaise INTEGER NOT NULL,
                    totalPaise INTEGER NOT NULL
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_return_items_returnId ON purchase_return_items(returnId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_return_items_purchaseItemId ON purchase_return_items(purchaseItemId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_return_items_productId ON purchase_return_items(productId)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS suppliers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    gstin TEXT NOT NULL,
                    phone TEXT NOT NULL,
                    email TEXT NOT NULL,
                    address TEXT NOT NULL,
                    notes TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_suppliers_name ON suppliers(name)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS supplier_payments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    supplierId INTEGER NOT NULL,
                    supplierName TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    amountPaise INTEGER NOT NULL,
                    paymentMode TEXT NOT NULL,
                    reference TEXT NOT NULL,
                    notes TEXT NOT NULL
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_supplier_payments_supplierId ON supplier_payments(supplierId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_supplier_payments_createdAt ON supplier_payments(createdAt)")
                db.execSQL("""INSERT OR IGNORE INTO suppliers (name, gstin, phone, email, address, notes, createdAt)
                    SELECT supplierName, MAX(supplierGstin), '', '', '', 'Imported from existing purchase records',
                           MIN(createdAt) FROM purchases GROUP BY supplierName COLLATE NOCASE""")
                db.execSQL("""INSERT OR IGNORE INTO suppliers (name, gstin, phone, email, address, notes, createdAt)
                    SELECT supplierName, '', '', '', '', 'Imported from existing purchase return records',
                           MIN(createdAt) FROM purchase_returns GROUP BY supplierName COLLATE NOCASE""")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS supplier_payment_allocations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    paymentId INTEGER NOT NULL,
                    purchaseId INTEGER NOT NULL,
                    amountPaise INTEGER NOT NULL
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_supplier_payment_allocations_paymentId ON supplier_payment_allocations(paymentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_supplier_payment_allocations_purchaseId ON supplier_payment_allocations(purchaseId)")
            }
        }

        @Volatile private var INSTANCE: AutoStockDatabase? = null

        fun get(context: Context): AutoStockDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutoStockDatabase::class.java,
                    "autostock.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10).build().also { INSTANCE = it }
            }
    }
}
