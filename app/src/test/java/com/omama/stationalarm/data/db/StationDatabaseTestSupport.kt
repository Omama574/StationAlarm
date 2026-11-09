package com.omama.stationalarm.data.db

/**
 * StationDatabase.getDatabase() caches one process-wide instance. Robolectric keeps static state across tests
 * in a sandbox, so a later test would otherwise reuse a DB whose file belongs to an earlier, deleted test dir.
 * Closes and clears that cache so every test builds its own file DB.
 */
internal fun resetStationDatabaseSingleton() {
    val field = StationDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    (field.get(null) as? StationDatabase)?.close()
    field.set(null, null)
}
