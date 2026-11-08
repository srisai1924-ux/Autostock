package com.autostock.app

import android.os.Bundle
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autostock.app.data.AutoStockDatabase
import com.autostock.app.data.Product
import com.autostock.app.data.ProductDao
import com.autostock.app.io.XlsxCatalogue
import com.autostock.app.io.ImportPreview
import com.autostock.app.interchange.InterchangeEngine
import android.widget.Toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong
import com.autostock.app.data.Sale
import com.autostock.app.data.SaleItem
import com.autostock.app.data.ProductSalesSummary
import com.autostock.app.data.Purchase
import com.autostock.app.data.PurchaseItem
import com.autostock.app.data.PurchaseReturnItem
import com.autostock.app.data.PurchaseReturn
import com.autostock.app.data.SupplierPurchaseSummary
import com.autostock.app.data.Supplier
import com.autostock.app.data.SupplierPayment
import com.autostock.app.data.SupplierPaymentAllocation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AutoStockDatabase.get(this).productDao()
        setContent { MaterialTheme { AutoStockApp(dao) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoStockApp(dao: ProductDao) {
    var tab by remember { mutableIntStateOf(0) }
    var editingProduct by remember { mutableStateOf<Product?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showPurchaseEntry by remember { mutableStateOf(false) }
    var showPurchaseReturn by remember { mutableStateOf(false) }
    var showSupplierEditor by remember { mutableStateOf(false) }
    var paymentSupplier by remember { mutableStateOf<Supplier?>(null) }
    var statementRequest by remember { mutableStateOf<SupplierStatementRequest?>(null) }
    var deleteTarget by remember { mutableStateOf<Product?>(null) }
    var search by remember { mutableStateOf("") }
    var interchangeQuery by remember { mutableStateOf("") }
    var invoiceSearch by remember { mutableStateOf("") }
    var selectedInvoice by remember { mutableStateOf<Sale?>(null) }
    var reportPeriod by remember { mutableStateOf(ReportPeriod.DAILY) }
    val allSales by dao.observeAllSales().collectAsStateWithLifecycle(initialValue = emptyList())
    val allSaleItems by dao.observeAllSaleItems().collectAsStateWithLifecycle(initialValue = emptyList())
    val allPurchases by dao.observeAllPurchases().collectAsStateWithLifecycle(initialValue = emptyList())
    val allPurchaseItems by dao.observeAllPurchaseItems().collectAsStateWithLifecycle(initialValue = emptyList())
    val allPurchaseReturns by dao.observeAllPurchaseReturns().collectAsStateWithLifecycle(initialValue = emptyList())
    val allPurchaseReturnItems by dao.observeAllPurchaseReturnItems().collectAsStateWithLifecycle(initialValue = emptyList())
    val suppliers by dao.observeSuppliers().collectAsStateWithLifecycle(initialValue = emptyList())
    val supplierPayments by dao.observeSupplierPayments().collectAsStateWithLifecycle(initialValue = emptyList())
    val supplierPaymentAllocations by dao.observeSupplierPaymentAllocations().collectAsStateWithLifecycle(initialValue = emptyList())
    val supplierPurchaseSummaries by dao.observeSupplierPurchases(
        YearMonth.now().atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        YearMonth.now().plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    ).collectAsStateWithLifecycle(initialValue = emptyList())
    val reportMonth = remember { YearMonth.now() }
    val reportMonthStart = remember(reportMonth) { reportMonth.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val reportNextMonthStart = remember(reportMonth) { reportMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val monthProductSales by dao.observeProductSales(reportMonthStart, reportNextMonthStart)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val sales by dao.searchSales(invoiceSearch).collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedInvoiceItemsFlow = remember(selectedInvoice?.id) {
        selectedInvoice?.let { dao.observeSaleItems(it.id) } ?: flowOf(emptyList())
    }
    val selectedInvoiceItems by selectedInvoiceItemsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var cart by remember { mutableStateOf<List<CartLine>>(emptyList()) }
    var customerName by remember { mutableStateOf("") }
    var billingMessage by remember { mutableStateOf<String?>(null) }
    val products by (if (search.isBlank()) dao.observeAll() else dao.search(search))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val allCatalogue by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val count by dao.observeCount().collectAsStateWithLifecycle(initialValue = 0)
    val lowStock by dao.observeLowStockCount().collectAsStateWithLifecycle(initialValue = 0)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences("autostock_gst", android.content.Context.MODE_PRIVATE) }
    var sellerName by remember { mutableStateOf(preferences.getString("seller_name", "").orEmpty()) }
    var sellerAddress by remember { mutableStateOf(preferences.getString("seller_address", "").orEmpty()) }
    var sellerGstin by remember { mutableStateOf(preferences.getString("seller_gstin", "").orEmpty()) }
    var sellerStateCode by remember { mutableStateOf(preferences.getString("seller_state", "").orEmpty()) }
    var customerAddress by remember { mutableStateOf("") }
    var customerGstin by remember { mutableStateOf("") }
    var customerStateCode by remember { mutableStateOf("") }
    var lastInvoice by remember { mutableStateOf<InvoiceSnapshot?>(null) }
    var importPreview by remember { mutableStateOf<ImportPreview?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var errorReport by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                importPreview = XlsxCatalogue.read(
                    context,
                    uri,
                    dao.getAllForDuplicateCheck()
                )
            } catch (e: Exception) {
                importMessage = e.message ?: "Could not read workbook."
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) scope.launch {
            try {
                XlsxCatalogue.write(context, uri, dao.getAllForDuplicateCheck())
                importMessage = "Catalogue exported successfully."
            } catch (e: Exception) {
                importMessage = e.message ?: "Export failed."
            }
        }
    }
    val reportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && errorReport != null) {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(errorReport!!.toByteArray(Charsets.UTF_8))
            }
            importMessage = "Import error report saved."
        }
    }

    val supplierStatementPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val request = statementRequest
        if (uri != null && request != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    writeSupplierStatementPdf(
                        request,
                        allPurchases,
                        allPurchaseReturns,
                        supplierPayments,
                        output
                    )
                }
                billingMessage = "Supplier statement PDF saved."
            } catch (e: Exception) {
                billingMessage = e.message ?: "Could not create supplier statement."
            }
        }
        statementRequest = null
    }

    val supplierAccountingPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            try {
                val reportDate = LocalDate.now()
                val ageingRows = supplierAgeingInvoices(allPurchases, allPurchaseReturns, supplierPaymentAllocations, reportDate)
                val accountingRows = buildSupplierAccountingMetrics(
                    suppliers, allPurchases, allPurchaseItems, allPurchaseReturns,
                    allPurchaseReturnItems, supplierPayments, allSaleItems, ageingRows
                )
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    writeSupplierAccountingDashboardPdf(
                        accountingRows = accountingRows,
                        ageingInvoices = ageingRows,
                        reportDate = reportDate,
                        output = output
                    )
                }
                billingMessage = "Supplier accounting dashboard PDF exported."
            } catch (e: Exception) {
                billingMessage = e.message ?: "Could not export supplier accounting dashboard."
            }
        }
    }

    val supplierAgeingPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            try {
                val reportDate = LocalDate.now()
                val rows = supplierAgeingInvoices(allPurchases, allPurchaseReturns, supplierPaymentAllocations, reportDate)
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    writeSupplierAgeingPdf(rows, reportDate, output)
                }
                billingMessage = "Supplier invoice ageing PDF exported."
            } catch (e: Exception) {
                billingMessage = e.message ?: "Could not export ageing PDF."
            }
        }
    }

    val supplierAgeingCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                val rows = supplierAgeingInvoices(allPurchases, allPurchaseReturns, supplierPaymentAllocations)
                val csv = buildSupplierAgeingCsv(rows)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                billingMessage = "Supplier invoice ageing report exported."
            } catch (e: Exception) {
                billingMessage = e.message ?: "Could not export ageing report."
            }
        }
    }

    val supplierLedgerCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                val csv = buildSupplierLedgerCsv(suppliers, allPurchases, allPurchaseReturns, supplierPayments)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                billingMessage = "Supplier ledger report exported."
            } catch (e: Exception) {
                billingMessage = e.message ?: "Could not export supplier ledger."
            }
        }
    }

    val reportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                val csv = buildSalesReportCsv(allSales, reportPeriod)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                billingMessage = "Sales report exported."
            } catch (e: Exception) {
                billingMessage = e.message ?: "Could not export sales report."
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val invoice = lastInvoice
        if (uri != null && invoice != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output -> writeInvoicePdf(invoice, output) }
                billingMessage = "PDF invoice ${invoice.invoiceNumber} saved."
            } catch (e: Exception) {
                billingMessage = e.message ?: "Could not create PDF invoice."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("AutoStock", fontWeight = FontWeight.Bold)
                    Text("Automotive inventory & part catalogue", style = MaterialTheme.typography.labelSmall)
                }
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                when (tab) {
                    8 -> showPurchaseEntry = true
                    9 -> showSupplierEditor = true
                    else -> showAdd = true
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = when (tab) { 8 -> "Record purchase"; 9 -> "Add supplier"; else -> "Add spare part" })
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Dashboard") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Inventory2, null) }, label = { Text("Catalogue") })
                NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("Interchange") })
                NavigationBarItem(tab == 3, { tab = 3 }, icon = { Icon(Icons.Default.Inventory2, null) }, label = { Text("By Vehicle") })
                NavigationBarItem(tab == 4, { tab = 4 }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("Scan") })
                NavigationBarItem(tab == 5, { tab = 5 }, icon = { Icon(Icons.Default.Inventory2, null) }, label = { Text("Billing") })
                NavigationBarItem(tab == 6, { tab = 6 }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("Invoices") })
                NavigationBarItem(tab == 7, { tab = 7 }, icon = { Icon(Icons.Default.Inventory2, null) }, label = { Text("Sales") })
                NavigationBarItem(tab == 8, { tab = 8 }, icon = { Icon(Icons.Default.Inventory2, null) }, label = { Text("Purchases") })
                NavigationBarItem(tab == 9, { tab = 9 }, icon = { Icon(Icons.Default.Inventory2, null) }, label = { Text("Suppliers") })
            }
        }
    ) { padding ->
        if (tab == 0) {
            DashboardScreen(count, lowStock, products.take(5), Modifier.padding(padding))
        } else if (tab == 1) {
            InventoryScreen(
                products = products, search = search, onSearch = { search = it },
                onEdit = { editingProduct = it },
                onDelete = { deleteTarget = it },
                onImport = { importLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel")) },
                onExport = { exportLauncher.launch("AutoStock_Catalogue.xlsx") },
                modifier = Modifier.padding(padding)
            )
        } else if (tab == 2) {
            InterchangeScreen(
                query = interchangeQuery,
                onQueryChange = { interchangeQuery = it },
                catalogue = allCatalogue,
                modifier = Modifier.padding(padding)
            )
        } else if (tab == 3) {
            VehicleWiseSearchScreen(
                catalogue = allCatalogue,
                modifier = Modifier.padding(padding)
            )
        } else if (tab == 4) {
            BarcodeScannerScreen(
                catalogue = allCatalogue,
                onAddToBill = { product ->
                    val current = cart.firstOrNull { it.product.id == product.id }
                    cart = if (current == null) cart + CartLine(product, 1)
                    else cart.map { if (it.product.id == product.id) it.copy(quantity = it.quantity + 1) else it }
                    tab = 5
                },
                modifier = Modifier.padding(padding)
            )
        } else {
            BillingScreen(
                cart = cart,
                customerName = customerName,
                onCustomerChange = { customerName = it },
                sellerName = sellerName,
                onSellerNameChange = { sellerName = it; preferences.edit().putString("seller_name", it).apply() },
                sellerAddress = sellerAddress,
                onSellerAddressChange = { sellerAddress = it; preferences.edit().putString("seller_address", it).apply() },
                customerAddress = customerAddress,
                onCustomerAddressChange = { customerAddress = it },
                sellerGstin = sellerGstin,
                onSellerGstinChange = { sellerGstin = it; preferences.edit().putString("seller_gstin", it).apply() },
                sellerStateCode = sellerStateCode,
                onSellerStateChange = { value -> sellerStateCode = value.filter(Char::isDigit).take(2); preferences.edit().putString("seller_state", sellerStateCode).apply() },
                customerGstin = customerGstin,
                onCustomerGstinChange = { customerGstin = it.uppercase(Locale.ROOT).take(15) },
                customerStateCode = customerStateCode,
                onCustomerStateChange = { customerStateCode = it.filter(Char::isDigit).take(2) },
                onExportPdf = { lastInvoice?.let { pdfLauncher.launch("${it.invoiceNumber.replace("/", "-")}.pdf") } },
                onQuantityChange = { productId, qty ->
                    cart = cart.mapNotNull { line ->
                        if (line.product.id != productId) line
                        else if (qty <= 0) null else line.copy(quantity = qty)
                    }
                },
                onRemove = { id -> cart = cart.filterNot { it.product.id == id } },
                onCheckout = {
                    scope.launch {
                        try {
                            require(sellerName.isNotBlank()) { "Enter the shop / supplier legal name." }
                            require(sellerAddress.isNotBlank()) { "Enter the shop / supplier address." }
                            require(sellerStateCode.length == 2) { "Enter the shop's 2-digit GST state code." }
                            require(customerStateCode.length == 2) { "Enter the customer's place-of-supply state code." }
                            require(customerGstin.isBlank() || customerGstin.length == 15) { "Customer GSTIN must contain 15 characters." }
                            require(sellerGstin.matches(Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$"))) { "A valid 15-character supplier GSTIN is required for a GST tax invoice." }
                            require(customerGstin.isBlank() || customerGstin.matches(Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$"))) { "Enter a valid-format 15-character customer GSTIN." }
                            require(sellerGstin.isBlank() || sellerGstin.take(2) == sellerStateCode) { "Shop GSTIN state code must match the shop state code." }
                            require(customerGstin.isBlank() || customerGstin.take(2) == customerStateCode) { "Customer GSTIN state code must match place of supply." }
                            require(cart.all { it.product.hsnCode.matches(Regex("[0-9]{4,8}")) }) { "Enter a 4–8 digit HSN/SAC code for every billed product before checkout." }
                            val sameState = sellerStateCode == customerStateCode
                            val fyStart = if (java.time.LocalDate.now().monthValue >= 4) java.time.LocalDate.now().year else java.time.LocalDate.now().year - 1
                            val fy = "$fyStart-${(fyStart + 1).toString().takeLast(2)}"
                            val invoicePrefix = "AS/$fy/"
                            val nextNumber = dao.countInvoices(invoicePrefix) + 1
                            val invoiceNo = invoicePrefix + nextNumber.toString().padStart(5, '0')
                            val subtotal = cart.sumOf { it.product.sellingPricePaise * it.quantity }
                            val lineDrafts = cart.map { line ->
                                val sub = line.product.sellingPricePaise * line.quantity
                                val tax = gstFor(sub, line.product.gstRate)
                                val cgst = if (sameState) tax / 2 else 0L
                                val sgst = if (sameState) tax - cgst else 0L
                                val igst = if (sameState) 0L else tax
                                Triple(line, sub, listOf(cgst, sgst, igst, tax))
                            }
                            val cgstTotal = lineDrafts.sumOf { it.third[0] }
                            val sgstTotal = lineDrafts.sumOf { it.third[1] }
                            val igstTotal = lineDrafts.sumOf { it.third[2] }
                            val gstTotal = lineDrafts.sumOf { it.third[3] }
                            val sale = Sale(
                                invoiceNumber = invoiceNo,
                                sellerName = sellerName.trim(),
                                sellerAddress = sellerAddress.trim(),
                                customerName = customerName.trim(),
                                customerAddress = customerAddress.trim(),
                                sellerGstin = sellerGstin,
                                customerGstin = customerGstin,
                                sellerStateCode = sellerStateCode,
                                customerStateCode = customerStateCode,
                                taxType = if (sameState) "INTRA_STATE" else "INTER_STATE",
                                createdAt = System.currentTimeMillis(),
                                subtotalPaise = subtotal,
                                cgstPaise = cgstTotal,
                                sgstPaise = sgstTotal,
                                igstPaise = igstTotal,
                                gstPaise = gstTotal,
                                totalPaise = subtotal + gstTotal
                            )
                            val lines = lineDrafts.map { (line, sub, taxParts) ->
                                SaleItem(
                                    saleId = 0, productId = line.product.id, productName = line.product.name,
                                    barcode = line.product.barcode, hsnCode = line.product.hsnCode,
                                    quantity = line.quantity, unitPricePaise = line.product.sellingPricePaise,
                                    gstRate = line.product.gstRate, lineSubtotalPaise = sub,
                                    cgstPaise = taxParts[0], sgstPaise = taxParts[1],
                                    igstPaise = taxParts[2], gstPaise = taxParts[3],
                                    lineTotalPaise = sub + taxParts[3]
                                )
                            }
                            dao.completeSale(sale, lines)
                            lastInvoice = InvoiceSnapshot(
                                invoiceNumber = invoiceNo, createdAt = sale.createdAt,
                                sellerName = sellerName, sellerAddress = sellerAddress,
                                customerAddress = customerAddress,
                                sellerGstin = sellerGstin, sellerStateCode = sellerStateCode,
                                customerName = customerName, customerGstin = customerGstin,
                                customerStateCode = customerStateCode, taxType = sale.taxType,
                                items = lines, subtotalPaise = subtotal, cgstPaise = cgstTotal,
                                sgstPaise = sgstTotal, igstPaise = igstTotal, totalPaise = subtotal + gstTotal
                            )
                            billingMessage = "GST invoice $invoiceNo saved. Stock deducted successfully. Use Save PDF invoice to export it."
                            cart = emptyList()
                            customerName = ""
                            customerAddress = ""
                            customerGstin = ""
                            customerStateCode = ""
                        } catch (e: Exception) {
                            billingMessage = e.message ?: "Could not complete sale."
                        }
                    }
                },
                message = billingMessage,
                onDismissMessage = { billingMessage = null },
                modifier = Modifier.padding(padding)
            )
        } else if (tab == 6) {
            InvoiceHistoryScreen(
                invoices = sales,
                search = invoiceSearch,
                onSearchChange = { invoiceSearch = it },
                selectedInvoice = selectedInvoice,
                items = selectedInvoiceItems,
                onOpen = { selectedInvoice = it },
                onBack = { selectedInvoice = null },
                onExportPdf = { sale, lines ->
                    lastInvoice = sale.toInvoiceSnapshot(lines)
                    pdfLauncher.launch("${sale.invoiceNumber.replace("/", "-")}.pdf")
                },
                modifier = Modifier.padding(padding)
            )
        } else if (tab == 7) {
            SalesReportsScreen(
                sales = allSales,
                productSales = monthProductSales,
                period = reportPeriod,
                onPeriodChange = { reportPeriod = it },
                onExport = { reportCsvLauncher.launch("AutoStock_${reportPeriod.name.lowercase(Locale.ROOT)}_sales.csv") },
                modifier = Modifier.padding(padding)
            )
        } else if (tab == 8) {
            PurchaseDashboardScreen(
                purchases = allPurchases,
                supplierSummaries = supplierPurchaseSummaries,
                sales = allSales,
                returns = allPurchaseReturns,
                onAddReturn = { showPurchaseReturn = true },
                modifier = Modifier.padding(padding)
            )
        } else {
            SupplierManagementScreen(
                suppliers = suppliers,
                purchases = allPurchases,
                purchaseItems = allPurchaseItems,
                returns = allPurchaseReturns,
                returnItems = allPurchaseReturnItems,
                payments = supplierPayments,
                sales = allSales,
                saleItems = allSaleItems,
                allocations = supplierPaymentAllocations,
                onAddPayment = { paymentSupplier = it },
                onGenerateStatement = { request ->
                    statementRequest = request
                    supplierStatementPdfLauncher.launch(
                        "Supplier_Statement_${request.supplier.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}_${request.fromDate}_${request.toDate}.pdf"
                    )
                },
                onExport = { supplierLedgerCsvLauncher.launch("AutoStock_supplier_ledger.csv") },
                onExportAgeing = { supplierAgeingCsvLauncher.launch("AutoStock_supplier_invoice_ageing.csv") },
                onExportAgeingPdf = { supplierAgeingPdfLauncher.launch("AutoStock_supplier_invoice_ageing.pdf") },
                onExportAccountingPdf = { supplierAccountingPdfLauncher.launch("AutoStock_supplier_accounting_dashboard.pdf") },
                modifier = Modifier.padding(padding)
            )
        }
    }

    if (showPurchaseEntry) {
        PurchaseEntryDialog(
            products = allCatalogue,
            onDismiss = { showPurchaseEntry = false },
            onSave = { purchase, items ->
                scope.launch {
                    try {
                        dao.completePurchase(purchase, items)
                        Toast.makeText(context, "Supplier invoice saved and stock increased.", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Could not save purchase", Toast.LENGTH_LONG).show()
                    }
                }
                showPurchaseEntry = false
            }
        )
    }

    if (showPurchaseReturn) {
        PurchaseReturnDialog(
            purchases = allPurchases,
            purchaseItems = allPurchaseItems,
            returnedItems = allPurchaseReturnItems,
            onDismiss = { showPurchaseReturn = false },
            onSave = { note, items ->
                scope.launch {
                    try {
                        dao.completePurchaseReturn(note, items)
                        Toast.makeText(context, "Debit note saved and stock reversed.", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Could not save debit note", Toast.LENGTH_LONG).show()
                    }
                }
                showPurchaseReturn = false
            }
        )
    }

    if (showSupplierEditor) {
        SupplierEditorDialog(
            onDismiss = { showSupplierEditor = false },
            onSave = { supplier ->
                scope.launch {
                    try {
                        dao.insertSupplier(supplier)
                        Toast.makeText(context, "Supplier added.", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Supplier name already exists or could not be saved.", Toast.LENGTH_LONG).show()
                    }
                }
                showSupplierEditor = false
            }
        )
    }

    paymentSupplier?.let { supplier ->
        SupplierPaymentDialog(
            supplier = supplier,
            invoices = allPurchases.filter { it.supplierName.equals(supplier.name, true) },
            returns = allPurchaseReturns.filter { it.supplierName.equals(supplier.name, true) },
            existingAllocations = supplierPaymentAllocations,
            onDismiss = { paymentSupplier = null },
            onSave = { amount, mode, reference, notes, invoiceAllocations ->
                scope.launch {
                    try {
                        val payment = SupplierPayment(
                            supplierId = supplier.id,
                            supplierName = supplier.name,
                            createdAt = System.currentTimeMillis(),
                            amountPaise = amount,
                            paymentMode = mode,
                            reference = reference,
                            notes = notes
                        )
                        val invoiceMap = allPurchases.associate { it.id to it.totalPaise }
                        val supplierMap = allPurchases.associate { it.id to it.supplierName }
                        val returnMap = allPurchaseReturns.groupBy { it.purchaseId }
                            .mapValues { (_, rows) -> rows.sumOf { it.totalPaise } }
                        dao.completeSupplierPayment(
                            payment,
                            invoiceAllocations.map { (purchaseId, paise) ->
                                SupplierPaymentAllocation(purchaseId = purchaseId, amountPaise = paise,
                                    paymentId = 0L)
                            },
                            invoiceMap, supplierMap, returnMap
                        )
                        Toast.makeText(context, "Payment saved and allocated to invoices.", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Could not record payment.", Toast.LENGTH_LONG).show()
                    }
                }
                paymentSupplier = null
            }
        )
    }

    if (showAdd) {
        ProductEditorDialog(
            existing = null,
            onDismiss = { showAdd = false },
            onCheckDuplicates = { candidate -> duplicateWarnings(candidate, dao.getAllForDuplicateCheck()) },
            onSave = { product -> scope.launch { dao.save(product) }; showAdd = false }
        )
    }

    editingProduct?.let { product ->
        ProductEditorDialog(
            existing = product,
            onDismiss = { editingProduct = null },
            onCheckDuplicates = { candidate -> duplicateWarnings(candidate, dao.getAllForDuplicateCheck()) },
            onSave = { updated -> scope.launch { dao.save(updated) }; editingProduct = null }
        )
    }

    deleteTarget?.let { product ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this part?") },
            text = { Text("“${product.name}” will be removed from this device's catalogue. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.delete(product.id) }
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    importMessage?.let { message ->
        AlertDialog(onDismissRequest = { importMessage = null }, title = { Text("AutoStock") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { importMessage = null }) { Text("OK") } })
    }

    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text("Import preview") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Automatically mapped columns", fontWeight = FontWeight.Bold)
                    preview.fieldMapping.forEach { (field, header) -> Text("$field  ←  $header", style = MaterialTheme.typography.bodySmall) }
                    Text("Ready to import: ${preview.validProducts.size}")
                    Text("Rows with errors / duplicates: ${preview.issues.size}")
                    if (preview.skippedDuplicates > 0) Text("Duplicate rows: ${preview.skippedDuplicates}")
                    preview.issues.take(12).forEach { issue ->
                        Text("Row ${issue.row} • ${issue.field}: ${issue.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (preview.issues.size > 12) Text("…and ${preview.issues.size - 12} more issues.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val inserted = dao.insertAll(preview.validProducts)
                        importMessage = "Imported ${inserted.count { it != -1L }} product(s). ${preview.issues.size} row(s) were skipped."
                    }
                    errorReport = XlsxCatalogue.errorReportCsv(preview)
                    importPreview = null
                }) { Text("Import valid rows") }
            },
            dismissButton = {
                Row {
                    if (preview.issues.isNotEmpty()) TextButton(onClick = {
                        errorReport = XlsxCatalogue.errorReportCsv(preview)
                        reportLauncher.launch("AutoStock_Import_Errors.csv")
                    }) { Text("Save error report") }
                    TextButton(onClick = { importPreview = null }) { Text("Cancel") }
                }
            }
        )
    }

}






private enum class ReportPeriod(val label: String) {
    DAILY("Daily"), MONTHLY("Monthly"), FINANCIAL_YEAR("Financial year")
}

private data class SalesReportRow(
    val label: String,
    val invoiceCount: Int,
    val taxablePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val totalGstPaise: Long,
    val grandTotalPaise: Long
)

private fun saleDate(sale: Sale): LocalDate =
    Instant.ofEpochMilli(sale.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()

private fun reportLabel(sale: Sale, period: ReportPeriod): String {
    val date = saleDate(sale)
    return when (period) {
        ReportPeriod.DAILY -> date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        ReportPeriod.MONTHLY -> YearMonth.from(date).toString()
        ReportPeriod.FINANCIAL_YEAR -> {
            val start = if (date.monthValue >= 4) date.year else date.year - 1
            "$start-${(start + 1).toString().takeLast(2)}"
        }
    }
}

private fun salesReportRows(sales: List<Sale>, period: ReportPeriod): List<SalesReportRow> =
    sales.groupBy { reportLabel(it, period) }.map { (label, group) ->
        SalesReportRow(
            label = label,
            invoiceCount = group.size,
            taxablePaise = group.sumOf { it.subtotalPaise },
            cgstPaise = group.sumOf { it.cgstPaise },
            sgstPaise = group.sumOf { it.sgstPaise },
            igstPaise = group.sumOf { it.igstPaise },
            totalGstPaise = group.sumOf { it.gstPaise },
            grandTotalPaise = group.sumOf { it.totalPaise }
        )
    }.sortedByDescending { it.label }

private fun buildSalesReportCsv(sales: List<Sale>, period: ReportPeriod): String {
    val rows = salesReportRows(sales, period)
    val output = StringBuilder()
    output.append("Period,Invoice Count,Taxable Sales (INR),CGST (INR),SGST (INR),IGST (INR),Total GST (INR),Grand Total (INR)\r\n")
    rows.forEach { row ->
        output.append(
            listOf(
                row.label, row.invoiceCount,
                "%.2f".format(Locale.US, row.taxablePaise / 100.0),
                "%.2f".format(Locale.US, row.cgstPaise / 100.0),
                "%.2f".format(Locale.US, row.sgstPaise / 100.0),
                "%.2f".format(Locale.US, row.igstPaise / 100.0),
                "%.2f".format(Locale.US, row.totalGstPaise / 100.0),
                "%.2f".format(Locale.US, row.grandTotalPaise / 100.0)
            ).joinToString(",")
        ).append("\r\n")
    }
    val totals = SalesReportRow(
        "TOTAL", sales.size,
        sales.sumOf { it.subtotalPaise },
        sales.sumOf { it.cgstPaise },
        sales.sumOf { it.sgstPaise },
        sales.sumOf { it.igstPaise },
        sales.sumOf { it.gstPaise },
        sales.sumOf { it.totalPaise }
    )
    output.append(
        listOf(
            totals.label, totals.invoiceCount,
            "%.2f".format(Locale.US, totals.taxablePaise / 100.0),
            "%.2f".format(Locale.US, totals.cgstPaise / 100.0),
            "%.2f".format(Locale.US, totals.sgstPaise / 100.0),
            "%.2f".format(Locale.US, totals.igstPaise / 100.0),
            "%.2f".format(Locale.US, totals.totalGstPaise / 100.0),
            "%.2f".format(Locale.US, totals.grandTotalPaise / 100.0)
        ).joinToString(",")
    ).append("\r\n")
    return output.toString()
}

@Composable
private fun SalesReportsScreen(
    sales: List<Sale>,
    productSales: List<ProductSalesSummary>,
    period: ReportPeriod,
    onPeriodChange: (ReportPeriod) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = remember(sales, period) { salesReportRows(sales, period) }
    val today = LocalDate.now()
    val month = YearMonth.now()
    val todaySales = sales.filter { saleDate(it) == today }
    val monthSales = sales.filter { YearMonth.from(saleDate(it)) == month }
    val dailyTotal = todaySales.sumOf { it.totalPaise }
    val monthlyTurnover = monthSales.sumOf { it.totalPaise }
    val monthlyGst = monthSales.sumOf { it.gstPaise }
    val totalTaxable = sales.sumOf { it.subtotalPaise }
    val totalCgst = sales.sumOf { it.cgstPaise }
    val totalSgst = sales.sumOf { it.sgstPaise }
    val totalIgst = sales.sumOf { it.igstPaise }
    val totalGst = sales.sumOf { it.gstPaise }
    val totalGrand = sales.sumOf { it.totalPaise }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Sales dashboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Live totals from completed invoices", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DashboardMetricCard("Today's sales", money(dailyTotal), "${todaySales.size} invoices", Modifier.weight(1f))
            DashboardMetricCard("Monthly turnover", money(monthlyTurnover), month.toString(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DashboardMetricCard("GST collected", money(monthlyGst), "This month", Modifier.weight(1f))
            DashboardMetricCard("Invoices this month", monthSales.size.toString(), "Completed bills", Modifier.weight(1f))
        }
        Text("Product-wise sales • $month", fontWeight = FontWeight.SemiBold)
        if (productSales.isEmpty()) {
            Text("No product sales recorded this month.", style = MaterialTheme.typography.bodySmall)
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                productSales.forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.productName, fontWeight = FontWeight.SemiBold)
                                Text("${item.quantitySold} units • GST ${money(item.gstPaise)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(money(item.totalPaise), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Text("Detailed sales reports", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReportPeriod.values().forEach { option ->
                FilterChip(selected = period == option, onClick = { onPeriodChange(option) }, label = { Text(option.label) })
            }
        }
        Text(
            when (period) {
                ReportPeriod.DAILY -> "Sales grouped by transaction date"
                ReportPeriod.MONTHLY -> "Sales grouped by calendar month"
                ReportPeriod.FINANCIAL_YEAR -> "Financial years run from 1 April to 31 March"
            },
            style = MaterialTheme.typography.bodySmall
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("All recorded sales", fontWeight = FontWeight.Bold)
                Text("Invoices: ${sales.size}")
                Text("Taxable sales: ${money(totalTaxable)}")
                Text("CGST: ${money(totalCgst)}  •  SGST: ${money(totalSgst)}")
                Text("IGST: ${money(totalIgst)}")
                Text("Total GST: ${money(totalGst)}")
                Text("Grand total: ${money(totalGrand)}", fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${rows.size} ${period.label.lowercase(Locale.ROOT)} period(s)", fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onExport, enabled = sales.isNotEmpty()) { Text("Export CSV") }
        }
        if (rows.isEmpty()) {
            Text("No completed sales available for reports.")
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.label }) { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(row.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("${row.invoiceCount} invoice(s)")
                            }
                            Text("Taxable sales: ${money(row.taxablePaise)}")
                            Text("CGST: ${money(row.cgstPaise)}  •  SGST: ${money(row.sgstPaise)}")
                            Text("IGST: ${money(row.igstPaise)}")
                            Text("Total GST: ${money(row.totalGstPaise)}")
                            Text("Grand total: ${money(row.grandTotalPaise)}", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Text("Legacy invoices may not contain the original CGST/SGST/IGST split.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DashboardMetricCard(title: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.bodySmall)
        }
    }
}


@Composable
private fun PurchaseEntryDialog(
    products: List<Product>,
    onDismiss: () -> Unit,
    onSave: (Purchase, List<PurchaseItem>) -> Unit
) {
    var supplier by remember { mutableStateOf("") }
    var gstin by remember { mutableStateOf("") }
    var invoice by remember { mutableStateOf("") }
    var itcEligible by remember { mutableStateOf(true) }
    var intraState by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Map<Long, Pair<String, String>>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun paise(value: String) = ((value.trim().replace(",", "").toDoubleOrNull() ?: 0.0) * 100.0).roundToLong()

    val items = products.mapNotNull { product ->
        val entry = selected[product.id] ?: return@mapNotNull null
        val qty = entry.first.toIntOrNull() ?: 0
        if (qty <= 0) return@mapNotNull null
        val unit = paise(entry.second)
        val taxable = unit * qty
        val gst = gstFor(taxable, product.gstRate)
        val cgst = if (intraState) gst / 2 else 0L
        val sgst = if (intraState) gst - cgst else 0L
        PurchaseItem(0, 0, product.id, product.name, product.oemNumber, qty, unit,
            product.gstRate, taxable, cgst, sgst, if (intraState) 0 else gst, gst, taxable + gst)
    }
    val taxable = items.sumOf { it.taxablePaise }
    val cgst = items.sumOf { it.cgstPaise }
    val sgst = items.sumOf { it.sgstPaise }
    val igst = items.sumOf { it.igstPaise }
    val gst = items.sumOf { it.gstPaise }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New supplier invoice") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(supplier, { supplier = it }, label = { Text("Supplier name *") }, singleLine = true)
                OutlinedTextField(gstin, { gstin = it.uppercase(Locale.ROOT).take(15) }, label = { Text("Supplier GSTIN") }, singleLine = true)
                OutlinedTextField(invoice, { invoice = it }, label = { Text("Supplier invoice number *") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Intra-state (CGST + SGST)", Modifier.weight(1f))
                    Switch(intraState, { intraState = it })
                }
                Text("Select products and enter quantities / unit prices", fontWeight = FontWeight.Bold)
                products.forEach { product ->
                    val entry = selected[product.id]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(entry != null, { checked ->
                                    selected = if (checked) selected + (product.id to ("1" to "%.2f".format(Locale.US, product.purchasePricePaise / 100.0))) else selected - product.id
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(product.name, fontWeight = FontWeight.SemiBold)
                                    Text("${product.brand} • ${product.oemNumber} • Stock ${product.quantity} • GST ${product.gstRate}%", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (entry != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(entry.first, { selected = selected + (product.id to (it.filter(Char::isDigit) to entry.second)) },
                                    label = { Text("Qty") }, modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                                OutlinedTextField(entry.second, { selected = selected + (product.id to (entry.first to it.filter { c -> c.isDigit() || c == '.' })) },
                                    label = { Text("Unit price ₹") }, modifier = Modifier.weight(2f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mark GST as ITC eligible", Modifier.weight(1f))
                    Switch(itcEligible, { itcEligible = it })
                }
                Text("Taxable ${money(taxable)} • GST ${money(gst)} • Total ${money(taxable + gst)}", fontWeight = FontWeight.Bold)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    supplier.isBlank() -> error = "Enter supplier name."
                    invoice.isBlank() -> error = "Enter supplier invoice number."
                    gstin.isNotBlank() && !gstin.matches(Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")) -> error = "Check supplier GSTIN."
                    items.isEmpty() -> error = "Select at least one product and enter a quantity."
                    items.any { it.unitPricePaise < 0 } -> error = "Enter valid purchase prices."
                    else -> onSave(Purchase(0, supplier.trim(), gstin, invoice.trim(), System.currentTimeMillis(),
                        taxable, cgst, sgst, igst, gst, taxable + gst, itcEligible), items)
                }
            }) { Text("Save invoice & increase stock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PurchaseReturnDialog(
    purchases: List<Purchase>,
    purchaseItems: List<PurchaseItem>,
    returnedItems: List<PurchaseReturnItem>,
    onDismiss: () -> Unit,
    onSave: (PurchaseReturn, List<PurchaseReturnItem>) -> Unit
) {
    var selectedPurchaseId by remember { mutableStateOf<Long?>(null) }
    var creditNote by remember { mutableStateOf("") }
    var quantities by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    val selectedPurchase = purchases.firstOrNull { it.id == selectedPurchaseId }
    val sourceItems = purchaseItems.filter { it.purchaseId == selectedPurchaseId }
    val returnableItems = sourceItems.mapNotNull { original ->
        val already = returnedItems.filter { it.purchaseItemId == original.id }.sumOf { it.quantity }
        val remaining = original.quantity - already
        if (remaining <= 0) null else original to remaining
    }
    val noteItems = returnableItems.mapNotNull { (original, remaining) ->
        val qty = quantities[original.id]?.toIntOrNull() ?: 0
        if (qty <= 0) return@mapNotNull null
        val taxable = (original.taxablePaise * qty.toDouble() / original.quantity).roundToLong()
        val c = (original.cgstPaise * qty.toDouble() / original.quantity).roundToLong()
        val s = (original.sgstPaise * qty.toDouble() / original.quantity).roundToLong()
        val i = (original.igstPaise * qty.toDouble() / original.quantity).roundToLong()
        val tax = c + s + i
        PurchaseReturnItem(0, 0, original.id, original.productId, original.productName, qty,
            taxable, c, s, i, tax, taxable + tax)
    }
    val taxable = noteItems.sumOf { it.taxablePaise }
    val cgst = noteItems.sumOf { it.cgstPaise }
    val sgst = noteItems.sumOf { it.sgstPaise }
    val igst = noteItems.sumOf { it.igstPaise }
    val gst = noteItems.sumOf { it.gstPaise }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Purchase return / supplier debit note") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 590.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedPurchase?.let { "${it.supplierName} • ${it.supplierInvoiceNumber}" } ?: "Select original supplier invoice")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        purchases.filter { p -> purchaseItems.any { it.purchaseId == p.id } }.forEach { p ->
                            DropdownMenuItem(
                                text = { Text("${p.supplierName} • ${p.supplierInvoiceNumber}") },
                                onClick = {
                                    selectedPurchaseId = p.id
                                    quantities = emptyMap()
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(creditNote, { creditNote = it }, label = { Text("Supplier credit note / debit note reference *") }, singleLine = true)
                if (selectedPurchase != null) {
                    Text("Select items to return", fontWeight = FontWeight.Bold)
                    if (returnableItems.isEmpty()) Text("No unreturned items remain on this invoice.")
                    returnableItems.forEach { (item, remaining) ->
                        val already = item.quantity - remaining
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.productName, fontWeight = FontWeight.SemiBold)
                                Text("Purchased ${item.quantity} • Already returned $already • Available to return $remaining",
                                    style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = quantities[item.id].orEmpty(),
                                    onValueChange = { quantities = quantities + (item.id to it.filter(Char::isDigit)) },
                                    label = { Text("Return quantity (max $remaining)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                            }
                        }
                    }
                    Text("Taxable credit ${money(taxable)} • GST reversal ${money(gst)}", fontWeight = FontWeight.SemiBold)
                    Text("Debit note total ${money(taxable + gst)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                } else {
                    Text("Only invoices with saved product lines can be returned.")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    selectedPurchase == null -> error = "Select the original supplier invoice."
                    creditNote.isBlank() -> error = "Enter the supplier credit/debit note reference."
                    noteItems.isEmpty() -> error = "Enter at least one return quantity."
                    noteItems.any { line -> line.quantity > (returnableItems.firstOrNull { it.first.id == line.purchaseItemId }?.second ?: 0) } -> error = "Return quantity exceeds the remaining purchased quantity."
                    else -> onSave(PurchaseReturn(
                        0, selectedPurchase.id, selectedPurchase.supplierName, creditNote.trim(),
                        System.currentTimeMillis(), taxable, cgst, sgst, igst, gst, taxable + gst,
                        selectedPurchase.itcEligible
                    ), noteItems)
                }
            }) { Text("Save debit note & reverse stock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PurchaseDashboardScreen(
    purchases: List<Purchase>,
    supplierSummaries: List<SupplierPurchaseSummary>,
    sales: List<Sale>,
    returns: List<PurchaseReturn>,
    onAddReturn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val month = YearMonth.now()
    fun monthOf(millis: Long) = YearMonth.from(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate())
    val monthPurchases = purchases.filter { monthOf(it.createdAt) == month }
    val monthReturns = returns.filter { monthOf(it.createdAt) == month }
    val monthSales = sales.filter { YearMonth.from(saleDate(it)) == month }
    val grossPurchases = monthPurchases.sumOf { it.totalPaise }
    val returnTotal = monthReturns.sumOf { it.totalPaise }
    val purchaseTotal = grossPurchases - returnTotal
    val grossPurchaseGst = monthPurchases.sumOf { it.gstPaise }
    val returnGst = monthReturns.sumOf { it.gstPaise }
    val purchaseGst = grossPurchaseGst - returnGst
    val grossEligibleItc = monthPurchases.filter { it.itcEligible }.sumOf { it.gstPaise }
    val eligibleReturnGst = monthReturns.filter { it.itcReversalEligible }.sumOf { it.gstPaise }
    val eligibleItc = (grossEligibleItc - eligibleReturnGst).coerceAtLeast(0L)
    val salesGst = monthSales.sumOf { it.gstPaise }
    val estimatedNet = salesGst - eligibleItc

    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Purchase dashboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Current month • $month", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DashboardMetricCard("Net purchases", money(purchaseTotal), "${monthPurchases.size} bills less returns", Modifier.weight(1f))
            DashboardMetricCard("Net purchase GST", money(purchaseGst), "After return adjustments", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DashboardMetricCard("Net eligible ITC", money(eligibleItc), "After ITC reversals", Modifier.weight(1f))
            DashboardMetricCard("Sales GST", money(salesGst), "Output tax this month", Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("GST comparison after purchase returns", fontWeight = FontWeight.Bold)
                Text("Sales GST collected: ${money(salesGst)}")
                Text("Purchase GST: ${money(grossPurchaseGst)}")
                Text("GST reversed on returns: ${money(returnGst)}")
                Text("Eligible ITC after reversals: ${money(eligibleItc)}")
                Text(if (estimatedNet >= 0) "Indicative net GST payable: ${money(estimatedNet)}"
                    else "Indicative ITC balance: ${money(-estimatedNet)}",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Management estimate only. Verify credit-note reporting, GSTR-2B and statutory eligibility before filing.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Supplier-wise net purchases", fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onAddReturn) { Text("Purchase return") }
        }
        if (supplierSummaries.isEmpty()) {
            Text("No purchase records for this month. Use + to record a supplier invoice.", style = MaterialTheme.typography.bodySmall)
        } else {
            supplierSummaries.forEach { supplier ->
                val supplierReturns = monthReturns.filter { it.supplierName.equals(supplier.supplierName, ignoreCase = true) }
                val netTotal = supplier.totalPaise - supplierReturns.sumOf { it.totalPaise }
                val netTaxable = supplier.taxablePaise - supplierReturns.sumOf { it.taxablePaise }
                val netGst = supplier.gstPaise - supplierReturns.sumOf { it.gstPaise }
                val netItc = supplier.eligibleItcPaise - supplierReturns.filter { it.itcReversalEligible }.sumOf { it.gstPaise }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(supplier.supplierName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(money(netTotal), fontWeight = FontWeight.Bold)
                        }
                        Text("${supplier.invoiceCount} invoice(s) • Net taxable ${money(netTaxable)}", style = MaterialTheme.typography.bodySmall)
                        Text("Net GST ${money(netGst)} • Net eligible ITC ${money(netItc.coerceAtLeast(0L))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("Recent purchase invoices", fontWeight = FontWeight.SemiBold)
        purchases.take(50).forEach { purchase ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${purchase.supplierName} • ${purchase.supplierInvoiceNumber}", fontWeight = FontWeight.SemiBold)
                    Text("${Instant.ofEpochMilli(purchase.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()} • GST ${money(purchase.gstPaise)} • ${if (purchase.itcEligible) "ITC eligible" else "ITC not claimed"}", style = MaterialTheme.typography.bodySmall)
                    Text("Total: ${money(purchase.totalPaise)}", fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("Recent supplier debit notes / purchase returns", fontWeight = FontWeight.SemiBold)
        if (returns.isEmpty()) Text("No purchase returns recorded.")
        returns.take(50).forEach { note ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${note.supplierName} • ${note.creditNoteNumber}", fontWeight = FontWeight.SemiBold)
                    Text("GST adjustment ${money(note.gstPaise)} • ${if (note.itcReversalEligible) "ITC reversal" else "No ITC reversal"}", style = MaterialTheme.typography.bodySmall)
                    Text("Credit total: ${money(note.totalPaise)}", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private data class SupplierStatementRequest(
    val supplier: Supplier,
    val fromDate: LocalDate,
    val toDate: LocalDate
)

private data class SupplierStatementEntry(
    val timestamp: Long,
    val date: LocalDate,
    val description: String,
    val reference: String,
    val debitPaise: Long,
    val creditPaise: Long,
    val paymentMode: String = "",
    val notes: String = ""
) {
    val signedPaise: Long get() = debitPaise - creditPaise
}

@Composable
private fun SupplierStatementDateDialog(
    supplier: Supplier,
    onDismiss: () -> Unit,
    onGenerate: (LocalDate, LocalDate) -> Unit
) {
    var fromText by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var toText by remember { mutableStateOf(LocalDate.now().toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supplier statement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(supplier.name, fontWeight = FontWeight.Bold)
                Text("Enter dates in YYYY-MM-DD format.")
                OutlinedTextField(fromText, { fromText = it; error = null },
                    label = { Text("From date") }, singleLine = true)
                OutlinedTextField(toText, { toText = it; error = null },
                    label = { Text("To date") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val from = LocalDate.parse(fromText.trim())
                    val to = LocalDate.parse(toText.trim())
                    if (to.isBefore(from)) error = "To date must be on or after from date."
                    else onGenerate(from, to)
                } catch (_: Exception) {
                    error = "Enter valid dates as YYYY-MM-DD."
                }
            }) { Text("Generate PDF") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun supplierStatementEntries(
    request: SupplierStatementRequest,
    purchases: List<Purchase>,
    returns: List<PurchaseReturn>,
    payments: List<SupplierPayment>
): List<SupplierStatementEntry> {
    val zone = ZoneId.systemDefault()
    return buildList {
        purchases.filter { it.supplierName.equals(request.supplier.name, true) }.forEach { purchase ->
            val date = Instant.ofEpochMilli(purchase.createdAt).atZone(zone).toLocalDate()
            add(SupplierStatementEntry(
                purchase.createdAt, date, "Purchase invoice", purchase.supplierInvoiceNumber,
                purchase.totalPaise, 0L
            ))
        }
        returns.filter { it.supplierName.equals(request.supplier.name, true) }.forEach { note ->
            val date = Instant.ofEpochMilli(note.createdAt).atZone(zone).toLocalDate()
            add(SupplierStatementEntry(
                note.createdAt, date, "Purchase return", note.creditNoteNumber,
                0L, note.totalPaise
            ))
        }
        payments.filter { it.supplierId == request.supplier.id }.forEach { payment ->
            val date = Instant.ofEpochMilli(payment.createdAt).atZone(zone).toLocalDate()
            add(SupplierStatementEntry(
                payment.createdAt, date, "Payment", payment.reference,
                0L, payment.amountPaise, payment.paymentMode, payment.notes
            ))
        }
    }.sortedWith(compareBy<SupplierStatementEntry> { it.timestamp }.thenBy { it.description })
}

private fun writeSupplierStatementPdf(
    request: SupplierStatementRequest,
    purchases: List<Purchase>,
    returns: List<PurchaseReturn>,
    payments: List<SupplierPayment>,
    output: java.io.OutputStream
) {
    val entries = supplierStatementEntries(request, purchases, returns, payments)
    val opening = entries.filter { it.date.isBefore(request.fromDate) }.sumOf { it.signedPaise }
    val inRange = entries.filter { !it.date.isBefore(request.fromDate) && !it.date.isAfter(request.toDate) }
    val closing = opening + inRange.sumOf { it.signedPaise }
    val document = PdfDocument()
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; isFakeBoldText = true }
    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; isFakeBoldText = true }
    val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f }
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7f }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.6f }
    var pageNo = 0
    var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNo).create())
    var canvas = page.canvas
    var y = 38f

    fun draw(value: String, x: Float, yy: Float, paint: Paint = normal) {
        canvas.drawText(value.replace('\n', ' ').take(85), x, yy, paint)
    }
    fun rule(yy: Float) { canvas.drawLine(28f, yy, 567f, yy, line) }
    fun pageHeader(continued: Boolean) {
        draw("SUPPLIER STATEMENT${if (continued) " (continued)" else ""}", 28f, y, title); y += 25f
        draw(request.supplier.name, 28f, y, bold); y += 14f
        draw("GSTIN: ${request.supplier.gstin.ifBlank { "Not recorded" }}", 28f, y, normal); y += 12f
        draw("Phone: ${request.supplier.phone.ifBlank { "—" }}", 28f, y, normal); y += 12f
        draw("Period: ${request.fromDate} to ${request.toDate}", 28f, y, normal); y += 17f
        rule(y); y += 17f
        draw("Date", 28f, y, bold)
        draw("Description / Reference", 100f, y, bold)
        draw("Debit", 365f, y, bold)
        draw("Credit", 430f, y, bold)
        draw("Balance", 495f, y, bold)
        y += 13f; rule(y); y += 15f
    }
    pageHeader(false)
    var balance = opening
    draw("OPENING BALANCE", 100f, y, bold)
    draw(money(balance), 495f, y, bold); y += 18f

    inRange.forEach { entry ->
        if (y > 770f) {
            document.finishPage(page)
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNo).create())
            canvas = page.canvas
            y = 38f
            pageHeader(true)
        }
        balance += entry.signedPaise
        draw(entry.date.toString(), 28f, y, small)
        draw("${entry.description} ${entry.reference}".trim(), 100f, y, normal)
        if (entry.debitPaise > 0) draw(money(entry.debitPaise), 365f, y, small)
        if (entry.creditPaise > 0) draw(money(entry.creditPaise), 430f, y, small)
        draw(money(balance), 495f, y, small)
        y += 15f
        if (entry.paymentMode.isNotBlank() || entry.notes.isNotBlank()) {
            val detail = listOf(entry.paymentMode, entry.notes).filter { it.isNotBlank() }.joinToString(" • ")
            draw(detail, 100f, y, small); y += 12f
        }
    }
    if (y > 700f) {
        document.finishPage(page)
        page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNo).create())
        canvas = page.canvas
        y = 45f
    }
    val periodPurchases = inRange.filter { it.description == "Purchase invoice" }.sumOf { it.debitPaise }
    val periodReturns = inRange.filter { it.description == "Purchase return" }.sumOf { it.creditPaise }
    val periodPayments = inRange.filter { it.description == "Payment" }.sumOf { it.creditPaise }
    rule(y); y += 19f
    draw("PERIOD SUMMARY", 28f, y, bold); y += 17f
    draw("Opening balance", 28f, y); draw(money(opening), 495f, y); y += 15f
    draw("Purchases", 28f, y); draw(money(periodPurchases), 495f, y); y += 15f
    draw("Returns", 28f, y); draw(money(periodReturns), 495f, y); y += 15f
    draw("Payments", 28f, y); draw(money(periodPayments), 495f, y); y += 17f
    rule(y); y += 18f
    draw("CLOSING BALANCE", 365f, y, bold)
    draw(money(closing), 495f, y, bold); y += 23f
    draw("Transactions: ${inRange.size}", 28f, y, normal); y += 15f
    draw("Prepared by AutoStock • This statement reflects transactions recorded in this app.", 28f, y, small)
    y += 12f
    draw("Please reconcile this statement with supplier records before making payment.", 28f, y, small)
    document.finishPage(page)
    document.writeTo(output)
    document.close()
}

@Composable
private fun SupplierEditorDialog(
    onDismiss: () -> Unit,
    onSave: (Supplier) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var gstin by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add supplier") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Supplier / business name *") }, singleLine = true)
                OutlinedTextField(gstin, { gstin = it.uppercase(Locale.ROOT).take(15) }, label = { Text("GSTIN") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("Address") })
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> error = "Enter supplier name."
                    gstin.isNotBlank() && !gstin.matches(Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")) -> error = "Check GSTIN format."
                    else -> onSave(Supplier(name = name.trim(), gstin = gstin, phone = phone.trim(),
                        email = email.trim(), address = address.trim(), notes = notes.trim()))
                }
            }) { Text("Save supplier") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SupplierPaymentDialog(
    supplier: Supplier,
    invoices: List<Purchase>,
    returns: List<PurchaseReturn>,
    existingAllocations: List<SupplierPaymentAllocation>,
    onDismiss: () -> Unit,
    onSave: (Long, String, String, String, Map<Long, Long>) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Bank transfer") }
    var reference by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var allocationText by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    val modes = listOf("Cash", "Bank transfer", "UPI", "Cheque", "Other")
    val returnedByInvoice = returns.groupBy { it.purchaseId }.mapValues { (_, rows) -> rows.sumOf { it.totalPaise } }
    val allocatedByInvoice = existingAllocations.groupBy { it.purchaseId }.mapValues { (_, rows) -> rows.sumOf { it.amountPaise } }
    val openInvoices = invoices.sortedBy { it.createdAt }.map { invoice ->
        val due = (invoice.totalPaise - (returnedByInvoice[invoice.id] ?: 0L) -
            (allocatedByInvoice[invoice.id] ?: 0L)).coerceAtLeast(0L)
        invoice to due
    }.filter { it.second > 0L }
    val parsedAllocations = allocationText.mapNotNull { (id, value) ->
        val paise = ((value.toDoubleOrNull() ?: 0.0) * 100).roundToLong()
        if (paise > 0L) id to paise else null
    }.toMap()
    val allocationTotal = parsedAllocations.values.sum()
    val paymentPaise = ((amount.toDoubleOrNull() ?: 0.0) * 100).roundToLong()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allocate supplier payment") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(supplier.name, fontWeight = FontWeight.Bold)
                OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }; error = null },
                    label = { Text("Total payment amount ₹ *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                Text("Allocate this payment to one or more outstanding invoices.", style = MaterialTheme.typography.bodySmall)
                if (openInvoices.isEmpty()) {
                    Text("No invoices have an outstanding balance. This payment cannot be allocated.", color = MaterialTheme.colorScheme.error)
                } else {
                    openInvoices.forEach { (invoice, due) ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${invoice.supplierInvoiceNumber} • Due ${money(due)}", fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = allocationText[invoice.id] ?: "",
                                onValueChange = { value ->
                                    allocationText = allocationText + (invoice.id to value.filter { c -> c.isDigit() || c == '.' })
                                    error = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Allocate ₹") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true
                            )
                        }
                    }
                }
                Text("Allocated: ${money(allocationTotal)} / Payment: ${money(paymentPaise)}",
                    fontWeight = FontWeight.SemiBold)
                Text("Unallocated amount must be ₹0.00. Each allocation is limited to that invoice's remaining balance.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Payment method", fontWeight = FontWeight.SemiBold)
                modes.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == option, onClick = { mode = option })
                        Text(option)
                    }
                }
                OutlinedTextField(reference, { reference = it }, label = { Text("Transaction / cheque reference") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    paymentPaise <= 0 -> error = "Enter a payment amount greater than zero."
                    parsedAllocations.isEmpty() -> error = "Allocate payment to at least one invoice."
                    allocationTotal != paymentPaise -> error = "Invoice allocations must equal the payment amount."
                    parsedAllocations.any { (id, value) -> value > (openInvoices.firstOrNull { it.first.id == id }?.second ?: 0L) } ->
                        error = "An allocation exceeds an invoice's outstanding balance."
                    else -> onSave(paymentPaise, mode, reference.trim(), notes.trim(), parsedAllocations)
                }
            }) { Text("Save allocated payment") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private data class SupplierAgeingInvoice(
    val supplierName: String,
    val invoiceNumber: String,
    val invoiceDate: LocalDate,
    val ageDays: Long,
    val invoiceTotalPaise: Long,
    val returnPaise: Long,
    val allocatedPaidPaise: Long,
    val outstandingPaise: Long
)

private data class SupplierAgeingBucket(
    val label: String,
    val invoiceCount: Int,
    val amountPaise: Long
)

private fun supplierAgeingInvoices(
    purchases: List<Purchase>,
    returns: List<PurchaseReturn>,
    allocations: List<SupplierPaymentAllocation>,
    asOf: LocalDate = LocalDate.now()
): List<SupplierAgeingInvoice> {
    val zone = ZoneId.systemDefault()
    val returnsByPurchase = returns.groupBy { it.purchaseId }.mapValues { (_, rows) -> rows.sumOf { it.totalPaise } }
    val allocatedByPurchase = allocations.groupBy { it.purchaseId }.mapValues { (_, rows) -> rows.sumOf { it.amountPaise } }
    return purchases.mapNotNull { invoice ->
        val date = Instant.ofEpochMilli(invoice.createdAt).atZone(zone).toLocalDate()
        val age = ChronoUnit.DAYS.between(date, asOf).coerceAtLeast(0L)
        val returned = returnsByPurchase[invoice.id] ?: 0L
        val paid = allocatedByPurchase[invoice.id] ?: 0L
        val due = (invoice.totalPaise - returned - paid).coerceAtLeast(0L)
        if (due <= 0L) null else SupplierAgeingInvoice(
            supplierName = invoice.supplierName,
            invoiceNumber = invoice.supplierInvoiceNumber,
            invoiceDate = date,
            ageDays = age,
            invoiceTotalPaise = invoice.totalPaise,
            returnPaise = returned,
            allocatedPaidPaise = paid,
            outstandingPaise = due
        )
    }.sortedWith(compareByDescending<SupplierAgeingInvoice> { it.ageDays }.thenBy { it.supplierName })
}

private fun supplierAgeingBucketRows(invoices: List<SupplierAgeingInvoice>): List<SupplierAgeingBucket> {
    val groups = listOf(
        "0–30 days" to { age: Long -> age >= 0L && age <= 30L },
        "31–60 days" to { age: Long -> age >= 31L && age <= 60L },
        "61–90 days" to { age: Long -> age >= 61L && age <= 90L },
        "Over 90 days" to { age: Long -> age > 90 }
    )
    return groups.map { (label, matches) ->
        val rows = invoices.filter { matches(it.ageDays) }
        SupplierAgeingBucket(label, rows.size, rows.sumOf { it.outstandingPaise })
    }
}

private fun writeSupplierAgeingPdf(
    invoices: List<SupplierAgeingInvoice>,
    reportDate: LocalDate,
    output: java.io.OutputStream
) {
    val document = PdfDocument()
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f; isFakeBoldText = true }
    val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; isFakeBoldText = true }
    val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; isFakeBoldText = true }
    val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7.5f }
    val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7f }
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.6f }
    var pageNumber = 0
    var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
    var canvas = page.canvas
    var y = 35f

    fun draw(text: String, x: Float, paint: Paint = normalPaint) {
        canvas.drawText(text.replace('\n', ' ').take(55), x, y, paint)
    }
    fun rule() {
        canvas.drawLine(28f, y, 567f, y, linePaint)
    }
    fun startPage(continued: Boolean) {
        draw("SUPPLIER INVOICE AGEING REPORT${if (continued) " (continued)" else ""}", 28f, titlePaint)
        y += 23f
        draw("Report date: $reportDate", 28f, normalPaint)
        y += 14f
        rule(); y += 15f
    }
    fun nextPage() {
        document.finishPage(page)
        page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
        canvas = page.canvas
        y = 35f
        startPage(true)
    }
    fun tableHeader() {
        draw("Invoice", 28f, boldPaint)
        draw("Date", 130f, boldPaint)
        draw("Age", 190f, boldPaint)
        draw("Bill", 225f, boldPaint)
        draw("Returns", 300f, boldPaint)
        draw("Paid", 375f, boldPaint)
        draw("Outstanding", 455f, boldPaint)
        y += 12f
        rule(); y += 13f
    }

    val buckets = supplierAgeingBucketRows(invoices)
    val total = invoices.sumOf { it.outstandingPaise }
    startPage(false)
    draw("AGEING SUMMARY", 28f, headingPaint); y += 16f
    buckets.forEach { bucket ->
        draw(bucket.label, 35f, normalPaint)
        draw("${bucket.invoiceCount} invoice(s)", 210f, normalPaint)
        draw(money(bucket.amountPaise), 455f, boldPaint)
        y += 14f
    }
    rule(); y += 15f
    draw("TOTAL OUTSTANDING", 28f, boldPaint)
    draw("${invoices.size} invoice(s)", 210f, boldPaint)
    draw(money(total), 455f, boldPaint)
    y += 24f

    val supplierGroups = invoices.groupBy { it.supplierName }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    if (supplierGroups.isEmpty()) {
        draw("No outstanding supplier invoices as of $reportDate.", 28f, normalPaint)
        y += 18f
    }
    supplierGroups.forEach { (supplierName, rows) ->
        if (y > 760f) nextPage()
        rule(); y += 15f
        draw(supplierName, 28f, headingPaint)
        draw("Supplier total: ${money(rows.sumOf { it.outstandingPaise })}", 365f, boldPaint)
        y += 15f
        tableHeader()
        rows.sortedWith(compareByDescending<SupplierAgeingInvoice> { it.ageDays }.thenBy { it.invoiceDate }).forEach { invoice ->
            if (y > 790f) {
                nextPage()
                draw("$supplierName (continued)", 28f, headingPaint)
                y += 15f
                tableHeader()
            }
            draw(invoice.invoiceNumber, 28f, smallPaint)
            draw(invoice.invoiceDate.toString(), 130f, smallPaint)
            draw("${invoice.ageDays}d", 190f, smallPaint)
            draw(money(invoice.invoiceTotalPaise), 225f, smallPaint)
            draw(money(invoice.returnPaise), 300f, smallPaint)
            draw(money(invoice.allocatedPaidPaise), 375f, smallPaint)
            draw(money(invoice.outstandingPaise), 455f, boldPaint)
            y += 13f
        }
        y += 8f
    }
    if (y > 790f) nextPage()
    rule(); y += 16f
    draw("Report generated by AutoStock • Based on recorded supplier invoices, returns and allocated payments.", 28f, smallPaint)
    y += 12f
    draw("Unallocated historical payments are not assigned to invoices in this ageing report.", 28f, smallPaint)
    document.finishPage(page)
    document.writeTo(output)
    document.close()
}

private fun buildSupplierAgeingCsv(invoices: List<SupplierAgeingInvoice>, asOf: LocalDate = LocalDate.now()): String {
    fun q(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
    fun rupees(paise: Long) = "%.2f".format(Locale.US, paise / 100.0)
    val out = StringBuilder("As Of,Supplier,Invoice Number,Invoice Date,Age Days,Invoice Total,Returns,Allocated Payments,Outstanding,Bucket\r\n")
    invoices.forEach { row ->
        val bucket = when {
            row.ageDays <= 30 -> "0-30 days"
            row.ageDays <= 60 -> "31-60 days"
            row.ageDays <= 90 -> "61-90 days"
            else -> "Over 90 days"
        }
        out.append(listOf(
            q(asOf.toString()), q(row.supplierName), q(row.invoiceNumber), q(row.invoiceDate.toString()),
            row.ageDays.toString(), rupees(row.invoiceTotalPaise), rupees(row.returnPaise),
            rupees(row.allocatedPaidPaise), rupees(row.outstandingPaise), q(bucket)
        ).joinToString(",")).append("\r\n")
    }
    supplierAgeingBucketRows(invoices).forEach { bucket ->
        out.append(listOf(
            q(asOf.toString()), q("BUCKET SUMMARY"), q(""), q(""), q(bucket.label),
            q(""), q(""), q(""), rupees(bucket.amountPaise), q("${bucket.invoiceCount} invoices")
        ).joinToString(",")).append("\r\n")
    }
    return out.toString()
}

private fun writeSupplierAccountingDashboardPdf(
    accountingRows: List<SupplierAccountingMetrics>,
    ageingInvoices: List<SupplierAgeingInvoice>,
    reportDate: LocalDate,
    output: java.io.OutputStream
) {
    val document = PdfDocument()
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f; isFakeBoldText = true }
    val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; isFakeBoldText = true }
    val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; isFakeBoldText = true }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f }
    val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7.5f }
    val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.7f }

    var pageNumber = 0
    var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
    var canvas = page.canvas
    var y = 38f

    fun line(text: String, paint: Paint = textPaint, indent: Float = 30f, advance: Float = 14f) {
        canvas.drawText(text.take(105), indent, y, paint)
        y += advance
    }
    fun divider() {
        canvas.drawLine(28f, y, 567f, y, rulePaint)
        y += 12f
    }
    fun header(continued: Boolean) {
        line("SUPPLIER ACCOUNTING DASHBOARD${if (continued) " (continued)" else ""}", titlePaint, advance = 24f)
        line("Report date: $reportDate", textPaint, advance = 15f)
        divider()
    }
    fun newPage() {
        document.finishPage(page)
        page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
        canvas = page.canvas
        y = 38f
        header(true)
    }
    fun ensureSpace(required: Float = 35f) {
        if (y + required > 800f) newPage()
    }
    fun wrapped(text: String, paint: Paint = smallPaint, indent: Float = 30f) {
        val maxChars = 100
        var remaining = text
        while (remaining.length > maxChars) {
            var cut = remaining.lastIndexOf(' ', maxChars)
            if (cut < 1) cut = maxChars
            ensureSpace(15f)
            line(remaining.substring(0, cut), paint, indent, 12f)
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty()) {
            ensureSpace(15f)
            line(remaining, paint, indent, 12f)
        }
    }

    val purchaseTurnover = accountingRows.sumOf { it.purchaseTurnoverPaise }
    val purchaseReturns = accountingRows.sumOf { it.purchaseReturnsPaise }
    val netPurchases = accountingRows.sumOf { it.netPurchasesPaise }
    val payments = accountingRows.sumOf { it.paymentsPaise }
    val outstanding = accountingRows.sumOf { it.outstandingPaise }
    val attributedSales = accountingRows.sumOf { it.attributedSalesPaise }
    val estimatedCost = accountingRows.sumOf { it.estimatedCostPaise }
    val estimatedProfit = accountingRows.sumOf { it.estimatedGrossProfitPaise }
    val margin = if (attributedSales > 0L) estimatedProfit.toDouble() * 100.0 / attributedSales else 0.0
    val buckets = supplierAgeingBucketRows(ageingInvoices)

    header(false)
    line("OVERALL FINANCIAL SUMMARY", sectionPaint, advance = 18f)
    line("Suppliers in directory: ${accountingRows.size}    Outstanding invoices: ${ageingInvoices.size}")
    line("Purchase turnover: ${money(purchaseTurnover)}")
    line("Purchase returns: ${money(purchaseReturns)}")
    line("Net purchases after returns: ${money(netPurchases)}")
    line("Payments made: ${money(payments)}")
    line("Outstanding supplier balance: ${money(outstanding)}", boldPaint)
    line("Attributed sales (taxable value): ${money(attributedSales)}")
    line("Estimated cost of goods sold: ${money(estimatedCost)}")
    line("Estimated gross profit: ${money(estimatedProfit)}", boldPaint)
    line("Estimated gross margin: ${"%.1f".format(Locale.US, margin)}%")
    y += 5f
    divider()

    line("CONSOLIDATED OUTSTANDING AGEING", sectionPaint, advance = 18f)
    buckets.forEach { bucket ->
        line("${bucket.label}: ${bucket.invoiceCount} invoice(s) • ${money(bucket.amountPaise)}")
    }
    line("Total invoice-aged outstanding: ${money(ageingInvoices.sumOf { it.outstandingPaise })}", boldPaint)
    y += 5f
    divider()

    line("SUPPLIER-WISE ACCOUNTING DETAILS", sectionPaint, advance = 19f)
    if (accountingRows.isEmpty()) {
        line("No suppliers are currently recorded.")
    }
    accountingRows.forEach { supplier ->
        ensureSpace(105f)
        line(supplier.supplierName, sectionPaint, advance = 16f)
        line("Purchase turnover: ${money(supplier.purchaseTurnoverPaise)}    Returns: ${money(supplier.purchaseReturnsPaise)}")
        line("Net purchases: ${money(supplier.netPurchasesPaise)}    Payments: ${money(supplier.paymentsPaise)}")
        line("Outstanding balance: ${money(supplier.outstandingPaise)}", boldPaint)
        line("Ageing buckets:", boldPaint)
        supplier.ageingBuckets.forEach { bucket ->
            line("  ${bucket.label}: ${bucket.invoiceCount} invoice(s) • ${money(bucket.amountPaise)}", smallPaint, advance = 12f)
        }
        val supplierMargin = if (supplier.attributedSalesPaise > 0L)
            supplier.estimatedGrossProfitPaise.toDouble() * 100.0 / supplier.attributedSalesPaise else 0.0
        line("Attributed sales (taxable): ${money(supplier.attributedSalesPaise)}")
        line("Estimated cost: ${money(supplier.estimatedCostPaise)}")
        line("Estimated gross profit: ${money(supplier.estimatedGrossProfitPaise)}    Margin: ${"%.1f".format(Locale.US, supplierMargin)}%", boldPaint)
        y += 5f
        divider()
    }

    ensureSpace(50f)
    line("IMPORTANT ACCOUNTING NOTES", sectionPaint, advance = 17f)
    wrapped("Profitability is an estimate, not batch-level actual profit. AutoStock does not record which supplier's exact stock unit was sold. Sold quantities are apportioned by each supplier's share of recorded net purchased units for the same product. Estimated cost uses recorded purchase-item unit prices. Sales revenue uses invoice-line taxable subtotal; GST is excluded.")
    wrapped("Products without item-level purchase history are not attributed to supplier profitability.")
    wrapped("Supplier outstanding includes all recorded payments. Invoice ageing subtracts only payments allocated to individual invoices; older unallocated payments can therefore cause the ageing total and supplier-level outstanding balance to differ.")
    wrapped("This report is based only on transactions recorded in AutoStock and is an operational management report, not a statutory financial statement.")
    document.finishPage(page)
    document.writeTo(output)
    document.close()
}

private data class SupplierAccountingMetrics(
    val supplierName: String,
    val purchaseTurnoverPaise: Long,
    val purchaseReturnsPaise: Long,
    val netPurchasesPaise: Long,
    val paymentsPaise: Long,
    val outstandingPaise: Long,
    val ageingBuckets: List<SupplierAgeingBucket>,
    val attributedSalesPaise: Long,
    val estimatedCostPaise: Long,
    val estimatedGrossProfitPaise: Long
)

private fun buildSupplierAccountingMetrics(
    suppliers: List<Supplier>,
    purchases: List<Purchase>,
    purchaseItems: List<PurchaseItem>,
    returns: List<PurchaseReturn>,
    returnItems: List<PurchaseReturnItem>,
    payments: List<SupplierPayment>,
    saleItems: List<SaleItem>,
    ageingInvoices: List<SupplierAgeingInvoice>
): List<SupplierAccountingMetrics> {
    val purchaseById = purchases.associateBy { it.id }
    val returnedQtyByPurchaseItem = returnItems.groupBy { it.purchaseItemId }
        .mapValues { (_, lines) -> lines.sumOf { it.quantity } }
    val returnValueBySupplier = returns.groupBy { it.supplierName.lowercase(Locale.ROOT) }
        .mapValues { (_, rows) -> rows.sumOf { it.totalPaise } }
    val purchaseLinesBySupplierProduct = purchaseItems.groupBy { line ->
        val supplier = purchaseById[line.purchaseId]?.supplierName.orEmpty().lowercase(Locale.ROOT)
        supplier to line.productId
    }

    // Estimate supplier-attributed COGS using net received units and recorded unit purchase costs.
    // Sales are apportioned by each supplier's share of net purchased units for that product.
    val supplierProductLots = purchaseLinesBySupplierProduct.mapValues { (_, lines) ->
        val netQty = lines.sumOf { (it.quantity - (returnedQtyByPurchaseItem[it.id] ?: 0)).coerceAtLeast(0) }
        val netCost = lines.sumOf { line ->
            (line.quantity - (returnedQtyByPurchaseItem[line.id] ?: 0)).coerceAtLeast(0).toLong() * line.unitPricePaise
        }
        netQty to netCost
    }
    val netQtyByProduct = supplierProductLots.entries.groupBy { it.key.second }
        .mapValues { (_, rows) -> rows.sumOf { it.value.first } }

    val salesRevenueBySupplier = mutableMapOf<String, Long>()
    val estimatedCostBySupplier = mutableMapOf<String, Long>()
    saleItems.forEach { line ->
        val totalQty = netQtyByProduct[line.productId] ?: 0
        if (totalQty <= 0) return@forEach
        val lots = supplierProductLots.filterKeys { it.second == line.productId }
        lots.forEach { (supplierProduct, lot) ->
            val supplierKey = supplierProduct.first
            val share = lot.first.toDouble() / totalQty.toDouble()
            val attributedRevenue = (line.lineSubtotalPaise * share).roundToLong()
            val averageUnitCost = if (lot.first > 0) lot.second.toDouble() / lot.first else 0.0
            val attributedQty = line.quantity * share
            val attributedCost = (attributedQty * averageUnitCost).roundToLong()
            salesRevenueBySupplier[supplierKey] = (salesRevenueBySupplier[supplierKey] ?: 0L) + attributedRevenue
            estimatedCostBySupplier[supplierKey] = (estimatedCostBySupplier[supplierKey] ?: 0L) + attributedCost
        }
    }

    val paymentsBySupplierId = payments.groupBy { it.supplierId }.mapValues { (_, rows) -> rows.sumOf { it.amountPaise } }
    val ageBySupplier = ageingInvoices.groupBy { it.supplierName.lowercase(Locale.ROOT) }
    return suppliers.map { supplier ->
        val key = supplier.name.lowercase(Locale.ROOT)
        val supplierPurchases = purchases.filter { it.supplierName.equals(supplier.name, true) }
        val purchaseTurnover = supplierPurchases.sumOf { it.totalPaise }
        val returnValue = returnValueBySupplier[key] ?: 0L
        val paymentValue = paymentsBySupplierId[supplier.id] ?: 0L
        val due = purchaseTurnover - returnValue - paymentValue
        val supplierAgeing = ageBySupplier[key].orEmpty()
        val bucketRows = supplierAgeingBucketRows(supplierAgeing)
        val attributedSales = salesRevenueBySupplier[key] ?: 0L
        val estimatedCost = estimatedCostBySupplier[key] ?: 0L
        SupplierAccountingMetrics(
            supplierName = supplier.name,
            purchaseTurnoverPaise = purchaseTurnover,
            purchaseReturnsPaise = returnValue,
            netPurchasesPaise = (purchaseTurnover - returnValue).coerceAtLeast(0L),
            paymentsPaise = paymentValue,
            outstandingPaise = due,
            ageingBuckets = bucketRows,
            attributedSalesPaise = attributedSales,
            estimatedCostPaise = estimatedCost,
            estimatedGrossProfitPaise = attributedSales - estimatedCost
        )
    }.sortedByDescending { it.purchaseTurnoverPaise }
}

private data class SupplierPeriodComparison(
    val current: List<SupplierAccountingMetrics>,
    val previous: List<SupplierAccountingMetrics>,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val previousFromDate: LocalDate,
    val previousToDate: LocalDate
)

private fun localDateOf(timestamp: Long): LocalDate =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

private fun supplierPeriodMetrics(
    suppliers: List<Supplier>,
    purchases: List<Purchase>,
    purchaseItems: List<PurchaseItem>,
    returns: List<PurchaseReturn>,
    returnItems: List<PurchaseReturnItem>,
    payments: List<SupplierPayment>,
    allocations: List<SupplierPaymentAllocation>,
    sales: List<Sale>,
    saleItems: List<SaleItem>,
    fromDate: LocalDate,
    toDate: LocalDate
): List<SupplierAccountingMetrics> {
    require(!toDate.isBefore(fromDate)) { "End date must be on or after start date." }

    val purchasesToDate = purchases.filter { !localDateOf(it.createdAt).isAfter(toDate) }
    val purchaseIdsToDate = purchasesToDate.map { it.id }.toSet()
    val returnsToDate = returns.filter { !localDateOf(it.createdAt).isAfter(toDate) && it.purchaseId in purchaseIdsToDate }
    val returnIdsToDate = returnsToDate.map { it.id }.toSet()
    val paymentsToDate = payments.filter { !localDateOf(it.createdAt).isAfter(toDate) }
    val paymentIdsToDate = paymentsToDate.map { it.id }.toSet()
    val allocationsToDate = allocations.filter { it.paymentId in paymentIdsToDate }
    val itemsToDate = purchaseItems.filter { it.purchaseId in purchaseIdsToDate }
    val returnItemsToDate = returnItems.filter { it.returnId in returnIdsToDate }

    // Ageing is a point-in-time snapshot at the selected end date, not a sum of ageing during the period.
    val ageingAtEnd = supplierAgeingInvoices(purchasesToDate, returnsToDate, allocationsToDate, toDate)

    val periodPurchases = purchases.filter {
        val date = localDateOf(it.createdAt)
        !date.isBefore(fromDate) && !date.isAfter(toDate)
    }
    val periodReturns = returns.filter {
        val date = localDateOf(it.createdAt)
        !date.isBefore(fromDate) && !date.isAfter(toDate)
    }
    val periodPayments = payments.filter {
        val date = localDateOf(it.createdAt)
        !date.isBefore(fromDate) && !date.isAfter(toDate)
    }
    val saleIdsInPeriod = sales.filter {
        val date = localDateOf(it.createdAt)
        !date.isBefore(fromDate) && !date.isAfter(toDate)
    }.map { it.id }.toSet()
    val periodSaleItems = saleItems.filter { it.saleId in saleIdsInPeriod }

    // Reuse the supplier attribution model with stock history available up to the report end date.
    // Override the flow totals below with transactions occurring only inside the selected range.
    val estimated = buildSupplierAccountingMetrics(
        suppliers, purchasesToDate, itemsToDate, returnsToDate, returnItemsToDate,
        paymentsToDate, periodSaleItems, ageingAtEnd
    )
    return estimated.map { row ->
        val supplierPurchases = periodPurchases.filter { it.supplierName.equals(row.supplierName, true) }
        val supplierReturns = periodReturns.filter { it.supplierName.equals(row.supplierName, true) }
        val supplierPayments = periodPayments.filter { it.supplierName.equals(row.supplierName, true) }
        val purchaseTurnover = supplierPurchases.sumOf { it.totalPaise }
        val returnValue = supplierReturns.sumOf { it.totalPaise }
        val paymentValue = supplierPayments.sumOf { it.amountPaise }
        val allPurchasesAtEnd = purchasesToDate.filter { it.supplierName.equals(row.supplierName, true) }.sumOf { it.totalPaise }
        val allReturnsAtEnd = returnsToDate.filter { it.supplierName.equals(row.supplierName, true) }.sumOf { it.totalPaise }
        val allPaymentsAtEnd = paymentsToDate.filter { it.supplierName.equals(row.supplierName, true) }.sumOf { it.amountPaise }
        val supplierAgeing = ageingAtEnd.filter { it.supplierName.equals(row.supplierName, true) }
        row.copy(
            purchaseTurnoverPaise = purchaseTurnover,
            purchaseReturnsPaise = returnValue,
            netPurchasesPaise = (purchaseTurnover - returnValue).coerceAtLeast(0L),
            paymentsPaise = paymentValue,
            outstandingPaise = allPurchasesAtEnd - allReturnsAtEnd - allPaymentsAtEnd,
            ageingBuckets = supplierAgeingBucketRows(supplierAgeing)
        )
    }.sortedByDescending { it.purchaseTurnoverPaise }
}

private fun buildSupplierPeriodComparison(
    suppliers: List<Supplier>,
    purchases: List<Purchase>,
    purchaseItems: List<PurchaseItem>,
    returns: List<PurchaseReturn>,
    returnItems: List<PurchaseReturnItem>,
    payments: List<SupplierPayment>,
    allocations: List<SupplierPaymentAllocation>,
    sales: List<Sale>,
    saleItems: List<SaleItem>,
    fromDate: LocalDate,
    toDate: LocalDate
): SupplierPeriodComparison {
    require(!toDate.isBefore(fromDate)) { "End date must be on or after start date." }
    val days = ChronoUnit.DAYS.between(fromDate, toDate) + 1L
    val previousTo = fromDate.minusDays(1)
    val previousFrom = previousTo.minusDays(days - 1L)
    val current = supplierPeriodMetrics(
        suppliers, purchases, purchaseItems, returns, returnItems, payments, allocations,
        sales, saleItems, fromDate, toDate
    )
    val previous = supplierPeriodMetrics(
        suppliers, purchases, purchaseItems, returns, returnItems, payments, allocations,
        sales, saleItems, previousFrom, previousTo
    )
    return SupplierPeriodComparison(current, previous, fromDate, toDate, previousFrom, previousTo)
}

private fun writeSupplierPeriodComparisonPdf(
    comparison: SupplierPeriodComparison,
    output: java.io.OutputStream
) {
    val document = PdfDocument()
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; isFakeBoldText = true }
    val section = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; isFakeBoldText = true }
    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; isFakeBoldText = true }
    val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f }
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7.2f }
    val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.6f }

    var pageNo = 0
    var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNo).create())
    var canvas = page.canvas
    var y = 36f

    fun draw(text: String, paint: Paint = normal, x: Float = 30f, step: Float = 13f) {
        canvas.drawText(text.replace('\n', ' ').take(105), x, y, paint)
        y += step
    }
    fun divider() {
        canvas.drawLine(28f, y, 567f, y, rule)
        y += 11f
    }
    fun pageHeader(continued: Boolean) {
        draw("SUPPLIER PERIOD ACCOUNTING COMPARISON${if (continued) " (continued)" else ""}", title, step = 23f)
        draw("Current period: ${comparison.fromDate} to ${comparison.toDate}")
        draw("Previous period: ${comparison.previousFromDate} to ${comparison.previousToDate}")
        divider()
    }
    fun nextPage() {
        document.finishPage(page)
        page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNo).create())
        canvas = page.canvas
        y = 36f
        pageHeader(true)
    }
    fun ensureSpace(height: Float = 30f) {
        if (y + height > 800f) nextPage()
    }
    fun wrapped(text: String, paint: Paint = small) {
        var remaining = text
        while (remaining.length > 100) {
            var cut = remaining.lastIndexOf(' ', 100)
            if (cut < 1) cut = 100
            ensureSpace(14f)
            draw(remaining.substring(0, cut), paint, step = 12f)
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty()) {
            ensureSpace(14f)
            draw(remaining, paint, step = 12f)
        }
    }
    fun total(rows: List<SupplierAccountingMetrics>, selector: (SupplierAccountingMetrics) -> Long) =
        rows.sumOf(selector)
    fun amountComparison(label: String, current: Long, previous: Long) {
        ensureSpace()
        draw("$label: Current ${money(current)}  |  Previous ${money(previous)}  |  Change ${percentageChange(current, previous)}")
    }

    val current = comparison.current
    val previous = comparison.previous
    pageHeader(false)
    draw("REPORT FILTERS", section, step = 17f)
    draw("Date filter: ${comparison.fromDate} through ${comparison.toDate} (inclusive)")
    draw("Comparison: immediately preceding period of equal duration")
    draw("Report generated: ${LocalDate.now()}")
    divider()

    draw("OVERALL COMPARATIVE TOTALS", section, step = 17f)
    amountComparison("Purchase turnover", total(current) { it.purchaseTurnoverPaise }, total(previous) { it.purchaseTurnoverPaise })
    amountComparison("Purchase returns", total(current) { it.purchaseReturnsPaise }, total(previous) { it.purchaseReturnsPaise })
    amountComparison("Net purchases", total(current) { it.netPurchasesPaise }, total(previous) { it.netPurchasesPaise })
    amountComparison("Payments", total(current) { it.paymentsPaise }, total(previous) { it.paymentsPaise })
    amountComparison("Outstanding at period end", total(current) { it.outstandingPaise }, total(previous) { it.outstandingPaise })
    amountComparison("Attributed taxable sales", total(current) { it.attributedSalesPaise }, total(previous) { it.attributedSalesPaise })
    amountComparison("Estimated cost", total(current) { it.estimatedCostPaise }, total(previous) { it.estimatedCostPaise })
    amountComparison("Estimated gross profit", total(current) { it.estimatedGrossProfitPaise }, total(previous) { it.estimatedGrossProfitPaise })
    val currentSales = total(current) { it.attributedSalesPaise }
    val previousSales = total(previous) { it.attributedSalesPaise }
    val currentProfit = total(current) { it.estimatedGrossProfitPaise }
    val previousProfit = total(previous) { it.estimatedGrossProfitPaise }
    val currentMargin = if (currentSales > 0L) currentProfit.toDouble() * 100.0 / currentSales else 0.0
    val previousMargin = if (previousSales > 0L) previousProfit.toDouble() * 100.0 / previousSales else 0.0
    draw("Estimated gross margin: Current ${"%.1f".format(Locale.US, currentMargin)}%  |  Previous ${"%.1f".format(Locale.US, previousMargin)}%")
    divider()

    val currentEndAgeing = current.flatMap { row ->
        row.ageingBuckets.map { it.label to it }
    }.groupBy({ it.first }, { it.second })
    val previousEndAgeing = previous.flatMap { row ->
        row.ageingBuckets.map { it.label to it }
    }.groupBy({ it.first }, { it.second })
    draw("AGEING BUCKET COMPARISON", section, step = 17f)
    listOf("0–30 days", "31–60 days", "61–90 days", "Over 90 days").forEach { label ->
        val c = currentEndAgeing[label].orEmpty()
        val p = previousEndAgeing[label].orEmpty()
        val cAmount = c.sumOf { it.amountPaise }
        val pAmount = p.sumOf { it.amountPaise }
        val cCount = c.sumOf { it.invoiceCount }
        val pCount = p.sumOf { it.invoiceCount }
        draw("$label: Current ${money(cAmount)} ($cCount invoices) | Previous ${money(pAmount)} ($pCount invoices) | ${percentageChange(cAmount, pAmount)}")
    }
    divider()

    draw("SUPPLIER-WISE COMPARATIVE DETAILS", section, step = 17f)
    if (current.isEmpty()) draw("No supplier records are available for this date range.")
    current.forEach { row ->
        val old = previous.firstOrNull { it.supplierName.equals(row.supplierName, true) }
            ?: SupplierAccountingMetrics(row.supplierName, 0L, 0L, 0L, 0L, 0L, supplierAgeingBucketRows(emptyList()), 0L, 0L, 0L)
        ensureSpace(145f)
        draw(row.supplierName, section, step = 16f)
        draw("Purchase turnover: ${money(row.purchaseTurnoverPaise)} vs ${money(old.purchaseTurnoverPaise)} (${percentageChange(row.purchaseTurnoverPaise, old.purchaseTurnoverPaise)})", small)
        draw("Returns: ${money(row.purchaseReturnsPaise)} vs ${money(old.purchaseReturnsPaise)} | Net purchases: ${money(row.netPurchasesPaise)} vs ${money(old.netPurchasesPaise)}", small)
        draw("Payments: ${money(row.paymentsPaise)} vs ${money(old.paymentsPaise)} (${percentageChange(row.paymentsPaise, old.paymentsPaise)})", small)
        draw("Outstanding at period end: ${money(row.outstandingPaise)} vs ${money(old.outstandingPaise)} (${percentageChange(row.outstandingPaise, old.outstandingPaise)})", small)
        draw("Ageing buckets (current vs previous):", bold)
        row.ageingBuckets.forEach { bucket ->
            val oldBucket = old.ageingBuckets.firstOrNull { it.label == bucket.label }
            draw("  ${bucket.label}: ${money(bucket.amountPaise)} (${bucket.invoiceCount}) vs ${money(oldBucket?.amountPaise ?: 0L)} (${oldBucket?.invoiceCount ?: 0})", small, step = 11f)
        }
        draw("Attributed sales: ${money(row.attributedSalesPaise)} vs ${money(old.attributedSalesPaise)}", small)
        draw("Estimated cost: ${money(row.estimatedCostPaise)} vs ${money(old.estimatedCostPaise)}", small)
        draw("Estimated gross profit: ${money(row.estimatedGrossProfitPaise)} vs ${money(old.estimatedGrossProfitPaise)} (${percentageChange(row.estimatedGrossProfitPaise, old.estimatedGrossProfitPaise)})", bold)
        val rowMargin = if (row.attributedSalesPaise > 0L) row.estimatedGrossProfitPaise.toDouble() * 100.0 / row.attributedSalesPaise else 0.0
        val oldMargin = if (old.attributedSalesPaise > 0L) old.estimatedGrossProfitPaise.toDouble() * 100.0 / old.attributedSalesPaise else 0.0
        draw("Estimated margin: ${"%.1f".format(Locale.US, rowMargin)}% vs ${"%.1f".format(Locale.US, oldMargin)}%", small)
        y += 5f
        divider()
    }

    ensureSpace(70f)
    draw("INTERPRETATION & LIMITATIONS", section, step = 17f)
    wrapped("Purchase turnover, returns and payments are activity inside each selected period. Outstanding balances and ageing are point-in-time snapshots as of each period end. Invoice ageing reduces outstanding only by payments allocated to individual invoices; unallocated historical payments may cause differences from supplier-level outstanding.")
    wrapped("Profitability is an estimate, not batch-level actual profit. Sold quantities are apportioned according to each supplier's share of recorded net purchased units for the same product, using purchase-item costs. Sales revenue is based on taxable invoice-line values; GST is excluded. Products without item-level purchase history are not attributed.")
    wrapped("This report is an operational management report based on transactions recorded in AutoStock, not a statutory financial statement.")
    document.finishPage(page)
    document.writeTo(output)
    document.close()
}

