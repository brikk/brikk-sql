package dev.brikk.house.sql.scope

import dev.brikk.house.sql.shape.catalogTableParts

/** Scope and shape facts share quoted-component identity, independent of the query's dialect. */
internal fun catalogPathParts(key: String): List<String> = catalogTableParts(key).map { it.name }
