package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import kotlin.test.Test
import kotlin.test.assertEquals

class IdentifierEscapeCatchupTest {
    @Test
    fun identifierNamesSurviveEscapeCharactersAndDelimiterQuotes() {
        for (dialect in listOf("clickhouse", "bigquery", "mysql", "postgres", "duckdb", "spark")) {
            val reader = Dialects.forName(dialect)
            for (name in listOf("path\\node", "tail\\", "back`tick", "double\"quote", "a\\`b\\\"c", "😀\\path")) {
                val identifier = Identifier(args("this" to name, "quoted" to true))
                val generated = identifier.sql(dialect)
                val reparsed = reader.parser().parseIntoIdentifier(reader.tokenize(generated), generated)
                assertEquals(name, reparsed?.name, "$dialect: $generated")
                assertEquals(name, identifier.name)
            }
        }
    }
}
