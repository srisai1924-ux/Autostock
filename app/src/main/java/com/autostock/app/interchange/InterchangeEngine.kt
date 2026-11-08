package com.autostock.app.interchange

import com.autostock.app.data.Product
import java.util.Locale

data class InterchangeMatch(
    val product: Product,
    val matchedNumbers: List<String>,
    val isDirectMatch: Boolean
)

data class InterchangeResult(
    val query: String,
    val matches: List<InterchangeMatch>,
    val searchedNumberFound: Boolean
)

/**
 * Local catalogue-based interchange lookup.
 *
 * A product's OEM number and aftermarket numbers are treated as aliases for
 * that catalogue item. Products are linked when their number sets overlap.
 * No unverified fitment is invented: vehicle compatibility is displayed
 * from the records entered by the shop.
 */
object InterchangeEngine {
    private fun normalize(value: String): String =
        value.trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), "")

    private fun numbers(product: Product): Set<String> =
        (listOf(product.oemNumber) + product.aftermarketNumbers.split("\n", ",", ";"))
            .map(::normalize)
            .filter { it.isNotBlank() }
            .toSet()

    fun search(query: String, catalogue: List<Product>): InterchangeResult {
        val q = normalize(query)
        if (q.isBlank()) return InterchangeResult(query, emptyList(), false)

        val indexed = catalogue.associateWith(::numbers)
        val direct = catalogue.filter {
            q == normalize(it.barcode) && it.barcode.isNotBlank() || q in (indexed[it] ?: emptySet())
        }
        if (direct.isEmpty()) return InterchangeResult(query, emptyList(), false)

        // Expand through shared OEM/aftermarket identifiers to find all linked brands.
        val included = linkedSetOf<Product>()
        val frontier = ArrayDeque<Product>()
        direct.forEach { if (included.add(it)) frontier.add(it) }

        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            val currentNumbers = indexed[current].orEmpty()
            catalogue.forEach { candidate ->
                if (candidate !in included && currentNumbers.intersect(indexed[candidate].orEmpty()).isNotEmpty()) {
                    included.add(candidate)
                    frontier.add(candidate)
                }
            }
        }

        val result = included.map { product ->
            val overlap = if (product in direct) listOf(query.trim()) else {
                val productNumbers = indexed[product].orEmpty()
                val linkedNumbers = included
                    .filter { it != product }
                    .flatMap { indexed[it].orEmpty() }
                    .toSet()
                (productNumbers intersect linkedNumbers).sorted()
            }
            InterchangeMatch(product, overlap, product in direct)
        }.sortedWith(compareByDescending<InterchangeMatch> { it.isDirectMatch }.thenBy { it.product.brand }.thenBy { it.product.name })

        return InterchangeResult(query, result, true)
    }

    fun allNumbers(product: Product): List<String> =
        (listOf(product.oemNumber) + product.aftermarketNumbers.split("\n", ",", ";"))
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
}
