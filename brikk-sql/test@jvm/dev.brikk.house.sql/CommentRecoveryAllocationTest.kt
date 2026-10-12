package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.DorisDialect
import dev.brikk.house.sql.parser.Tokenizer
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Recovering an unbalanced nested comment allocates in proportion to its delimiters, not its length. */
class CommentRecoveryAllocationTest {

    private val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    private fun allocatedBytes(block: () -> Unit): Long {
        val bean = threads ?: return 0
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    @Test
    fun recoveryDoesNotAllocatePerCharacter() {
        if (threads?.isThreadAllocatedMemorySupported != true) return
        val length = 4_000_000
        val sql = "SELECT /* /*" + "a".repeat(length) + "*/ 1"
        val tokenizer = Tokenizer(DorisDialect.TOKENIZER_CONFIG)
        var tokens = emptyList<String>()
        val strict = allocatedBytes { tokens = tokenizer.tokenize(sql).map { it.text } }
        assertEquals(listOf("SELECT", "1"), tokens)
        // The comment text itself is one byte per character; the rest is constant.
        assertTrue(strict < 2L * length + 8_000_000, "strict recovery allocated $strict bytes for $length characters")
        var failure: Int? = null
        val editor = allocatedBytes { failure = tokenizer.tokenizeForEditor(sql).failureOffset }
        assertEquals(sql.indexOf("/*"), failure)
        assertTrue(editor < 8_000_000, "editor refusal allocated $editor bytes for $length characters")
    }
}
