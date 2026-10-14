package dev.brikk.house.sql.intellij.testing

import org.jetbrains.annotations.ApiStatus

/** Syntax a dialect may lack; a case needing one runs only where the target has it. */
@ApiStatus.Experimental
public enum class RealSqlFeature {
    /** `* EXCEPT (a, b)` in a projection, or as the dialect spells it (DuckDB's `* EXCLUDE (a, b)`). */
    STAR_EXCEPT,

    /** `* REPLACE (expr AS a)` in a projection. */
    STAR_REPLACE,

    /** A projection alias usable later in the same SELECT list or its WHERE. */
    LATERAL_ALIAS,

    /** Aliases of the SELECT list in GROUP BY and HAVING. */
    GROUP_BY_ALIAS,
    NATURAL_JOIN,
    FULL_JOIN,
    INTERSECT_EXCEPT,

    /** `WINDOW w AS (...)` and `OVER w`. */
    NAMED_WINDOW,

    /** `UNNEST(array) AS u(x)` in FROM. */
    UNNEST,

    /** `LATERAL (subquery)` in FROM. */
    LATERAL_SUBQUERY,

    /** `(VALUES ...) AS v(a, b)`. */
    VALUES_COLUMN_ALIASES,

    /** `CREATE TABLE t AS SELECT`. */
    CREATE_TABLE_AS,

    /** `catalog.schema.table` names. */
    THREE_PART_NAMES,

    /** `"Quoted"` identifiers that keep their case. */
    DOUBLE_QUOTED_IDENTIFIERS,

    /** `` `quoted` `` identifiers. */
    BACKTICK_IDENTIFIERS,

    /** Doris functions and syntax (`FROM_SECOND`, multi-catalog). */
    DORIS,

    /** PostgreSQL's set-returning functions in FROM (`generate_series`). */
    SET_RETURNING_FUNCTIONS,

    /** ClickHouse table functions (`numbers`, `s3`, `url`) and `INSERT INTO FUNCTION`. */
    CLICKHOUSE_TABLE_FUNCTIONS,

    /** Doris table functions given properties (`numbers("number" = "10")`, `s3(...)`), read and written. */
    DORIS_TABLE_FUNCTIONS,

    /** DuckDB's file and range table functions (`read_parquet`, `read_csv`, `range`). */
    DUCKDB_TABLE_FUNCTIONS,

    /** Trino's `TABLE(system.sequence(...))` and `UNNEST`. */
    TRINO_TABLE_FUNCTIONS,
}

/**
 * A completion check: at `<caret>` in [sql], [offered] are all offered and none of [notOffered] (another
 * namespace's columns, say). [variants] replaces the SQL for a dialect.
 */
@ApiStatus.Experimental
public data class RealSqlCompletion(
    val sql: String,
    val offered: Set<String>,
    val notOffered: Set<String> = emptySet(),
    val variants: Map<String, String> = emptyMap(),
) {
    init {
        require((variants.values + sql).all { "<caret>" in it }) { "A completion needs a <caret>" }
    }

    // Binary compatibility (AGENTS.md): the constructor and copy from before notOffered and variants.
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    public constructor(sql: String, offered: Set<String>) : this(sql, offered, emptySet(), emptyMap())

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    public fun copy(sql: String = this.sql, offered: Set<String> = this.offered): RealSqlCompletion = copy(sql, offered, notOffered, variants)

    /** The SQL for [dialect]. */
    public fun sqlFor(dialect: String): String = variants[dialect] ?: sql
}

/** The dialects spelling `* EXCEPT (...)` `* EXCLUDE (...)`. */
private val STAR_EXCLUDE_DIALECTS = listOf("duckdb", "snowflake")

/** [sql]'s `* EXCEPT (...)` as each dialect spelling it otherwise writes it, for [RealSqlCase.variants]. */
private fun starExceptVariants(sql: String): Map<String, String> =
    STAR_EXCLUDE_DIALECTS.associateWith { sql.replace("* EXCEPT (", "* EXCLUDE (") }

