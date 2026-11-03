package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialect
import kotlinx.serialization.json.*
import kotlin.String
import kotlin.Boolean

/** Arrow names are analysis data, not SQL cast syntax. Retain width, units, zones and container identity. */
internal object DatafusionTypes {
    const val NATIVE = "datafusion_arrow_type"
    private val primitive = mapOf(
        "Null" to DType.NULL, "Boolean" to DType.BOOLEAN,
        "Int8" to DType.TINYINT, "Int16" to DType.SMALLINT, "Int32" to DType.INT, "Int64" to DType.BIGINT,
        "UInt8" to DType.UTINYINT, "UInt16" to DType.USMALLINT, "UInt32" to DType.UINT, "UInt64" to DType.UBIGINT,
        "Float16" to DType.FLOAT, "Float32" to DType.FLOAT, "Float64" to DType.DOUBLE,
        "Utf8" to DType.VARCHAR, "LargeUtf8" to DType.VARCHAR, "Utf8View" to DType.VARCHAR,
        "Binary" to DType.BINARY, "LargeBinary" to DType.BINARY, "BinaryView" to DType.BINARY,
        "Date32" to DType.DATE, "Date64" to DType.DATE,
    )

    fun parse(text: String, depth: Int = 0): DataType? {
        if (depth > 64 || text.length > 100_000) return null
        val raw = text.trim()
        fun type(kind: DType, children: List<Expression> = emptyList()) = DataType(args(
            "this" to kind, "expressions" to children, "nested" to (kind in setOf(DType.ARRAY, DType.STRUCT, DType.MAP))
        )).also { it.meta[NATIVE] = raw }
        primitive[raw]?.let { return type(it) }
        val name = raw.substringBefore('(')
        if (!raw.endsWith(')')) return null
        val body = raw.substringAfter('(', "").dropLast(1)
        val parts = split(body) ?: return null
        fun child(s: String) = parse(s, depth + 1)
        when (name) {
            "Decimal128", "Decimal256" -> {
                if (parts.size != 2) return null
                val precision = parts[0].toIntOrNull() ?: return null
                val scale = parts[1].toIntOrNull() ?: return null
                if (precision !in 1..(if (name == "Decimal128") 38 else 76) || scale !in -128..precision) return null
                return type(if (name == "Decimal256") DType.DECIMAL256 else DType.DECIMAL, parts.map {
                    DataTypeParam(args("this" to Literal.number(it)))
                })
            }
            "List", "LargeList", "ListView", "LargeListView" -> {
                if (parts.size != 1) return null
                val element = child(parts[0]) ?: field(parts[0], depth)?.args?.get("kind") as? DataType ?: return null
                return type(DType.ARRAY, listOf(element))
            }
            "FixedSizeList" -> {
                val values = if (parts.size == 1) body.split(" x ", limit = 2) else parts
                if (values.size != 2 || values[0].trim().toIntOrNull()?.let { it >= 0 } != true) return null
                return type(DType.ARRAY, listOf(child(values[1].trim()) ?: return null))
            }
            "Struct" -> return type(DType.STRUCT, parts.filter { it.isNotEmpty() }.map { field(it, depth) ?: return null })
            "Map" -> {
                if (parts.size != 2 || parts[1] !in setOf("sorted", "unsorted")) return null
                val entries = field(parts[0], depth)?.args?.get("kind") as? DataType ?: return null
                if (entries.thisArg != DType.STRUCT || entries.expressionsArg.size != 2) return null
                return type(DType.MAP, entries.expressionsArg.map { (it as ColumnDef).args["kind"] as DataType })
            }
            "Timestamp" -> {
                if (parts.size !in 1..2 || parts[0] !in UNITS) return null
                val zone = parts.getOrNull(1)
                if (zone != null && zone != "None" && !zone.startsWith('"') && !zone.startsWith("Some(\"")) return null
                return type(if (zone == null || zone == "None") DType.TIMESTAMP else DType.TIMESTAMPTZ)
            }
            "Time32", "Time64" -> if (parts.size == 1 && parts[0] in UNITS) return type(DType.TIME)
            "Duration", "Interval" -> if (parts.size == 1 && (parts[0] in UNITS || parts[0] in setOf("YearMonth", "DayTime", "MonthDayNano"))) return type(DType.INTERVAL)
            // Union fields may carry Arrow extension metadata. There is no equivalent single SQL type.
            "Union" -> if (parts.firstOrNull() in setOf("Sparse", "Dense")) return type(DType.UNKNOWN)
            "FixedSizeBinary" -> if (body.toIntOrNull()?.let { it >= 0 } == true) return type(DType.BINARY)
        }
        return null
    }

