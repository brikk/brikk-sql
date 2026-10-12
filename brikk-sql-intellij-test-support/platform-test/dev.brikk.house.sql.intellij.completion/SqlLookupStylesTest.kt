package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import dev.brikk.house.sql.intellij.completion.SqlLookupKind.*
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.InvocationSyntax
import icons.DatabaseIcons

/** The shared presentation policy, tested once here rather than pinned in each plugin. */
class SqlLookupStylesTest : CompletionTestCase() {
    fun testTheDefaultTable() {
        val styles = SqlLookupStyles.DEFAULT
        fun callable(icon: javax.swing.Icon, type: String) = SqlLookupStyle(icon, type, showsSignature = true, showsReturnType = true)
        val expected = mapOf(
            KEYWORD to SqlLookupStyle(bold = true, case = LookupCase.UPPER),
            FUNCTION to callable(DatabaseIcons.Function, "function"),
            AGGREGATE to callable(DatabaseIcons.Aggregate, "aggregate"),
            WINDOW to callable(DatabaseIcons.Aggregate, "window function"),
            MACRO to callable(DatabaseIcons.Routine, "macro"),
            TABLE_FUNCTION to SqlLookupStyle(DatabaseIcons.Table, "table function", showsSignature = true),
            TABLE_MACRO to SqlLookupStyle(DatabaseIcons.Table, "table macro", showsSignature = true),
            EXTENSION_FUNCTION to SqlLookupStyle(typeText = "requires %s"),
            SERVER_FUNCTION to SqlLookupStyle(typeText = "server"),
            TABLE_FUNCTION_PROPERTY to SqlLookupStyle(AllIcons.Nodes.Parameter, "%s property", priority = 100.0),
            TABLE_FUNCTION_VALUE to SqlLookupStyle(AllIcons.Nodes.Parameter, "%s value", priority = 100.0),
            COLUMN to SqlLookupStyle(DatabaseIcons.Col),
            PIPE_INPUT_COLUMN to SqlLookupStyle(DatabaseIcons.Col, "PIPE input column", priority = 95.0),
            BRIKK_COLUMN to SqlLookupStyle(DatabaseIcons.Col, priority = 120.0),
            CATALOG to SqlLookupStyle(DatabaseIcons.Database, "catalog", priority = 96.0),
            SCHEMA to SqlLookupStyle(DatabaseIcons.Schema, "schema", priority = 96.0),
            TABLE to SqlLookupStyle(DatabaseIcons.Table, "table", priority = 96.0),
        )
        assertEquals(SqlLookupKind.entries.toSet(), expected.keys)
        for ((kind, style) in expected) assertEquals(kind.name, style, styles[kind])
    }

    fun testADialectReplacesOnlyTheRowsItNames() {
        val custom = SqlLookupStyle(typeText = "custom")
        val styles = SqlLookupStyles.DEFAULT.with(FUNCTION to custom)
        assertEquals(custom, styles[FUNCTION])
        assertEquals(SqlLookupStyles.DEFAULT[AGGREGATE], styles[AGGREGATE])
    }

    private fun render(item: LookupElement) = LookupElementPresentation.renderElement(item)

    private fun priority(item: LookupElement) = item.`as`(PrioritizedLookupElement.CLASS_CONDITION_KEY)?.priority ?: 0.0

    private fun offered(name: String, text: String = "SELECT $name<caret>") = run {
        configure(text, DuckdbCompletion::class.java)
        complete().filter { (it.`object` as? FunctionPresentation)?.name == name }
    }

    fun testFunctionItemsFollowTheirKindsRow() {
        val style = SqlLookupStyle(AllIcons.Nodes.Field, "scalar", bold = true, priority = 7.0, case = LookupCase.UPPER, showsSignature = true)
        duckdbProfile.styles = SqlLookupStyles.DEFAULT.with(FUNCTION to style)
        val abs = offered("abs").single { (it.`object` as FunctionPresentation).kind == FunctionKind.SCALAR }
        val entry = abs.`object` as FunctionPresentation
        val presentation = render(abs)
        assertEquals("ABS", abs.lookupString)
        assertEquals(AllIcons.Nodes.Field, presentation.icon)
        assertEquals("scalar", presentation.typeText)
        assertTrue(presentation.isItemTextBold)
        assertEquals("(${entry.signature})  +${entry.overloadCount - 1}", presentation.tailText)
        assertEquals(7.0, priority(abs))
    }

    fun testTheReturnTypeReplacesTheLabelWhenTheRowShowsIt() {
        duckdbProfile.styles = SqlLookupStyles.DEFAULT.with(FUNCTION to SqlLookupStyle(typeText = "scalar", showsReturnType = true))
        val abs = offered("abs").single { (it.`object` as FunctionPresentation).kind == FunctionKind.SCALAR }
        val presentation = render(abs)
        assertEquals((abs.`object` as FunctionPresentation).returnType, presentation.typeText)
        assertNull(presentation.tailText)
    }

    fun testExtensionAndServerItemsTakeTheirLabelAndKeepTheirKindsIcon() {
        val extension = DUCKDB_EXTENSION_ENTRY
        val server = FunctionPresentation("server_only_fn", false, FunctionKind.AGGREGATE, null, null, null, 0, null, setOf(InvocationSyntax.CALL))
        duckdbProfile.serverOnly = listOf(server)
        configure("SELECT ${extension.name}<caret>", DuckdbCompletion::class.java)
        val extensionItem = render(complete().single { it.`object` == extension })
        assertEquals("requires ${extension.extension}", extensionItem.typeText)
        assertEquals(DatabaseIcons.Function, extensionItem.icon)
        configure("SELECT server_only<caret>", DuckdbCompletion::class.java)
        val serverItem = render(complete().single { it.`object` == server })
        assertEquals("server", serverItem.typeText)
        assertEquals(DatabaseIcons.Aggregate, serverItem.icon)
    }

    fun testAPlainItemTakesTheRowsPresentation() {
        val item = SqlLookupStyles.DEFAULT[CATALOG].lookup("Warehouse")
        val presentation = render(item)
        assertEquals(DatabaseIcons.Database, presentation.icon)
        assertEquals("catalog", presentation.typeText)
        assertEquals(96.0, priority(item))
        assertEquals("Warehouse", item.lookupString)
    }

    private companion object {
        val DUCKDB_EXTENSION_ENTRY: FunctionPresentation =
            dev.brikk.house.sql.metadata.DUCKDB_METADATA.default.completableFunctions().first { it.extension != null && it.kind == FunctionKind.SCALAR }
    }
}
