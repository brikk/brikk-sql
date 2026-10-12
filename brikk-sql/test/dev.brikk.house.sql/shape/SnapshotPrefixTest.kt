package dev.brikk.house.sql.shape

import dev.brikk.house.sql.parser.SqlScriptSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/brikk-extensions.md #55: what a window of a text proves. */
class SnapshotPrefixTest {
    /** A prefix of a text proves only what no continuation can change: each of its ranges is the whole text's. */
    @Test fun aPrefixProvesOnlyRangesItsContinuationCannotChange() {
        val head = "INSERT INTO t FORMAT TSV\n"
        val texts = listOf(
            "SELECT 1;\nINSERT INTO t FORMAT TSV\na;\nb\n\nSELECT 'x;y' /* ; */;\nINSERT INTO t FORMAT JSONEachRow\n{\"a\":1};\n;\nSELECT 3",
            head + "a".repeat(40) + ";  b\n\nSELECT 2;",
            "INSERT INTO t FORMAT TSV SETTINGS a = 1,\n\nb = 2\nx;\n\nINSERT INTO t SELECT s FROM input('s String') FORMAT TSV\n1\n;\nSELECT 4;",
            // A `;` ending the format's line, then spaces: the next character decides whether it is data.
            "INSERT INTO t FORMAT TSV ;  x\n\nSELECT 5;\nINSERT INTO t FORMAT TSV\na\n ; b\n;\nSELECT 6;",
            "INSERT INTO t SETTINGS x = 'VALUES' FORMAT TSV\n\t;\t\nSELECT 3;",
        )
        for (text in texts) {
            val full = SqlScriptSnapshot.parse(text, "clickhouse").statements
            for (cut in 1..text.length) {
                val prefix = SqlScriptSnapshot.parsePrefix(text.substring(0, cut), "clickhouse") {}
                assertEquals(full.take(prefix.size), prefix, "cut $cut of $text")
                // A range is proven once the line after its end is: a blank line or a `;` line ends data.
                assertTrue(full.count { it.end + 4 < cut } <= prefix.size, "cut $cut of $text")
            }
        }
    }
}
