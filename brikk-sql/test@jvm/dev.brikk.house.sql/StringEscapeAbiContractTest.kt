package dev.brikk.house.sql

import dev.brikk.house.sql.parser.TokenizerConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class StringEscapeAbiContractTest {
    @Test
    fun tokenizerConfigKeepsItsPublishedConstructorAndDefaultBridge() {
        // Numeric policy must not change the 26-argument published constructor or Kotlin mask.
        assertEquals(setOf(0, 26, 28), TokenizerConfig::class.java.constructors.map { it.parameterCount }.toSet())
        val bool = Boolean::class.javaPrimitiveType!!
        val map = Map::class.java
        val set = Set::class.java
        val signature = arrayOf(map, map, map, map, map, map, set, set, set, set, set, set,
            bool, String::class.java, set, bool, bool, map, set, bool, bool,
            dev.brikk.house.sql.parser.TokenType::class.java, bool, bool, bool, map)
        TokenizerConfig::class.java.getConstructor(*signature)
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        TokenizerConfig::class.java.getConstructor(*signature, Int::class.javaPrimitiveType!!, marker)
    }
}
