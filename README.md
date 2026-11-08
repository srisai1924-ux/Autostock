# AutoStock v1.0.0 — GST Invoice & PDF

Offline-first Android automotive spare-parts inventory, barcode lookup and scan-to-sale billing.

## GST invoice capabilities added
- Intra-state supply: GST is split into CGST and SGST, with each half calculated per invoice line.
- Inter-state supply: GST is charged as IGST.
- Supplier legal name, address and GSTIN.
- Customer name, address and optional GSTIN.
- Supplier state code and customer place-of-supply state code.
- Product-level HSN/SAC code, carried into the invoice line.
- Financial-year invoice sequence: `AS/YYYY-YY/00001` (increments within the financial year).
- PDF tax invoice export through Android's document picker.
- Sale header and line-item snapshots retain invoice number, GSTINs, state codes, HSN, tax rates, tax split, quantities and prices.

## Billing workflow
1. Maintain each part's selling price, GST rate and 4–8 digit HSN/SAC code in the product record.
2. Open Scan, scan the part, then choose **Add 1 to bill**.
3. In Billing, enter supplier details, supplier GSTIN and supplier state code. These are retained on the device.
4. Enter customer details and the customer's place-of-supply state code. Customer GSTIN is optional for an unregistered/B2C customer.
5. AutoStock compares the two state codes:
   - Same state: CGST + SGST.
   - Different state: IGST.
6. Complete sale. The sale record and stock deductions are committed in a Room transaction.
7. Choose **Save last GST invoice as PDF** to create a printable PDF.

## Tax calculation assumptions and controls
- Catalogue selling prices are treated as taxable values exclusive of GST.
- GST is calculated per line and rounded to the nearest paise. For intra-state supply, the line GST is split equally between CGST and SGST; any odd paise is assigned to SGST.
- Supplier GSTIN must match the standard 15-character GSTIN format and its first two digits must match the supplier state code.
- If customer GSTIN is entered, it must match the format and its first two digits must match the place-of-supply state code.
- Supplier GSTIN, supplier state code, place-of-supply state code and HSN/SAC for each billed item are required to complete a GST tax invoice.
- Invoice numbering uses the device's local date and financial year. This is a single-device MVP sequence; multi-device concurrency, cancellation/credit-note series and statutory audit controls are not yet implemented.
- The current screen exports the most recently completed invoice snapshot. A searchable invoice history and reprint of older invoices are future work.

## Database
Room database upgraded from version 4 to version 5. Existing products and sales are migrated; older sales are labelled `LEGACY-<id>` and their historical tax is not retroactively split.

## Important GST compliance scope
This is a functional invoice-generation MVP, not a certification of GST compliance or tax advice. It does not yet implement supplier/recipient full legal identity validation, customer billing/shipping state distinctions, place-of-supply rules for every transaction type, reverse charge, cess, discounts, freight, export/SEZ cases, e-invoicing/IRN, e-way bills, credit/debit notes, payment tracking, digital signatures, or tax-return reporting. Have a GST practitioner verify the invoice layout and tax treatment for the shop's business and transaction types before issuing invoices in production. State codes must be entered accurately; the app uses the entered place-of-supply code to decide CGST/SGST versus IGST.

## Build APK
Requirements: Android Studio, JDK 17, Android SDK Platform 35.
1. Open the project folder in Android Studio.
2. Sync Gradle and download CameraX / ML Kit dependencies.
3. Build → Build Bundle(s) / APK(s) → Build APK(s).
4. Debug APK path: `app/build/outputs/apk/debug/app-debug.apk`.

Command line: `gradle assembleDebug`.

## Test status
Source project only. APK has not been compiled or tested on a device in this environment. Verify Gradle sync, database migration, GST calculations, invoice numbering, PDF layout and stock deduction on a test device before real billing.


## Invoice history (v1.1.0)
- New **Invoices** tab lists saved invoices newest first.
- Search accepts invoice number, customer name, customer GSTIN, supplier GSTIN, and date in `YYYY-MM-DD` or `DD-MM-YYYY` format.
- Open any invoice to review supplier/customer details, invoice date, line items, HSN/SAC, quantities, prices, tax rate, tax split and total.
- Reprint/export the selected invoice as PDF using Android's document picker. The PDF is rebuilt from the persisted sale header and sale-item snapshots, so it does not depend on the current product catalogue.
- Older v1.0.0 invoices remain visible. Their original total GST is retained, but CGST/SGST/IGST split is marked as unavailable because it was not stored in those historical records.


