package com.autostock.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "suppliers", indices = [Index(value = ["name"], unique = true)])
data class Supplier(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val gstin: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "supplier_payments", indices = [Index("supplierId"), Index("createdAt")])
data class SupplierPayment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val supplierId: Long,
    val supplierName: String,
    val createdAt: Long,
    val amountPaise: Long,
    val paymentMode: String,
    val reference: String,
    val notes: String
)


@Entity(tableName = "supplier_payment_allocations", indices = [Index("paymentId"), Index("purchaseId")])
data class SupplierPaymentAllocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paymentId: Long,
    val purchaseId: Long,
    val amountPaise: Long
)
