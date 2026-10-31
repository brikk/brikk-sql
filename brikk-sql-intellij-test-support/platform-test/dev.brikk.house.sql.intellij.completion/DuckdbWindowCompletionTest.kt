package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import dev.brikk.house.sql.metadata.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assume

/** These run the real completion contributor and insertion handler in the IDE, not a catalog-only mock. */
class DuckdbWindowCompletionTest : CompletionTestCase() {
    fun testBuiltinWindowIsOfferedBeforeOverAndInsertionKeepsTheWindowSpecification() {
        configure("SELECT row_n<caret> OVER (PARTITION BY p ORDER BY v)",DuckdbCompletion::class.java)
        val entry=functionItems().single { it.name=="row_number" && it.kind==FunctionKind.WINDOW }
        accept(entry)
        assertEquals("SELECT row_number()<caret> OVER (PARTITION BY p ORDER BY v)",resultWithCaret())
    }
    fun testAggregateWithOverStaysAnAggregateAndHasADifferentIconFromAWindowFunction() {
        configure("SELECT row_n<caret>() OVER (ORDER BY v)",DuckdbCompletion::class.java)
        val window=complete().single { (it.`object` as? FunctionPresentation)?.let { entry -> entry.name=="row_number" && entry.kind==FunctionKind.WINDOW }==true }
        val windowIcon=LookupElementPresentation.renderElement(window).icon
        configure("SELECT su<caret>(v) OVER (ORDER BY v)",DuckdbCompletion::class.java)
        val aggregate=complete().single { (it.`object` as? FunctionPresentation)?.let { entry -> entry.name=="sum" && entry.kind==FunctionKind.AGGREGATE }==true }
        assertEquals(SqlLookupIcons.WindowFunction,windowIcon)
        assertFalse(windowIcon==LookupElementPresentation.renderElement(aggregate).icon)
    }
    fun testScalarCompletionInsideAnOverOrderingKeepsTheExistingArgumentsAndFrame() {
        configure("SELECT sum(v) OVER (PARTITION BY p ORDER BY ab<caret>(v) ROWS UNBOUNDED PRECEDING)",DuckdbCompletion::class.java)
        val scalar=functionItems().single { it.name=="abs" && it.kind==FunctionKind.SCALAR }
        accept(scalar)
        assertEquals("SELECT sum(v) OVER (PARTITION BY p ORDER BY abs(<caret>v) ROWS UNBOUNDED PRECEDING)",resultWithCaret())
    }
    fun testLiveExtensionWindowFromSignedNativeCaptureIsOfferedWithItsWindowIconAndInsertedBeforeOver() {
        live()
        configure("SELECT brikk_row_n<caret> OVER (PARTITION BY p ORDER BY v)",LiveDuckdbWindowCompletion::class.java)
        val item=complete().single { (it.`object` as? FunctionPresentation)?.name=="brikk_row_number" }
        val entry=item.`object` as FunctionPresentation
        assertEquals(FunctionKind.WINDOW,entry.kind)
        assertEquals(SqlLookupIcons.WindowFunction,LookupElementPresentation.renderElement(item).icon)
        accept(entry)
        assertEquals("SELECT brikk_row_number()<caret> OVER (PARTITION BY p ORDER BY v)",resultWithCaret())
    }
    fun testLiveSameNameScalarAndWindowAreBothOfferedAsDistinctLookupsBeforeOver() {
        live()
        configure("SELECT brikk_ki<caret>() OVER (ORDER BY v)",LiveDuckdbWindowCompletion::class.java)
        val items=complete().filter { (it.`object` as? FunctionPresentation)?.name=="brikk_kind" }
        assertEquals(setOf(FunctionKind.SCALAR,FunctionKind.WINDOW),items.map { (it.`object` as FunctionPresentation).kind }.toSet())
        assertEquals(2,items.size)
        assertFalse(LookupElementPresentation.renderElement(items[0]).icon==LookupElementPresentation.renderElement(items[1]).icon)
        val window=items.single { (it.`object` as FunctionPresentation).kind==FunctionKind.WINDOW }.`object` as FunctionPresentation
        accept(window)
        assertEquals("SELECT brikk_kind(<caret>) OVER (ORDER BY v)",resultWithCaret())
    }
    private fun live() {
        Assume.assumeTrue("Run native signed window fixture verification and set BRIKK_DUCKDB_WINDOW_CAPTURE",System.getenv("BRIKK_DUCKDB_WINDOW_CAPTURE")!=null)
        // Force validation before the contributor is registered. No engine is touched on the EDT.
        liveWindowProfile.metadata.default
    }
}

private val liveWindowProfile: TestCompletionProfile by lazy {
    val file=Path.of(checkNotNull(System.getenv("BRIKK_DUCKDB_WINDOW_CAPTURE")))
    val capture=Json.parseToJsonElement(Files.readString(file)).jsonObject
    check(capture.getValue("engine_version").jsonPrimitive.content=="v2.0.0")
    check(!capture.getValue("allow_unsigned_extensions").jsonPrimitive.boolean)
    check(capture.getValue("executed_window_results").jsonPrimitive.boolean)
    check(capture.getValue("source_revision").jsonPrimitive.content=="d54cbbe3df17e8e5ce68312f483b4155836112aa")
    System.getenv("BRIKK_DUCKDB_WINDOW_EXTENSION")?.let { binary ->
        val hash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(binary))).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(hash==capture.getValue("template_sha256").jsonPrimitive.content)
    }
    check(capture.getValue("columns").jsonArray.map { it.jsonPrimitive.content }==DUCKDB_FUNCTIONS.columns)
    val rows=capture.getValue("after").jsonArray.map { row -> DUCKDB_FUNCTIONS.decode(row.jsonArray.map { it.jsonPrimitive.contentOrNull }) }
    val snapshot=DuckdbDump("2.0.0",rows).toSnapshot()
    check(snapshot.functions["brikk_row_number"]?.kind==FunctionKind.WINDOW)
    TestCompletionProfile(DialectMetadata("duckdb",listOf(snapshot),"2.0.0"))
}

class LiveDuckdbWindowCompletion : SqlCompletionContributor(liveWindowProfile)
