package dev.brikk.house.sql.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal class ApiJava17CompatibilityTest {
    @Test
    fun apiTargetsAndRunsOnJava17() {
        assertEquals(17, Runtime.version().feature())
        val stream = assertNotNull(SqlVerifier::class.java.getResourceAsStream("/dev/brikk/house/sql/verify/SqlVerifier.class"))
        val header = stream.use { it.readNBytes(8) }
        val major = (header[6].toInt() and 255) shl 8 or (header[7].toInt() and 255)
        assertEquals(61, major)
        assertEquals("example", UnavailableVerifier("example", "unavailable").engine)
    }
}
