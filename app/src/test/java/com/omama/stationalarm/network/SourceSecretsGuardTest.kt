package com.omama.stationalarm.network

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Guards CLAUDE.md's non-negotiables by scanning the shipped sources (app/src/main) and app/build.gradle.kts:
 *   1. no API key / token in the APK            4. no Mapbox
 *   2. no `countrycodes` restriction             5. the app never talks to LocationIQ directly (Worker only)
 * Pure JVM; the working directory of an AGP unit test is the module directory (app/).
 */
class SourceSecretsGuardTest {

    companion object {
        private lateinit var mainDir: File
        private lateinit var files: List<File>

        private val textExt = setOf("kt", "java", "xml", "kts", "gradle", "properties", "pro", "json", "txt", "md")

        @JvmStatic
        @BeforeClass
        fun scan() {
            val dir = listOf("src/main", "app/src/main").map(::File).firstOrNull { it.isDirectory }
            assertNotNull("src/main not found from ${File(".").absolutePath}", dir)
            mainDir = dir!!
            files = mainDir.walkTopDown().filter {
                it.isFile && it.extension in textExt && it.name != "stations.json"
            }.toList() + listOfNotNull(File(mainDir, "../../build.gradle.kts").takeIf { it.isFile })
        }

        private fun hits(regex: Regex, codeOnly: Boolean = false): List<String> = files.flatMap { f ->
            f.readLines(Charsets.UTF_8).mapIndexedNotNull { i, line ->
                val t = line.trim()
                val isComment = t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                if (codeOnly && isComment) null
                else if (regex.containsMatchIn(line)) "${f.name}:${i + 1}: ${t.take(120)}" else null
            }
        }
    }

    @Test
    fun scansARealisticNumberOfFiles() = assertTrue("only ${files.size} files", files.size > 50)

    @Test
    fun noKnownApiKeyShapes() {
        val patterns = listOf(
            Regex("""[ps]k\.[0-9a-fA-F]{16,}"""),          // LocationIQ access tokens
            Regex("""[ps]k\.eyJ"""),                       // Mapbox tokens
            Regex("""AIza[0-9A-Za-z_\-]{35}"""),           // Google API keys
            Regex("""AKIA[0-9A-Z]{16}"""),                 // AWS
            Regex("""(?i)(api[_-]?key|apikey|access[_-]?token|auth[_-]?token|client[_-]?secret)\s*[=:]\s*["'][^"']{8,}["']"""),
            Regex("""(?i)bearer\s+[A-Za-z0-9._\-]{16,}"""),
            Regex("""(?<![0-9a-fA-F])[0-9a-f]{32,}(?![0-9a-fA-F])"""),   // long hex blobs
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        )
        val found = patterns.flatMap { hits(it) }
        assertTrue("key-like strings in the APK sources:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun noCountryCodesRestriction_appIsGlobal() {
        val found = hits(Regex("(?i)countrycodes"))
        assertTrue("countrycodes found:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun appNeverTalksToLocationIqDirectly() {
        val found = hits(Regex("""(?i)(https?://|\b[a-z0-9-]+\.)(api\.|us1\.|eu1\.|ap1\.)?locationiq\.(com|org)/?[^\s"'<]*"""), codeOnly = true) +
            // attribution text ("Search by LocationIQ.com") is allowed in strings.xml, URLs are not
            hits(Regex("""(?i)https?://[^\s"'<]*locationiq"""))
        assertTrue("direct LocationIQ endpoint:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun noMapboxSdkOrEndpoint() {
        val found = hits(Regex("""(?i)(com\.mapbox|api\.mapbox|mapbox\.com|mapbox-android)"""))
        assertTrue("Mapbox usage (TOS violation):\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun everyHardcodedUrlInKotlinCode_isOnAnApprovedHost() {
        val approved = listOf(
            "stationalarm-geo.mohammedomama2005.workers.dev", // geocoding Worker (default; Remote Config may override)
            "play.google.com", "stationalarm-legal.pages.dev", "schemas.android.com",
        )
        val url = Regex("""https?://([A-Za-z0-9.\-]+)""")
        val offenders = files.filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@mapIndexedNotNull null
                url.findAll(line).map { it.groupValues[1] }.firstOrNull { h -> approved.none { h == it } }
                    ?.let { "${f.name}:${i + 1}: $it" }
            }
        }
        assertTrue("unapproved hosts (review before shipping):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun geocodingDefaultUrlIsHttpsAndHasNoCredentialsInIt() {
        val line = hits(Regex("""DEFAULT_WORKER_URL\s*=\s*"""), codeOnly = true).single()
        assertTrue(line, line.contains("\"https://") && !line.contains("@") && !line.contains("?") && !line.contains("key", true))
    }
}
