package com.portionspot.pos.data

/**
 * How the shop's tags READ and how they are OFFERED back — grouping, labels and the
 * typeahead behind the attribute editor. Pure; no Android, no Room, no I/O.
 *
 * Tags are shared vocabulary, not per-item free text. A shop selling parts for Honda Fits
 * types "Honda Fit" onto a hundred items, and typing it a hundred times is both tedious and
 * the surest way to end up with "Honda fit", "honda  Fit" and "HondaFit" as three separate
 * tags. Canonicalisation ([attrNorm]) silently merges the first two; nothing can save the
 * third — so the fix is not to type it a third time. Everything here exists to put what has
 * already been used in front of the owner before they retype it slightly differently.
 */

/**
 * The keys a motor-spares counter reaches for first, mirroring the web's `SUGGESTED_KEYS`.
 *
 * Suggestions only. The field stays free text: this shop's 671 tags are ALL `car`, and the
 * next shop will want something this list has never heard of.
 */
val SUGGESTED_ATTR_KEYS = listOf("car", "brand", "part_number", "engine", "year", "size", "colour")

/** `part_number` → `Part number`, for a label. Underscores and dashes read as spaces. */
fun prettyAttrKey(key: String): String {
    val s = key.replace(KEY_WORD_BREAK, " ").trim()
    return if (s.isEmpty()) s else s.replaceFirstChar { it.uppercase() }
}

private val KEY_WORD_BREAK = Regex("[_-]+")

/** One key's worth of an item's tags: `Car` → [Vezel, Fit GK3, …]. */
data class AttrGroup(val keyNorm: String, val label: String, val rows: List<ItemAttribute>)

/**
 * Group an item's tags by canonical key, keeping the casing of the first row for the label.
 *
 * Grouped by [ItemAttribute.keyNorm] rather than by the typed key, so a part tagged "Car"
 * on one till and "car" on another shows as one heading and not two. Tombstones are left
 * out — a removed fitment must stop being displayed, not turn into an empty row.
 */
fun groupAttributes(rows: List<ItemAttribute>): List<AttrGroup> =
    rows.asSequence()
        .filter { !it.deleted }
        .groupBy { it.keyNorm.ifBlank { attrNorm(it.key) } }
        .map { (keyNorm, group) ->
            AttrGroup(
                keyNorm = keyNorm,
                label = prettyAttrKey(group.first().key),
                rows = group.sortedBy { it.valueNorm },
            )
        }
        .sortedBy { it.label.lowercase() }

/**
 * Every key and value the shop has ever used, ordered by how often — so the values offered
 * first are the ones actually reached for, not whichever happens to sort first.
 *
 * [keys] carries the typed casing of the most-used spelling; [valuesByKey] is indexed by
 * CANONICAL key, because that is what the caller has after folding whatever was typed into
 * the key field.
 */
data class AttrVocabulary(
    val keys: List<String> = emptyList(),
    val valuesByKey: Map<String, List<String>> = emptyMap(),
) {
    companion object { val EMPTY = AttrVocabulary() }
}

/** Fold the shop's live tags into [AttrVocabulary]. Tombstones do not count as vocabulary. */
fun attrVocabulary(rows: List<ItemAttribute>): AttrVocabulary {
    // keyNorm → label + use count, and keyNorm → (valueNorm → label + use count).
    val keyUse = LinkedHashMap<String, Use>()
    val valueUse = LinkedHashMap<String, LinkedHashMap<String, Use>>()
    for (a in rows) {
        if (a.deleted || a.key.isBlank() || a.value.isBlank()) continue
        val k = a.keyNorm.ifBlank { attrNorm(a.key) }
        val v = a.valueNorm.ifBlank { attrNorm(a.value) }
        keyUse.getOrPut(k) { Use(a.key) }.n++
        valueUse.getOrPut(k) { LinkedHashMap() }.getOrPut(v) { Use(a.value) }.n++
    }
    return AttrVocabulary(
        keys = keyUse.values.sortedWith(BY_USE).map { it.label },
        valuesByKey = valueUse.mapValues { (_, vals) -> vals.values.sortedWith(BY_USE).map { it.label } },
    )
}

private class Use(val label: String) { var n: Int = 0 }

/** Most-used first, ties broken alphabetically so the list is stable between passes. */
private val BY_USE = compareByDescending<Use> { it.n }.thenBy { it.label.lowercase() }

/**
 * Keys to offer while [typed] is being entered: what the shop already uses, then the
 * standard suggestions it has not adopted yet.
 *
 * Substring, not prefix — someone typing "num" is looking for `part_number`.
 */
fun keySuggestions(vocab: AttrVocabulary, typed: String, limit: Int = 8): List<String> {
    val q = attrNorm(typed)
    val seen = HashSet<String>()
    return (vocab.keys + SUGGESTED_ATTR_KEYS)
        .filter { seen.add(attrNorm(it)) }
        .filter { q.isEmpty() || attrNorm(it).contains(q) }
        // The exact key already typed is not a suggestion, it is what is in the box.
        .filter { attrNorm(it) != q }
        .take(limit)
}

/**
 * Values already used under [key] that this item does not carry yet, narrowed by what is
 * being typed.
 *
 * [onItemValueNorms] is excluded rather than shown greyed out: offering "Vezel" on an item
 * already tagged Vezel invites a tap that can only be a no-op (the derived id is the same
 * row), and a control that does nothing reads as a bug.
 */
fun valueSuggestions(
    vocab: AttrVocabulary,
    key: String,
    onItemValueNorms: Set<String>,
    typed: String,
    limit: Int = 12,
): List<String> {
    val pool = vocab.valuesByKey[attrNorm(key)] ?: return emptyList()
    val q = attrNorm(typed)
    return pool.asSequence()
        .filter { attrNorm(it) !in onItemValueNorms }
        .filter { q.isEmpty() || attrNorm(it).contains(q) }
        .filter { attrNorm(it) != q }
        .take(limit)
        .toList()
}