/**
 * One realistic statement over [RealSqlModel.STANDARD]: it must highlight with no error or warning,
 * except inside the SQL fragments in [reported] (the qualified name of a table the model lacks, say;
 * the same name elsewhere still counts). It runs where the target has every feature in [needs];
 * [variants] replaces the SQL for a dialect.
 */
@ApiStatus.Experimental
public data class RealSqlCase(
    val name: String,
    val sql: String,
    val needs: Set<RealSqlFeature> = emptySet(),
    val variants: Map<String, String> = emptyMap(),
    val completions: List<RealSqlCompletion> = emptyList(),
    val reported: Set<String> = emptySet(),
) {
    /** The SQL for [dialect]. */
    public fun sqlFor(dialect: String): String = variants[dialect] ?: sql
}

/**
 * The tables cases name. [tables] are introspected, by dotted path and columns; [bare] are in the
 * model without columns (listed, never introspected); [absent] are named by SQL but not in the model
 * at all; [namespaces] are listed namespaces (`catalog` or `catalog.schema`) that may hold nothing.
 * Paths are `catalog.schema.table`; a target without catalogs drops the first part.
 */
@ApiStatus.Experimental
public data class RealSqlModel(
    val tables: Map<String, List<String>>,
    val bare: List<String>,
    val absent: List<String>,
    val namespaces: List<String> = emptyList(),
) {
    // Binary compatibility (AGENTS.md): the constructor and copy from before namespaces.
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    public constructor(tables: Map<String, List<String>>, bare: List<String>, absent: List<String>) : this(tables, bare, absent, emptyList())

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    public fun copy(tables: Map<String, List<String>> = this.tables, bare: List<String> = this.bare, absent: List<String> = this.absent): RealSqlModel =
        copy(tables, bare, absent, namespaces)

    public companion object {
        /** The external table [STATES] vary. */
        public const val EXTERNAL_TABLE: String = "ext_catalog.archive_db.facts"

        public val STANDARD: RealSqlModel = RealSqlModel(
            tables = mapOf(
                "shop.sales.orders" to listOf("order_id", "user_id", "amount", "status", "created_at"),
                "shop.sales.users" to listOf("user_id", "name", "country", "signup_at"),
                "shop.sales.items" to listOf("item_id", "order_id", "sku", "qty", "price"),
                EXTERNAL_TABLE to listOf("ts", "uid", "kind"),
            ),
            bare = listOf("shop.sales.archive"),
            absent = listOf("shop.sales.ghost"),
        )

        /**
         * [STANDARD] with [EXTERNAL_TABLE] in each state a catalog can hold a table nobody has loaded:
         * introspected, listed without columns, its schema listed without it, its catalog listed empty.
         */
        public val STATES: Map<String, RealSqlModel> = run {
            val others = STANDARD.tables - EXTERNAL_TABLE
            mapOf(
                "introspected" to STANDARD,
                "columnless" to STANDARD.copy(tables = others, bare = STANDARD.bare + EXTERNAL_TABLE),
                "schema-without-table" to STANDARD.copy(tables = others, namespaces = listOf("ext_catalog.archive_db")),
                "catalog-empty" to STANDARD.copy(tables = others, namespaces = listOf("ext_catalog")),
            )
        }
    }
}

