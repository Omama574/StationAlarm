package com.omama.stationalarm.data.db

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migration tests for StationDatabase.
 *
 * Why not MigrationTestHelper: room-testing is not a dependency and build.gradle.kts does not add app/schemas
 * to the test assets, and build files are out of scope here. Instead old databases are recreated from the
 * exported schema JSON (app/schemas/.../N.json: createSql + setupQueries, i.e. what Room itself would have
 * written), seeded, and then opened through the production [StationDatabase.getDatabase] so the real migration
 * list and fallbackToDestructiveMigrationFrom(1) are what is under test. Room validates the migrated schema
 * against the current entities on open, so a migration that leaves the wrong columns fails here.
 *
 * Version 2 has no exported schema; it is reconstructed from 3.json assuming (per MIGRATION_2_3's doc comment)
 * that active_stations was unchanged by that migration.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class) // plain Application: no StationAlarmApplication.onCreate racing the DB files
class StationDatabaseMigrationTest {

    private val dbName = "station_database" // hard-coded in StationDatabase.getDatabase
    private lateinit var context: Context
    private val schemaDir: File by lazy { locateSchemaDir() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetStationDatabaseSingleton()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        resetStationDatabaseSingleton()
        context.deleteDatabase(dbName)
    }

    // ── version + schema export guards ───────────────────────────────────────

    @Test
    fun declaredVersionIsSix_andIsTheLatestExportedSchema() {
        val exported = schemaDir.listFiles { f -> f.extension == "json" }!!.map { it.nameWithoutExtension.toInt() }.sorted()
        assertEquals("every version from 3 up must be exported", listOf(3, 4, 5, 6), exported)
        exported.forEach { assertEquals(it, schema(it).getAsJsonObject("database")["version"].asInt) }

        val db = Room.inMemoryDatabaseBuilder(context, StationDatabase::class.java).allowMainThreadQueries().build()
        try {
            val version = db.openHelper.writableDatabase.version
            assertEquals("StationDatabase @Database(version)", 6, version)
            assertEquals("version must equal the newest exported schema", exported.last(), version)
        } finally {
            db.close()
        }
    }

