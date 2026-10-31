package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.metadata.DuckdbIdentifierCase

/** Applies an explicitly supplied native policy to modeled Identifier nodes. Never reads
 * a connection, infers a preceding SET, rewrites opaque command text or changes literals.
 * Resolution remains ASCII case-insensitive in every mode, including quoted identifiers.
 */
fun Expression.withDuckdbIdentifierCase(policy: DuckdbIdentifierCase, copy: Boolean = true): Expression =
    transform(copy = copy) { node ->
        if (node is Identifier && !node.quoted) node.set("this", policy.foldUnquoted(node.name))
        node
    }