## Sales reports (v1.2.0)
- New **Reports** tab with Daily, Monthly and Financial year views.
- Daily groups completed invoices by local transaction date (`YYYY-MM-DD`).
- Monthly groups by calendar month (`YYYY-MM`).
- Financial year groups from 1 April through 31 March (for example, `2026-27`).
- Each period displays invoice count, taxable sales, CGST, SGST, IGST, total GST and grand total.
- An all-recorded-sales summary appears above the period breakdown.
- **Export CSV** saves the currently selected grouping through Android's document picker. The CSV contains period rows and a grand-total row, suitable for Excel or spreadsheet software.
- Reports use saved sale headers. Legacy invoices are included in taxable sales, invoice counts and total GST; their unavailable historical CGST/SGST/IGST split remains zero rather than being guessed.


## Sales dashboard (v1.3.0)
- The Reports tab now opens as a sales dashboard with KPI cards for today's completed sales, current calendar-month turnover, GST collected this month, and invoice count this month.
- Product-wise sales for the current month are ranked by sales value and show product name, units sold, GST amount and total billed value.
- Product summaries are calculated from saved invoice-line snapshots joined to their original sales; they do not depend on the current product catalogue price or stock.
- The existing Daily, Monthly and Financial Year report breakdowns and CSV export remain available below the dashboard.
- Dashboard monthly turnover and product-wise totals use invoice grand totals, including GST. GST collected is the sum of GST recorded on completed invoices.


## Purchase dashboard (v1.4.0)
- New Purchases tab with a supplier purchase entry form for supplier name, supplier GSTIN, supplier invoice number, taxable value, CGST, SGST and IGST.
- Purchase entries are stored offline in a new Room `purchases` table. Existing installations migrate from database version 5 to 6 without deleting catalogue, invoice or sales history.
- Dashboard shows current-month purchase totals, purchase GST, marked-eligible input tax credit (ITC), sales GST collected, and an indicative sales-GST-minus-eligible-ITC comparison.
- Supplier-wise monthly summaries show invoice count, taxable purchases, GST, eligible ITC and billed total.
- Recent purchase records remain visible in the dashboard.
- Use the + action on the Purchases tab to record a supplier invoice.
- This release records purchase invoice totals only; it does not yet capture individual purchased products, update inventory quantities, or calculate GST returns. ITC eligibility is user-marked and must be verified against invoices, GSTR-2B, blocked-credit rules and applicable GST law. The comparison is an internal estimate, not a statutory tax liability.


## Purchase item entry and automatic stock receipt (v1.5.0)
- Supplier invoices now include selected catalogue products, quantities and unit purchase prices.
- Product GST rates from the catalogue are used to calculate purchase tax line-by-line. Choose intra-state (CGST + SGST) or inter-state (IGST) treatment on the invoice.
- Each saved purchase creates a supplier invoice header and immutable purchase-item snapshots.
- Saving a purchase is one Room transaction: the invoice and its item lines are recorded, product quantities are increased, and each product's latest purchase price is updated. If any item cannot be applied, the entire transaction rolls back.
- Existing purchase records remain in the purchase dashboard; older header-only purchases remain valid and do not create guessed item or stock movements.
- Database upgraded from version 6 to 7 with a `purchase_items` table. Existing catalogue, sales, invoices and purchase headers are preserved.
- This MVP does not yet support purchase returns, partial receipts, landed-cost allocation, supplier payment tracking, duplicate supplier invoice blocking, or GST return reconciliation. Validate ITC and tax treatment with your GST professional before claiming credits.


