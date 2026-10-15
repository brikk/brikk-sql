package dev.brikk.house.sql.docs

import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every bundled docs page answers 200. It reaches the engines' sites, so it runs only with
 * `BRIKK_DOCS_CHECK=1`: a few requests at a time, within an overall deadline.
 */
class EngineDocsPagesTest {
    @Test
    fun theBundledPagesResolve() {
        if (System.getenv("BRIKK_DOCS_CHECK") != "1") return println("EngineDocsPagesTest: set BRIKK_DOCS_CHECK=1 to check the docs pages")
        val urls = METADATA.values.mapNotNull { it.docs }.flatMap { docs ->
            (docs.statements.map { it.path } + docs.tableFunctions.values + docs.engines.values).distinct().map(docs::url)
        }.distinct()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val answers = pool.invokeAll(urls.map { url -> Callable { url to status(url) } }, 5, TimeUnit.MINUTES)
            val failures = answers.mapNotNull { answer ->
                if (answer.isCancelled) "timed out" else answer.get().takeIf { it.second != 200 }?.let { "${it.second} ${it.first}" }
            }
            assertTrue(urls.isNotEmpty())
            assertEquals(emptyList(), failures)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun status(url: String): Int = runCatching {
        (URI(url).toURL().openConnection() as HttpURLConnection).run {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "brikk-sql docs check")
            responseCode.also { disconnect() }
        }
    }.getOrDefault(-1)
}
