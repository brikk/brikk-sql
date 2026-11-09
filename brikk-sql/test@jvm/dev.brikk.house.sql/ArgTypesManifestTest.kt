package dev.brikk.house.sql

import dev.brikk.house.sql.ast.ExpressionRegistry
import dev.brikk.house.sql.ast.NATIVE_EXPRESSION_CLASSES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Gate: the argTypes of every node class (handwritten AND generated) must match
 * sqlglot's arg_types exactly — same keys, same order, same required flags — as
 * recorded in ast-corpus/arg-types-manifest.json by tools/gen_ast_nodes.py.
 */
class ArgTypesManifestTest {

    private fun loadManifest() =
        Json.parseToJsonElement(testResource("ast-corpus/arg-types-manifest.json")).jsonObject

    @Test
    fun argTypesMatchManifest() {
        val manifest = loadManifest()
        val classes = manifest.getValue("classes").jsonObject
        check(classes.isNotEmpty()) { "empty manifest" }
        val compatibility = manifest.getValue("compatibility_arg_types").jsonObject
        assertEquals(setOf("ArraySort", "Lambda", "Nearest"), compatibility.keys)
        val upstreamShapes = mapOf(
            "ArraySort" to listOf("this" to true, "expression" to false, "asc" to false, "nulls_first" to false),
            "Lambda" to listOf("this" to true, "expressions" to true),
            "Nearest" to listOf("this" to true, "count" to true, "approximate" to false, "similarity" to false),
        )
        val retainedShapes = mapOf(
            "ArraySort" to listOf("this" to true, "expression" to false),
            "Lambda" to listOf("this" to true, "expressions" to true, "colon" to false),
            "Nearest" to listOf("this" to true, "count" to false, "approximate" to false, "exact" to false, "similarity" to false),
        )
        fun fields(obj: kotlinx.serialization.json.JsonObject) = obj.entries.map { (k, v) -> k to v.jsonPrimitive.boolean }
        for ((name, shape) in retainedShapes) assertEquals(shape, fields(compatibility.getValue(name).jsonObject))
        assertEquals(setOf("AggregateKeyProperty"), manifest.getValue("native_module_overrides").jsonObject.keys)
        assertEquals("brikk.doris", manifest.getValue("native_module_overrides").jsonObject.getValue("AggregateKeyProperty").jsonPrimitive.content)
        val legacy = manifest.getValue("legacy_classes").jsonObject
        assertEquals(setOf("SortArray"), legacy.keys)
        assertEquals("sqlglot.expressions.array", legacy.getValue("SortArray").jsonObject.getValue("module").jsonPrimitive.content)
        assertEquals(listOf("this" to true, "asc" to false, "nulls_first" to false),
            fields(legacy.getValue("SortArray").jsonObject.getValue("arg_types").jsonObject))

        val failures = mutableListOf<String>()

        for ((name, spec) in classes) {
            val obj = spec.jsonObject
            val entry = ExpressionRegistry.entries[name]
            if (entry == null) {
                failures.add("$name: not registered in ExpressionRegistry")
                continue
            }

            val expectedModule = if (name == "AggregateKeyProperty") {
                assertEquals("sqlglot.expressions.properties", obj.getValue("module").jsonPrimitive.content)
                "brikk.doris"
            } else obj.getValue("module").jsonPrimitive.content
            if (entry.module != expectedModule) {
                failures.add("$name: module ${entry.module} != $expectedModule")
            }

            val node = entry.factory()
            if (node::class.simpleName != name) {
                failures.add("$name: factory produced ${node::class.simpleName}")
                continue
            }

            var expected = obj.getValue("arg_types").jsonObject
                .entries.map { (k, v) -> k to v.jsonPrimitive.boolean }
            if (name in upstreamShapes) {
                assertEquals(upstreamShapes.getValue(name), expected, "Review a changed upstream compatibility shape")
                expected = retainedShapes.getValue(name)
            }
            val actual = node.argTypes.entries.map { (k, v) -> k to v }
            if (expected != actual) {
                failures.add("$name:\n  expected ${expected.toMap()}\n  actual   ${actual.toMap()}")
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size}/${classes.size} argTypes mismatches:\n" + failures.joinToString("\n"))
        }

        // Registry must contain exactly the manifest's classes plus the explicitly
        // allowlisted NATIVE (brikk-original) classes — no gaps, no strays. The Python
        // parity check above is untouched: every manifest class is still verified.
        val native = ExpressionRegistry.entries.filterKeys { it in NATIVE_EXPRESSION_CLASSES }
        val bounded = legacy.keys
        for ((name, spec) in legacy) {
            val entry = ExpressionRegistry.entries.getValue(name)
            assertEquals(spec.jsonObject.getValue("module").jsonPrimitive.content, entry.module)
            assertEquals(fields(spec.jsonObject.getValue("arg_types").jsonObject), entry.factory().argTypes.entries.map { it.key to it.value })
            check(name !in classes)
        }
        assertEquals(
            NATIVE_EXPRESSION_CLASSES,
            native.keys,
            "NATIVE_EXPRESSION_CLASSES out of sync with registry NATIVE section",
        )
        for ((name, entry) in native) {
            if (!entry.module.startsWith("brikk.")) {
                fail("$name: native class must use a brikk module, got ${entry.module}")
            }
            if (name in classes.keys && name != "AggregateKeyProperty") fail("$name: native class collides with a Python manifest class")
        }
        assertEquals(
            classes.size + native.size + bounded.size - 1,
            ExpressionRegistry.entries.size,
            "registry size != manifest class count + native allowlist; strays: " +
                (ExpressionRegistry.entries.keys - classes.keys - NATIVE_EXPRESSION_CLASSES - bounded),
        )
    }
}
