package com.autostock.app.io

import android.content.Context
import android.net.Uri
import com.autostock.app.data.Product
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ImportIssue(val row: Int, val field: String, val message: String)
data class ImportPreview(
    val headers: List<String>,
    val fieldMapping: Map<String, String>,
    val validProducts: List<Product>,
    val issues: List<ImportIssue>,
    val skippedDuplicates: Int
)

object XlsxCatalogue {
    private val aliases = mapOf(
        "name" to listOf("name", "product name", "part name", "description", "item name"),
        "oem" to listOf("oem", "oem number", "oem no", "oe number", "oe no", "part number"),
        "aftermarket" to listOf("aftermarket", "aftermarket numbers", "cross reference", "cross references", "alternate numbers"),
        "brand" to listOf("brand", "manufacturer", "make"),
        "compatibility" to listOf("vehicle compatibility", "compatibility", "vehicle", "vehicles", "fitment", "vehicle models"),
        "make" to listOf("vehicle make", "make", "manufacturer make"),
        "model" to listOf("vehicle model", "model"),
        "variant" to listOf("vehicle variant", "variant", "trim"),
        "yearFrom" to listOf("year from", "from year", "start year", "model year from"),
        "yearTo" to listOf("year to", "to year", "end year", "model year to"),
        "notes" to listOf("compatibility notes", "fitment notes", "notes"),
        "category" to listOf("category", "part category", "product category"),
        "hsn" to listOf("hsn", "hsn code", "hsn/sac", "sac code"),
        "barcode" to listOf("barcode", "ean", "upc", "ean/upc"),
        "quantity" to listOf("quantity", "qty", "stock", "stock quantity"),
        "purchase" to listOf("purchase price", "buy price", "cost", "cost price"),
        "selling" to listOf("selling price", "sale price", "mrp", "retail price"),
        "gst" to listOf("gst", "gst rate", "gst %", "tax rate")
    )

