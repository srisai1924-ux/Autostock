package com.autostock.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "products",
    indices = [Index(value = ["oemNumber"]), Index(value = ["barcode"])]
)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val oemNumber: String = "",
    // Multiple aftermarket numbers are stored as one newline-separated list in this MVP.
    val aftermarketNumbers: String = "",
    val brand: String = "",
    // Fitment entries are stored as newline-separated vehicle descriptions.
    val vehicleCompatibility: String = "",
    val category: String = "",
    @ColumnInfo(defaultValue = "''") val hsnCode: String = "",
    val barcode: String = "",
    // Prices are stored as paise to avoid floating-point currency errors.
    val purchasePricePaise: Long = 0,
    val sellingPricePaise: Long = 0,
    val gstRate: Double = 18.0,
    val quantity: Int = 0,
    val lowStockAlert: Int = 2,
    val vehicleMake: String = "",
    val vehicleModel: String = "",
    val vehicleVariant: String = "",
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val compatibilityNotes: String = ""
)