/** The shared pack of realistic statements every SQL plugin must highlight without a false error. */
@ApiStatus.Experimental
public object RealSqlCases {
    /**
     * Columns read through derived tables, CTEs and enclosing queries over [RealSqlModel.EXTERNAL_TABLE],
     * checked in every [RealSqlModel.STATES] state: never a column error, whatever the table's state. The
     * table's own name may be reported where the model lacks it.
     */
    public val EXTERNAL: List<RealSqlCase> = listOf(
        RealSqlCase(
            "external-star-in-derived",
            "SELECT uid, * FROM (SELECT ts AS ts1, *, 0 AS k FROM ext_catalog.archive_db.facts) AS u",
            needs = setOf(RealSqlFeature.THREE_PART_NAMES),
            reported = setOf(RealSqlModel.EXTERNAL_TABLE),
        ),
        RealSqlCase(
            "external-star-in-cte-of-derived",
            "WITH c AS (SELECT * FROM (SELECT ts AS ts1, *, 0 AS k FROM ext_catalog.archive_db.facts) AS u) SELECT ts1, uid, kind FROM c",
            needs = setOf(RealSqlFeature.THREE_PART_NAMES),
            reported = setOf(RealSqlModel.EXTERNAL_TABLE),
        ),
        RealSqlCase(
            "external-enclosing-query",
            "SELECT u.uid FROM (SELECT f.uid, f.kind FROM ext_catalog.archive_db.facts f) AS u WHERE u.kind IS NOT NULL",
            needs = setOf(RealSqlFeature.THREE_PART_NAMES),
            reported = setOf(RealSqlModel.EXTERNAL_TABLE),
        ),
        RealSqlCase(
            "external-except-star-in-cte-of-derived",
            "WITH c AS (SELECT * FROM (SELECT ts AS ts, * EXCEPT (ts), 0 AS k FROM ext_catalog.archive_db.facts) AS u) SELECT ts, uid FROM c",
            needs = setOf(RealSqlFeature.THREE_PART_NAMES, RealSqlFeature.STAR_EXCEPT),
            variants = starExceptVariants("WITH c AS (SELECT * FROM (SELECT ts AS ts, * EXCEPT (ts), 0 AS k FROM ext_catalog.archive_db.facts) AS u) SELECT ts, uid FROM c"),
            reported = setOf(RealSqlModel.EXTERNAL_TABLE),
        ),
        RealSqlCase(
            "external-table-name-qualifier",
            "SELECT facts.uid FROM ext_catalog.archive_db.facts GROUP BY facts.uid",
            needs = setOf(RealSqlFeature.THREE_PART_NAMES),
            reported = setOf(RealSqlModel.EXTERNAL_TABLE),
        ),
        // Doris accepts a view body without AS.
        RealSqlCase(
            "external-view-cte-without-as",
            "CREATE OR REPLACE VIEW shop.sales.v WITH c AS (SELECT * FROM ext_catalog.archive_db.facts) SELECT uid, kind FROM c",
            needs = setOf(RealSqlFeature.THREE_PART_NAMES, RealSqlFeature.DORIS),
            reported = setOf(RealSqlModel.EXTERNAL_TABLE),
        ),
        // Doris runs a scheduled job's statement, and writes through a table function given properties.
        RealSqlCase(
            "external-scheduled-job",
            "CREATE JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO shop.sales.archive SELECT * EXCEPT (kind) FROM ext_catalog.archive_db.facts WHERE uid = 1",
            needs = setOf(RealSqlFeature.THREE_PART_NAMES, RealSqlFeature.DORIS, RealSqlFeature.STAR_EXCEPT),
            reported = setOf(RealSqlModel.EXTERNAL_TABLE),
        ),
        RealSqlCase(
            "external-insert-into-table-function",
            "INSERT INTO s3(\"uri\" = \"s3://bucket/path/\", \"format\" = \"csv\") SELECT ts, uid FROM ext_catalog.archive_db.facts WHERE kind = 'a'",
            needs = setOf(RealSqlFeature.THREE_PART_NAMES, RealSqlFeature.DORIS),
            reported = setOf(RealSqlModel.EXTERNAL_TABLE),
        ),
    )


