package com.autostock.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.ColumnInfo

@Entity(tableName = "sales", indices = [Index(value = ["invoiceNumber"], unique = true)])
data class Sale(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "''") val invoiceNumber: String,
    @ColumnInfo(defaultValue = "''") val sellerName: String,
    @ColumnInfo(defaultValue = "''") val sellerAddress: String,
    val customerName: String,
    @ColumnInfo(defaultValue = "''") val customerAddress: String,
    @ColumnInfo(defaultValue = "''") val sellerGstin: String,
    @ColumnInfo(defaultValue = "''") val customerGstin: String,
    @ColumnInfo(defaultValue = "''") val sellerStateCode: String,
    @ColumnInfo(defaultValue = "''") val customerStateCode: String,
    @ColumnInfo(defaultValue = "'LEGACY'") val taxType: String,
    val createdAt: Long,
    val subtotalPaise: Long,
    @ColumnInfo(defaultValue = "0") val cgstPaise: Long,
    @ColumnInfo(defaultValue = "0") val sgstPaise: Long,
    @ColumnInfo(defaultValue = "0") val igstPaise: Long,
    val gstPaise: Long,
    val totalPaise: Long
)

@Entity(tableName = "sale_items", indices = [Index("saleId"), Index("productId")])
data class SaleItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val productId: Long,
    val productName: String,
    val barcode: String,
    @ColumnInfo(defaultValue = "''") val hsnCode: String,
    val quantity: Int,
    val unitPricePaise: Long,
    val gstRate: Double,
    val lineSubtotalPaise: Long,
    @ColumnInfo(defaultValue = "0") val cgstPaise: Long,
    @ColumnInfo(defaultValue = "0") val sgstPaise: Long,
    @ColumnInfo(defaultValue = "0") val igstPaise: Long,
    val gstPaise: Long,
    val lineTotalPaise: Long
)


data class ProductSalesSummary(
    val productId: Long,
    val productName: String,
    val quantitySold: Int,
    val taxablePaise: Long,
    val gstPaise: Long,
    val totalPaise: Long
)
