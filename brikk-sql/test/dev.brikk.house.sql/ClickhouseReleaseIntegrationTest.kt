package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClickhouseReleaseIntegrationTest {
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
