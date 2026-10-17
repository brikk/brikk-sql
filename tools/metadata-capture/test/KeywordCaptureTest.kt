import dev.brikk.house.sql.metadata.CLICKHOUSE_METADATA
import dev.brikk.house.sql.metadata.DORIS_METADATA
import dev.brikk.house.sql.metadata.DialectMetadata
import dev.brikk.house.sql.metadata.KeywordCategory
import dev.brikk.house.sql.metadata.KeywordDef
import dev.brikk.house.sql.metadata.TRINO_METADATA
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class KeywordCaptureTest {

    private fun assertBundledEqualsDump(metadata: DialectMetadata) {
        val snapshot = metadata.default
        val dump = readDump(Path("../../vendor/data/${metadata.dialect}-keywords-${snapshot.engineVersion}.tsv"))
        val vendored = dump.rows.map { (word, category, token) -> KeywordDef(checkNotNull(word), KeywordCategory.valueOf(checkNotNull(category)), token) }
        assertEquals(vendored, snapshot.keywords?.keywords)
    }

    @Test
    fun trinoKeywordsEqualTheVendoredDump() = assertBundledEqualsDump(TRINO_METADATA)

    @Test
    fun dorisKeywordsEqualTheVendoredDump() = assertBundledEqualsDump(DORIS_METADATA)

    @Test
    fun clickhouseKeywordsEqualTheVendoredDump() = assertBundledEqualsDump(CLICKHOUSE_METADATA)
}