## Purchase returns and supplier debit notes (v1.6.0)
- Purchase entry is now item-based: select products from the catalogue, enter quantity and unit purchase price, and create a supplier invoice with line-level GST using the catalogue GST rates.
- Saving a purchase invoice and its item snapshots updates inventory quantities and latest purchase prices in one Room transaction.
- Purchase return flow selects an original supplier invoice and its purchase lines, accepts partial return quantities and a supplier credit/debit-note reference, and calculates taxable and GST reversals from the original saved item tax split.
- Return validation prevents returning more than the original quantity less previous returns, prevents returning stock that is no longer physically available, and verifies each returned item belongs to the selected source invoice.
- Saving a return note, its item lines and inventory deductions is atomic. If any validation or stock deduction fails, the entire transaction rolls back.
- Purchase dashboard now nets supplier purchases, purchase GST and eligible ITC against recorded purchase returns. Eligible ITC is reduced by returns against invoices marked ITC eligible. Sales GST comparison uses the adjusted eligible ITC.
- Database upgraded from version 7 to 8 with `purchase_returns` and `purchase_return_items`; previous catalogue, sales, purchases and invoice history are retained.
- Older header-only purchase records without saved item lines cannot be selected for item-level returns; no stock quantities are inferred for those records.
- This is an operational recordkeeping aid, not a GST compliance engine. Validate supplier credit-note treatment, ITC reversal timing, GSTR-2B matching and return reporting with a qualified GST practitioner. E-invoicing, statutory debit/credit-note numbering, GST return filing and audit workflows are not implemented.


## Supplier directory, payments and ledger (v1.7.0)
- New Suppliers tab and directory with supplier name, GSTIN, phone, email, address and notes.
- Supplier records are unique by name. Existing supplier names are seeded from historical purchase invoices and purchase returns during migration.
- Record supplier payments with amount, date/time, payment method (cash, bank transfer, UPI, cheque or other), transaction reference and notes.
- Supplier ledger combines purchase invoices (debit to supplier account), purchase returns/credit notes (credit) and payments (credit), with a running balance.
- Supplier cards show purchase total, returns, payments, invoice count and current outstanding balance. Negative balances represent advances/credits.
- Outstanding = recorded purchase invoice totals - purchase return totals - supplier payments. This is calculated from stored transactions, not from bank feeds.
- Database upgraded from version 8 to 9 with `suppliers` and `supplier_payments` tables; all previous inventory, sales, purchases and returns are retained.
- Purchases continue to store supplier names as text; enter the exact directory supplier name when recording an invoice so the invoice appears under the intended supplier ledger. Existing transactions are matched case-insensitively by name.
- Opening balances, payment allocation to individual invoices, supplier reconciliation, edit/merge supplier records, payment reversals and supplier statement PDF/CSV export are not yet included.


## Supplier directory, payments and ledger (v1.7.0)
- New Suppliers tab and directory with supplier name, GSTIN, phone, email, address and notes.
- Supplier names are unique in the directory. Existing supplier names are seeded from historical purchase invoices and purchase returns during the v8-to-v9 database migration.
- Record supplier payments with amount, date/time, payment method (cash, bank transfer, UPI, cheque or other), transaction reference and notes.
- Supplier ledger combines purchase invoices (debit to supplier account), purchase returns/credit notes (credit) and payments (credit), with a chronological running balance.
- Supplier cards show purchase total, returns, payments, invoice count and outstanding balance. Negative balances represent advances/credits.
- Outstanding = recorded purchase invoice totals - purchase return totals - supplier payments. Balances use recorded transactions only and do not import bank data.
- Export all supplier ledger transactions and running balances to CSV.
- Database upgraded from version 8 to 9 with `suppliers` and `supplier_payments` tables; previous catalogue, sales, purchases and returns are retained.
- Purchase records still store supplier names as text. Enter the exact directory supplier name when recording a purchase so it appears in that supplier's ledger; matching is case-insensitive.
- Opening balances, payment allocation to individual invoices, supplier reconciliation, edit/merge supplier records and payment reversals are not yet included.


## Supplier statement PDF with date range (v1.8.0)
- Each supplier card now has a Statement PDF action.
- Choose a from-date and to-date in `YYYY-MM-DD` format; the app validates the range and includes both dates.
- PDF statement includes supplier details, selected period, opening balance (all recorded supplier transactions before the start date), individual purchase invoices, purchase returns and supplier payments, running balance after each transaction, period purchase/return/payment totals, and closing balance.
- Purchases increase the payable balance; purchase returns and payments reduce it. Payments include method, reference and notes when recorded.
- Closing balance = opening balance + period purchases - period returns - period payments.
- PDF is generated locally on Android and saved using the system document picker.
- This statement uses recorded app transactions only. It does not include unrecorded opening balances, invoice-wise payment allocation, interest, disputes or adjustments not entered in AutoStock.


