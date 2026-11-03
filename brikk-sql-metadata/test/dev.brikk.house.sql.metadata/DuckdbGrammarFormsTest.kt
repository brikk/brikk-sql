package dev.brikk.house.sql.metadata

import kotlin.test.*

class DuckdbGrammarFormsTest {
    @Test fun registeredNamesSpecialSyntaxAndBareOnlyBuiltinsRemainDistinct() {
        val snapshot=DUCKDB_METADATA.default
        val forms=snapshot.grammarForms!!.associateBy { it.name }
        assertEquals(setOf("COALESCE","GROUPING","GROUPING_ID"),snapshot.functions.grammarBuiltins)
        for(name in snapshot.functions.grammarBuiltins) {
            assertFalse(name in snapshot.functions,name)
            assertTrue(snapshot.functions.isKnown(name.lowercase()),name)
        }
        assertTrue("if" in snapshot.functions)
        assertEquals(setOf(InvocationSyntax.SPECIAL_FORM),forms.getValue("POSITION").syntaxes)
        assertEquals("POSITION(substring IN string)",forms.getValue("POSITION").template)
        for(name in listOf("CURRENT_TIMESTAMP","CURRENT_TIME","LOCALTIME","LOCALTIMESTAMP"))
            assertEquals(setOf(InvocationSyntax.BARE),forms.getValue(name).syntaxes,name)
        for(name in listOf("CURRENT_DATE","CURRENT_CATALOG","CURRENT_SCHEMA","CURRENT_USER","CURRENT_ROLE","SESSION_USER","USER"))
            assertEquals(setOf(InvocationSyntax.BARE,InvocationSyntax.CALL),forms.getValue(name).syntaxes,name)
        val position=snapshot.completionEntries().single { it.name.equals("position",true) }
        assertEquals(setOf(InvocationSyntax.SPECIAL_FORM),position.syntaxes)
        assertNotNull(position.template)
    }
}