    @Test
    fun currentEntities_matchTheNewestExportedSchema() {
        // A changed entity without a version bump + re-export shows up as an identity-hash mismatch.
        val db = Room.inMemoryDatabaseBuilder(context, StationDatabase::class.java).allowMainThreadQueries().build()
        try {
            val hash = scalar(db.openHelper.writableDatabase, "SELECT identity_hash FROM room_master_table")
            assertEquals(schema(6).getAsJsonObject("database")["identityHash"].asString, hash)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrations_coverEveryVersionStepFrom2ToCurrent_withoutGaps() {
        val steps = listOf(
            StationDatabase.MIGRATION_2_3, StationDatabase.MIGRATION_3_4,
            StationDatabase.MIGRATION_4_5, StationDatabase.MIGRATION_5_6,
        )
        assertEquals(listOf(2, 3, 4, 5), steps.map { it.startVersion })
        assertEquals(listOf(3, 4, 5, 6), steps.map { it.endVersion })
    }

    // ── upgrade paths through the production builder ─────────────────────────

    @Test
    fun v2_toCurrent_keepsRows_andAddsNewColumnsWithDefaults() {
        createLegacyDb(2, tables = setOf("active_stations"), withRoomIdentity = false) { it.insertLegacyStation("NDLS") }
        assertMigratedStation("NDLS")
        assertCurrentShape()
    }

    @Test
    fun v3_toCurrent_keepsRows_andDropsSavedPlaces() {
        createLegacyDb(3) {
            it.insertLegacyStation("NDLS")
            it.execSQL("INSERT INTO saved_places (id,name,lat,lon,radiusKm,notes,createdAt) VALUES ('p1','Home',1.0,2.0,3.0,NULL,10)")
        }
        assertMigratedStation("NDLS")
        assertCurrentShape()
    }

    @Test
    fun v4_toCurrent_keepsRows_andDropsSavedPlaces() {
        createLegacyDb(4) {
            it.insertLegacyStation("NDLS")
            it.execSQL("INSERT INTO saved_places (id,name,lat,lon,radiusKm,notify,vibrate,sound,notes,createdAt) VALUES ('p1','Home',1.0,2.0,3.0,1,1,1,'n',10)")
        }
        assertMigratedStation("NDLS")
        assertCurrentShape()
    }

    @Test
    fun v5_toCurrent_keepsCoordinatesAndNames_andAddsNullLastTriggeredAt() {
        createLegacyDb(5) {
            val cv = legacyStationValues("liq-1").apply {
                put("lat", 12.5); put("lon", 77.5); put("stationName", "My Pin"); put("status", "PAUSED")
            }
            it.insertOrThrow("active_stations", null, cv)
            it.execSQL("INSERT INTO saved_places (id,name,lat,lon,radiusKm,notify,vibrate,sound,notes,createdAt) VALUES ('p1','Home',1.0,2.0,3.0,1,1,1,NULL,10)")
        }
        val row = migratedRow("liq-1")
        assertEquals(12.5, row.lat, 0.0)
        assertEquals(77.5, row.lon, 0.0)
        assertEquals("My Pin", row.stationName)
        assertEquals("PAUSED", row.status)
        assertNull(row.lastTriggeredAt)
        assertCurrentShape()
    }

    @Test
    fun migration_preservesAlertingStatusAndAllSettings() {
        createLegacyDb(3) {
            val cv = legacyStationValues("ALRT").apply {
                put("alertDistanceKm", 17.25); put("notify", 0); put("vibrate", 1); put("sound", 0)
                put("customReminder", "wake 🚆"); put("sendReminder", 1); put("status", "ALERTING")
            }
            it.insertOrThrow("active_stations", null, cv)
        }
        val row = migratedRow("ALRT")
        assertEquals(17.25, row.alertDistanceKm, 0.0)
        assertEquals(false, row.notify)
        assertEquals(true, row.vibrate)
        assertEquals(false, row.sound)
        assertEquals("wake 🚆", row.customReminder)
        assertEquals(true, row.sendReminder)
        assertEquals("ALERTING", row.status) // reset happens later, in LocationService, not in the migration
    }

    @Test
    fun migration_ofManyRows_keepsThemAll() {
        createLegacyDb(4) { sdb -> (1..25).forEach { sdb.insertLegacyStation("S$it") } }
        val rows = runBlocking { openCurrent().activeStationDao().getAllActiveStationsList() }
        assertEquals(25, rows.size)
        assertEquals((1..25).map { "S$it" }.toSet(), rows.map { it.stationId }.toSet())
    }

    @Test
    fun migratedDatabase_isFullyUsableAfterwards() = runBlocking {
        createLegacyDb(4) { it.insertLegacyStation("OLD") }
        val dao = openCurrent().activeStationDao()
        dao.insert(ActiveStationEntity("NEW", 4.0, true, true, true, null, false, lat = 1.0, lon = 2.0, stationName = "n"))
        dao.setLastTriggeredAt("OLD", 123L)
        assertEquals(setOf("OLD", "NEW"), dao.getAllActiveStationsList().map { it.stationId }.toSet())
        assertEquals(123L, dao.getStationById("OLD")!!.lastTriggeredAt)
    }

    /**
     * v4 keeps a custom (map-pin) alarm's coordinates ONLY in saved_places. MIGRATION_4_5 adds lat/lon with
     * DEFAULT 0.0 and never copies them over, and MIGRATION_5_6 then drops saved_places, so the alarm ends up
     * at (0,0) with no name and can never be resolved again (StationRepository.getStationById returns null).
     */
    @Ignore("BUG: StationDatabase.kt:52-68 MIGRATION_4_5 has no backfill from saved_places and MIGRATION_5_6 drops it; v<=4 custom alarms lose lat/lon/name")
    @Test
    fun v4_customAlarm_keepsItsCoordinatesFromSavedPlaces() {
        createLegacyDb(4) {
            it.insertLegacyStation("manual-171")
            it.execSQL("INSERT INTO saved_places (id,name,lat,lon,radiusKm,notify,vibrate,sound,notes,createdAt) VALUES ('manual-171','My Pin',12.5,77.5,4.0,1,1,1,NULL,10)")
        }
        val row = migratedRow("manual-171")
        assertEquals(12.5, row.lat, 0.0)
        assertEquals(77.5, row.lon, 0.0)
        assertEquals("My Pin", row.stationName)
    }

    // ── destructive v1 fallback / downgrade ──────────────────────────────────

    @Test
    fun v1_isWipedByTheDestructiveFallback_insteadOfCrashing() {
        val file = context.getDatabasePath(dbName).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sdb ->
            sdb.execSQL("CREATE TABLE active_stations (stationId TEXT PRIMARY KEY, legacyColumn TEXT)")
            sdb.execSQL("INSERT INTO active_stations VALUES ('OLD','x')")
            sdb.version = 1
        }
        val rows = runBlocking { openCurrent().activeStationDao().getAllActiveStationsList() }
        assertTrue("v1 data is intentionally discarded", rows.isEmpty())
        assertCurrentShape()
    }

    @Test
    fun downgrade_failsLoudly_andDoesNotWipeTheUsersAlarms() {
        val file = context.getDatabasePath(dbName).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sdb ->
            sdb.execSQL(createSql(schema(6), "active_stations"))
            sdb.insertOrThrow("active_stations", null, legacyStationValues("KEEP").apply { put("lat", 0.0); put("lon", 0.0); put("stationName", "") })
            sdb.version = 7
        }
        try {
            runBlocking { openCurrent().activeStationDao().getAllActiveStationsList() }
            fail("opening a v7 database with a v6 build must not silently succeed")
        } catch (expected: Exception) {
            // Room refuses: no migration path 7 -> 6 and no destructive fallback for downgrades.
        }
        resetStationDatabaseSingleton()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sdb ->
            assertEquals(1L, scalar(sdb, "SELECT COUNT(*) FROM active_stations").toLong())
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun openCurrent(): StationDatabase = StationDatabase.getDatabase(context)

    private fun migratedRow(id: String): ActiveStationEntity =
        runBlocking { openCurrent().activeStationDao().getStationById(id) } ?: throw AssertionError("row $id lost in migration")

    /** Asserts the row written by [insertLegacyStation] survived with the expected defaults for new columns. */
    private fun assertMigratedStation(id: String) {
        val row = migratedRow(id)
        assertEquals(5.0, row.alertDistanceKm, 0.0)
        assertEquals(true, row.notify)
        assertEquals(false, row.vibrate)
        assertEquals(true, row.sound)
        assertEquals("remind me", row.customReminder)
        assertEquals(true, row.sendReminder)
        assertEquals("MONITORING", row.status)
        assertEquals("lat defaults to 0.0 for rows that predate v5", 0.0, row.lat, 0.0)
        assertEquals("lon defaults to 0.0 for rows that predate v5", 0.0, row.lon, 0.0)
        assertEquals("", row.stationName)
        assertNull(row.lastTriggeredAt)
    }

    /** The DB on disk now has exactly the current schema: version, identity hash, columns, no saved_places. */
    private fun assertCurrentShape() {
        val sdb = openCurrent().openHelper.writableDatabase
        assertEquals(6, sdb.version)
        assertEquals(schema(6).getAsJsonObject("database")["identityHash"].asString, scalar(sdb, "SELECT identity_hash FROM room_master_table"))
        assertEquals(
            "saved_places must be gone", 0L,
            scalar(sdb, "SELECT count(*) FROM sqlite_master WHERE name = 'saved_places'").toLong(),
        )
        val columns = mutableListOf<String>()
        sdb.query("PRAGMA table_info(active_stations)").use { while (it.moveToNext()) columns += it.getString(it.getColumnIndexOrThrow("name")) }
        val expected = schema(6).getAsJsonObject("database").getAsJsonArray("entities")[0].asJsonObject
            .getAsJsonArray("fields").map { it.asJsonObject["columnName"].asString }
        assertEquals(expected.sorted(), columns.sorted())
        assertFalse(columns.isEmpty())
    }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { it.moveToFirst(); it.getString(0) }

    private fun scalar(db: SQLiteDatabase, sql: String): String =
        db.rawQuery(sql, null).use { it.moveToFirst(); it.getString(0) }

    /** Columns shared by every legacy active_stations version (v2-v4 have exactly these). */
    private fun legacyStationValues(id: String) = ContentValues().apply {
        put("stationId", id); put("alertDistanceKm", 5.0); put("notify", 1); put("vibrate", 0); put("sound", 1)
        put("customReminder", "remind me"); put("sendReminder", 1); put("status", "MONITORING")
    }

    private fun SQLiteDatabase.insertLegacyStation(id: String) {
        insertOrThrow("active_stations", null, legacyStationValues(id))
    }

    /**
     * Writes a database file exactly as Room of that schema version would have: entity tables from the exported
     * createSql, then the exported setupQueries (room_master_table + identity hash), then user_version.
     */
    private fun createLegacyDb(
        version: Int,
        tables: Set<String>? = null,
        withRoomIdentity: Boolean = true,
        seed: (SQLiteDatabase) -> Unit,
    ) {
        val file = context.getDatabasePath(dbName).apply { parentFile!!.mkdirs() }
        val source = schema(if (version == 2) 3 else version)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sdb ->
            source.getAsJsonObject("database").getAsJsonArray("entities").forEach {
                val name = it.asJsonObject["tableName"].asString
                if (tables == null || name in tables) sdb.execSQL(createSql(source, name))
            }
            if (withRoomIdentity) {
                source.getAsJsonObject("database").getAsJsonArray("setupQueries").forEach { sdb.execSQL(it.asString) }
            }
            seed(sdb)
            sdb.version = version
        }
    }

    private fun createSql(schema: JsonObject, table: String): String =
        schema.getAsJsonObject("database").getAsJsonArray("entities").map { it.asJsonObject }
            .first { it["tableName"].asString == table }["createSql"].asString.replace("\${TABLE_NAME}", table)

    private fun schema(version: Int): JsonObject =
        JsonParser.parseString(File(schemaDir, "$version.json").readText()).asJsonObject

    private fun locateSchemaDir(): File {
        val rel = "schemas/com.omama.stationalarm.data.db.StationDatabase"
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("exported Room schemas not found from ${System.getProperty("user.dir")}")
    }
}
