package dev.brikk.house.sql.intellij.relation

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlFunctionCallExpression

/** The native adapter: a slot call's provider columns as the platform's own table type. */
class RelationTableTypesTest : RelationTestCase() {
    private fun call(text: String, name: String): SqlFunctionCallExpression {
        open(text)
        return PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).single { it.nameElement?.name == name }
    }

    fun testASlotCallHasItsProvidersColumns() {
        val events = myFixture.addFileToProject("events.txt", "events")
        val id = myFixture.addFileToProject("id.txt", "id")
        pgRelations.fake.targets = { _, provenance -> mapOf("decl:id" to id, "decl:events" to events)[provenance] }
        val type = checkNotNull(RelationTableTypes.of(call("SELECT * FROM events() e", "events"), pgRelations))
        assertEquals(listOf("id", "kind"), (0 until type.columnCount).map { type.getColumnName(it) })
        // A column leads to its declaration, else its relation's.
        assertEquals(id, type.getColumnElement(0)?.navigationElement)
        assertEquals(events, type.getColumnElement(1)?.navigationElement)
    }

    fun testOtherCallsKeepThePlatformsTyping() {
        assertNull(RelationTableTypes.of(call("SELECT * FROM events(1) e", "events"), pgRelations))
        assertNull(RelationTableTypes.of(call("SELECT * FROM unknown() u", "unknown"), pgRelations))
        assertNull(RelationTableTypes.of(call("SELECT lower('a') FROM events() e", "lower"), pgRelations))
        pgRelations.fake.pending = true
        assertNull(RelationTableTypes.of(call("SELECT * FROM events() e", "events"), pgRelations))
        pgRelations.fake.pending = false
        pgRelations.fake.platformResolves = true
        assertNull(RelationTableTypes.of(call("SELECT * FROM events() e", "events"), pgRelations))
    }
}
