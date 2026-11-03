package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.*

class DuckdbReaderIdentifierTest {
    @Test fun readerIdentifiersAndPathStringsKeepDifferentAstRolesWithoutImplicitRewrites() {
        for(argument in listOf("audit_file","\"audit.file.csv\"","'audit.file.csv'")) {
            val sql="SELECT * FROM read_csv($argument,header => true)"
            val tree=parseDuckdbNativeOne(sql)
            if(argument.startsWith("'")) {
                assertTrue(tree.findAll<Literal>().any { it.isString && it.name=="audit.file.csv" })
            }else{
                val column=tree.findAll<Column>().single()
                assertEquals(argument.startsWith("\""),assertIs<Identifier>(column.thisArg).quoted)
            }
            assertEquals(tree,Serde.load(Serde.dump(tree)))
            val generated=SqlFragment(sql,"duckdb").toExecutable("duckdb").sql
            assertTrue(argument in generated,generated)
            assertEquals(generated,parseDuckdbNativeOne(generated).sql("duckdb"))
        }
    }
}
