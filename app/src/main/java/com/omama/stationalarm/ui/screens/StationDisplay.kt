package com.omama.stationalarm.ui.screens

// Railway station codes are 1–6 letters/digits (NDLS, BCT, PUNE).
private val STATION_CODE = Regex("[A-Za-z0-9]{1,6}")

/**
 * Returns a short user-facing identifier for a station, or null if the id is an internal one we don't
 * want to surface — geocoder results (`liq-481312177`) and pins dropped on the map (`manual-1718…`).
 * Railway codes (`NDLS`, `BCT`, …) flow through unchanged.
 */
internal fun displayableStationCode(id: String): String? = id.takeIf { STATION_CODE.matches(it) }
