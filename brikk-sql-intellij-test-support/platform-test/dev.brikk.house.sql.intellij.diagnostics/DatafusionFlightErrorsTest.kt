package dev.brikk.house.sql.intellij.diagnostics

import com.intellij.openapi.util.TextRange
import dev.brikk.house.sql.intellij.pipe.PipeRunStatement
import junit.framework.TestCase
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

class DatafusionFlightErrorsTest : TestCase() {
    fun testCapturedNativeColumnsMapSupplementaryCharactersToUtf16() {
        val path = "vendor/data/datafusion-spice/round10/spice-execution.json"
        val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve(path)) }
        val observations = Json.parseToJsonElement(Files.readString(root.resolve(path))).jsonObject.getValue("observations").jsonObject
        val message = observations.getValue("parse_error_position").jsonObject.getValue("message").jsonPrimitive.content
        val sql = "SELECT '😀' AS x |> BOGUS 1"
        val error = DatafusionFlightErrors.syntaxError(sql, message)
        assertEquals(sql.indexOf("BOGUS") - 1, error.offset)
        assertEquals(1, error.line)
        assertEquals(20, error.column)
        var nativeCoordinates: Pair<Int, Int>? = null
        val statement = PipeRunStatement("authored", sql, TextRange(0, 8)) { line, column ->
            nativeCoordinates = line to column
            TextRange(2, 4)
        }
        assertEquals(TextRange(2, 4), DatafusionFlightErrors.authoredRange(statement, message))
        assertEquals(1 to 19, nativeCoordinates)
        val noPosition = observations.getValue("plan_error").jsonObject.getValue("message").jsonPrimitive.content
        assertNull(DatafusionFlightErrors.syntaxError(sql, noPosition).offset)
        assertNull(DatafusionFlightErrors.authoredRange(statement, noPosition))
        val df = Json.parseToJsonElement(Files.readString(root.resolve("vendor/data/datafusion-spice/round10/datafusion-execution.json")))
            .jsonObject.getValue("observations").jsonObject.getValue("parse_error_position").jsonObject.getValue("message").jsonPrimitive.content
        assertEquals(sql.indexOf("BOGUS"), DatafusionFlightErrors.syntaxError(sql, df).offset)
    }
    fun testUnavailableCoordinatesAndParameterExpandedMapsStayUnknown() {
        val sql = "SELECT 'at Line: 2, Column: 1'"
        assertNull(DatafusionFlightErrors.syntaxError(sql, "Error while executing SQL \"$sql\": schema unavailable").offset)
        assertNull(DatafusionFlightErrors.syntaxError(sql, "Expected expression at Line: 99, Column: 1").offset)
        assertNull(DatafusionFlightErrors.syntaxError(sql, "Expected expression at Line: 999999999999, Column: 1").offset)
        val statement = PipeRunStatement(sql, sql, TextRange(0, sql.length), null)
        assertNull(DatafusionFlightErrors.authoredRange(statement, "Expected expression at Line: 1, Column: 1"))
    }
}
