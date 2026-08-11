package com.portionspot.pos.data

/**
 * Catalogue search — name, SKU, barcode, AND the item's tags ([ItemAttribute]).
 *
 * The tags are the reason this is a file rather than three `contains` calls inline in the
 * screen. In this shop every tag is `key = "car"`: the vehicles a part fits. A customer
 * does not ask for "A376", they ask for pads for a Vezel, and the product named
 * "Airwave/Vezel Brake Pads" is the lucky case — most parts fit more cars than their name
 * has room for. Searching the name alone silently hides stock the shop is holding.
 *
 * Two properties this deliberately has:
 *
 *  - **A tag hit never outranks a direct hit.** Type "Fit" and "Fit Air Filter" is still
 *    the first result, ahead of the twenty other parts that merely fit a Honda Fit. Within
 *    each tier the caller's order (name ascending, out of the DAO) is preserved, so with no
 *    tag matches the result is byte-for-byte what the old inline filter produced.
 *
 *  - **Tags need [MIN_TAG_QUERY] characters.** A single letter typed on the way to a real
 *    query matches a fitment on nearly every item in the shop, which turns the first
 *    keystroke into a full-catalogue flash. Name/SKU/barcode keep matching from one
 *    character, exactly as before.
 */

/** Shortest query allowed to match a tag. Below this, only name/SKU/barcode match. */
const val MIN_TAG_QUERY = 2

/** One searchable tag: [value] is shown to the cashier, [valueNorm] is matched. */
data class TagValue(val key: String, val value: String, val valueNorm: String)

/**
 * A search result: the [item], plus the tags that matched — empty when the item was found
 * by name, SKU or barcode, so the UI can say "Fits Vezel" only when that is actually the
 * reason the row is on screen.
 */
data class CatalogHit(val item: Item, val matchedTags: List<TagValue>)

/**
 * Group live tags by item id, ready for [searchCatalog]. Deleted rows are dropped and
 * duplicate values on the same item are collapsed, so a part tagged "Vezel" twice does not
 * offer to fit it twice.
 */
fun tagIndex(rows: List<ItemAttribute>): Map<String, List<TagValue>> =
    rows.asSequence()
        .filter { !it.deleted && it.value.isNotBlank() }
        .groupBy { it.itemId }
        .mapValues { (_, tags) ->
            tags.distinctBy { it.keyNorm to it.valueNorm }
                .sortedWith(compareBy({ it.keyNorm }, { it.valueNorm }))
                .map { TagValue(it.key, it.value, it.valueNorm) }
        }

/**
 * Filter and rank [items] for [query], returning direct (name/SKU/barcode) hits first and
 * tag-only hits after. A blank query returns everything, unranked and untouched.
 *
 * Callers pass the catalogue already narrowed to what is sellable/visible; this does not
 * filter on `isActive` or `deleted` itself.
 */
fun searchCatalog(
    items: List<Item>,
    query: String,
    tags: Map<String, List<TagValue>>,
    /** Inventory searches the free-text category too; the sell screen has chips for it. */
    includeCategory: Boolean = false,
): List<CatalogHit> {
    val q = attrNorm(query)
    if (q.isEmpty()) return items.map { CatalogHit(it, emptyList()) }

    val direct = ArrayList<CatalogHit>()
    val viaTag = ArrayList<CatalogHit>()
    for (item in items) {
        if (item.matchesDirectly(q, includeCategory)) {
            direct += CatalogHit(item, emptyList())
            continue
        }
        val matched = matchedTagValues(q, tags[item.id].orEmpty())
        if (matched.isNotEmpty()) viaTag += CatalogHit(item, matched)
    }
    return direct + viaTag
}

/** Tags on this item whose value contains [normalisedQuery]. */
fun matchedTagValues(normalisedQuery: String, tags: List<TagValue>): List<TagValue> {
    if (normalisedQuery.length < MIN_TAG_QUERY) return emptyList()
    return tags.filter { it.valueNorm.contains(normalisedQuery) }
}

/**
 * One line of text explaining a tag match, for the product card.
 *
 * The `car` key gets the reading a cashier would actually use out loud — "Fits Hiace,
 * Caravan" — because that is what the tag means in this shop. Any other key falls back to
 * naming itself, so a catalogue that later grows sizes or colours stays readable without
 * this needing to know about them in advance.
 */
fun tagCaption(tags: List<TagValue>): String {
    if (tags.isEmpty()) return ""
    val byKey = tags.groupBy { attrNorm(it.key) }
    return byKey.entries.joinToString("  ·  ") { (keyNorm, group) ->
        val values = group.joinToString(", ") { it.value }
        if (keyNorm == CAR_KEY) "Fits $values" else "${group.first().key}: $values"
    }
}

/** The one tag key this shop's catalogue actually uses. */
private const val CAR_KEY = "car"

/** Name / SKU / barcode (and optionally category) contains the query — the match these
 *  screens always had. */
private fun Item.matchesDirectly(normalisedQuery: String, includeCategory: Boolean): Boolean =
    attrNorm(name).contains(normalisedQuery) ||
        (sku?.let { attrNorm(it).contains(normalisedQuery) } == true) ||
        (barcode?.let { attrNorm(it).contains(normalisedQuery) } == true) ||
        (includeCategory && category?.let { attrNorm(it).contains(normalisedQuery) } == true)
