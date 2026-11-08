package com.autostock.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Index

@Entity(tableName = "purchases", indices = [Index("createdAt")])
data class Purchase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val supplierName: String,
    @ColumnInfo(defaultValue = "''") val supplierGstin: String,
    val supplierInvoiceNumber: String,
    val createdAt: Long,
    val taxablePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val gstPaise: Long,
    val totalPaise: Long,
    val itcEligible: Boolean
)

data class SupplierPurchaseSummary(
    val supplierName: String,
    val invoiceCount: Int,
    val taxablePaise: Long,
    val gstPaise: Long,
    val eligibleItcPaise: Long,
    val totalPaise: Long
)


@Entity(tableName = "purchase_items", indices = [Index("purchaseId"), Index("productId")])
data class PurchaseItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseId: Long,
    val productId: Long,
    val productName: String,
    val partNumber: String,
    val quantity: Int,
    val unitPricePaise: Long,
    val gstRate: Double,
    val taxablePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val gstPaise: Long,
    val totalPaise: Long
)


@Entity(tableName = "purchase_returns", indices = [Index("purchaseId"), Index("createdAt")])
data class PurchaseReturn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseId: Long,
    val supplierName: String,
    val creditNoteNumber: String,
    val createdAt: Long,
    val taxablePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val gstPaise: Long,
    val totalPaise: Long,
    val itcReversalEligible: Boolean
)

@Entity(tableName = "purchase_return_items", indices = [Index("returnId"), Index("purchaseItemId"), Index("productId")])
data class PurchaseReturnItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val returnId: Long,
    val purchaseItemId: Long,
    val productId: Long,
    val productName: String,
    val quantity: Int,
    val taxablePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val gstPaise: Long,
    val totalPaise: Long
)
