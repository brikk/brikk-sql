package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.nativeDuckdbAnalysisDialect
import dev.brikk.house.sql.dialects.parseDuckdbNativeOne
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClickhouseReleaseIntegrationTest {
    @Test fun nativeDuckdbRootCastPrecisionAndCallerOwnedSlotsSurviveTheSharedEntryGuard() {
        val cast = parseDuckdbNativeOne("SELECT CAST('2024-01-01' AS TIMESTAMP(0))").findAll<Cast>().single()
        annotateTypes(cast, dialect = nativeDuckdbAnalysisDialect(), overwriteTypes = false)
        assertEquals(DType.TIMESTAMP_S, cast.type?.thisArg)
        assertEquals(DType.TIMESTAMPNTZ, (cast.args["to"] as DataType).thisArg)
        cast.typeSlot = DataType.build(DType.VARCHAR)
        annotateTypes(cast, dialect = nativeDuckdbAnalysisDialect(), overwriteTypes = false)
        assertEquals(DType.VARCHAR, cast.type?.thisArg)
    }

    @Test fun mergedScientificAndEncodedStringPoliciesRemainIndependentAndCopied() {
        val base = TokenizerConfig.BASE
        for (config in listOf(base.withScientificNumberBoundaries().withStrictEncodedStrings(),
            base.withStrictEncodedStrings().withScientificNumberBoundaries())) {
            val copied = config.withInlineInsertData().withStringEscapes().withKeywords(emptyMap())
            assertTrue(copied.strictScientificNumberBoundaries)
            assertTrue(copied.strictEncodedStrings)
            assertEquals(listOf("1e0", "+", "2.0"), Tokenizer(copied).tokenize("1e0+2.0").map { it.text })
            val genericNumbers = copied.withScientificNumberBoundaries(false)
            assertFalse(genericNumbers.strictScientificNumberBoundaries)
            assertTrue(genericNumbers.strictEncodedStrings)
        }
        assertFalse(base.strictScientificNumberBoundaries)
        assertFalse(base.strictEncodedStrings)
        assertTrue(Dialects.CLICKHOUSE.tokenizerConfig.strictScientificNumberBoundaries)
        assertFalse(Dialects.CLICKHOUSE.tokenizerConfig.strictEncodedStrings)
        assertFalse(Dialects.forName("datafusion").tokenizerConfig.strictScientificNumberBoundaries)
        assertTrue(Dialects.forName("datafusion").tokenizerConfig.strictEncodedStrings)
    }
}
