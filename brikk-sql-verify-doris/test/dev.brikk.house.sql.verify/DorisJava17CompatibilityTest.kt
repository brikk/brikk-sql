package dev.brikk.house.sql.verify

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

internal class DorisJava17CompatibilityTest {
    @Test
    fun nativeParserAndLibraryGraphRunOnJava17() {
        assertEquals(17, Runtime.version().feature())
        val stream = assertNotNull(DorisVerifier::class.java.getResourceAsStream("/dev/brikk/house/sql/verify/DorisVerifier.class"))
        val header = stream.use { it.readNBytes(8) }
        val major = (header[6].toInt() and 255) shl 8 or (header[7].toInt() and 255)
        assertEquals(61, major)
        val parser = DorisVerifier.create(Path.of(checkNotNull(System.getProperty("brikk.doris.parser.jar"))))
        assertIs<VerifyResult.Accepted>(parser.verify("SELECT JSON_EXTRACT('{\"n\":1}', '$.n')"))
        assertIs<VerifyResult.Rejected>(parser.verify("SELECT FROM WHERE"))
    }
}
