// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.crew

import org.xcsoar.mobile.core.WeGlideAircraft

/**
 * A glider model the pilot picks: a WeGlide type (with its seats), and
 * XCSoar's built-in polar for it when there is one ([polar] is its index
 * in the polar list).  Without WeGlide's list, models are XCSoar's
 * polars alone.
 */
data class AircraftModel(
    val name: String,
    val weGlide: WeGlideAircraft? = null,
    val polar: Int? = null,
)

/** "ASG-29 (18m)" and "ASG 29 18m" both become "asg2918m". */
internal fun normalizeModel(name: String): String =
    name.lowercase().filter { it.isLetterOrDigit() }

/* what may follow a model's name in a variant: anything but a digit
   (LS-1 is not LS-10), or a span (ASG 29 is ASG-29 (18m)) */
private val SPAN = Regex("^\\d+m")

/* a model number before the name, as in "206 Hornet" */
private val MODEL_NUMBER = Regex("^\\d+ (?=[A-Za-z])")

private fun isVariant(rest: String) =
    rest.isEmpty() || !rest[0].isDigit() || SPAN.containsMatchIn(rest)

/**
 * XCSoar's polar for the model called [name]: the same name once spaces,
 * dashes and brackets are left out, else the closest variant (one name
 * starting with the other); null if none.
 */
fun matchPolar(name: String, polars: List<String>): Int? {
    val wanted = normalizeModel(name)
    if (wanted.length < 2) return null

    var best: Int? = null
    var bestRest = Int.MAX_VALUE
    polars.forEachIndexed { index, polar ->
        // "303 Mosquito" is WeGlide's "Mosquito": without the model number
        val candidate = normalizeModel(polar.replace(MODEL_NUMBER, ""))
        val rest = when {
            candidate == wanted -> return index
            candidate.startsWith(wanted) -> candidate.substring(wanted.length)
            wanted.startsWith(candidate) && candidate.length >= 3 ->
                wanted.substring(candidate.length)
            else -> return@forEachIndexed
        }
        if (isVariant(rest) && rest.length < bestRest) {
            best = index
            bestRest = rest.length
        }
    }
    return best
}

/**
 * Every model: WeGlide's types with their polar, then the polars no
 * WeGlide type uses; sorted by name.
 */
fun aircraftModels(weGlide: List<WeGlideAircraft>, polars: List<String>): List<AircraftModel> {
    val models = weGlide.map { AircraftModel(it.name, it, matchPolar(it.name, polars)) }
    val used = models.mapNotNull { it.polar }.toSet()
    val polarOnly = polars.indices.filter { it !in used }
        .map { AircraftModel(polars[it], polar = it) }
    return (models + polarOnly).sortedBy { it.name.lowercase() }
}

/** The models whose name contains [query], spaces and dashes ignored. */
fun List<AircraftModel>.search(query: String): List<AircraftModel> {
    val wanted = normalizeModel(query)
    return if (wanted.isEmpty()) this else filter { normalizeModel(it.name).contains(wanted) }
}
