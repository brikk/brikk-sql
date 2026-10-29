package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.TrinoIntervalQualifier
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects

/** Keep normalized type spellings, but retain native qualifiers the base generator cannot render. */
internal fun renderType(type: DataType, dialect: Dialect): String =
    if (type.find(TrinoIntervalQualifier::class) != null) dialect.generate(type)
    else Dialects.BASE.generate(type)
