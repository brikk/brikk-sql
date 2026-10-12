package dev.brikk.house.sql.intellij.completion

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import dev.brikk.house.sql.intellij.completion.SqlLookupKind.*
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.InvocationSyntax
import icons.DatabaseIcons
import java.awt.image.BufferedImage
import javax.swing.Icon

/** The shared presentation policy, tested once here rather than pinned in each plugin. */
class SqlLookupStylesTest : CompletionTestCase() {
    fun testTheDefaultTable() {
        val styles = SqlLookupStyles.DEFAULT
        fun callable(icon: Icon, type: String) = SqlLookupStyle(icon, type, showsSignature = true, showsReturnType = true)
        val expected = mapOf(
            KEYWORD to SqlLookupStyle(bold = true, case = LookupCase.UPPER),
            FUNCTION to callable(DatabaseIcons.Function, "function"),
            AGGREGATE to callable(DatabaseIcons.Aggregate, "aggregate"),
            WINDOW to callable(SqlLookupIcons.WindowFunction, "window function"),
            MACRO to callable(DatabaseIcons.Routine, "macro"),
            TABLE_FUNCTION to SqlLookupStyle(DatabaseIcons.Table, "table function", showsSignature = true),
            TABLE_MACRO to SqlLookupStyle(DatabaseIcons.Table, "table macro", showsSignature = true),
            EXTENSION_FUNCTION to SqlLookupStyle(typeText = "requires %s"),
            SERVER_FUNCTION to SqlLookupStyle(typeText = "server"),
            TABLE_FUNCTION_PROPERTY to SqlLookupStyle(AllIcons.Nodes.Parameter, "%s property", priority = 100.0),
            TABLE_FUNCTION_VALUE to SqlLookupStyle(AllIcons.Nodes.Parameter, "%s value", priority = 100.0),
            COLUMN to SqlLookupStyle(DatabaseIcons.Col),
            PIPE_STAGE to SqlLookupStyle(typeText = "PIPE stage", bold = true, case = LookupCase.UPPER),
            PIPE_INPUT_COLUMN to SqlLookupStyle(DatabaseIcons.Col, "PIPE input column", priority = 95.0),
            BRIKK_COLUMN to SqlLookupStyle(SqlLookupIcons.BrikkColumn, priority = 120.0),
            CATALOG to SqlLookupStyle(DatabaseIcons.Database, "catalog", priority = 96.0),
            SCHEMA to SqlLookupStyle(DatabaseIcons.Schema, "schema", priority = 96.0),
            TABLE to SqlLookupStyle(DatabaseIcons.Table, "table", priority = 96.0),
        )
        assertEquals(SqlLookupKind.entries.toSet(), expected.keys)
        for ((kind, style) in expected) assertEquals(kind.name, style, styles[kind])
    }

    fun testTheLibraryIconsLoadInBothThemes() {
        // Light tests paint no icons unless the loader is active.
        IconLoader.activate()
        Disposer.register(testRootDisposable, IconLoader::deactivate)
        for ((icon, name) in listOf(SqlLookupIcons.WindowFunction to "windowFunction", SqlLookupIcons.BrikkColumn to "brikkColumn")) {
            for (variant in listOf(name, "${name}_dark")) {
                assertNotNull(variant, SqlLookupIcons::class.java.classLoader.getResource("dev/brikk/house/sql/intellij/icons/$variant.svg"))
            }
            val light = pixels(icon)
            val dark = pixels(IconLoader.getDarkIcon(icon, true))
            assertTrue(name, light.any { it ushr 24 != 0 })
            assertFalse("$name has its own dark variant", light.contentEquals(dark))
        }
    }

    private fun pixels(icon: Icon): IntArray {
        assertEquals(16, icon.iconWidth)
        assertEquals(16, icon.iconHeight)
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply { icon.paintIcon(null, this, 0, 0); dispose() }
        return image.getRGB(0, 0, 16, 16, null, 0, 16)
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
        duckdbProfile.styles = SqlLookupStyles.DEFAULT.with(
            FUNCTION to SqlLookupStyle(AllIcons.Nodes.Function, "scalar"),
            AGGREGATE to SqlLookupStyle(AllIcons.Nodes.Method, "aggregate"),
            EXTENSION_FUNCTION to SqlLookupStyle(AllIcons.Nodes.Plugin, "needs %s"),
            SERVER_FUNCTION to SqlLookupStyle(AllIcons.Nodes.Plugin, "on server"),
        )
        configure("SELECT ${extension.name}<caret>", DuckdbCompletion::class.java)
        val extensionItem = render(complete().single { it.`object` == extension })
        assertEquals("needs ${extension.extension}", extensionItem.typeText)
        assertEquals(AllIcons.Nodes.Function, extensionItem.icon)
        configure("SELECT server_only<caret>", DuckdbCompletion::class.java)
        val serverItem = render(complete().single { it.`object` == server })
        assertEquals("on server", serverItem.typeText)
        assertEquals(AllIcons.Nodes.Method, serverItem.icon)
    }

    fun testAPlainItemTakesTheRowsPresentation() {
        val style = SqlLookupStyle(AllIcons.Nodes.Folder, "warehouse", bold = true, priority = 3.0, case = LookupCase.LOWER)
        val item = SqlLookupStyles.DEFAULT.with(CATALOG to style)[CATALOG].lookup("Warehouse")
        val presentation = render(item)
        assertEquals(AllIcons.Nodes.Folder, presentation.icon)
        assertEquals("warehouse", presentation.typeText)
        assertTrue(presentation.isItemTextBold)
        assertEquals(3.0, priority(item))
        assertEquals("warehouse", item.lookupString)
    }

    fun testANativeKindTakesTheRowTheProfileMapsItTo() {
        val native = dev.brikk.house.sql.metadata.DUCKDB_METADATA.default.completableFunctions()
            .first { it.nativeKind != null && it.kind == FunctionKind.SCALAR && it.extension == null }
        duckdbProfile.styles = SqlLookupStyles.DEFAULT.with(MACRO to SqlLookupStyle(typeText = "mapped"), FUNCTION to SqlLookupStyle(typeText = "plain"))
        fun typeText(): String? {
            configure("SELECT ${native.name}<caret>", DuckdbCompletion::class.java)
            return render(complete().single { it.`object` == native }).typeText
        }
        assertEquals("plain", typeText())
        duckdbProfile.nativeKinds = mapOf(native.nativeKind!! to MACRO)
        assertEquals("mapped", typeText())
    }

    private companion object {
        val DUCKDB_EXTENSION_ENTRY: FunctionPresentation =
            dev.brikk.house.sql.metadata.DUCKDB_METADATA.default.completableFunctions().first { it.extension != null && it.kind == FunctionKind.SCALAR }
    }
}
