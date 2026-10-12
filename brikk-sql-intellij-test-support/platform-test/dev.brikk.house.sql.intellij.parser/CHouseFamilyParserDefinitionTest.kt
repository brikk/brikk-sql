package dev.brikk.house.sql.intellij.parser

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlInsertDmlInstruction
import com.intellij.sql.psi.impl.SqlStatementImpl
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureDialect
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureParserDefinition
import dev.brikk.house.sql.intellij.fixture.CHouseFixtureTestCase

class CHouseFamilyParserDefinitionTest : CHouseFixtureTestCase() {
    fun testLenientStatementsGetPlatformPsiBesideClickHousePsi() {
        configureCHouse("SHOW CREATE TABLE ( t;\nINSERT INTO events (id) SELECT number FROM numbers(3)")
        val (show, insert) = statements()
        assertEquals("SHOW CREATE TABLE ( t", show.text)
        assertEquals(SqlCompositeElementTypes.SQL_STATEMENT, show.node.elementType)
        assertEquals(SqlStatementImpl::class.java, show.javaClass)
        // The grammar's nodes keep the platform ClickHouse PSI.
        val instruction = requireNotNull(PsiTreeUtil.findChildOfType(insert, SqlInsertDmlInstruction::class.java))
        assertTrue(instruction.javaClass.name, instruction.javaClass.name.startsWith("com.intellij.sql.dialects.clickhouse."))
        assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java))
        // Composite PSI such as a generic statement is its own node; `createElement` never sees it.
        assertInstanceOf(CHouseFixtureParserDefinition().createComposite(SqlCompositeElementTypes.SQL_STATEMENT), SqlStatementImpl::class.java)
    }

    fun testFileNodeTypeBelongsToTheDialectAndIsShared() {
        val type = CHouseFixtureParserDefinition().fileNodeType
        assertSame(CHouseFixtureDialect, type.language)
        assertSame(type, CHouseFixtureParserDefinition().fileNodeType)
        configureCHouse("SELECT 1")
        assertSame(type, myFixture.file.node.elementType)
    }
}
