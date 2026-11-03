package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.scope.*
import dev.brikk.house.sql.shape.*
import kotlin.test.*

class DuckdbBinaryLiteralTest {
    @Test fun nativeLiteralTokensValuesLeadingZerosAndSerdeDoNotBecomeTextOrIntegers() {
        for ((literal,kind,type) in listOf(Triple("X'0041ff'","X","BINARY"), Triple("b'00101'","B","BIT"),
            Triple("X''","X","BINARY"), Triple("B''","B","BIT"))) {
            val token=Dialects.DUCKDB.tokenize(literal).single()
            assertEquals(if(kind=="X")TokenType.HEX_STRING else TokenType.BIT_STRING,token.tokenType)
            val tree=Dialects.DUCKDB.parseOne("SELECT $literal AS result")
            val node=assertNotNull(tree.find<DuckdbBinaryLiteral>())
            assertEquals(kind,node.text("kind"));assertEquals(token.text,node.text("this"))
            for(copy in listOf(tree.copy(),assertIs<Expression>(Serde.load(Serde.dump(tree))))) {
                assertEquals(tree,copy)
                assertEquals(copy.sql("duckdb"),Dialects.DUCKDB.parseOne(copy.sql("duckdb")).sql("duckdb"))
            }
            val shape=SqlFragment("SELECT $literal AS result","duckdb").outputShape().columns.single()
            assertEquals(type,shape.type);assertEquals(false,shape.nullable)
            assertEquals(type,RelationScope.analyze("SELECT $literal AS result","duckdb",RelationFacts()).output!!.columns.single().sqlType)
        }
    }
    @Test fun newlineContinuationsAreJoinedBeforeValidationButMalformedOrEscapedPayloadsRefuse() {
        assertEquals("SELECT X'4142'",SqlFragment("SELECT X'4'\n'142'","duckdb").toExecutable("duckdb").sql)
        assertEquals("SELECT B'0010'",SqlFragment("SELECT B'00'\n'10'","duckdb").toExecutable("duckdb").sql)
        val cast=Dialects.DUCKDB.parseOne("X'4'\n'142'::VARCHAR")
        assertEquals("4142",assertIs<DuckdbBinaryLiteral>(assertIs<Cast>(cast).thisArg).text("this"))
        for(literal in listOf("X'F'","X'gg'","B'012'","B'０１'","X'００'","X'4\\x31'","X'4''1'","X'41' '42'","X'41'\nX'42'"))
            assertFails(literal) { SqlFragment("SELECT $literal","duckdb").toExecutable("duckdb") }
        assertEquals(TokenType.VAR,Dialects.DUCKDB.tokenize("x '41'").first().tokenType)
        assertNull(Dialects.DUCKDB.parseOne("SELECT \"x\",\"b\" FROM t").find<DuckdbBinaryLiteral>())
    }
    @Test fun castsParametersSourceMapsAndCrossTargetRefusalsRetainBinaryMeaning() {
        val sql="SELECT X'3F'::BLOB,B'00101'::VARCHAR,CAST(? AS BLOB)"
        assertEquals(listOf(sql.indexOf('?')),parseParameterOccurrences(sql,"duckdb").map { it.range.start })
        val rendered=SqlFragment(sql,"duckdb").toExecutable("duckdb")
        assertEquals(rendered.sql,assertNotNull(rendered.sourceMap).output)
        assertEquals(1,rendered.sql.count { it=='?' })
        for(target in listOf("trino","postgres","doris","clickhouse","bigquery","mysql"))
            assertFailsWith<UnsupportedError> { SqlFragment(sql,"duckdb").toExecutable(target) }
        // Other dialect literal contracts and generated tokenizer tables are unchanged.
        assertIs<HexString>(Dialects.TRINO.parseOne("X'41'"))
    }
}