## Invoice-wise supplier payment allocation (v1.9.0)
- Supplier payment entry now requires allocation of the full payment amount to one or more outstanding supplier invoices.
- The payment dialog lists open invoices, their current due balances, and editable allocation amounts. Allocations must total exactly to the payment amount.
- Allocations are validated transactionally: invoice must belong to the supplier, amounts must be positive, duplicate invoice rows are rejected, and an allocation cannot exceed the invoice's remaining balance after returns and prior allocations.
- Supplier directory cards show each invoice's bill total, allocated payments, returns, due amount and status: PAID, PARTIALLY PAID or UNPAID.
- Purchase returns reduce the outstanding amount of the original purchase invoice.
- Database upgraded from version 9 to 10 with `supplier_payment_allocations`; existing suppliers, payments, purchase records and all previous data are retained.
- Payments entered in older versions remain unallocated. They continue to reduce supplier-level outstanding balances, but do not get attributed to an invoice automatically. This avoids inventing historical allocations.
- New payments must be fully allocated; supplier advances / unallocated payments are not supported in this workflow.


## Supplier invoice ageing reports (v1.10.0)
- Supplier management now includes a consolidated invoice ageing report across suppliers.
- Outstanding purchase invoices are grouped into 0–30, 31–60, 61–90 and over 90 days based on elapsed calendar days from the recorded supplier invoice date (`Purchase.createdAt`) to the device's current local date.
- Invoice outstanding = original invoice total - purchase returns recorded against that invoice - payments explicitly allocated to that invoice.
- Fully settled or fully returned invoices are excluded from ageing buckets. Future-dated invoices are treated as age zero.
- Report displays invoice count and outstanding value per bucket, total aged outstanding, and a short list of the oldest open invoices.
- Export CSV includes every outstanding invoice with supplier, invoice number/date, age, invoice total, returns, allocated payments, outstanding amount and bucket, plus bucket summary rows.
- Payments recorded in earlier versions without invoice allocation are deliberately not assigned to invoices. They continue to affect supplier-level balance but are excluded from invoice-level ageing until allocated (no automatic historical assumptions).
- No database change is required; database remains version 10.


## Supplier invoice ageing PDF export (v1.11.0)
- Added a PDF export action alongside the existing CSV export in the supplier invoice ageing report.
- PDF report date is captured when the user starts the export and printed in the report header.
- First page contains the four ageing buckets (0–30, 31–60, 61–90 and over 90 days), invoice counts and outstanding amounts, plus total aged outstanding.
- Invoice details are grouped by supplier and include invoice number, invoice date, age in days, original bill total, purchase returns, allocated payments and outstanding amount.
- Each supplier group displays its own outstanding subtotal. Long reports automatically continue onto additional PDF pages with repeated column headings.
- Report is generated locally using Android PdfDocument and saved using the Android document picker.
- Ageing calculation remains based on the device's local report date and the recorded invoice date. Fully settled invoices are excluded. Unallocated older payments are not assigned to invoices.
- No database change; database remains version 10.


## Supplier accounting dashboard (v1.12.0)
- Added an all-time supplier accounting dashboard above the supplier invoice ageing report.
- Consolidated KPIs: gross purchase turnover, payments, outstanding supplier balances, net purchases after returns, attributed sales and estimated gross profit.
- Consolidated ageing totals and invoice counts for 0–30, 31–60, 61–90 and over 90 days.
- Supplier-wise view shows purchase turnover, returns, net purchases, payments, outstanding balance, ageing bucket totals, attributed sales, estimated cost, estimated gross profit and estimated gross margin.
- Profitability is explicitly labelled as an estimate. Since stock is not tracked by supplier-specific batch/lot at sale time, sold quantities are apportioned across suppliers according to their share of recorded net purchased units for each product. Estimated cost uses recorded purchase-item unit prices; sales revenue uses invoice-line taxable subtotal; GST is excluded. Products without item-level purchase history are not attributed.
- Added an observer for all saved sale-item snapshots. No database schema change; database remains version 10.


