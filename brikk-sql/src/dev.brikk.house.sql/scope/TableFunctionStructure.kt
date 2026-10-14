package dev.brikk.house.sql.scope

import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Schema
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne

/**
 * brikk-native: the columns a table function's structure argument declares, such as ClickHouse's
 * `file('x.csv', 'CSV', 'a UInt32, b Nullable(String)')`, read in the dialect's own column grammar.
 */
object TableFunctionStructure {
    /** [structure]'s columns with their SQL types in [dialect], or null when it is no list of named, typed columns. */
    fun parse(structure: String, dialect: String): List<ColumnFact>? {
        if (structure.isBlank()) return null
        val create = runCatching { parseOne("CREATE TABLE structure ($structure)", dialect) }.getOrNull() as? Create ?: return null
        val definitions = (create.thisArg as? Schema)?.expressionsArg ?: return null
        return definitions.map { definition ->
            val column = definition as? ColumnDef ?: return null
            val type = column.args["kind"] as? DataType ?: return null
            ColumnFact(column.name, sqlType = type.sql(dialect))
        }.ifEmpty { null }
    }
}
