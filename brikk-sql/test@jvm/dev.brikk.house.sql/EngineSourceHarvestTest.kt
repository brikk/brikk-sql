package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Lambda
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Original engine fixtures are read unchanged; no Polyglot engine corpus is imported. */
class EngineSourceHarvestTest {
    private val root = File(projectRoot(), "vendor/data/test-expansion")

    @Test fun vendoredSourcesMatchTheirPinnedHashes() {
        val manifest = Json.parseToJsonElement(File(root, "manifest.json").readText()).jsonObject
        val rows = manifest.getValue("files").jsonArray
        assertEquals(16, rows.size)
        for (element in rows) {
            val row = element.jsonObject
            val path = row.getValue("local_path").jsonPrimitive.content
            val data = File(root, path).readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            assertEquals(row.getValue("sha256").jsonPrimitive.content, hash, path)
            val revision = row.getValue("revision").jsonPrimitive.content
            assertTrue(Regex("[0-9a-f]{40}").matches(revision), path)
            assertTrue("/$revision/" in row.getValue("url").jsonPrimitive.content, path)
        }
    }

    @Test fun clickhouseNativeLambdaFixturesRemainStructuredThroughCopyAndSerde() {
        for ((name, lambdaCount) in listOf(
            "00157_aliases_and_lambda_formal_parameters" to 1,
            "01960_lambda_precedence" to 3,
        )) {
            val source = File(root, "clickhouse/tests/queries/0_stateless/$name.sql").readText()
            val trees = Dialects.CLICKHOUSE.parse(source).filterNotNull()
            assertEquals(lambdaCount, trees.sumOf { it.findAll<Lambda>().count() }, name)
            for (tree in trees) {
                val output = tree.sql("clickhouse")
                assertEquals(output, tree.copy().sql("clickhouse"), name)
                assertEquals(output, Serde.loadExpression(Serde.dump(tree)).sql("clickhouse"), name)
                assertEquals(output, Dialects.CLICKHOUSE.parseOne(output).sql("clickhouse"), name)
            }
        }
    }

    @Test fun clickhouseNativeJsonFixturePreservesItsFunctionAndTypeArguments() {
        val source = File(root, "clickhouse/tests/queries/0_stateless/01915_json_extract_raw_string.sql").readText()
        val trees = Dialects.CLICKHOUSE.parse(source).filterNotNull()
        assertEquals(6, trees.size)
        for (tree in trees) {
            val output = tree.sql("clickhouse")
            assertEquals(output, tree.copy().sql("clickhouse"))
            assertEquals(output, Serde.loadExpression(Serde.dump(tree)).sql("clickhouse"))
            assertEquals(output, Dialects.CLICKHOUSE.parseOne(output).sql("clickhouse"))
        }
    }
}