    /**
     * Table functions, read alone, joined to a table under an alias, through `t.*` and `t.col`, and written
     * where the dialect writes through one: a known function is no unknown function, its columns are open
     * unless its schema is known, and a table function target counts no columns against the query.
     */
    public val TABLE_FUNCTIONS: List<RealSqlCase> = listOf(
        RealSqlCase("srf-read", "SELECT g FROM generate_series(1, 3) AS g", needs = setOf(RealSqlFeature.SET_RETURNING_FUNCTIONS)),
        RealSqlCase(
            "srf-joined",
            "SELECT o.order_id, g.n FROM sales.orders o JOIN generate_series(1, 3) AS g(n) ON g.n = o.order_id",
            needs = setOf(RealSqlFeature.SET_RETURNING_FUNCTIONS),
        ),
        RealSqlCase("srf-star", "SELECT t.* FROM generate_series(1, 3) AS t", needs = setOf(RealSqlFeature.SET_RETURNING_FUNCTIONS)),

        RealSqlCase("clickhouse-tf-read", "SELECT number FROM numbers(10)", needs = setOf(RealSqlFeature.CLICKHOUSE_TABLE_FUNCTIONS)),
        RealSqlCase(
            "clickhouse-tf-joined",
            "SELECT t.number, o.order_id FROM numbers(3) AS t JOIN sales.orders AS o ON o.order_id = t.number",
            needs = setOf(RealSqlFeature.CLICKHOUSE_TABLE_FUNCTIONS),
        ),
        RealSqlCase("clickhouse-tf-star", "SELECT t.* FROM s3('https://bucket.example/path/data.csv', 'CSV') AS t", needs = setOf(RealSqlFeature.CLICKHOUSE_TABLE_FUNCTIONS)),
        RealSqlCase("clickhouse-tf-open-column", "SELECT t.c1 FROM url('https://example.com/data.csv', 'CSV') AS t", needs = setOf(RealSqlFeature.CLICKHOUSE_TABLE_FUNCTIONS)),
        RealSqlCase(
            "clickhouse-insert-into-function",
            "INSERT INTO FUNCTION s3('https://bucket.example/path/out.csv', 'CSV') SELECT order_id, amount FROM sales.orders",
            needs = setOf(RealSqlFeature.CLICKHOUSE_TABLE_FUNCTIONS),
        ),

        RealSqlCase("doris-tf-read", "SELECT number FROM numbers(\"number\" = \"10\")", needs = setOf(RealSqlFeature.DORIS_TABLE_FUNCTIONS)),
        RealSqlCase(
            "doris-tf-joined",
            "SELECT t.number, o.order_id FROM numbers(\"number\" = \"3\") AS t JOIN sales.orders o ON o.order_id = t.number",
            needs = setOf(RealSqlFeature.DORIS_TABLE_FUNCTIONS),
        ),
        RealSqlCase(
            "doris-tf-star",
            "SELECT t.* FROM s3(\"uri\" = \"s3://bucket/path/data.csv\", \"format\" = \"csv\") AS t",
            needs = setOf(RealSqlFeature.DORIS_TABLE_FUNCTIONS),
        ),
        RealSqlCase(
            "doris-tf-open-column",
            "SELECT t.c1 FROM s3(\"uri\" = \"s3://bucket/path/data.csv\", \"format\" = \"csv\") AS t",
            needs = setOf(RealSqlFeature.DORIS_TABLE_FUNCTIONS),
        ),
        RealSqlCase(
            "doris-insert-into-table-function",
            "INSERT INTO s3(\"uri\" = \"s3://bucket/path/out/\", \"format\" = \"csv\") SELECT order_id, amount FROM sales.orders",
            needs = setOf(RealSqlFeature.DORIS_TABLE_FUNCTIONS),
        ),

        RealSqlCase("duckdb-tf-read", "SELECT * FROM read_parquet('data/orders.parquet')", needs = setOf(RealSqlFeature.DUCKDB_TABLE_FUNCTIONS)),
        RealSqlCase(
            "duckdb-tf-joined",
            "SELECT t.order_id, u.name FROM read_csv('data/orders.csv') AS t JOIN sales.users u ON u.user_id = t.user_id",
            needs = setOf(RealSqlFeature.DUCKDB_TABLE_FUNCTIONS),
        ),
        RealSqlCase("duckdb-tf-star", "SELECT t.* FROM read_parquet('data/*.parquet') AS t", needs = setOf(RealSqlFeature.DUCKDB_TABLE_FUNCTIONS)),
        RealSqlCase("duckdb-range", "SELECT r.range FROM range(3) AS r", needs = setOf(RealSqlFeature.DUCKDB_TABLE_FUNCTIONS)),

        RealSqlCase("trino-sequence", "SELECT * FROM TABLE(system.sequence(start => 1, stop => 10))", needs = setOf(RealSqlFeature.TRINO_TABLE_FUNCTIONS)),
        RealSqlCase(
            "trino-sequence-aliased",
            "SELECT s.sequential_number FROM TABLE(system.sequence(start => 1, stop => 3)) AS s",
            needs = setOf(RealSqlFeature.TRINO_TABLE_FUNCTIONS),
        ),
        RealSqlCase(
            "trino-unnest-joined",
            "SELECT o.order_id, u.x FROM sales.orders o CROSS JOIN UNNEST(ARRAY[1, 2]) AS u(x)",
            needs = setOf(RealSqlFeature.TRINO_TABLE_FUNCTIONS),
        ),
    )

