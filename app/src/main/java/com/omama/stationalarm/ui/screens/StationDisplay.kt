package com.omama.stationalarm.ui.screens

/**
 * Returns a short user-facing identifier for a station, or null if the id is
 * an internal one we don't want to surface (e.g. `custom-<uuid>` for places
 * dropped on the map). Railway codes (`NDLS`, `BCT`, …) flow through unchanged.
 */
internal fun displayableStationCode(id: String): String? {
    if (id.isBlank()) return null
    if (id.startsWith("custom-")) return null
    return id
}