## Supplier accounting dashboard PDF export (v1.13.0)
- Added an Export PDF action directly on the supplier accounting dashboard.
- PDF report date is captured at export time and shown in the document header and continuation pages.
- Overall totals include purchase turnover, purchase returns, net purchases, payments, supplier outstanding, attributed sales, estimated cost, estimated gross profit and estimated gross margin.
- Consolidated ageing includes all four buckets (0–30, 31–60, 61–90, over 90 days), invoice counts and outstanding values.
- Supplier-wise details include purchase turnover, returns, net purchases, payments, outstanding, each ageing bucket, attributed taxable sales, estimated cost, estimated gross profit and estimated margin.
- Long reports automatically paginate.
- The PDF is generated locally with Android PdfDocument and saved through the Android document picker.
- Profitability remains an estimate based on supplier share of net purchased units for each product, not actual supplier-lot tracing. GST is excluded from attributed sales/profit calculations. Unallocated historical payments are not assigned to individual invoices for ageing.
- No database schema change; Room database remains version 10.


## Supplier-wise monthly and yearly accounting reports (v1.14.0)
- Added a date-filtered supplier accounting report section to Supplier Management.
- Quick filters: This month and This year. Users can also edit inclusive From / To dates in `YYYY-MM-DD` format for custom reporting windows.
- Every selected window is compared with the immediately preceding window of the same number of calendar days.
- Overall and supplier-wise comparisons include purchase turnover, purchase returns, net purchases, payments, outstanding balance, attributed taxable sales, estimated cost, estimated gross profit and estimated gross margin.
- Ageing is reported as a point-in-time snapshot at each period end with the four buckets: 0–30, 31–60, 61–90 and over 90 days. Current and previous period-end bucket amounts and invoice counts are compared.
- Purchase turnover, returns and payments are period activity. Outstanding and ageing are point-in-time balances as of the selected end date. Outstanding includes all recorded payments; invoice ageing reduces balances only by invoice-allocated payments, so those totals may differ where historical unallocated payments exist.
- Profitability estimates use sales in the selected period and supplier purchase-item history available up to that period's end date to apportion sold quantities. They are not batch-level actual profit; GST is excluded.
- No database schema change; Room database remains version 10.


## Supplier period comparison PDF export (v1.15.0)
- Added an `Export this comparison as PDF` action to the monthly/yearly/custom supplier accounting report.
- The export uses the currently selected inclusive From and To dates and records both the current date range and immediately preceding equal-length comparison range.
- PDF includes overall current-versus-previous totals and percentage changes for purchase turnover, returns, net purchases, payments, period-end outstanding, attributed taxable sales, estimated cost, estimated gross profit and estimated gross margin.
- Includes consolidated four-bucket ageing comparisons (0–30, 31–60, 61–90, over 90 days), invoice counts and outstanding amounts as of each period end.
- Includes supplier-wise comparisons for turnover, returns, net purchases, payments, outstanding, each ageing bucket, attributed sales, estimated cost, estimated gross profit and estimated gross margin.
- Long reports paginate automatically and repeat report headings on continuation pages.
- PDF is generated locally using Android PdfDocument and saved through Android's document picker.
- The export re-calculates the report from the date filter values at the time the PDF is saved.
- Profitability is an estimate based on supplier shares of recorded net purchased units by product, not supplier-batch tracing. GST is excluded from attributed sales/profit. Unallocated historical payments are not allocated to invoices for ageing.
- No database schema change; Room remains version 10.


## Build APK using only an Android phone (GitHub Actions)
This project includes `.github/workflows/android-apk.yml`.
1. Extract this ZIP on your phone.
2. Create a new empty repository on GitHub.
3. Upload the contents of the `AutoStock` project folder (including the hidden `.github` folder) to the repository root. If the GitHub mobile website hides hidden folders, use a ZIP upload/extraction workflow that preserves `.github`, or use GitHub's web editor to create the workflow file at `.github/workflows/android-apk.yml` using the file from this project.
4. Open the repository's Actions tab and run **Build AutoStock APK** (or push to `main`).
5. Open the completed workflow run and download the `AutoStock-debug-apk` artifact. Extract the downloaded artifact ZIP to obtain `app-debug.apk`.
6. Install the APK on your Android device. You may need to allow installation from your browser or file manager.

The workflow uses JDK 17 and Gradle 8.9. The APK is a debug build for testing, not a signed Play Store release.