private fun percentageChange(current: Long, previous: Long): String {
    if (previous == 0L) return if (current == 0L) "0.0%" else "New"
    val change = (current - previous).toDouble() * 100.0 / kotlin.math.abs(previous).toDouble()
    return "${if (change > 0) "+" else ""}${"%.1f".format(Locale.US, change)}%"
}

private fun SupplierManagementScreen(
    suppliers: List<Supplier>,
    purchases: List<Purchase>,
    purchaseItems: List<PurchaseItem>,
    returns: List<PurchaseReturn>,
    returnItems: List<PurchaseReturnItem>,
    payments: List<SupplierPayment>,
    sales: List<Sale>,
    saleItems: List<SaleItem>,
    allocations: List<SupplierPaymentAllocation>,
    onAddPayment: (Supplier) -> Unit,
    onGenerateStatement: (SupplierStatementRequest) -> Unit,
    onExport: () -> Unit,
    onExportAgeing: () -> Unit,
    onExportAgeingPdf: () -> Unit,
    onExportAccountingPdf: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalPurchases = purchases.sumOf { it.totalPaise }
    val totalReturns = returns.sumOf { it.totalPaise }
    val totalPayments = payments.sumOf { it.amountPaise }
    val totalOutstanding = totalPurchases - totalReturns - totalPayments
    var statementSupplier by remember { mutableStateOf<Supplier?>(null) }
    var accountingFromDate by remember { mutableStateOf(YearMonth.now().atDay(1).toString()) }
    var accountingToDate by remember { mutableStateOf(LocalDate.now().toString()) }
    val screenContext = androidx.compose.ui.platform.LocalContext.current
    val periodReportPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            try {
                val from = LocalDate.parse(accountingFromDate.trim())
                val to = LocalDate.parse(accountingToDate.trim())
                val comparison = buildSupplierPeriodComparison(
                    suppliers, purchases, purchaseItems, returns, returnItems, payments,
                    allocations, sales, saleItems, from, to
                )
                screenContext.contentResolver.openOutputStream(uri)?.use { output ->
                    writeSupplierPeriodComparisonPdf(comparison, output)
                } ?: throw IllegalStateException("Could not open the selected PDF destination.")
                Toast.makeText(screenContext, "Supplier period report PDF exported.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(screenContext, e.message ?: "Could not export supplier period report.", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Supplier management", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Directory, supplier balances and ledger", style = MaterialTheme.typography.bodySmall)
        val ageingInvoices = supplierAgeingInvoices(purchases, returns, allocations)
        val accountingRows = buildSupplierAccountingMetrics(
            suppliers, purchases, purchaseItems, returns, returnItems, payments, saleItems, ageingInvoices
        )
        val dashboardPurchases = purchases.sumOf { it.totalPaise }
        val dashboardReturns = returns.sumOf { it.totalPaise }
        val dashboardPayments = payments.sumOf { it.amountPaise }
        val dashboardOutstanding = dashboardPurchases - dashboardReturns - dashboardPayments
        val dashboardProfit = accountingRows.sumOf { it.estimatedGrossProfitPaise }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Supplier accounting dashboard", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onExportAccountingPdf) { Text("Export PDF") }
                }
                Text("All recorded transactions • Ageing as of ${LocalDate.now()}", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    DashboardMetricCard("Purchase turnover", money(dashboardPurchases), "Recorded bills", Modifier.weight(1f))
                    DashboardMetricCard("Payments", money(dashboardPayments), "Paid to suppliers", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    DashboardMetricCard("Outstanding", money(dashboardOutstanding), "After returns & payments", Modifier.weight(1f))
                    DashboardMetricCard("Net purchases", money((dashboardPurchases-dashboardReturns).coerceAtLeast(0L)), "After purchase returns", Modifier.weight(1f))
                }
                val dashboardAttributedSales = accountingRows.sumOf { it.attributedSalesPaise }
                DashboardMetricCard(
                    "Estimated gross profit",
                    money(dashboardProfit),
                    "On ${money(dashboardAttributedSales)} attributed sales • excludes GST",
                    Modifier.fillMaxWidth()
                )
                val allBuckets = supplierAgeingBucketRows(ageingInvoices)
                Text("Outstanding ageing", fontWeight = FontWeight.SemiBold)
                allBuckets.forEach { bucket ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${bucket.label} • ${bucket.invoiceCount} invoices", style = MaterialTheme.typography.bodySmall)
                        Text(money(bucket.amountPaise), fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    "Supplier outstanding includes all recorded payments. Invoice ageing subtracts only payments allocated to individual invoices, so older unallocated payments may cause the two totals to differ.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Text("Supplier-wise performance", fontWeight = FontWeight.SemiBold)
                if (accountingRows.isEmpty()) {
                    Text("Add suppliers and record purchase invoices to build this dashboard.")
                } else {
                    accountingRows.forEach { row ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(row.supplierName, fontWeight = FontWeight.Bold)
                            Text("Purchases ${money(row.purchaseTurnoverPaise)} • Returns ${money(row.purchaseReturnsPaise)} • Net ${money(row.netPurchasesPaise)}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Payments ${money(row.paymentsPaise)} • Outstanding ${money(row.outstandingPaise)}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Ageing: " + row.ageingBuckets.joinToString("  |  ") { "${it.label}: ${money(it.amountPaise)}" },
                                style = MaterialTheme.typography.bodySmall)
                            val margin = if (row.attributedSalesPaise > 0L)
                                row.estimatedGrossProfitPaise.toDouble() * 100.0 / row.attributedSalesPaise else 0.0
                            Text("Estimated attributed sales ${money(row.attributedSalesPaise)} • Estimated cost ${money(row.estimatedCostPaise)}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Estimated gross profit ${money(row.estimatedGrossProfitPaise)} • Margin ${"%.1f".format(Locale.US, margin)}%",
                                fontWeight = FontWeight.SemiBold,
                                color = if (row.estimatedGrossProfitPaise >= 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                        HorizontalDivider()
                    }
                }
                Text(
                    "Profitability is an estimate, not batch-level actual profit. AutoStock does not track which supplier's exact stock unit was sold; sold quantities are apportioned by each supplier's share of recorded net purchases for the same product. It uses purchase-item unit costs and excludes GST. Products without purchase-item history are not attributed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val parsedAccountingFrom = runCatching { LocalDate.parse(accountingFromDate.trim()) }.getOrNull()
        val parsedAccountingTo = runCatching { LocalDate.parse(accountingToDate.trim()) }.getOrNull()
        val periodComparison = if (parsedAccountingFrom != null && parsedAccountingTo != null && !parsedAccountingTo.isBefore(parsedAccountingFrom)) {
            runCatching {
                buildSupplierPeriodComparison(
                    suppliers, purchases, purchaseItems, returns, returnItems, payments,
                    allocations, sales, saleItems, parsedAccountingFrom, parsedAccountingTo
                )
            }.getOrNull()
        } else null
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Monthly & yearly supplier reports", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Choose a reporting period or enter a custom date range. Comparisons use the immediately preceding period of equal length.",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(onClick = {
                        accountingFromDate = YearMonth.now().atDay(1).toString()
                        accountingToDate = LocalDate.now().toString()
                    }, modifier = Modifier.weight(1f)) { Text("This month") }
                    OutlinedButton(onClick = {
                        accountingFromDate = LocalDate.now().withDayOfYear(1).toString()
                        accountingToDate = LocalDate.now().toString()
                    }, modifier = Modifier.weight(1f)) { Text("This year") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = accountingFromDate,
                        onValueChange = { accountingFromDate = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("From (YYYY-MM-DD)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = accountingToDate,
                        onValueChange = { accountingToDate = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("To (YYYY-MM-DD)") },
                        singleLine = true
                    )
                }
                if (periodComparison == null) {
                    Text("Enter valid dates in YYYY-MM-DD format. The end date must be on or after the start date.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "Current: ${periodComparison.fromDate} to ${periodComparison.toDate}  •  Previous: ${periodComparison.previousFromDate} to ${periodComparison.previousToDate}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = {
                            val safeFrom = periodComparison.fromDate.toString()
                            val safeTo = periodComparison.toDate.toString()
                            periodReportPdfLauncher.launch("AutoStock_Supplier_Comparison_${safeFrom}_to_${safeTo}.pdf")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Export this comparison as PDF") }
                    val currentRows = periodComparison.current
                    val previousRows = periodComparison.previous
                    fun totalOf(rows: List<SupplierAccountingMetrics>, selector: (SupplierAccountingMetrics) -> Long) =
                        rows.sumOf(selector)
                    val currentPurchase = totalOf(currentRows) { it.purchaseTurnoverPaise }
                    val previousPurchase = totalOf(previousRows) { it.purchaseTurnoverPaise }
                    val currentReturns = totalOf(currentRows) { it.purchaseReturnsPaise }
                    val previousReturns = totalOf(previousRows) { it.purchaseReturnsPaise }
                    val currentNetPurchases = totalOf(currentRows) { it.netPurchasesPaise }
                    val previousNetPurchases = totalOf(previousRows) { it.netPurchasesPaise }
                    val currentPayment = totalOf(currentRows) { it.paymentsPaise }
                    val previousPayment = totalOf(previousRows) { it.paymentsPaise }
                    val currentOutstanding = totalOf(currentRows) { it.outstandingPaise }
                    val previousOutstanding = totalOf(previousRows) { it.outstandingPaise }
                    val currentProfit = totalOf(currentRows) { it.estimatedGrossProfitPaise }
                    val previousProfit = totalOf(previousRows) { it.estimatedGrossProfitPaise }
                    val currentSales = totalOf(currentRows) { it.attributedSalesPaise }
                    val previousSales = totalOf(previousRows) { it.attributedSalesPaise }
                    Text("Overall comparison", fontWeight = FontWeight.SemiBold)
                    listOf(
                        Triple("Purchase turnover", currentPurchase, previousPurchase),
                        Triple("Purchase returns", currentReturns, previousReturns),
                        Triple("Net purchases", currentNetPurchases, previousNetPurchases),
                        Triple("Payments", currentPayment, previousPayment),
                        Triple("Outstanding at period end", currentOutstanding, previousOutstanding),
                        Triple("Attributed sales", currentSales, previousSales),
                        Triple("Estimated gross profit", currentProfit, previousProfit)
                    ).forEach { (label, current, previous) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodySmall)
                                Text("Previous ${money(previous)}", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(current), fontWeight = FontWeight.SemiBold)
                                Text(percentageChange(current, previous), style = MaterialTheme.typography.labelSmall,
                                    color = if (current >= previous) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    val paymentsThroughCurrentEnd = payments.filter { !localDateOf(it.createdAt).isAfter(periodComparison.toDate) }
                    val currentSnapshotInvoices = supplierAgeingInvoices(
                        purchases.filter { !localDateOf(it.createdAt).isAfter(periodComparison.toDate) },
                        returns.filter { !localDateOf(it.createdAt).isAfter(periodComparison.toDate) },
                        allocations.filter { allocation -> paymentsThroughCurrentEnd.any { it.id == allocation.paymentId } },
                        periodComparison.toDate
                    )
                    val paymentsThroughPreviousEnd = payments.filter { !localDateOf(it.createdAt).isAfter(periodComparison.previousToDate) }
                    val previousSnapshotInvoices = supplierAgeingInvoices(
                        purchases.filter { !localDateOf(it.createdAt).isAfter(periodComparison.previousToDate) },
                        returns.filter { !localDateOf(it.createdAt).isAfter(periodComparison.previousToDate) },
                        allocations.filter { allocation -> paymentsThroughPreviousEnd.any { it.id == allocation.paymentId } },
                        periodComparison.previousToDate
                    )
                    val currentAgeing = supplierAgeingBucketRows(currentSnapshotInvoices)
                    val previousAgeing = supplierAgeingBucketRows(previousSnapshotInvoices)
                    Text("Ageing bucket comparison (as of each period end)", fontWeight = FontWeight.SemiBold)
                    currentAgeing.zip(previousAgeing).forEach { (current, previous) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(current.label, style = MaterialTheme.typography.bodySmall)
                                Text("Previous ${money(previous.amountPaise)} (${previous.invoiceCount} invoices)", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(current.amountPaise), fontWeight = FontWeight.SemiBold)
                                Text("${current.invoiceCount} invoices", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Supplier-wise comparative trends", fontWeight = FontWeight.SemiBold)
                    currentRows.forEach { current ->
                        val previous = previousRows.firstOrNull { it.supplierName.equals(current.supplierName, true) }
                        val prior = previous ?: SupplierAccountingMetrics(
                            current.supplierName, 0L, 0L, 0L, 0L, 0L,
                            supplierAgeingBucketRows(emptyList()), 0L, 0L, 0L
                        )
                        val currentMargin = if (current.attributedSalesPaise > 0L)
                            current.estimatedGrossProfitPaise.toDouble() * 100.0 / current.attributedSalesPaise else 0.0
                        val priorMargin = if (prior.attributedSalesPaise > 0L)
                            prior.estimatedGrossProfitPaise.toDouble() * 100.0 / prior.attributedSalesPaise else 0.0
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(current.supplierName, fontWeight = FontWeight.Bold)
                            Text("Purchases ${money(current.purchaseTurnoverPaise)} (${percentageChange(current.purchaseTurnoverPaise, prior.purchaseTurnoverPaise)})",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Payments ${money(current.paymentsPaise)} (${percentageChange(current.paymentsPaise, prior.paymentsPaise)}) • Outstanding ${money(current.outstandingPaise)} (${percentageChange(current.outstandingPaise, prior.outstandingPaise)})",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Ageing buckets (current vs previous): " + current.ageingBuckets.joinToString(" • ") { bucket ->
                                val old = prior.ageingBuckets.firstOrNull { it.label == bucket.label }?.amountPaise ?: 0L
                                "${bucket.label}: ${money(bucket.amountPaise)} vs ${money(old)}"
                            }, style = MaterialTheme.typography.bodySmall)
                            Text("Previous profit ${money(prior.estimatedGrossProfitPaise)} • Current profit ${money(current.estimatedGrossProfitPaise)} • Change ${percentageChange(current.estimatedGrossProfitPaise, prior.estimatedGrossProfitPaise)}",
                                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                            Text("Attributed sales ${money(current.attributedSalesPaise)} vs ${money(prior.attributedSalesPaise)} previous • Estimated cost ${money(current.estimatedCostPaise)} vs ${money(prior.estimatedCostPaise)}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Estimated margin ${"%.1f".format(Locale.US, currentMargin)}% vs ${"%.1f".format(Locale.US, priorMargin)}% previous",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                    if (currentRows.isEmpty()) Text("No supplier records for this report.")
                    Text(
                        "Purchase turnover and payments are activity within the selected date range. Outstanding and ageing are balances as of each period end. Profitability is estimated using purchase history available up to the period end and sales within the selected period; GST is excluded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DashboardMetricCard("Suppliers", suppliers.size.toString(), "Directory records", Modifier.weight(1f))
            DashboardMetricCard("Outstanding", money(totalOutstanding), "All suppliers", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DashboardMetricCard("Purchases", money(totalPurchases), "All recorded invoices", Modifier.weight(1f))
            DashboardMetricCard("Payments", money(totalPayments), "Paid to suppliers", Modifier.weight(1f))
        }
        val ageingBuckets = supplierAgeingBucketRows(ageingInvoices)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Supplier invoice ageing", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("Outstanding invoices as of ${LocalDate.now()}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onExportAgeing) { Text("CSV") }
                        OutlinedButton(onClick = onExportAgeingPdf) { Text("PDF") }
                    }
                }
                ageingBuckets.forEach { bucket ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(bucket.label, fontWeight = FontWeight.SemiBold)
                            Text("${bucket.invoiceCount} outstanding invoice(s)", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(money(bucket.amountPaise), fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total outstanding across aged invoices", fontWeight = FontWeight.Bold)
                    Text(money(ageingInvoices.sumOf { it.outstandingPaise }), fontWeight = FontWeight.Bold)
                }
                if (ageingInvoices.isEmpty()) Text("No outstanding supplier invoices.")
                else {
                    Text("Oldest outstanding invoices", fontWeight = FontWeight.SemiBold)
                    ageingInvoices.take(8).forEach { invoice ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("${invoice.supplierName} • ${invoice.invoiceNumber}", style = MaterialTheme.typography.bodySmall)
                                Text("${invoice.invoiceDate} • ${invoice.ageDays} days old", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(money(invoice.outstandingPaise), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text("Age is calculated from the purchase invoice date to today. Returns and payments allocated to an invoice reduce its aged outstanding balance. Older unallocated payments are not assigned to invoices.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Supplier directory & ledger reports", fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onExport, enabled = suppliers.isNotEmpty()) { Text("Export CSV") }
        }
        if (suppliers.isEmpty()) {
            Text("No suppliers yet. Tap + to add your first supplier.")
        } else {
            suppliers.forEach { supplier ->
                val supplierPurchases = purchases.filter { it.supplierName.equals(supplier.name, ignoreCase = true) }
                val supplierReturns = returns.filter { it.supplierName.equals(supplier.name, ignoreCase = true) }
                val supplierPays = payments.filter { it.supplierId == supplier.id }
                val supplierAllocations = allocations.filter { allocation ->
                    supplierPurchases.any { it.id == allocation.purchaseId }
                }
                val purchaseValue = supplierPurchases.sumOf { it.totalPaise }
                val returnValue = supplierReturns.sumOf { it.totalPaise }
                val paidValue = supplierPays.sumOf { it.amountPaise }
                val allocatedPaymentValue = supplierAllocations.sumOf { it.amountPaise }
                val unallocatedPaymentValue = (paidValue - allocatedPaymentValue).coerceAtLeast(0L)
                val outstanding = purchaseValue - returnValue - paidValue
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(supplier.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                if (supplier.gstin.isNotBlank()) Text("GSTIN: ${supplier.gstin}", style = MaterialTheme.typography.bodySmall)
                                if (supplier.phone.isNotBlank()) Text("Phone: ${supplier.phone}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(money(outstanding), fontWeight = FontWeight.Bold)
                        }
                        Text("Purchases ${money(purchaseValue)} • Returns ${money(returnValue)}", style = MaterialTheme.typography.bodySmall)
                        Text("Payments ${money(paidValue)} • ${supplierPurchases.size} invoices", style = MaterialTheme.typography.bodySmall)
                        if (unallocatedPaymentValue > 0L) {
                            Text("Unallocated older payments: ${money(unallocatedPaymentValue)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        Text("Invoice payment status", fontWeight = FontWeight.SemiBold)
                        supplierPurchases.sortedByDescending { it.createdAt }.forEach { invoice ->
                            val invoiceReturned = supplierReturns.filter { it.purchaseId == invoice.id }.sumOf { it.totalPaise }
                            val invoicePaid = supplierAllocations.filter { it.purchaseId == invoice.id }.sumOf { it.amountPaise }
                            val invoiceDue = (invoice.totalPaise - invoiceReturned - invoicePaid).coerceAtLeast(0L)
                            val status = when {
                                invoiceDue == 0L -> "PAID"
                                invoicePaid > 0L -> "PARTIALLY PAID"
                                else -> "UNPAID"
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(invoice.supplierInvoiceNumber, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                    Text("Bill ${money(invoice.totalPaise)} • Paid ${money(invoicePaid)} • Returns ${money(invoiceReturned)}",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(status, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall,
                                        color = when (status) {
                                            "PAID" -> MaterialTheme.colorScheme.primary
                                            "PARTIALLY PAID" -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.error
                                        })
                                    Text("Due ${money(invoiceDue)}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (supplier.address.isNotBlank()) Text(supplier.address, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onAddPayment(supplier) }) { Text("Record payment") }
                            OutlinedButton(onClick = { statementSupplier = supplier }) { Text("Statement PDF") }
                        }
                        HorizontalDivider()
                        Text("Ledger entries", fontWeight = FontWeight.SemiBold)
                        val ledger = buildList {
                            supplierPurchases.forEach { add(Triple(it.createdAt, "Purchase • ${it.supplierInvoiceNumber}", it.totalPaise)) }
                            supplierReturns.filter { it.supplierName.equals(supplier.name, true) }.forEach {
                                add(Triple(it.createdAt, "Return • ${it.creditNoteNumber}", -it.totalPaise))
                            }
                            supplierPays.forEach { payment ->
                                val invoiceRefs = allocations.filter { it.paymentId == payment.id }
                                    .mapNotNull { allocation -> supplierPurchases.firstOrNull { it.id == allocation.purchaseId }?.supplierInvoiceNumber }
                                val allocationLabel = if (invoiceRefs.isEmpty()) "Unallocated" else "Invoices ${invoiceRefs.joinToString(", ")}"
                                add(Triple(payment.createdAt, "Payment • ${payment.paymentMode} ${payment.reference} • $allocationLabel", -payment.amountPaise))
                            }
                        }.sortedByDescending { it.first }
                        var running = 0L
                        val chronological = ledger.sortedBy { it.first }
                        val withBalance = chronological.map { row ->
                            running += row.third
                            row to running
                        }.reversed()
                        if (withBalance.isEmpty()) Text("No ledger transactions.")
                        withBalance.take(12).forEach { (row, balance) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.first.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() },
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(row.second, style = MaterialTheme.typography.bodySmall)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(money(row.third), fontWeight = FontWeight.SemiBold)
                                    Text("Bal. ${money(balance)}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("Ledger convention: supplier invoices increase payable balance; returns and payments reduce it.", style = MaterialTheme.typography.bodySmall)
        Text("Invoice status is based on payments explicitly allocated to each invoice. Older payments recorded before invoice allocation remain unallocated and affect supplier-level outstanding only.", style = MaterialTheme.typography.bodySmall)
        Text("Returns reduce the balance of their original supplier invoice. Supplier-level outstanding = purchases - returns - all payments.", style = MaterialTheme.typography.bodySmall)
    }
    statementSupplier?.let { supplier ->
        SupplierStatementDateDialog(
            supplier = supplier,
            onDismiss = { statementSupplier = null },
            onGenerate = { from, to ->
                onGenerateStatement(SupplierStatementRequest(supplier, from, to))
                statementSupplier = null
            }
        )
    }
}

private fun buildSupplierLedgerCsv(
    suppliers: List<Supplier>,
    purchases: List<Purchase>,
    returns: List<PurchaseReturn>,
    payments: List<SupplierPayment>
): String {
    fun q(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
    fun rupees(paise: Long) = "%.2f".format(Locale.US, paise / 100.0)
    val out = StringBuilder("Supplier,Date,Transaction,Reference,Debit,Credit,Running Balance,Payment Method,Notes\n")
    suppliers.forEach { supplier ->
        val entries = buildList {
            purchases.filter { it.supplierName.equals(supplier.name, true) }.forEach {
                add(Triple(it.createdAt, "Purchase invoice", it.supplierInvoiceNumber) to (it.totalPaise to 0L))
            }
            returns.filter { it.supplierName.equals(supplier.name, true) }.forEach {
                add(Triple(it.createdAt, "Purchase return", it.creditNoteNumber) to (0L to it.totalPaise))
            }
            payments.filter { it.supplierId == supplier.id }.forEach {
                add(Triple(it.createdAt, "Payment • ${it.paymentMode}", it.reference) to (0L to it.amountPaise))
            }
        }.sortedBy { it.first.first }
        var balance = 0L
        entries.forEach { entry ->
            val (dateAndType, amounts) = entry
            val (timestamp, type, reference) = dateAndType
            val (debit, credit) = amounts
            balance += debit - credit
            val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            val payment = payments.firstOrNull { it.supplierId == supplier.id && it.createdAt == timestamp && "Payment • ${it.paymentMode}" == type }
            val notes = payment?.notes.orEmpty()
            out.append(listOf(
                q(supplier.name), q(date), q(type), q(reference), rupees(debit), rupees(credit),
                rupees(balance), q(payment?.paymentMode.orEmpty()), q(notes)
            ).joinToString(",")).append('\n')
        }
    }
    return out.toString()
}

private fun Sale.toInvoiceSnapshot(lines: List<SaleItem>) = InvoiceSnapshot(
    invoiceNumber = invoiceNumber,
    createdAt = createdAt,
    sellerName = sellerName,
    sellerAddress = sellerAddress,
    sellerGstin = sellerGstin,
    sellerStateCode = sellerStateCode,
    customerName = customerName,
    customerAddress = customerAddress,
    customerGstin = customerGstin,
    customerStateCode = customerStateCode,
    taxType = taxType,
    items = lines,
    subtotalPaise = subtotalPaise,
    cgstPaise = cgstPaise,
    sgstPaise = sgstPaise,
    igstPaise = igstPaise,
    totalPaise = totalPaise
)

@Composable
private fun InvoiceHistoryScreen(
    invoices: List<Sale>,
    search: String,
    onSearchChange: (String) -> Unit,
    selectedInvoice: Sale?,
    items: List<SaleItem>,
    onOpen: (Sale) -> Unit,
    onBack: () -> Unit,
    onExportPdf: (Sale, List<SaleItem>) -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale("en", "IN")) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selectedInvoice == null) {
            Text("Invoice history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Search by invoice number, date (YYYY-MM-DD or DD-MM-YYYY), customer name or GSTIN.")
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search invoices") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.isNotBlank()) TextButton(onClick = { onSearchChange("") }) { Text("Clear") }
                },
                singleLine = true
            )
            Text("${invoices.size} invoice(s)", style = MaterialTheme.typography.bodySmall)
            if (invoices.isEmpty()) {
                Text(if (search.isBlank()) "No invoices have been created yet." else "No invoices match this search.")
            } else {
                invoices.forEach { sale ->
                    Card(
                        onClick = { onOpen(sale) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(sale.invoiceNumber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(dateFormat.format(java.util.Date(sale.createdAt)))
                            Text(sale.customerName.ifBlank { "Walk-in customer" })
                            if (sale.customerGstin.isNotBlank()) Text("GSTIN: ${sale.customerGstin}")
                            Text("Total: ${money(sale.totalPaise)}", fontWeight = FontWeight.SemiBold)
                            Text(
                                when (sale.taxType) {
                                    "INTRA_STATE" -> "CGST + SGST"
                                    "INTER_STATE" -> "IGST"
                                    else -> "Legacy invoice"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Invoice details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(selectedInvoice.invoiceNumber, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(dateFormat.format(java.util.Date(selectedInvoice.createdAt)))
            HorizontalDivider()
            Text("SUPPLIER", fontWeight = FontWeight.Bold)
            Text(selectedInvoice.sellerName.ifBlank { "Supplier details not recorded" })
            Text(selectedInvoice.sellerAddress)
            Text("GSTIN: ${selectedInvoice.sellerGstin.ifBlank { "Not recorded" }}")
            Text("State code: ${selectedInvoice.sellerStateCode.ifBlank { "Not recorded" }}")
            HorizontalDivider()
            Text("CUSTOMER", fontWeight = FontWeight.Bold)
            Text(selectedInvoice.customerName.ifBlank { "Walk-in customer" })
            Text(selectedInvoice.customerAddress)
            Text("GSTIN: ${selectedInvoice.customerGstin.ifBlank { "Unregistered / not recorded" }}")
            Text("Place of supply: ${selectedInvoice.customerStateCode.ifBlank { "Not recorded" }}")
            HorizontalDivider()
            Text("ITEMS (${items.size})", fontWeight = FontWeight.Bold)
            if (items.isEmpty()) Text("No line-item records were found for this invoice.")
            items.forEach { line ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(line.productName, fontWeight = FontWeight.SemiBold)
                        Text("HSN/SAC: ${line.hsnCode.ifBlank { "—" }} • Barcode: ${line.barcode.ifBlank { "—" }}")
                        Text("Qty ${line.quantity} × ${money(line.unitPricePaise)} • GST ${line.gstRate}%")
                        Text("Taxable: ${money(line.lineSubtotalPaise)}")
                        if (selectedInvoice.taxType == "INTRA_STATE") {
                            Text("CGST: ${money(line.cgstPaise)} • SGST: ${money(line.sgstPaise)}")
                        } else if (selectedInvoice.taxType == "INTER_STATE") {
                            Text("IGST: ${money(line.igstPaise)}")
                        } else {
                            Text("GST recorded: ${money(line.gstPaise)}")
                        }
                        Text("Line total: ${money(line.lineTotalPaise)}", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            HorizontalDivider()
            Text("Taxable subtotal: ${money(selectedInvoice.subtotalPaise)}")
            when (selectedInvoice.taxType) {
                "INTRA_STATE" -> {
                    Text("CGST: ${money(selectedInvoice.cgstPaise)}")
                    Text("SGST: ${money(selectedInvoice.sgstPaise)}")
                }
                "INTER_STATE" -> Text("IGST: ${money(selectedInvoice.igstPaise)}")
                else -> Text("GST: ${money(selectedInvoice.gstPaise)}")
            }
            Text("Grand total: ${money(selectedInvoice.totalPaise)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(
                onClick = { onExportPdf(selectedInvoice, items) },
                enabled = items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reprint / Export GST invoice PDF") }
            if (selectedInvoice.taxType == "LEGACY") {
                Text("This is an older invoice. Original line tax is retained, but the historical CGST/SGST/IGST split was not recorded.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private data class InvoiceSnapshot(
    val invoiceNumber: String,
    val createdAt: Long,
    val sellerName: String,
    val sellerAddress: String,
    val sellerGstin: String,
    val sellerStateCode: String,
    val customerName: String,
    val customerAddress: String,
    val customerGstin: String,
    val customerStateCode: String,
    val taxType: String,
    val items: List<SaleItem>,
    val subtotalPaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val totalPaise: Long
)

private fun writeInvoicePdf(invoice: InvoiceSnapshot, output: java.io.OutputStream) {
    val document = PdfDocument()
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; isFakeBoldText = true }
    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; isFakeBoldText = true }
    val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f }
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f }
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.7f }
    val date = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm", Locale("en", "IN")).format(java.util.Date(invoice.createdAt))
    var pageNumber = 0
    var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
    var canvas = page.canvas
    var y = 38f

    fun text(value: String, x: Float, yy: Float, paint: Paint = normal) { canvas.drawText(value.take(100), x, yy, paint) }
    fun rule(yy: Float) { canvas.drawLine(28f, yy, 567f, yy, linePaint) }
    fun header() {
        text("TAX INVOICE", 28f, y, title); y += 24f
        text("Invoice No: ${invoice.invoiceNumber}", 28f, y, bold)
        text("Date: $date", 390f, y, normal); y += 18f
        rule(y); y += 15f
        text("SUPPLIER", 28f, y, bold); text("BILL TO", 310f, y, bold); y += 14f
        text(invoice.sellerName, 28f, y, bold); text(invoice.customerName.ifBlank { "Walk-in customer" }, 310f, y, bold); y += 13f
        invoice.sellerAddress.split("\n").forEach { text(it, 28f, y, small); y += 11f }
        invoice.customerAddress.split("\n").filter { it.isNotBlank() }.forEachIndexed { idx, value -> text(value, 310f, 64f + idx * 11f, small) }
        y = maxOf(y, 96f)
        text("GSTIN: ${invoice.sellerGstin.ifBlank { "Unregistered" }}", 28f, y, small)
        text("GSTIN: ${invoice.customerGstin.ifBlank { "Unregistered / B2C" }}", 310f, y, small); y += 12f
        text("State code: ${invoice.sellerStateCode}", 28f, y, small)
        text("Place of supply state code: ${invoice.customerStateCode}", 310f, y, small); y += 16f
        rule(y); y += 15f
        text("Item", 28f, y, bold); text("HSN", 220f, y, bold); text("Qty", 278f, y, bold)
        text("Rate", 320f, y, bold); text("Taxable", 385f, y, bold); text("GST", 465f, y, bold); y += 13f
        rule(y); y += 14f
    }
    header()
    invoice.items.forEach { item ->
        if (y > 755f) {
            document.finishPage(page)
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
            canvas = page.canvas
            y = 38f
            text("TAX INVOICE — ${invoice.invoiceNumber} (continued)", 28f, y, title); y += 28f
            text("Item", 28f, y, bold); text("HSN", 220f, y, bold); text("Qty", 278f, y, bold)
            text("Rate", 320f, y, bold); text("Taxable", 385f, y, bold); text("GST", 465f, y, bold); y += 16f
            rule(y); y += 14f
        }
        text(item.productName, 28f, y, normal)
        text(item.hsnCode.ifBlank { "—" }, 220f, y, small)
        text(item.quantity.toString(), 278f, y)
        text(money(item.unitPricePaise), 320f, y, small)
        text(money(item.lineSubtotalPaise), 385f, y, small)
        text("${item.gstRate}%", 465f, y, small)
        y += 17f
        when (invoice.taxType) {
            "INTRA_STATE" -> text("CGST ${money(item.cgstPaise)}  SGST ${money(item.sgstPaise)}", 42f, y, small)
            "INTER_STATE" -> text("IGST ${money(item.igstPaise)}", 42f, y, small)
            else -> text("GST ${money(item.gstPaise)} (historical split unavailable)", 42f, y, small)
        }
        y += 16f
    }
    if (y > 700f) {
        document.finishPage(page)
        page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
        canvas = page.canvas
        y = 45f
    }
    rule(y); y += 22f
    text("Taxable value: ${money(invoice.subtotalPaise)}", 350f, y, bold); y += 17f
    if (invoice.taxType == "INTRA_STATE") {
        text("CGST: ${money(invoice.cgstPaise)}", 350f, y); y += 16f
        text("SGST: ${money(invoice.sgstPaise)}", 350f, y); y += 16f
    } else if (invoice.taxType == "INTER_STATE") {
        text("IGST: ${money(invoice.igstPaise)}", 350f, y); y += 16f
    } else {
        text("GST: ${money(invoice.items.sumOf { it.gstPaise })}", 350f, y); y += 16f
    }
    text("Invoice total: ${money(invoice.totalPaise)}", 350f, y, title); y += 30f
    text("Tax treatment: ${when (invoice.taxType) { "INTRA_STATE" -> "CGST + SGST (intra-state)"; "INTER_STATE" -> "IGST (inter-state)"; else -> "Historical GST split not recorded" }}", 28f, y, normal)
    y += 30f
    text("This invoice is generated by AutoStock. Verify supplier, recipient, HSN and tax details before issue.", 28f, y, small)
    document.finishPage(page)
    document.writeTo(output)
    document.close()
}

private data class CartLine(val product: Product, val quantity: Int)
private fun gstFor(taxablePaise: Long, rate: Double): Long = (taxablePaise * rate / 100.0).roundToLong()

@Composable
private fun BarcodeScannerScreen(catalogue: List<Product>, onAddToBill: (Product) -> Unit, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var scanned by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL).build()) }
    val currentScanned by rememberUpdatedState(scanned)
    DisposableEffect(scanner) { onDispose { scanner.close() } }
    val matches = remember(scanned, catalogue) { scanned?.let { InterchangeEngine.search(it, catalogue).matches } ?: emptyList() }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Scan a part to add it to a bill", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (!granted) Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera access") }
        else AndroidView(modifier = Modifier.fillMaxWidth().height(230.dp), factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val executor = Executors.newSingleThreadExecutor()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null) proxy.close()
                    else scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val value = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                            if (value != null && currentScanned == null) scanned = value
                        }.addOnFailureListener { scanError = it.localizedMessage }
                        .addOnCompleteListener { proxy.close() }
                }
                try { provider.unbindAll(); provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                catch (e: Exception) { scanError = e.localizedMessage ?: "Camera could not start." }
            }, ContextCompat.getMainExecutor(ctx))
            view
        })
        scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        scanned?.let { code ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Scanned: $code", fontWeight = FontWeight.Bold)
                TextButton(onClick = { scanned = null; scanError = null }) { Text("Scan again") }
            }
            if (matches.isEmpty()) Text("No catalogue match. Save this barcode or part number against a product first.")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(matches, key = { it.product.id }) { match ->
                    val p = match.product
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.name, fontWeight = FontWeight.Bold)
                            Text("${p.brand} • ${p.category}")
                            Text("OEM: ${p.oemNumber.ifBlank { "—" }}")
                            Text("Aftermarket: ${p.aftermarketNumbers.replace("\n", ", ").ifBlank { "—" }}")
                            Text("Vehicles: ${listOf(p.vehicleMake, p.vehicleModel, p.vehicleVariant).filter { it.isNotBlank() }.joinToString(" ").ifBlank { p.vehicleCompatibility.ifBlank { "Not recorded" } }}")
                            Text("Stock: ${p.quantity}")
                            Button(onClick = { onAddToBill(p) }, enabled = p.quantity > 0) { Text(if (p.quantity > 0) "Add 1 to bill" else "Out of stock") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BillingScreen(
    cart: List<CartLine>, customerName: String, onCustomerChange: (String) -> Unit,
    sellerName: String, onSellerNameChange: (String) -> Unit,
    sellerAddress: String, onSellerAddressChange: (String) -> Unit,
    customerAddress: String, onCustomerAddressChange: (String) -> Unit,
    sellerGstin: String, onSellerGstinChange: (String) -> Unit,
    sellerStateCode: String, onSellerStateChange: (String) -> Unit,
    customerGstin: String, onCustomerGstinChange: (String) -> Unit,
    customerStateCode: String, onCustomerStateChange: (String) -> Unit,
    onExportPdf: () -> Unit,
    onQuantityChange: (Long, Int) -> Unit, onRemove: (Long) -> Unit, onCheckout: () -> Unit,
    message: String?, onDismissMessage: () -> Unit, modifier: Modifier = Modifier
) {
    val subtotal = cart.sumOf { it.product.sellingPricePaise * it.quantity }
    val gst = cart.sumOf { gstFor(it.product.sellingPricePaise * it.quantity, it.product.gstRate) }
    val sameState = sellerStateCode.length == 2 && sellerStateCode == customerStateCode
    val cgst = if (sameState) cart.sumOf { gstFor(it.product.sellingPricePaise * it.quantity, it.product.gstRate) / 2 } else 0L
    val sgst = if (sameState) gst - cgst else 0L
    val igst = if (sameState) 0L else gst
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Customer bill", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(sellerName, onSellerNameChange, label = { Text("Supplier / shop legal name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(sellerAddress, onSellerAddressChange, label = { Text("Supplier address *") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(sellerGstin, onSellerGstinChange, label = { Text("Supplier GSTIN (required for GST tax invoice)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(sellerStateCode, onSellerStateChange, label = { Text("Shop state code (2 digits)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(customerName, onCustomerChange, label = { Text("Customer name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(customerAddress, onCustomerAddressChange, label = { Text("Customer address (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(customerGstin, onCustomerGstinChange, label = { Text("Customer GSTIN (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(customerStateCode, onCustomerStateChange, label = { Text("Customer place-of-supply state code") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text(if (sellerStateCode.isNotBlank() && sellerStateCode == customerStateCode) "Tax type: CGST + SGST (intra-state)" else "Tax type: IGST (inter-state or state not yet matched)", style = MaterialTheme.typography.bodySmall)
        if (cart.isEmpty()) Text("Bill is empty. Scan a part and tap Add to bill.")
        else cart.forEach { line ->
            val p = line.product
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, fontWeight = FontWeight.Bold)
                    Text("${p.brand} • HSN ${p.hsnCode.ifBlank { "not set" }} • ${money(p.sellingPricePaise)} each • GST ${p.gstRate}%")
                    Text("Available: ${p.quantity}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onQuantityChange(p.id, line.quantity - 1) }) { Text("−") }
                        Text("Qty ${line.quantity}")
                        TextButton(onClick = { onQuantityChange(p.id, line.quantity + 1) }, enabled = line.quantity < p.quantity) { Text("+") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onRemove(p.id) }) { Text("Remove") }
                    }
                    Text("Line total incl. GST: ${money(p.sellingPricePaise * line.quantity + gstFor(p.sellingPricePaise * line.quantity, p.gstRate))}")
                }
            }
        }
        HorizontalDivider()
        Text("Taxable subtotal: ${money(subtotal)}")
        if (sameState) {
            Text("CGST: ${money(cgst)}")
            Text("SGST: ${money(sgst)}")
        } else {
            Text("IGST: ${money(igst)}")
        }
        Text("Total GST: ${money(gst)}")
        Text("Grand total: ${money(subtotal + gst)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Button(onClick = onCheckout, enabled = cart.isNotEmpty() && cart.all { it.quantity <= it.product.quantity }, modifier = Modifier.fillMaxWidth()) {
            Text("Complete sale & deduct stock")
        }
        OutlinedButton(onClick = onExportPdf, modifier = Modifier.fillMaxWidth()) { Text("Save last GST invoice as PDF") }
        message?.let { AlertDialog(onDismissRequest = onDismissMessage, confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } }, title = { Text("Billing") }, text = { Text(it) }) }
    }
}

@Composable
private fun InterchangeScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    catalogue: List<Product>,
    modifier: Modifier = Modifier
) {
    val result = remember(query, catalogue) { InterchangeEngine.search(query, catalogue) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Automotive part interchange", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Search an OEM or aftermarket number to find linked brands and recorded vehicle fitment.")
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Enter OEM / aftermarket part number") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true
        )
        if (query.isBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("How interchange works", fontWeight = FontWeight.Bold)
                    Text("AutoStock links catalogue products when they share an OEM or aftermarket number. Add accurate cross-reference numbers to each product record to build your shop's interchange network.")
                    Text("Vehicle compatibility comes from your catalogue records; AutoStock does not assume fitment from a similar-looking part number.")
                }
            }
        } else if (!result.searchedNumberFound) {
            Text("No exact number match found. Check the spelling or add this number to a product's OEM / aftermarket fields.")
        } else {
            Text("${result.matches.size} linked catalogue item(s)", fontWeight = FontWeight.SemiBold)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(result.matches, key = { it.product.id }) { match ->
                    val p = match.product
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                if (match.isDirectMatch) {
                                    AssistChip(onClick = {}, label = { Text("Direct match") })
                                } else {
                                    AssistChip(onClick = {}, label = { Text("Linked reference") })
                                }
                            }
                            Text("Brand: ${p.brand.ifBlank { "Not specified" }}", style = MaterialTheme.typography.bodyMedium)
                            Text("OEM: ${p.oemNumber.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                            Text("Aftermarket: ${InterchangeEngine.allNumbers(p).filter { it != p.oemNumber }.joinToString().ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                            Text("Compatible vehicles: ${p.vehicleCompatibility.replace("\n", ", ").ifBlank { "Not recorded" }}", style = MaterialTheme.typography.bodySmall)
                            Text("Category: ${p.category.ifBlank { "—" }}  •  Stock: ${p.quantity}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(count: Int, lowStock: Int, recent: List<Product>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Shop overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Catalogue items", count.toString(), Modifier.weight(1f))
            MetricCard("Low stock", lowStock.toString(), Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Automotive coverage", fontWeight = FontWeight.Bold)
                Text("Cars • Commercial vehicles • Tractors • Two-wheelers • Three-wheelers")
                Text("Search by product name, OEM number, aftermarket number, brand, vehicle or barcode.")
            }
        }
        Text("Recently added", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (recent.isEmpty()) Text("Your catalogue is empty. Tap + to add your first part.")
        else recent.forEach { ProductCard(it) }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label)
        }
    }
}

@Composable
private fun InventoryScreen(
    products: List<Product>, search: String, onSearch: (String) -> Unit,
    onEdit: (Product) -> Unit, onDelete: (Product) -> Unit,
    onImport: () -> Unit, onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth(),
            label = { Text("Search part no., brand, vehicle, barcode…") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Import Excel") }
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("Export Excel") }
        }
        Spacer(Modifier.height(10.dp))
        Text("${products.size} matching item(s)", style = MaterialTheme.typography.labelMedium)
        if (products.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No matching parts found") }
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(products, key = { it.id }) { product ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(product.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                Text("Qty ${product.quantity}", style = MaterialTheme.typography.labelLarge)
                            }
                            Text("Brand: ${product.brand}  •  Category: ${product.category.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                            Text("OEM: ${product.oemNumber.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                            if (product.aftermarketNumbers.isNotBlank())
                                Text("Aftermarket: ${product.aftermarketNumbers.replace("\n", ", ")}", style = MaterialTheme.typography.bodySmall)
                            if (product.vehicleCompatibility.isNotBlank())
                                Text("Fits: ${product.vehicleCompatibility.replace("\n", ", ")}", style = MaterialTheme.typography.bodySmall)
                            Text("Barcode: ${product.barcode.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                            Text("Buy ${money(product.purchasePricePaise)}  •  Sell ${money(product.sellingPricePaise)}  •  GST ${product.gstRate}%", style = MaterialTheme.typography.labelMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onEdit(product) }) { Text("Edit") }
                                TextButton(onClick = { onDelete(product) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun money(paise: Long): String =
    NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(paise / 100.0)


private fun normalizePartNumber(value: String): String =
    value.trim().uppercase(Locale.ROOT)

private fun duplicateWarnings(candidate: Product, existingProducts: List<Product>): List<String> {
    val others = existingProducts.filter { it.id != candidate.id }
    val warnings = mutableListOf<String>()

    val candidateOem = normalizePartNumber(candidate.oemNumber)
    if (candidateOem.isNotBlank()) {
        val matches = others.filter { normalizePartNumber(it.oemNumber) == candidateOem }
        if (matches.isNotEmpty()) {
            warnings += "OEM number '${candidate.oemNumber}' is already used by: ${matches.joinToString { it.name }}."
        }
    }

    val candidateAftermarket = candidate.aftermarketNumbers
        .split(",", "\\n")
        .map { normalizePartNumber(it) }
        .filter { it.isNotBlank() }
        .distinct()
    for (number in candidateAftermarket) {
        val matches = others.filter { product ->
            product.aftermarketNumbers.split(",", "\\n").any { normalizePartNumber(it) == number } ||
                normalizePartNumber(product.oemNumber) == number
        }
        if (matches.isNotEmpty()) {
            warnings += "Aftermarket number '$number' is already listed for: ${matches.joinToString { it.name }}."
        }
    }

    val candidateBarcode = candidate.barcode.trim()
    if (candidateBarcode.isNotBlank()) {
        val matches = others.filter { it.barcode.trim() == candidateBarcode }
        if (matches.isNotEmpty()) {
            warnings += "Barcode '$candidateBarcode' is already assigned to: ${matches.joinToString { it.name }}."
        }
    }
    return warnings
}

private data class FormErrors(
    val name: String? = null,
    val oem: String? = null,
    val aftermarket: String? = null,
    val brand: String? = null,
    val compatibility: String? = null,
    val barcode: String? = null,
    val quantity: String? = null,
    val purchase: String? = null,
    val selling: String? = null,
    val gst: String? = null
) {
    val hasErrors: Boolean get() = listOf(name, oem, aftermarket, brand, compatibility, barcode, quantity, purchase, selling, gst).any { it != null }
}

private val PART_NUMBER_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9./_-]{0,63}$")
private val BARCODE_REGEX = Regex("^(?:[0-9]{8}|[0-9]{12,14})$")
private val MONEY_REGEX = Regex("^\\d{1,9}(?:\\.\\d{1,2})?$")
private val RATE_REGEX = Regex("^\\d{1,3}(?:\\.\\d{1,2})?$")

private fun validateForm(
    name: String, oem: String, aftermarket: String, brand: String,
    compatibility: String, barcode: String, quantity: String,
    purchase: String, selling: String, gst: String
): FormErrors {
    val aftermarketItems = aftermarket.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
    val errors = FormErrors(
        name = if (name.isBlank()) "Product name is required." else if (name.trim().length > 100) "Maximum 100 characters." else null,
        oem = if (oem.isNotBlank() && !PART_NUMBER_REGEX.matches(oem.trim())) "Use letters, numbers, ., /, _ or - (max 64 characters)." else null,
        aftermarket = if (aftermarketItems.any { !PART_NUMBER_REGEX.matches(it) }) "Each number must use letters, numbers, ., /, _ or - (max 64 characters)." else null,
        brand = if (brand.isBlank()) "Brand / manufacturer is required." else if (brand.trim().length > 60) "Maximum 60 characters." else null,
        compatibility = if (compatibility.isBlank()) "Enter at least one compatible vehicle or model." else if (compatibility.trim().length > 500) "Maximum 500 characters." else null,
        barcode = if (barcode.isNotBlank() && !BARCODE_REGEX.matches(barcode.trim())) "Barcode must be 8, 12, 13 or 14 digits." else null,
        quantity = if (quantity.toIntOrNull() == null || quantity.toIntOrNull()!! < 0) "Enter a whole number of 0 or more." else null,
        purchase = if (!MONEY_REGEX.matches(purchase.trim())) "Enter a non-negative amount with up to 2 decimals." else null,
        selling = if (!MONEY_REGEX.matches(selling.trim())) "Enter a non-negative amount with up to 2 decimals." else null,
        gst = if (!RATE_REGEX.matches(gst.trim()) || (gst.toDoubleOrNull() ?: 101.0) > 100.0) "Enter a GST rate from 0 to 100%." else null
    )
    return errors
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductEditorDialog(
    existing: Product?,
    onDismiss: () -> Unit,
    onCheckDuplicates: suspend (Product) -> List<String>,
    onSave: (Product) -> Unit
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var oem by remember(existing?.id) { mutableStateOf(existing?.oemNumber.orEmpty()) }
    var aftermarket by remember(existing?.id) { mutableStateOf(existing?.aftermarketNumbers?.replace("\n", ", ").orEmpty()) }
    var brand by remember(existing?.id) { mutableStateOf(existing?.brand.orEmpty()) }
    var compatibility by remember(existing?.id) {
        mutableStateOf(existing?.vehicleCompatibility?.split("\n")?.filter { it != "Car" && it != "Commercial vehicle" && it != "Tractor" && it != "Two-wheeler" && it != "Three-wheeler" && it != "Universal" }?.joinToString(", ").orEmpty())
    }
    var category by remember(existing?.id) { mutableStateOf(existing?.category.orEmpty()) }
    var hsnCode by remember(existing?.id) { mutableStateOf(existing?.hsnCode.orEmpty()) }
    var vehicleMake by remember(existing?.id) { mutableStateOf(existing?.vehicleMake.orEmpty()) }
    var vehicleModel by remember(existing?.id) { mutableStateOf(existing?.vehicleModel.orEmpty()) }
    var vehicleVariant by remember(existing?.id) { mutableStateOf(existing?.vehicleVariant.orEmpty()) }
    var yearFrom by remember(existing?.id) { mutableStateOf(existing?.yearFrom?.toString().orEmpty()) }
    var yearTo by remember(existing?.id) { mutableStateOf(existing?.yearTo?.toString().orEmpty()) }
    var compatibilityNotes by remember(existing?.id) { mutableStateOf(existing?.compatibilityNotes.orEmpty()) }
    var barcode by remember(existing?.id) { mutableStateOf(existing?.barcode.orEmpty()) }
    var vehicleGroup by remember(existing?.id) {
        mutableStateOf(existing?.vehicleCompatibility?.split("\n")?.firstOrNull() ?: "Car")
    }
    var quantity by remember(existing?.id) { mutableStateOf(existing?.quantity?.toString() ?: "0") }
    var purchase by remember(existing?.id) { mutableStateOf(existing?.purchasePricePaise?.let { "%.2f".format(Locale.US, it / 100.0) } ?: "0.00") }
    var selling by remember(existing?.id) { mutableStateOf(existing?.sellingPricePaise?.let { "%.2f".format(Locale.US, it / 100.0) } ?: "0.00") }
    var gst by remember(existing?.id) { mutableStateOf(existing?.gstRate?.toString() ?: "18") }
    var attemptedSave by remember(existing?.id) { mutableStateOf(false) }
    var duplicateMessages by remember(existing?.id) { mutableStateOf<List<String>>(emptyList()) }
    var checkingDuplicates by remember(existing?.id) { mutableStateOf(false) }
    val dialogScope = rememberCoroutineScope()

    val errors = validateForm(name, oem, aftermarket, brand, compatibility, barcode, quantity, purchase, selling, gst)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add automotive part" else "Edit automotive part") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("Part identity", fontWeight = FontWeight.Bold)
                ValidatedField(name, { name = it }, "Part name *", null, attemptedSave && errors.name != null, errors.name)
                ValidatedField(oem, { oem = it }, "OEM number (optional)", null, attemptedSave && errors.oem != null, errors.oem)
                ValidatedField(aftermarket, { aftermarket = it }, "Aftermarket numbers (comma separated)", null, attemptedSave && errors.aftermarket != null, errors.aftermarket, singleLine = false)
                ValidatedField(brand, { brand = it }, "Brand / manufacturer *", null, attemptedSave && errors.brand != null, errors.brand)
                ValidatedField(category, { category = it }, "Part category", null, false, null)
                ValidatedField(hsnCode, { hsnCode = it.filter(Char::isLetterOrDigit).take(8) }, "HSN code (optional)", KeyboardType.Number, false, null)

                Text("Vehicle compatibility", fontWeight = FontWeight.Bold)
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = vehicleGroup, onValueChange = {}, readOnly = true,
                        label = { Text("Vehicle group") }, modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("Car", "Commercial vehicle", "Tractor", "Two-wheeler", "Three-wheeler", "Universal").forEach {
                            DropdownMenuItem(text = { Text(it) }, onClick = { vehicleGroup = it; expanded = false })
                        }
                    }
                }
                ValidatedField(compatibility, { compatibility = it }, "Legacy compatibility text (optional)", null, false, null, singleLine = false)
                Text("Structured vehicle fitment", fontWeight = FontWeight.Bold)
                ValidatedField(vehicleMake, { vehicleMake = it }, "Vehicle make (e.g. Tata)", null, false, null)
                ValidatedField(vehicleModel, { vehicleModel = it }, "Vehicle model (e.g. Intra V30)", null, false, null)
                ValidatedField(vehicleVariant, { vehicleVariant = it }, "Variant / engine / trim", null, false, null)
                ValidatedField(yearFrom, { yearFrom = it.filter(Char::isDigit).take(4) }, "Year from (optional)", KeyboardType.Number, false, null)
                ValidatedField(yearTo, { yearTo = it.filter(Char::isDigit).take(4) }, "Year to (optional)", KeyboardType.Number, false, null)
                ValidatedField(compatibilityNotes, { compatibilityNotes = it }, "Compatibility notes", null, false, null, singleLine = false)
                Text("Stock & pricing", fontWeight = FontWeight.Bold)
                ValidatedField(barcode, { barcode = it }, "Barcode (8, 12, 13 or 14 digits)", KeyboardType.Number, attemptedSave && errors.barcode != null, errors.barcode)
                ValidatedField(quantity, { quantity = it }, "Stock quantity *", KeyboardType.Number, attemptedSave && errors.quantity != null, errors.quantity)
                ValidatedField(purchase, { purchase = it }, "Purchase price (₹) *", KeyboardType.Decimal, attemptedSave && errors.purchase != null, errors.purchase)
                ValidatedField(selling, { selling = it }, "Selling price (₹) *", KeyboardType.Decimal, attemptedSave && errors.selling != null, errors.selling)
                ValidatedField(gst, { gst = it }, "GST rate (%) *", KeyboardType.Decimal, attemptedSave && errors.gst != null, errors.gst)
                Text("OEM and barcode are optional. Numbers and prices are validated before saving.", style = MaterialTheme.typography.bodySmall)
                duplicateMessages.forEach { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !checkingDuplicates, onClick = {
                attemptedSave = true
                duplicateMessages = emptyList()
                if (!errors.hasErrors) {
                    fun paise(value: String) = ((value.toBigDecimal()) * java.math.BigDecimal(100)).longValueExact()
                    val compatibleVehicles = (listOf(vehicleGroup) + compatibility.split(",").map { it.trim() }.filter { it.isNotBlank() })
                        .distinct().joinToString("\\n")
                    val candidate = Product(
                        id = existing?.id ?: 0,
                        name = name.trim(),
                        oemNumber = oem.trim(),
                        aftermarketNumbers = aftermarket.split(",", "\\n").map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString("\\n"),
                        brand = brand.trim(),
                        vehicleCompatibility = compatibleVehicles,
                        vehicleMake = vehicleMake.trim(),
                        vehicleModel = vehicleModel.trim(),
                        vehicleVariant = vehicleVariant.trim(),
                        yearFrom = yearFrom.toIntOrNull(),
                        yearTo = yearTo.toIntOrNull(),
                        compatibilityNotes = compatibilityNotes.trim(),
                        category = category.trim(),
                        hsnCode = hsnCode.trim(),
                        barcode = barcode.trim(),
                        quantity = quantity.toInt(),
                        purchasePricePaise = paise(purchase),
                        sellingPricePaise = paise(selling),
                        gstRate = gst.toDouble()
                    )
                    dialogScope.launch {
                        checkingDuplicates = true
                        val duplicates = onCheckDuplicates(candidate)
                        checkingDuplicates = false
                        if (duplicates.isEmpty()) onSave(candidate)
                        else duplicateMessages = duplicates
                    }
                }
            }) { Text(if (checkingDuplicates) "Checking…" else if (existing == null) "Save part" else "Save changes") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ValidatedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType?,
    isError: Boolean,
    error: String?,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard ?: KeyboardType.Text),
        isError = isError, supportingText = if (isError && error != null) ({ Text(error) }) else null,
        singleLine = singleLine
    )
}