    private val UNITS = setOf("s", "ms", "us", "µs", "ns", "Second", "Millisecond", "Microsecond", "Nanosecond")
    private fun field(text: String, depth: Int): ColumnDef? {
        val match = Regex("^\"((?:[^\"]|\\\\.)*)\":\\s*(.*)$").matchEntire(text) ?: return null
        val name = try { Json.parseToJsonElement("\"${match.groupValues[1]}\"").jsonPrimitive.content } catch (_: Exception) { return null }
        val raw = match.groupValues[2]
        val type = parse(raw.removePrefix("non-null "), depth + 1) ?: return null
        type.set("nullable", !raw.startsWith("non-null "))
        return ColumnDef(args("this" to Identifier(args("this" to name, "quoted" to true)), "kind" to type))
    }

    private fun split(text: String): List<String>? {
        var depth = 0; var quoted = false; var escaped = false; var start = 0
        val out = mutableListOf<String>()
        for ((i, c) in text.withIndex()) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '(', '{', '[' -> depth++
                ')', '}', ']' -> if (--depth < 0) return null
                ',' -> if (depth == 0) { out += text.substring(start, i).trim(); start = i + 1 }
            }
        }
        if (depth != 0 || quoted) return null
        return out + text.substring(start).trim()
    }

    fun logical(kind: DType) = DataType(args("this" to kind))
    private fun unknown() = logical(DType.UNKNOWN)

    /** Null means use the shared rule; UNKNOWN means no supported proof, never a foreign-engine guess. */
    fun infer(e: Expression, dialect: Dialect): DataType? {
        if (dialect.name !in setOf("datafusion", "spice")) return null
        fun type(v: Any?) = (v as? Expression)?.type as? DataType
        if (e is Literal) return logical(if (e.isString) DType.VARCHAR else if (e.isInt) DType.BIGINT else DType.DOUBLE)
        if (e is Null) return logical(DType.NULL)
        if (e is Count || e is RowNumber || e is Rank || e is DenseRank) return logical(DType.BIGINT)
        if (e is Sum) return when (type(e.thisArg)?.thisArg) {
            DType.TINYINT, DType.SMALLINT, DType.INT, DType.BIGINT -> logical(DType.BIGINT)
            DType.UTINYINT, DType.USMALLINT, DType.UINT, DType.UBIGINT -> logical(DType.UBIGINT)
            DType.FLOAT, DType.DOUBLE -> logical(DType.DOUBLE)
            else -> unknown()
        }
        if (e is Avg) return if (type(e.thisArg)?.thisArg in DataType.INTEGER_TYPES + setOf(DType.FLOAT, DType.DOUBLE)) logical(DType.DOUBLE) else unknown()
        if (e is Div || e is Add || e is Sub || e is Mul || e is Mod) {
            val l = type(e.thisArg); val r = type(e.expressionArg)
            // Decimal precision and mixed unsigned coercions depend on the engine's rules/settings.
            if (l == null || r == null || l.thisArg != r.thisArg || l.thisArg in setOf(DType.DECIMAL, DType.DECIMAL128, DType.DECIMAL256, DType.UNKNOWN)) return unknown()
            return l.copy() as DataType
        }
        if (e is Sqrt || e is Floor || e is Ceil) return when (type(e.thisArg)?.thisArg) {
            DType.FLOAT -> logical(DType.FLOAT)
            in DataType.INTEGER_TYPES, DType.DOUBLE -> logical(DType.DOUBLE)
            else -> unknown()
        }
        if (e is Pow) return logical(DType.DOUBLE)
        if (e is Length) return when {
            type(e.thisArg)?.metaOrNull?.get(NATIVE) == "LargeUtf8" -> if (dialect.name == "datafusion") logical(DType.BIGINT) else unknown()
            type(e.thisArg)?.thisArg in setOf(DType.VARCHAR, DType.TEXT) -> logical(DType.INT)
            else -> unknown()
        }
        if (e is Abs || e is Min || e is Max) return type(e.thisArg) ?: unknown()
        if (e is Coalesce || e is Nullif) {
            val types = e.iterExpressions().mapNotNull { it.type as? DataType }.filter { it.thisArg != DType.NULL }.toList()
            return types.firstOrNull()?.takeIf { first -> types.all { it == first && it.metaOrNull?.get(NATIVE) == first.metaOrNull?.get(NATIVE) } } ?: unknown()
        }
        if (e is Lower || e is Upper || e is Substring || e is Concat || e is ConcatWs) return logical(DType.VARCHAR)
        if (e is RegexpLike) return logical(DType.BOOLEAN)
        if (dialect.name == "spice" && e is JSONExtractScalar) return logical(DType.VARCHAR)
        if (dialect.name == "spice" && e is JSONExtract) return jsonUnion()
        if (e !is Anonymous && e !is AnonymousAggFunc) {
            // Unverified shared function typing often encodes another engine's widening/coercion rules.
            return if (e is Func && e !is ArrayAgg && e.metaOrNull?.get("datafusion_native_call") == true) unknown() else null
        }
        if (e.parent is Dot || e.thisArg is Identifier && (e.thisArg as Identifier).name != e.name.lowercase()) return unknown()
        val values = e.expressionsArg.filterIsInstance<Expression>()
        val positional = values.filterNot { it is Kwarg }
        fun text(index: Int) = (positional.getOrNull(index) as? Literal)?.takeIf { it.isString }?.name
        when (e.name.lowercase()) {
            "struct" -> {
                if (positional.isEmpty() || positional.any { it !is Alias }) return unknown()
                return DataType(args("this" to DType.STRUCT, "nested" to true, "expressions" to positional.map {
                    ColumnDef(args("this" to (it.args["alias"] as Expression).copy(), "kind" to (type(it.thisArg)?.copy() ?: unknown())))
                }))
            }
            "arrow_cast" -> return if (positional.size == 2) text(1)?.let(::parse) ?: unknown() else unknown()
            "arrow_typeof" -> return logical(DType.VARCHAR)
            "named_struct" -> {
                if (positional.size % 2 != 0) return unknown()
                val fields = positional.chunked(2).map { pair ->
                    val key = (pair[0] as? Literal)?.takeIf { it.isString }?.name ?: return unknown()
                    ColumnDef(args("this" to Identifier(args("this" to key, "quoted" to true)), "kind" to (type(pair[1]) ?: unknown())))
                }
                return DataType(args("this" to DType.STRUCT, "expressions" to fields, "nested" to true))
            }
            "map" -> {
                if (positional.isEmpty() || positional.size % 2 != 0) return unknown()
                val keys = positional.filterIndexed { i, _ -> i % 2 == 0 }.map { type(it) }
                val vals = positional.filterIndexed { i, _ -> i % 2 == 1 }.map { type(it) }
                if (keys.any { it == null || it != keys.first() } || vals.any { it == null || it != vals.first() }) return unknown()
                return DataType(args("this" to DType.MAP, "expressions" to listOf(keys.first()!!.copy(), vals.first()!!.copy()), "nested" to true))
            }
        }
        if (dialect.name == "spice") when (e.name.lowercase()) {
            "json_get" -> return jsonUnion()
            "json_get_int" -> return logical(DType.BIGINT)
            "json_get_float", "ai_probability", "ai_score" -> return logical(DType.DOUBLE)
            "json_get_str", "json_as_text", "ai", "ai_classify" -> return logical(DType.VARCHAR)
            "json_get_bool", "json_contains", "ai_if" -> return logical(DType.BOOLEAN)
            "ai_decide" -> return decisionType(text(1) ?: (values.filterIsInstance<Kwarg>().firstOrNull { it.thisArg.let { n -> (n as? Expression)?.name } == "questions" }?.expressionArg as? Literal)?.name)
        }
        // Representative SHOW FUNCTIONS rows are not authoritative return types.
        return unknown()
    }

    private fun jsonUnion() = unknown().also { it.meta[NATIVE] = JSON_UNION }

    // Exact arrow_typeof result from v2.4.0, round5/spice.json; union alternatives are not a struct.
    private val JSON_UNION = "Union(Sparse, 0: (\"null\": Null), 1: (\"bool\": non-null Boolean), " +
        "2: (\"int\": non-null Int64), 3: (\"float\": non-null Float64), 4: (\"str\": non-null Utf8), " +
        "5: (\"array\": non-null Utf8, metadata: {\"ARROW:extension:metadata\": \"{}\", \"ARROW:extension:name\": \"arrow.json\", \"is_json\": \"true\"}), " +
        "6: (\"object\": non-null Utf8, metadata: {\"ARROW:extension:metadata\": \"{}\", \"ARROW:extension:name\": \"arrow.json\", \"is_json\": \"true\"}))"

    private fun decisionType(text: String?): DataType {
        val questions = try { text?.let { Json.parseToJsonElement(it) as? JsonObject } } catch (_: Exception) { null } ?: return unknown()
        if (questions.isEmpty() || text == null || duplicateJsonKeys(text)) return unknown()
        fun field(name: String, type: DataType) = ColumnDef(args("this" to Identifier(args("this" to name, "quoted" to true)), "kind" to type.also { it.set("nullable", true) }))
        fun struct(fields: List<Expression>) = DataType(args("this" to DType.STRUCT, "expressions" to fields, "nested" to true))
        val fields = questions.entries.sortedBy { it.key }.map { (id, value) ->
            if (id.isBlank()) return unknown()
            val q = value as? JsonObject ?: return unknown()
            val kind = (q["type"] as? JsonPrimitive)?.content ?: return unknown()
            if ((q["instructions"] as? JsonPrimitive)?.isString != true) return unknown()
            if (kind == "choice") {
                val criteria = q["criteria"] as? JsonObject ?: return unknown()
                if (criteria.size !in 1..255 || criteria.keys.any { it.isBlank() }) return unknown()
            }
            if (kind == "score" && q["criteria"] !is JsonArray) return unknown()
            val answer = when (kind) {
                "noul" -> listOf(field("probability", logical(DType.DOUBLE)))
                "choice", "score" -> {
                    val probabilityFields = if (kind == "choice") listOf(field("value", logical(DType.VARCHAR))) else
                        listOf(field("value", logical(DType.BIGINT)), field("label", logical(DType.VARCHAR)))
                    listOf(field(kind, logical(if (kind == "choice") DType.VARCHAR else DType.DOUBLE)),
                        field("probabilities", DataType(args("this" to DType.ARRAY, "expressions" to listOf(struct(probabilityFields + field("probability", logical(DType.DOUBLE)))), "nested" to true))),
                        field("confidence", logical(DType.DOUBLE)))
                }
                else -> return unknown()
            }
            field(id, struct(answer))
        }
        return struct(fields)
    }

    /** kotlinx JSON maps overwrite repeats; native question parsing explicitly rejects them. */
    private fun duplicateJsonKeys(text: String): Boolean {
        class ObjectKeys(val keys: MutableSet<String> = mutableSetOf(), var expectingKey: Boolean = true)
        val stack = mutableListOf<ObjectKeys?>()
        for (match in Regex("\"(?:[^\"\\\\]|\\\\.)*\"|[{}\\[\\],:]").findAll(text)) {
            val token = match.value
            when (token) {
                "{" -> stack.add(ObjectKeys())
                "[" -> stack.add(null)
                "}", "]" -> stack.removeAt(stack.lastIndex)
                "," -> stack.lastOrNull()?.expectingKey = true
                ":" -> stack.lastOrNull()?.expectingKey = false
                else -> stack.lastOrNull()?.takeIf { it.expectingKey }?.let {
                    if (!it.keys.add(Json.parseToJsonElement(token).jsonPrimitive.content)) return true
                    it.expectingKey = false
                }
            }
        }
        return false
    }
}
