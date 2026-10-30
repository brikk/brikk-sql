package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Copy
import dev.brikk.house.sql.ast.CopyParameter
import dev.brikk.house.sql.ast.Expression

internal fun CopyParameter.isDuckdbCopyLayout(): Boolean =
    (thisArg as? Expression)?.name?.uppercase() in setOf("PARTITION_BY", "PARTITIONED_BY", "PARTITION BY", "PARTITIONED BY", "ORDER_BY", "ORDER BY")

internal fun Expression.hasDuckdbCopyLayout(): Boolean =
    findAll<Copy>().any { copy -> (copy.args["params"] as? List<*>).orEmpty().filterIsInstance<CopyParameter>().any { it.isDuckdbCopyLayout() } }
