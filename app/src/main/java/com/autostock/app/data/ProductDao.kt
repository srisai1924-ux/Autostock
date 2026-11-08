package com.autostock.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Product>>

    @Query("SELECT COUNT(*) FROM products")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM products WHERE quantity <= lowStockAlert")
    fun observeLowStockCount(): Flow<Int>

    @Query("""
        SELECT * FROM products
        WHERE name LIKE '%' || :term || '%'
           OR oemNumber LIKE '%' || :term || '%'
           OR aftermarketNumbers LIKE '%' || :term || '%'
           OR brand LIKE '%' || :term || '%'
           OR vehicleCompatibility LIKE '%' || :term || '%'
           OR category LIKE '%' || :term || '%'
           OR barcode = :term
        ORDER BY name COLLATE NOCASE
    """)
    fun search(term: String): Flow<List<Product>>

    @Query("SELECT * FROM products")
    suspend fun getAllForDuplicateCheck(): List<Product>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(product: Product)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(products: List<Product>): List<Long>

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM sales WHERE invoiceNumber LIKE :prefix || '%'")
    suspend fun countInvoices(prefix: String): Int

    @Query("""
        SELECT * FROM sales
        WHERE invoiceNumber LIKE '%' || :term || '%'
           OR customerName LIKE '%' || :term || '%'
           OR customerGstin LIKE '%' || :term || '%'
           OR sellerGstin LIKE '%' || :term || '%'
           OR strftime('%Y-%m-%d', createdAt / 1000, 'unixepoch', 'localtime') LIKE '%' || :term || '%'
           OR strftime('%d-%m-%Y', createdAt / 1000, 'unixepoch', 'localtime') LIKE '%' || :term || '%'
        ORDER BY createdAt DESC, id DESC
    """)
    fun searchSales(term: String): Flow<List<Sale>>

    @Query("SELECT * FROM sale_items WHERE saleId = :saleId ORDER BY id")
    fun observeSaleItems(saleId: Long): Flow<List<SaleItem>>

    @Query("SELECT * FROM sales ORDER BY createdAt DESC, id DESC")
    fun observeAllSales(): Flow<List<Sale>>

    @Query("SELECT * FROM sale_items ORDER BY id")
    fun observeAllSaleItems(): Flow<List<SaleItem>>

    @Query("""
        SELECT si.productId AS productId, si.productName AS productName,
               SUM(si.quantity) AS quantitySold,
               SUM(si.lineSubtotalPaise) AS taxablePaise,
               SUM(si.gstPaise) AS gstPaise, SUM(si.lineTotalPaise) AS totalPaise
        FROM sale_items si INNER JOIN sales s ON s.id = si.saleId
        WHERE s.createdAt >= :startMillis AND s.createdAt < :endMillis
        GROUP BY si.productId, si.productName ORDER BY totalPaise DESC
    """)
    fun observeProductSales(startMillis: Long, endMillis: Long): Flow<List<ProductSalesSummary>>

    @Query("SELECT * FROM purchases ORDER BY createdAt DESC, id DESC")
    fun observeAllPurchases(): Flow<List<Purchase>>

    @Query("""
        SELECT supplierName AS supplierName, COUNT(*) AS invoiceCount,
               SUM(taxablePaise) AS taxablePaise, SUM(gstPaise) AS gstPaise,
               SUM(CASE WHEN itcEligible = 1 THEN gstPaise ELSE 0 END) AS eligibleItcPaise,
               SUM(totalPaise) AS totalPaise
        FROM purchases WHERE createdAt >= :startMillis AND createdAt < :endMillis
        GROUP BY supplierName COLLATE NOCASE ORDER BY totalPaise DESC
    """)
    fun observeSupplierPurchases(startMillis: Long, endMillis: Long): Flow<List<SupplierPurchaseSummary>>

    @Query("SELECT * FROM suppliers ORDER BY name COLLATE NOCASE")
    fun observeSuppliers(): Flow<List<Supplier>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSupplier(supplier: Supplier): Long

    @Query("UPDATE suppliers SET gstin = :gstin, phone = :phone, email = :email, address = :address, notes = :notes WHERE id = :id")
    suspend fun updateSupplierDetails(id: Long, gstin: String, phone: String, email: String, address: String, notes: String)

    @Query("SELECT * FROM supplier_payments ORDER BY createdAt DESC, id DESC")
    fun observeSupplierPayments(): Flow<List<SupplierPayment>>

    @Insert
    suspend fun insertSupplierPayment(payment: SupplierPayment): Long

    @Query("SELECT * FROM supplier_payment_allocations ORDER BY id")
    fun observeSupplierPaymentAllocations(): Flow<List<SupplierPaymentAllocation>>

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM supplier_payment_allocations WHERE purchaseId = :purchaseId")
    suspend fun allocatedToPurchase(purchaseId: Long): Long

    @Insert
    suspend fun insertSupplierPaymentAllocations(allocations: List<SupplierPaymentAllocation>)

    @Transaction
    suspend fun completeSupplierPayment(
        payment: SupplierPayment,
        allocations: List<SupplierPaymentAllocation>,
        invoiceTotals: Map<Long, Long>,
        invoiceSuppliers: Map<Long, String>,
        returnTotals: Map<Long, Long>
    ): Long {
        require(payment.amountPaise > 0) { "Payment amount must be greater than zero." }
        require(allocations.isNotEmpty()) { "Allocate the payment to at least one supplier invoice." }
        require(allocations.all { it.amountPaise > 0 }) { "Allocation amounts must be greater than zero." }
        require(allocations.map { it.purchaseId }.distinct().size == allocations.size) {
            "Use one allocation amount per supplier invoice."
        }
        require(allocations.sumOf { it.amountPaise } == payment.amountPaise) {
            "The invoice allocations must add up exactly to the payment amount."
        }
        allocations.forEach { allocation ->
            val invoiceTotal = invoiceTotals[allocation.purchaseId]
                ?: throw IllegalArgumentException("Selected supplier invoice was not found.")
            require(invoiceSuppliers[allocation.purchaseId].equals(payment.supplierName, true)) {
                "Payment can only be allocated to this supplier's invoices."
            }
            val alreadyAllocated = allocatedToPurchase(allocation.purchaseId)
            val returnValue = returnTotals[allocation.purchaseId] ?: 0L
            require(allocation.amountPaise + alreadyAllocated <= (invoiceTotal - returnValue).coerceAtLeast(0L)) {
                "Allocation exceeds the outstanding balance of a selected invoice."
            }
        }
        val paymentId = insertSupplierPayment(payment)
        insertSupplierPaymentAllocations(allocations.map { it.copy(paymentId = paymentId) })
        return paymentId
    }

    @Query("SELECT COALESCE(SUM(totalPaise), 0) FROM purchases WHERE supplierName = :supplierName COLLATE NOCASE")
    suspend fun supplierPurchaseTotal(supplierName: String): Long

    @Query("SELECT COALESCE(SUM(totalPaise), 0) FROM purchase_returns WHERE supplierName = :supplierName COLLATE NOCASE")
    suspend fun supplierReturnTotal(supplierName: String): Long

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM supplier_payments WHERE supplierId = :supplierId")
    suspend fun supplierPaymentTotal(supplierId: Long): Long

    @Insert
    suspend fun insertPurchase(purchase: Purchase): Long

    @Insert
    suspend fun insertPurchaseItems(items: List<PurchaseItem>)

    @Query("SELECT * FROM purchase_items ORDER BY id")
    fun observeAllPurchaseItems(): Flow<List<PurchaseItem>>

    @Query("SELECT * FROM purchase_returns ORDER BY createdAt DESC, id DESC")
    fun observeAllPurchaseReturns(): Flow<List<PurchaseReturn>>

    @Query("SELECT * FROM purchase_return_items ORDER BY id")
    fun observeAllPurchaseReturnItems(): Flow<List<PurchaseReturnItem>>

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM purchase_return_items WHERE purchaseItemId = :purchaseItemId")
    suspend fun returnedQuantity(purchaseItemId: Long): Int

    @Query("SELECT * FROM purchase_items WHERE id = :id LIMIT 1")
    suspend fun getPurchaseItem(id: Long): PurchaseItem?

    @Insert
    suspend fun insertPurchaseReturn(note: PurchaseReturn): Long

    @Insert
    suspend fun insertPurchaseReturnItems(items: List<PurchaseReturnItem>)

    @Query("UPDATE products SET quantity = quantity + :qty, purchasePricePaise = :unitPricePaise WHERE id = :productId")
    suspend fun receivePurchaseStock(productId: Long, qty: Int, unitPricePaise: Long): Int

    @Query("UPDATE products SET quantity = quantity - :qty WHERE id = :productId AND quantity >= :qty")
    suspend fun reversePurchaseStock(productId: Long, qty: Int): Int

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun getProduct(id: Long): Product?

    @Transaction
    suspend fun completePurchase(purchase: Purchase, items: List<PurchaseItem>): Long {
        require(items.isNotEmpty()) { "Select at least one catalogue product." }
        items.forEach { line ->
            require(line.quantity > 0) { "Purchase quantity must be greater than zero." }
            require(line.unitPricePaise >= 0) { "Purchase price cannot be negative." }
            require(getProduct(line.productId) != null) { "${line.productName} is no longer in the catalogue." }
        }
        val purchaseId = insertPurchase(purchase)
        items.forEach { line ->
            check(receivePurchaseStock(line.productId, line.quantity, line.unitPricePaise) == 1) {
                "Could not update stock for ${line.productName}."
            }
        }
        insertPurchaseItems(items.map { it.copy(purchaseId = purchaseId) })
        return purchaseId
    }

    @Transaction
    suspend fun completePurchaseReturn(note: PurchaseReturn, items: List<PurchaseReturnItem>): Long {
        require(items.isNotEmpty()) { "Select at least one purchase item to return." }
        items.forEach { line ->
            require(line.quantity > 0) { "Return quantity must be greater than zero." }
            val original = getPurchaseItem(line.purchaseItemId)
                ?: throw IllegalStateException("Original purchase item not found.")
            require(original.purchaseId == note.purchaseId) { "A return item must belong to the selected supplier invoice." }
            require(original.productId == line.productId) { "Return product does not match the original purchase item." }
            val alreadyReturned = returnedQuantity(line.purchaseItemId)
            require(line.quantity + alreadyReturned <= original.quantity) {
                "Return quantity exceeds the unreturned quantity for ${line.productName}."
            }
            val product = getProduct(line.productId)
                ?: throw IllegalStateException("Product ${line.productName} no longer exists.")
            require(product.quantity >= line.quantity) {
                "Cannot return ${line.quantity} units of ${line.productName}; only ${product.quantity} are currently in stock."
            }
        }
        val returnId = insertPurchaseReturn(note)
        items.forEach { line ->
            check(reversePurchaseStock(line.productId, line.quantity) == 1) {
                "Could not reverse stock for ${line.productName}."
            }
        }
        insertPurchaseReturnItems(items.map { it.copy(returnId = returnId) })
        return returnId
    }

    @Insert
    suspend fun insertSale(sale: Sale): Long

    @Insert
    suspend fun insertSaleItems(items: List<SaleItem>)

    @Query("UPDATE products SET quantity = quantity - :qty WHERE id = :id AND quantity >= :qty")
    suspend fun deductStock(id: Long, qty: Int): Int

    @Transaction
    suspend fun completeSale(sale: Sale, items: List<SaleItem>): Long {
        require(items.isNotEmpty()) { "Add at least one item to the bill." }
        items.forEach { line ->
            require(line.quantity > 0) { "Quantity must be greater than zero." }
            val product = getProduct(line.productId)
                ?: throw IllegalStateException("${line.productName} is no longer in the catalogue.")
            if (product.quantity < line.quantity) {
                throw IllegalStateException("Insufficient stock for ${line.productName}. Available: ${product.quantity}.")
            }
        }
        val saleId = insertSale(sale)
        items.forEach { line ->
            check(deductStock(line.productId, line.quantity) == 1) {
                "Stock changed while billing ${line.productName}. Please retry."
            }
        }
        insertSaleItems(items.map { it.copy(saleId = saleId) })
        return saleId
    }
}
