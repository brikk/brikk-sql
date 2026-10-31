package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.dataTypeFromStr
import dev.brikk.house.sql.shape.SqlFragment
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

class DatafusionAnalysisEvidenceTest {
    @Test
    fun staticClaimsAgreeWithPinnedEngineFields() {
        val root = File(projectRoot(), "vendor/data/datafusion-spice/round5")
        val inputs = Json.parseToJsonElement(File(root, "cases.json").readText()).jsonArray
        val observations = Json.parseToJsonElement(File(root, "upstream.json").readText()).jsonObject
        var checked = 0
        for (input in inputs) {
            val id = input.jsonObject.getValue("id").jsonPrimitive.content
            val native = observations[id]?.jsonObject ?: continue
            if (native.getValue("status").jsonPrimitive.content != "accepted") continue
            val sql = input.jsonObject.getValue("sql").jsonPrimitive.content
            val shape = SqlFragment(sql, "datafusion").outputShape()
            val fields = native.getValue("fields").jsonArray
            assertEquals(fields.map { it.jsonObject.getValue("name").jsonPrimitive.content }, shape.names(), id)
            for ((column, field) in shape.columns.zip(fields)) {
                if (column.type == "UNKNOWN") continue
                val actual = dataTypeFromStr(column.type, Dialects.DATAFUSION)
                val expected = dataTypeFromStr(field.jsonObject.getValue("type").jsonPrimitive.content, Dialects.DATAFUSION)
                assertEquals(logical(expected), logical(actual), "$id: ${column.type}")
                if (column.nullable != null) assertEquals(field.jsonObject.getValue("nullable").jsonPrimitive.boolean, column.nullable, "$id nullability")
                checked++
            }
        }
        assertTrue(checked >= 40, "Only $checked field claims checked")
    }

    private fun logical(type: DataType): kotlin.String = type.thisArg.toString() + type.expressionsArg.joinToString(prefix = "[", postfix = "]") {
        when (it) {
            is DataType -> logical(it)
            is ColumnDef -> it.name + ":" + logical(it.args["kind"] as DataType)
            is DataTypeParam -> it.toString()
            else -> it.toString()
        }
    }

    @Test
    fun spiceTypeClaimsAgreeWithReleasedRuntime() {
        val root = File(projectRoot(), "vendor/data/datafusion-spice/round5")
        val inputs = Json.parseToJsonElement(File(root, "cases.json").readText()).jsonArray
        val observations = Json.parseToJsonElement(File(root, "spice.json").readText()).jsonObject.getValue("cases").jsonObject
        var checked = 0
        for (input in inputs) {
            val id = input.jsonObject.getValue("id").jsonPrimitive.content
            // HTTP's JSON object cannot represent the duplicate id labels of the qualified-star control.
            if (id == "qualified-using") continue
            val native = observations.getValue(id).jsonObject
            val types = native["type_observation"]?.jsonObject?.get("rows")?.jsonArray?.firstOrNull()?.jsonObject ?: continue
            val shape = SqlFragment(input.jsonObject.getValue("sql").jsonPrimitive.content, "spice").outputShape()
            assertEquals(types.keys.toList(), shape.names(), id)
            for (column in shape.columns) {
                if (column.type == "UNKNOWN") continue
                assertEquals(logical(dataTypeFromStr(types.getValue(column.name).jsonPrimitive.content, Dialects.SPICE)),
                    logical(dataTypeFromStr(column.type, Dialects.SPICE)), "$id: ${column.type}")
                checked++
            }
        }
        assertTrue(checked >= 45, "Only $checked field claims checked")
    }
}
