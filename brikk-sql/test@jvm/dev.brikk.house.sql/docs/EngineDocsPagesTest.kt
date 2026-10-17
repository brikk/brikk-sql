package dev.brikk.house.sql.docs

import dev.brikk.house.sql.metadata.EngineDocs
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every bundled docs page answers 200, is no not-found page, and documents what links to it: its `<title>`
 * or `<h1>` names the entry's title, or the table function or engine its address names. Sites with a version
 * badge still show [EngineDocs.release]. It reaches the engines' sites, so it runs only with
 * `BRIKK_DOCS_CHECK=1`, a few requests at a time within an overall deadline:
 *
 *     BRIKK_DOCS_CHECK=1 ./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.docs.EngineDocsPagesTest'
 */
class EngineDocsPagesTest {
    /** A page to check and the names its heading may carry. */
    private data class Page(val url: String, val names: Set<String>)

    @Test
    fun theBundledPagesDocumentTheirConstructs() {
        if (System.getenv("BRIKK_DOCS_CHECK") != "1") return println("EngineDocsPagesTest: set BRIKK_DOCS_CHECK=1 to check the docs pages")
        val docs = METADATA.values.mapNotNull { it.docs }
        val pages = docs.flatMap(::pages).groupBy { it.url }.map { (url, all) -> Page(url, all.flatMap { it.names }.toSet()) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val answers = pool.invokeAll(pages.map { page -> Callable { problem(page) } }, 5, TimeUnit.MINUTES)
            val problems = answers.zip(pages).mapNotNull { (answer, page) -> if (answer.isCancelled) "timed out: ${page.url}" else answer.get() } +
                docs.mapNotNull(::drift)
            assertTrue(pages.isNotEmpty())
            assertEquals(emptyList(), problems, problems.joinToString("\n", prefix = "\n"))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun pages(docs: EngineDocs): List<Page> =
        docs.statements.mapNotNull { entry -> entry.path?.let { Page(docs.url(it), setOf(entry.title) + addressNames(it)) } } +
            (docs.tableFunctions.values + docs.engines.values).map { path -> Page(docs.url(path), addressNames(path)) }

    /** The names a page's address gives it: its last segment, or its folder's for an `overview`, and a heading the address cannot say. */
    private fun addressNames(path: String): Set<String> {
        val segments = path.trimEnd('/').split('/')
        val name = segments.last().takeUnless { it == "overview" } ?: segments.dropLast(1).last()
        return setOfNotNull(name, HEADINGS.entries.firstOrNull { path.trimEnd('/').endsWith(it.key) }?.value)
    }

    private fun problem(page: Page): String? {
        val (status, body) = fetch(page.url)
        if (status != 200) return "$status ${page.url}"
        if (NOT_FOUND.containsMatchIn(heading(body))) return "not found: ${page.url}"
        val heading = normalized(heading(body))
        return if (page.names.any { normalized(it) in heading }) null else "heading '${heading(body).trim()}' names none of ${page.names}: ${page.url}"
    }

    /** A site's version badge against the release its current or latest pages are said to show. */
    private fun drift(docs: EngineDocs): String? {
        val release = docs.release ?: return null
        val badge = BADGES[docs.dialect] ?: return null
        val shown = badge.find(fetch(docs.url(docs.statements.first { it.path != null }.path!!)).second)?.groupValues?.get(1)
        return if (shown == release) null else "${docs.dialect} pages show release $shown, not ${docs.release}: pin or update EngineDocs.release"
    }

    /** The page's `<title>` and every `<h1>`, tags removed. */
    private fun heading(body: String): String =
        (TITLE.findAll(body) + H1.findAll(body)).joinToString(" ") { it.groupValues[1].replace(TAG, " ") }

    private fun fetch(url: String): Pair<Int, String> = runCatching {
        (URI(url).toURL().openConnection() as HttpURLConnection).run {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "brikk-sql docs check")
            val code = responseCode
            (code to if (code == 200) inputStream.bufferedReader().readText() else "").also { disconnect() }
        }
    }.getOrDefault(-1 to "")

    private companion object {
        val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val H1 = Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val TAG = Regex("<[^>]+>")
        val NOT_FOUND = Regex("page not found|404", RegexOption.IGNORE_CASE)

        /** Lower case, with `_`, `-` and runs of spaces as one space: `SET-LDAP_ADMIN_PASSWORD` and `set ldap admin password` match. */
        fun normalized(text: String): String = text.lowercase().replace(Regex("&[a-z]+;|[_\\-\\s]+"), " ").trim()

        /** Pages whose heading their address does not name. */
        val HEADINGS = mapOf("mergetree-family/replication" to "replicated", "sql/meta/duckdb_table_functions" to "metadata functions")

        val BADGES = mapOf(
            "starrocks" to Regex("Latest-(\\d+\\.\\d+)"),
            "duckdb" to Regex("class=\"selectedversion\">(\\d+\\.\\d+)"),
        )
    }
}