    fun read(context: Context, uri: Uri, existing: List<Product>): ImportPreview {
        val entries = unzip(context, uri)
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheet = entries["xl/worksheets/sheet1.xml"]
            ?: throw IllegalArgumentException("Workbook has no first worksheet.")
        val rows = parseSheet(sheet, shared)
        if (rows.isEmpty()) throw IllegalArgumentException("The worksheet is empty.")
        val headers = rows.first().map { it.trim() }
        val mapping = mutableMapOf<String, Int>()
        headers.forEachIndexed { index, header ->
            val normalized = normalizeHeader(header)
            aliases.forEach { (field, names) ->
                if (normalized in names.map(::normalizeHeader) && field !in mapping) mapping[field] = index
            }
        }
        if ("name" !in mapping) throw IllegalArgumentException("Could not map a required Product Name column. Add a header such as 'Product Name'.")

        val issues = mutableListOf<ImportIssue>()
        val accepted = mutableListOf<Product>()
        var duplicateCount = 0
        val known = existing.toMutableList()
        rows.drop(1).forEachIndexed { offset, cells ->
            val rowNo = offset + 2
            if (cells.all { it.isBlank() }) return@forEachIndexed
            fun value(field: String) = mapping[field]?.let { cells.getOrNull(it)?.trim().orEmpty() }.orEmpty()
            val name = value("name")
            val oem = value("oem")
            val aftermarket = value("aftermarket").split(",", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val brand = value("brand")
            val compatibility = value("compatibility").split(",", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val barcode = value("barcode")
            val quantity = value("quantity").ifBlank { "0" }.toIntOrNull()
            val purchase = value("purchase").ifBlank { "0" }.toBigDecimalOrNull()
            val selling = value("selling").ifBlank { "0" }.toBigDecimalOrNull()
            val gst = value("gst").ifBlank { "18" }.toDoubleOrNull()
            val rowIssues = mutableListOf<ImportIssue>()

            if (name.isBlank()) rowIssues += ImportIssue(rowNo, "Product Name", "Required value is missing.")
            if (oem.isNotBlank() && !PART_NO.matches(oem)) rowIssues += ImportIssue(rowNo, "OEM Number", "Invalid part-number format.")
            aftermarket.filterNot { PART_NO.matches(it) }.forEach { rowIssues += ImportIssue(rowNo, "Aftermarket Numbers", "Invalid number: $it") }
            if (brand.isBlank()) rowIssues += ImportIssue(rowNo, "Brand", "Required value is missing.")
            if (compatibility.isEmpty()) rowIssues += ImportIssue(rowNo, "Vehicle Compatibility", "At least one vehicle/model is required.")
            if (barcode.isNotBlank() && !BARCODE.matches(barcode)) rowIssues += ImportIssue(rowNo, "Barcode", "Must contain 8, 12, 13 or 14 digits.")
            if (quantity == null || quantity < 0) rowIssues += ImportIssue(rowNo, "Quantity", "Must be a whole number of 0 or more.")
            if (purchase == null || purchase < java.math.BigDecimal.ZERO || purchase.scale().coerceAtLeast(0) > 2) rowIssues += ImportIssue(rowNo, "Purchase Price", "Must be a non-negative amount with up to 2 decimals.")
            if (selling == null || selling < java.math.BigDecimal.ZERO || selling.scale().coerceAtLeast(0) > 2) rowIssues += ImportIssue(rowNo, "Selling Price", "Must be a non-negative amount with up to 2 decimals.")
            if (gst == null || gst !in 0.0..100.0) rowIssues += ImportIssue(rowNo, "GST Rate", "Must be between 0 and 100.")

            val candidate = if (rowIssues.isEmpty()) Product(
                name = name, oemNumber = oem,
                aftermarketNumbers = aftermarket.joinToString("\n"),
                brand = brand,
                vehicleCompatibility = compatibility.joinToString("\n"),
                vehicleMake = value("make"), vehicleModel = value("model"), vehicleVariant = value("variant"),
                yearFrom = value("yearFrom").toIntOrNull(), yearTo = value("yearTo").toIntOrNull(),
                compatibilityNotes = value("notes"),
                category = value("category"), hsnCode = value("hsn"), barcode = barcode,
                quantity = quantity!!,
                purchasePricePaise = purchase!!.movePointRight(2).longValueExact(),
                sellingPricePaise = selling!!.movePointRight(2).longValueExact(),
                gstRate = gst!!
            ) else null

            if (candidate != null) {
                val dup = findDuplicate(candidate, known)
                if (dup != null) {
                    duplicateCount++
                    issues += ImportIssue(rowNo, "Duplicate", dup)
                } else {
                    accepted += candidate
                    known += candidate
                }
            } else issues += rowIssues
        }
        val mappingLabels = mapping.mapValues { (_, index) -> headers.getOrNull(index).orEmpty() }
        return ImportPreview(headers, mappingLabels, accepted, issues, duplicateCount)
    }

    fun write(context: Context, uri: Uri, products: List<Product>) {
        val headers = listOf("Product Name", "OEM Number", "Aftermarket Numbers", "Brand", "Vehicle Compatibility", "Vehicle Make", "Vehicle Model", "Vehicle Variant", "Year From", "Year To", "Compatibility Notes", "Category", "HSN Code", "Barcode", "Stock Quantity", "Purchase Price", "Selling Price", "GST Rate")
        val rows = mutableListOf(headers)
        products.forEach { p ->
            rows += listOf(
                p.name, p.oemNumber, p.aftermarketNumbers.replace("\n", ", "), p.brand,
                p.vehicleCompatibility.replace("\n", ", "), p.vehicleMake, p.vehicleModel,
                p.vehicleVariant, p.yearFrom?.toString().orEmpty(), p.yearTo?.toString().orEmpty(),
                p.compatibilityNotes, p.category, p.hsnCode, p.barcode, p.quantity.toString(),
                "%.2f".format(Locale.US, p.purchasePricePaise / 100.0),
                "%.2f".format(Locale.US, p.sellingPricePaise / 100.0), p.gstRate.toString()
            )
        }
        context.contentResolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output).use { zip ->
                fun entry(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
                entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
                entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="AutoStock Catalogue" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
                val xml = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                rows.forEachIndexed { r, row ->
                    xml.append("<row r=\"").append(r + 1).append("\">")
                    row.forEachIndexed { c, value ->
                        xml.append("<c r=\"").append(columnName(c + 1)).append(r + 1).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        xml.append(escapeXml(value))
                        xml.append("</t></is></c>")
                    }
                    xml.append("</row>")
                }
                xml.append("</sheetData></worksheet>")
                entry("xl/worksheets/sheet1.xml", xml.toString())
            }
        } ?: throw IllegalStateException("Could not open the selected destination.")
    }

    fun errorReportCsv(preview: ImportPreview): String {
        val out = StringBuilder("Row,Field,Error\n")
        preview.issues.forEach {
            out.append(it.row).append(",").append(csv(it.field)).append(",").append(csv(it.message)).append("\n")
        }
        return out.toString()
    }

    private fun findDuplicate(p: Product, list: List<Product>): String? {
        fun norm(s: String) = s.trim().uppercase(Locale.ROOT)
        val pAfter = p.aftermarketNumbers.split("\n").map(::norm).filter { it.isNotBlank() }
        list.forEach { old ->
            if (p.oemNumber.isNotBlank() && norm(old.oemNumber) == norm(p.oemNumber))
                return "OEM ${p.oemNumber} already belongs to ${old.name}."
            val oldAfter = old.aftermarketNumbers.split("\n").map(::norm)
            pAfter.forEach { n ->
                if (n == norm(old.oemNumber) || n in oldAfter)
                    return "Aftermarket number $n already belongs to ${old.name}."
            }
            if (p.barcode.isNotBlank() && p.barcode == old.barcode)
                return "Barcode ${p.barcode} already belongs to ${old.name}."
            val oldAll = oldAfter + norm(old.oemNumber)
            if (norm(p.oemNumber).isNotBlank() && norm(p.oemNumber) in oldAfter)
                return "OEM ${p.oemNumber} is already listed as an aftermarket number for ${old.name}."
            if (oldAll.any { it.isNotBlank() && it in pAfter })
                return "An aftermarket number conflicts with an existing OEM/aftermarket number for ${old.name}."
        }
        return null
    }

    private fun unzip(context: Context, uri: Uri): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory) result[e.name] = zip.readBytes()
                    e = zip.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("Cannot open selected workbook.")
        return result
    }

    private fun parseSharedStrings(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val parser = parser(bytes)
        val result = mutableListOf<String>()
        var inSi = false
        var current = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "si") { inSi = true; current = StringBuilder() }
            else if (event == XmlPullParser.START_TAG && parser.name == "t" && inSi) current.append(parser.nextText())
            else if (event == XmlPullParser.END_TAG && parser.name == "si") { result += current.toString(); inSi = false }
            event = parser.next()
        }
        return result
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val parser = parser(bytes)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        var cellRef = ""
        var cellType = ""
        var cellValue = ""
        var inValue = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (parser.name) {
                "row" -> row = mutableListOf()
                "c" -> { cellRef = parser.getAttributeValue(null, "r") ?: ""; cellType = parser.getAttributeValue(null, "t") ?: ""; cellValue = "" }
                "v" -> { inValue = true; cellValue = parser.nextText(); inValue = false }
                "t" -> if (cellType == "inlineStr") cellValue += parser.nextText()
            } else if (event == XmlPullParser.END_TAG) when (parser.name) {
                "c" -> {
                    val index = columnIndex(cellRef)
                    while (row.size <= index) row.add("")
                    row[index] = if (cellType == "s") shared.getOrNull(cellValue.toIntOrNull() ?: -1).orEmpty() else cellValue
                }
                "row" -> rows += row
            }
            event = parser.next()
        }
        return rows
    }

    private fun parser(bytes: ByteArray): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }

    private fun normalizeHeader(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun columnIndex(ref: String): Int {
        var n = 0
        ref.takeWhile { it.isLetter() }.uppercase().forEach { n = n * 26 + (it - 'A' + 1) }
        return (n - 1).coerceAtLeast(0)
    }
    private fun columnName(number: Int): String {
        var n = number
        val out = StringBuilder()
        while (n > 0) { val rem = (n - 1) % 26; out.insert(0, ('A'.code + rem).toChar()); n = (n - 1) / 26 }
        return out.toString()
    }
    private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private val PART_NO = Regex("^[A-Za-z0-9][A-Za-z0-9./_-]{0,63}$")
    private val BARCODE = Regex("^(?:[0-9]{8}|[0-9]{12,14})$")
}