    public val ALL: List<RealSqlCase> = listOf(
        // Stars through derived tables and CTEs.
        RealSqlCase(
            "star-derived",
            "SELECT d.user_id, d.amount FROM (SELECT * FROM sales.orders) AS d WHERE d.status = 'paid'",
            completions = listOf(RealSqlCompletion("SELECT d.<caret> FROM (SELECT * FROM sales.orders) AS d", setOf("user_id", "amount"))),
        ),
        RealSqlCase(
            "star-cte",
            "WITH o AS (SELECT * FROM sales.orders) SELECT user_id, sum(amount) AS total FROM o GROUP BY user_id",
            completions = listOf(RealSqlCompletion("WITH o AS (SELECT * FROM sales.orders) SELECT <caret> FROM o", setOf("user_id", "status"))),
        ),
        RealSqlCase(
            "star-nested-cte",
            "WITH a AS (SELECT * FROM sales.orders), b AS (SELECT * FROM a WHERE amount > 0) SELECT b.order_id, b.user_id FROM b",
        ),
        RealSqlCase(
            "star-cte-of-derived",
            "WITH c AS (SELECT * FROM (SELECT * FROM sales.users) AS u) SELECT c.name, c.country FROM c",
        ),
        RealSqlCase(
            "star-mixed-projection",
            "SELECT x.total, x.user_id, x.tag FROM (SELECT amount * 2 AS total, *, 'k' AS tag FROM sales.orders) AS x",
        ),
        RealSqlCase(
            "star-qualified",
            "SELECT p.name, p.order_id FROM (SELECT u.*, o.order_id FROM sales.users u JOIN sales.orders o ON o.user_id = u.user_id) AS p",
        ),
        RealSqlCase(
            "star-except",
            "SELECT e.user_id, e.amount FROM (SELECT * EXCEPT (status) FROM sales.orders) AS e",
            needs = setOf(RealSqlFeature.STAR_EXCEPT),
            variants = starExceptVariants("SELECT e.user_id, e.amount FROM (SELECT * EXCEPT (status) FROM sales.orders) AS e"),
            completions = listOf(
                RealSqlCompletion(
                    "SELECT e.<caret> FROM (SELECT * EXCEPT (status) FROM sales.orders) AS e", setOf("user_id"), notOffered = setOf("status"),
                    variants = starExceptVariants("SELECT e.<caret> FROM (SELECT * EXCEPT (status) FROM sales.orders) AS e"),
                ),
            ),
        ),
        RealSqlCase(
            "star-except-in-cte-with-expression",
            "WITH c AS (SELECT upper(status) AS status, * EXCEPT (status) FROM sales.orders) SELECT status, user_id FROM c",
            needs = setOf(RealSqlFeature.STAR_EXCEPT),
            variants = starExceptVariants("WITH c AS (SELECT upper(status) AS status, * EXCEPT (status) FROM sales.orders) SELECT status, user_id FROM c"),
        ),
        RealSqlCase(
            "star-replace",
            "SELECT r.amount, r.user_id FROM (SELECT * REPLACE (amount * 100 AS amount) FROM sales.orders) AS r",
            needs = setOf(RealSqlFeature.STAR_REPLACE),
        ),
        // Relations the model knows nothing about stay open.
        RealSqlCase(
            "star-over-bare-table",
            "WITH a AS (SELECT * FROM sales.archive) SELECT a.anything, a.order_id FROM a",
        ),
        RealSqlCase(
            "star-over-absent-table-in-derived",
            "SELECT g.whatever FROM (SELECT * FROM sales.ghost) AS g WHERE g.other = 1",
            // The missing table itself may be reported; nothing downstream.
            reported = setOf("sales.ghost"),
        ),
        RealSqlCase(
            "three-part-name",
            "SELECT o.order_id, o.amount FROM shop.sales.orders o",
            needs = setOf(RealSqlFeature.THREE_PART_NAMES),
        ),
        // Joins.
        RealSqlCase(
            "join-on",
            "SELECT o.order_id, u.name, i.sku FROM sales.orders o JOIN sales.users u ON u.user_id = o.user_id " +
                "LEFT JOIN sales.items i ON i.order_id = o.order_id",
        ),
        RealSqlCase(
            "join-using",
            "SELECT user_id, name, amount FROM sales.orders JOIN sales.users USING (user_id)",
        ),
        RealSqlCase(
            "join-natural",
            "SELECT user_id, name FROM sales.orders NATURAL JOIN sales.users",
            needs = setOf(RealSqlFeature.NATURAL_JOIN),
        ),
        RealSqlCase(
            "join-full-using",
            "SELECT user_id, amount, name FROM sales.orders FULL JOIN sales.users USING (user_id)",
            needs = setOf(RealSqlFeature.FULL_JOIN),
        ),
        // Set operations name their columns after the first branch.
        RealSqlCase(
            "union-naming",
            "SELECT s.uid FROM (SELECT user_id AS uid FROM sales.orders UNION ALL SELECT user_id FROM sales.users) AS s ORDER BY uid",
        ),
        RealSqlCase(
            "intersect-except",
            "SELECT user_id FROM sales.orders INTERSECT SELECT user_id FROM sales.users EXCEPT SELECT user_id FROM sales.orders WHERE amount < 0",
            needs = setOf(RealSqlFeature.INTERSECT_EXCEPT),
        ),
        // Windows.
        RealSqlCase(
            "window-partition-order",
            "SELECT order_id, row_number() OVER (PARTITION BY user_id ORDER BY created_at DESC) AS rn, " +
                "sum(amount) OVER (PARTITION BY user_id) AS total FROM sales.orders",
        ),
        RealSqlCase(
            "window-named",
            "SELECT order_id, rank() OVER w AS r FROM sales.orders WINDOW w AS (PARTITION BY user_id ORDER BY amount)",
            needs = setOf(RealSqlFeature.NAMED_WINDOW),
        ),
        // Aliases in later clauses.
        RealSqlCase(
            "order-by-alias",
            "SELECT user_id AS u, sum(amount) AS total FROM sales.orders GROUP BY user_id ORDER BY total DESC, u",
        ),
        RealSqlCase(
            "group-by-having-alias",
            "SELECT status AS s, count(*) AS n FROM sales.orders GROUP BY s HAVING n > 1",
            needs = setOf(RealSqlFeature.GROUP_BY_ALIAS),
        ),
        RealSqlCase(
            "lateral-alias",
            "SELECT amount * 2 AS doubled, doubled + 1 AS more FROM sales.orders",
            needs = setOf(RealSqlFeature.LATERAL_ALIAS),
        ),
        // Subqueries.
        RealSqlCase(
            "correlated-exists",
            "SELECT u.name FROM sales.users u WHERE EXISTS (SELECT 1 FROM sales.orders o WHERE o.user_id = u.user_id AND o.amount > 10)",
        ),
        RealSqlCase(
            "in-subquery-and-scalar",
            "SELECT u.name, (SELECT max(o.amount) FROM sales.orders o WHERE o.user_id = u.user_id) AS top " +
                "FROM sales.users u WHERE u.user_id IN (SELECT user_id FROM sales.orders)",
        ),
        RealSqlCase(
            "lateral-subquery",
            "SELECT u.name, l.amount FROM sales.users u, LATERAL (SELECT o.amount FROM sales.orders o WHERE o.user_id = u.user_id) AS l",
            needs = setOf(RealSqlFeature.LATERAL_SUBQUERY),
        ),
        RealSqlCase(
            "unnest",
            "SELECT u.name, t.tag FROM sales.users u CROSS JOIN UNNEST(ARRAY['a', 'b']) AS t(tag)",
            needs = setOf(RealSqlFeature.UNNEST),
        ),
        RealSqlCase(
            "values-aliases",
            "SELECT v.a, v.b FROM (VALUES (1, 'x'), (2, 'y')) AS v(a, b)",
            needs = setOf(RealSqlFeature.VALUES_COLUMN_ALIASES),
        ),
        // Statements around queries.
        RealSqlCase(
            "insert-select",
            "INSERT INTO sales.archive SELECT * FROM sales.orders WHERE status = 'done'",
        ),
        RealSqlCase(
            "create-table-as",
            "CREATE TABLE sales.big_orders AS SELECT order_id, amount FROM sales.orders WHERE amount > 100",
            needs = setOf(RealSqlFeature.CREATE_TABLE_AS),
        ),
        RealSqlCase(
            "case-cast-dates",
            "SELECT CASE WHEN amount > 10 THEN 'big' ELSE 'small' END AS size, CAST(amount AS DECIMAL(10, 2)) AS money, " +
                "created_at + INTERVAL '1' DAY AS due FROM sales.orders",
            variants = mapOf("mysql" to "SELECT CASE WHEN amount > 10 THEN 'big' ELSE 'small' END AS size, " +
                "CAST(amount AS DECIMAL(10, 2)) AS money, created_at + INTERVAL 1 DAY AS due FROM sales.orders"),
        ),
        // Identifier case.
        RealSqlCase(
            "unquoted-case-folding",
            "SELECT O.ORDER_ID, o.User_Id FROM SALES.ORDERS o",
        ),
        RealSqlCase(
            "double-quoted-identifiers",
            "SELECT \"o\".\"order_id\" FROM \"sales\".\"orders\" AS \"o\"",
            needs = setOf(RealSqlFeature.DOUBLE_QUOTED_IDENTIFIERS),
        ),
        RealSqlCase(
            "backtick-identifiers",
            "SELECT `o`.`order_id` FROM `sales`.`orders` AS `o`",
            needs = setOf(RealSqlFeature.BACKTICK_IDENTIFIERS),
        ),
        // Shapes from Doris dogfooding (invented names): a renamed column beside a star in a derived table, under a CTE.
        RealSqlCase(
            "doris-except-star-in-derived-cte",
            """
            WITH recent AS (
               SELECT * FROM (
                   select FROM_SECOND(ts) AS ts, * EXCEPT(ts), 0 AS src_flag
                   FROM ext_catalog.archive_db.facts
               ) AS d
            )
            SELECT ts, uid FROM recent
            """.trimIndent(),
            needs = setOf(RealSqlFeature.DORIS, RealSqlFeature.STAR_EXCEPT, RealSqlFeature.THREE_PART_NAMES),
        ),
        RealSqlCase(
            "doris-star-in-derived-cte",
            """
            WITH recent AS (
               SELECT * FROM (
                   select FROM_SECOND(ts) AS ts2, * , 0 AS src_flag
                   FROM ext_catalog.archive_db.facts
               ) AS d
            )
            SELECT ts2, uid FROM recent
            """.trimIndent(),
            needs = setOf(RealSqlFeature.DORIS, RealSqlFeature.THREE_PART_NAMES),
        ),
        RealSqlCase(
            "doris-star-in-derived",
            """
            SELECT uid, * FROM (
                 select FROM_SECOND(ts) AS ts2, * , 0 AS src_flag
                 FROM ext_catalog.archive_db.facts
             ) AS d
            """.trimIndent(),
            needs = setOf(RealSqlFeature.DORIS, RealSqlFeature.THREE_PART_NAMES),
            completions = listOf(RealSqlCompletion(
                "SELECT <caret> FROM (select FROM_SECOND(ts) AS ts2, * , 0 AS src_flag FROM ext_catalog.archive_db.facts) AS d",
                setOf("uid", "ts2"),
            )),
        ),
    )
}
