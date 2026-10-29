package dev.brikk.house.sql.metadata

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FunctionCatalogTest {

    // ------------------------------------------------------------ clickhouse

    @Test
    fun clickhouseCatalogLoadsVendoredSystemFunctions() {
        val historical = CLICKHOUSE_26_5_1_1_FUNCTION_CATALOG
        // 1570 system.functions definitions less 9 `__` internals, plus 87 system.table_functions.
        assertEquals(1648, historical.size)
        assertTrue("length" in historical)
        assertEquals(FunctionKind.AGGREGATE, historical["groupBitAnd"]!!.kind)
        assertEquals(
            historical["groupBitAnd"],
            historical["BIT_AND"],
        )
        // system.functions exposes no signatures; do not fabricate overloads.
        assertTrue(historical["length"]!!.overloads.isEmpty())
    }

    @Test
    fun clickhouseCatalogHasTheTableFunctionsDump() {
        val historical = CLICKHOUSE_26_5_1_1_FUNCTION_CATALOG
        // Pinned to vendor/data/clickhouse-table-functions-26.5.1.1.tsv (87 rows).
        val tables = historical.functions.filter { it.kind == FunctionKind.TABLE_VALUED }
        assertEquals(87, tables.size)
        for (name in listOf("numbers", "s3", "file", "url", "remote", "generate_series", "null")) {
            assertTrue(historical.getAll(name).any { it.kind == FunctionKind.TABLE_VALUED }, name)
        }
        // Names in both dumps are defined once per kind.
        for (name in listOf("file", "format", "fuzzQuery")) {
            assertEquals(setOf(FunctionKind.SCALAR, FunctionKind.TABLE_VALUED), historical.getAll(name).map { it.kind }.toSet(), name)
        }
        assertEquals(listOf(FunctionKind.SCALAR), historical.getAll("length").map { it.kind })
    }

    @Test
    fun clickhouseCatalogLeavesOutEngineInternals() {
        assertTrue(CLICKHOUSE_FUNCTION_CATALOG.functions.none { it.name.startsWith("__") })
        assertFalse("__getScalar" in CLICKHOUSE_FUNCTION_CATALOG)
        assertTrue("_CAST" in CLICKHOUSE_FUNCTION_CATALOG)
    }

    // ---------------------------------------------------------------- doris

    @Test
    fun dorisCatalogLoadsWithExpectedSurface() {
        // Pinned to the Doris 4.1.5-release registry the generator ran against.
        assertEquals(713, DORIS_FUNCTION_CATALOG.size)
        assertTrue("ABS" in DORIS_FUNCTION_CATALOG)
        // alias lookup (SUBSTR/SUBSTRING/MID registered on one def)
        val substr = DORIS_FUNCTION_CATALOG["substr"]
        assertEquals(DORIS_FUNCTION_CATALOG["substring"], substr)
        // case-insensitive
        assertEquals(DORIS_FUNCTION_CATALOG["abs"], DORIS_FUNCTION_CATALOG["ABS"])
    }

    @Test
    fun tableFunctionsAreClassified() {
        assertTrue(DORIS_FUNCTION_CATALOG.isTableFunction("numbers"))
        assertTrue(DORIS_FUNCTION_CATALOG.isTableFunction("explode"))
        assertTrue(!DORIS_FUNCTION_CATALOG.isTableFunction("abs"))
    }

    @Test
    fun dorisCatalogIsTheRegistryOf415() {
        val parse = DORIS_FUNCTION_CATALOG["parse_to_variant"]!!
        val tryParse = DORIS_FUNCTION_CATALOG["try_parse_to_variant"]!!
        for (def in listOf(parse, tryParse)) {
            assertEquals(FunctionKind.SCALAR, def.kind)
            assertEquals(listOf(FunctionOverload(listOf("VARCHAR"), "VARIANT")), def.overloads)
        }
        assertEquals(NullPropagation.STRICT, parse.profile?.nullPropagation)
        assertEquals(NullPropagation.ALWAYS_NULLABLE, tryParse.profile?.nullPropagation)
        val vectorSearch = DORIS_FUNCTION_CATALOG["vector_search"]!!
        assertEquals(FunctionKind.TABLE_VALUED, vectorSearch.kind)
        assertTrue(vectorSearch.overloads.isEmpty(), "VectorSearch has a dynamic relation schema")
        assertTrue("EMBED" in DORIS_FUNCTION_CATALOG)
        for (name in listOf("full_text_search", "lance_index_entries")) {
            val function = DORIS_FUNCTION_CATALOG[name]!!
            assertEquals(FunctionKind.TABLE_VALUED, function.kind)
            assertTrue(function.overloads.isEmpty(), "$name is a relation, not a scalar signature")
        }
        // Not registered by 4.1.5; the first three were on the earlier master pin.
        for (name in listOf("LEVENSHTEIN", "AVG_MAP", "BINLOG", "ICEBERG_META")) {
            assertFalse(name in DORIS_FUNCTION_CATALOG, name)
        }
        assertTrue("DEFAULT" in DORIS_GRAMMAR_BUILTINS)
    }

    @Test
    fun dorisSignatureConstantsAreResolved() {
        // scalar/ElementAt.java lists VARIANT_KEY_SIGNATURE and VARIANT_INDEX_SIGNATURE by name.
        assertEquals(
            listOf(
                FunctionOverload(listOf("ARRAY<ANY_0>", "BIGINT"), "ANY_0"),
                FunctionOverload(listOf("VARIANT", "VARCHAR"), "VARIANT"),
                FunctionOverload(listOf("VARIANT", "BIGINT"), "VARIANT"),
                FunctionOverload(listOf("MAP<ANY_0,ANY_1>", "ANY_0"), "ANY_1"),
            ),
            DORIS_FUNCTION_CATALOG["element_at"]!!.overloads,
        )
    }

    @Test
    fun dorisOverloadsMatchTheSourceSignatures() {
        // Pinned against scalar/Abs.java's SIGNATURES in the reference/doris checkout:
        // 9 overloads; widening integer returns (abs(BIGINT) -> LARGEINT, ...).
        val abs = DORIS_FUNCTION_CATALOG["abs"]!!
        assertEquals(9, abs.overloads.size)
        assertTrue(FunctionOverload(listOf("DOUBLE"), "DOUBLE") in abs.overloads)
        assertTrue(FunctionOverload(listOf("BIGINT"), "LARGEINT") in abs.overloads)
        assertTrue(FunctionOverload(listOf("TINYINT"), "SMALLINT") in abs.overloads)
        // agg/Count.java: count() and count(varargs ANY).
        val count = DORIS_FUNCTION_CATALOG["count"]!!
        assertEquals(
            listOf(
                FunctionOverload(emptyList(), "BIGINT"),
                FunctionOverload(listOf("ANY"), "BIGINT", variadic = true),
            ),
            count.overloads,
        )
    }

    // ---------------------------------------------------------------- starrocks

    @Test
    fun starrocksCatalogLoadsWithExpectedSurface() {
        // Pinned to StarRocks 4.1.4 (SHOW FULL BUILTIN FUNCTIONS live dump).
        assertEquals(820, STARROCKS_FUNCTION_CATALOG.size)
        assertTrue("ABS" in STARROCKS_FUNCTION_CATALOG)
        // case-insensitive lookup
        assertEquals(STARROCKS_FUNCTION_CATALOG["abs"], STARROCKS_FUNCTION_CATALOG["ABS"])
        // scalar/aggregate/window/table kinds are all represented
        assertEquals(FunctionKind.SCALAR, STARROCKS_FUNCTION_CATALOG["abs"]!!.kind)
        assertEquals(FunctionKind.AGGREGATE, STARROCKS_FUNCTION_CATALOG["sum"]!!.kind)
        assertEquals(FunctionKind.WINDOW, STARROCKS_FUNCTION_CATALOG["row_number"]!!.kind)
    }

    @Test
    fun starrocksTableFunctionsAreClassified() {
        // From TableFunction.java initBuiltins() (live Function Type = Table).
        assertTrue(STARROCKS_FUNCTION_CATALOG.isTableFunction("unnest"))
        assertTrue(STARROCKS_FUNCTION_CATALOG.isTableFunction("generate_series"))
        assertTrue(STARROCKS_FUNCTION_CATALOG.isTableFunction("json_each"))
        assertTrue(!STARROCKS_FUNCTION_CATALOG.isTableFunction("abs"))
    }

    @Test
    fun starrocksWindowFunctionsAreClassified() {
        // onlyAnalyticUsedFunctions (FunctionSet.java): reported as "Aggregate" by the live
        // dump, reclassified WINDOW.
        for (w in listOf("lead", "lag", "dense_rank", "rank", "cume_dist", "percent_rank",
                "ntile", "row_number", "first_value", "last_value")) {
            assertEquals(FunctionKind.WINDOW, STARROCKS_FUNCTION_CATALOG[w]!!.kind, "kind of $w")
        }
    }

    @Test
    fun starrocksVariadicsAreFlagged() {
        // Live dump: concat(VARCHAR, ...) -> normalized to variadic=true (sentinel dropped).
        val concat = STARROCKS_FUNCTION_CATALOG["concat"]!!
        assertTrue(concat.overloads.any { it.variadic && it.argTypes == listOf("VARCHAR") })
        // No literal "..." sentinel leaks into any overload arg list.
        assertTrue(
            STARROCKS_FUNCTION_CATALOG.functions.none { d -> d.overloads.any { "..." in it.argTypes } }
        )
    }

    @Test
    fun starrocksAbsOverloadsMatchLiveEngine() {
        // Pinned to the 4.1.4 live dump: widening integer returns (abs(BIGINT) -> LARGEINT).
        val abs = STARROCKS_FUNCTION_CATALOG["abs"]!!
        assertTrue(FunctionOverload(listOf("DOUBLE"), "DOUBLE") in abs.overloads)
        assertTrue(FunctionOverload(listOf("BIGINT"), "LARGEINT") in abs.overloads)
        // LARGEINT (INT128) is a StarRocks-native type; abs(LARGEINT) -> LARGEINT.
        assertTrue(FunctionOverload(listOf("LARGEINT"), "LARGEINT") in abs.overloads)
    }

    @Test
    fun dorisVariadicsAreFlagged() {
        // scalar/Concat.java: ret(VARCHAR).varArgs(VARCHAR), ret(STRING).varArgs(STRING).
        val concat = DORIS_FUNCTION_CATALOG["concat"]!!
        assertEquals(
            listOf(
                FunctionOverload(listOf("VARCHAR"), "VARCHAR", variadic = true),
                FunctionOverload(listOf("STRING"), "STRING", variadic = true),
            ),
            concat.overloads,
        )
    }

    @Test
    fun dorisArrayTypesAndPlaceholdersAreRendered() {
        // scalar/ArraySort.java: retArgType(0).args(ArrayType.of(AnyDataType...)) and a
        // lambda overload — rendered with the extractor's ARG_/ANY placeholders.
        val arraySort = DORIS_FUNCTION_CATALOG["array_sort"]!!
        assertEquals(
            listOf(
                FunctionOverload(listOf("ARRAY<ANY>"), "ARG_0"),
                FunctionOverload(listOf("LAMBDA"), "ARRAY<ANY>"),
            ),
            arraySort.overloads,
        )
    }

    @Test
    fun dorisOverloadTotalsArePinned() {
        // Doris 4.1.5: 618 defs carry 1418 overloads; the rest (dynamic getSignatures(): all table-valued functions,
        // rank-like window functions, ...) stay empty.
        assertEquals(1418, DORIS_FUNCTION_CATALOG.functions.sumOf { it.overloads.size })
        assertEquals(618, DORIS_FUNCTION_CATALOG.functions.count { it.overloads.isNotEmpty() })
        // Dynamic-signature examples remain overload-free (names still resolvable).
        assertTrue(DORIS_FUNCTION_CATALOG["rank"]!!.overloads.isEmpty())
        assertTrue(DORIS_FUNCTION_CATALOG["numbers"]!!.overloads.isEmpty())
    }

    // ------------------------------------------------- doris semantic profiles

    @Test
    fun dorisNullPropagationIsPinnedAgainstTheSourceMarkers() {
        // Each verdict is pinned against the class declaration in the reference/doris
        // checkout (nereids .../functions/scalar/*.java implements clauses):
        // Abs implements PropagateNullable -> STRICT.
        assertEquals(SemanticProfile(NullPropagation.STRICT), DORIS_FUNCTION_CATALOG["abs"]!!.profile)
        // StrToDate implements AlwaysNullable -> ALWAYS_NULLABLE.
        assertEquals(
            SemanticProfile(NullPropagation.ALWAYS_NULLABLE),
            DORIS_FUNCTION_CATALOG["str_to_date"]!!.profile,
        )
        // Uuid implements AlwaysNotNullable -> NEVER_NULL.
        assertEquals(SemanticProfile(NullPropagation.NEVER_NULL), DORIS_FUNCTION_CATALOG["uuid"]!!.profile)
        // Coalesce declares a concrete nullable() override ("class wins" over markers):
        // honest UNKNOWN with the provenance preserved in notes.
        assertEquals(
            SemanticProfile(NullPropagation.UNKNOWN, "doris: custom nullable() override"),
            DORIS_FUNCTION_CATALOG["coalesce"]!!.profile,
        )
    }

    @Test
    fun dorisProfileCoverageIsPinned() {
        // The Doris 4.1.5 registry (713 defs). A regeneration
        // against a new Doris pin is EXPECTED to move these — update deliberately.
        val byMode = DORIS_FUNCTION_CATALOG.functions.groupingBy { it.profile?.nullPropagation }.eachCount()
        assertEquals(336, byMode[NullPropagation.STRICT])
        assertEquals(144, byMode[NullPropagation.ALWAYS_NULLABLE])
        assertEquals(110, byMode[NullPropagation.NEVER_NULL])
        assertEquals(68, byMode[NullPropagation.UNKNOWN]) // all custom nullable() overrides
        assertEquals(55, byMode[null]) // no marker on the class (incl. all table kinds)
        assertEquals(713, byMode.values.sum())
        // Every emitted UNKNOWN carries its provenance note; no unmapped markers at the pin.
        assertTrue(
            DORIS_FUNCTION_CATALOG.functions
                .filter { it.profile?.nullPropagation == NullPropagation.UNKNOWN }
                .all { it.profile?.notes == "doris: custom nullable() override" },
        )
    }

    @Test
    fun dorisTableKindsCarryNoProfile() {
        // Row-set producers: TableValuedFunction.nullable() throws in the engine —
        // scalar nullability is not applicable, so the generator emits no profile.
        val tableKinds = setOf(FunctionKind.TABLE_VALUED, FunctionKind.TABLE_GENERATING)
        val tableDefs = DORIS_FUNCTION_CATALOG.functions.filter { it.kind in tableKinds }
        assertTrue(tableDefs.isNotEmpty())
        assertTrue(tableDefs.all { it.profile == null })
    }

    // ---------------------------------------------------------------- duckdb

    @Test
    fun duckdbCatalogLoadsWithExpectedSurface() {
        // DuckDB 2.0's native ICU replacement exposes the complete locale collation set again.
        for (locale in listOf("fy", "lij", "nso", "st", "tn")) {
            assertTrue("icu_collate_$locale" in DUCKDB_FUNCTION_CATALOG)
        }
        assertTrue("icu_collate_en" in DUCKDB_FUNCTION_CATALOG)
        assertTrue("list_transform" in DUCKDB_FUNCTION_CATALOG)
        // Engine-verified overload: list_transform(ANY[], LAMBDA) -> ANY[], with the
        // engine's verbatim parameter names incl. the lambda shape.
        val listTransform = DUCKDB_FUNCTION_CATALOG["list_transform"]!!
        assertEquals(
            listOf(
                FunctionOverload(
                    listOf("ANY[]", "LAMBDA"), "ANY[]",
                    argNames = listOf("list", "lambda"),
                ),
            ),
            listTransform.overloads,
        )
        assertNull(listTransform.nativeKind)
        for (name in listOf("json_set", "json_insert", "json_replace", "json_remove", "json_deep_merge")) {
            assertEquals(FunctionKind.SCALAR, DUCKDB_FUNCTION_CATALOG[name]?.kind, name)
        }
        for (name in listOf("variant_type", "variant_keys", "variant_contains", "variant_array_length")) {
            assertEquals(FunctionKind.SCALAR, DUCKDB_FUNCTION_CATALOG[name]?.kind, name)
        }
        assertEquals(FunctionKind.AGGREGATE, DUCKDB_FUNCTION_CATALOG["variant_group_object"]?.kind)
        assertEquals(FunctionKind.SCALAR, DUCKDB_FUNCTION_CATALOG["setval"]?.kind)
    }

    @Test
    fun duckdbVariadicsAreFlagged() {
        // duckdb_functions() reports concat as (value ANY, varargs ANY) — the single
        // fixed parameter is named.
        val concat = DUCKDB_FUNCTION_CATALOG["concat"]!!
        assertEquals(
            listOf(FunctionOverload(listOf("ANY"), "ANY", variadic = true, argNames = listOf("value"))),
            concat.overloads,
        )
    }

    @Test
    fun duckdbMacrosKeepNativeKind() {
        // pg_has_role is a macro: normalized to SCALAR, native kind preserved.
        val pgHasRole = DUCKDB_FUNCTION_CATALOG["pg_has_role"]!!
        assertEquals(FunctionKind.SCALAR, pgHasRole.kind)
        assertEquals("macro", pgHasRole.nativeKind)
        // histogram_values is a table_macro: normalized to TABLE_VALUED.
        val histogramValues = DUCKDB_FUNCTION_CATALOG["histogram_values"]!!
        assertEquals(FunctionKind.TABLE_VALUED, histogramValues.kind)
        assertEquals("table_macro", histogramValues.nativeKind)
        assertTrue(DUCKDB_FUNCTION_CATALOG.isTableFunction("histogram_values"))
        // current_database is registered as BOTH scalar and macro -> plain scalar def.
        assertNull(DUCKDB_FUNCTION_CATALOG["current_database"]!!.nativeKind)
    }

    @Test
    fun duckdbArgNamesArePinnedAgainstTheEngine() {
        // duckdb_functions() (v2.0.0) lists round's parameters as ['x'] and
        // ['x', 'precision'] per numeric overload — names attach per overload, in
        // argTypes order.
        val round = DUCKDB_FUNCTION_CATALOG["round"]!!
        assertTrue(
            FunctionOverload(listOf("DOUBLE"), "DOUBLE", argNames = listOf("x")) in round.overloads,
        )
        assertTrue(
            FunctionOverload(
                listOf("DOUBLE", "INTEGER"), "DOUBLE", argNames = listOf("x", "precision"),
            ) in round.overloads,
        )
        // Names differ ACROSS overloads of one function — the reason argNames lives on
        // FunctionOverload, not on a per-def profile.
        assertEquals(
            setOf(listOf("x"), listOf("x", "precision")),
            round.overloads.mapNotNull { it.argNames }.toSet(),
        )
        val all = DUCKDB_FUNCTION_CATALOG.functions.flatMap { it.overloads }
        // Named overloads keep names aligned with the fixed parameter types (variadic
        // rows may name the vararg tail, e.g. concat(string, ...)).
        assertTrue(
            all.filter { it.argNames != null && !it.variadic }
                .all { it.argNames!!.size == it.argTypes.size },
        )
    }

    @Test
    fun duckdbProfilesStayNull() {
        // duckdb_functions() exposes no null-handling column (v2.0.0: has_side_effects
        // and stability are the only behavioral flags) — honest UNKNOWN as null.
        assertTrue(DUCKDB_FUNCTION_CATALOG.functions.all { it.profile == null })
    }

    @Test
    fun duckdbTableFunctionsAreClassified() {
        assertTrue(DUCKDB_FUNCTION_CATALOG.isTableFunction("glob"))
        assertTrue(DUCKDB_FUNCTION_CATALOG.isTableFunction("read_csv"))
        assertTrue(!DUCKDB_FUNCTION_CATALOG.isTableFunction("abs"))
    }

    // ---------------------------------------------------------------- trino

    @Test
    fun trinoCatalogLoadsWithExpectedSurface() {
        // Stock trinodb/trino:484, including geospatial and ML plugins:
        // 915 rows -> 825 distinct signatures over 443 (name, kind) definitions.
        assertEquals(443, TRINO_FUNCTION_CATALOG.size)
        assertTrue("json_scalar" in TRINO_FUNCTION_CATALOG)
        assertTrue("approx_percentile" in TRINO_FUNCTION_CATALOG)
        // TSV-verified overload count for approx_percentile.
        assertEquals(15, TRINO_FUNCTION_CATALOG["approx_percentile"]!!.overloads.size)
        assertEquals(FunctionKind.AGGREGATE, TRINO_FUNCTION_CATALOG["approx_percentile"]!!.kind)
    }

    @Test
    fun trinoWindowAndTableKinds() {
        val rowNumber = TRINO_FUNCTION_CATALOG["row_number"]!!
        assertEquals(FunctionKind.WINDOW, rowNumber.kind)
        assertEquals(listOf(FunctionOverload(emptyList(), "bigint")), rowNumber.overloads)
        // sequence is registered both as scalar and as a table function; the table def
        // wins name lookup (emitted later in kind order).
        assertTrue(TRINO_FUNCTION_CATALOG.isTableFunction("sequence"))
    }

    @Test
    fun trinoVariadicIsNeverFlagged() {
        // SHOW FUNCTIONS does not flag variadics — arity is a lower bound (see the
        // generated header). concat accepts N args but the catalog must not claim so.
        assertTrue(TRINO_FUNCTION_CATALOG["concat"]!!.overloads.all { !it.variadic })
    }

    // ------------------------------------------------------- grammar builtins

    @Test
    fun grammarBuiltinsAreKnownButNotRegistered() {
        // Trino COALESCE: absent from SHOW FUNCTIONS (parser special form,
        // AstBuilder.visitFunctionCall), so contains() is honest-false while
        // isKnown() clears it. Registered names satisfy both.
        assertTrue("coalesce" !in TRINO_FUNCTION_CATALOG)
        assertTrue(TRINO_FUNCTION_CATALOG.isKnown("coalesce"))
        assertTrue(TRINO_FUNCTION_CATALOG.isKnown("Coalesce")) // case-insensitive
        assertTrue(TRINO_FUNCTION_CATALOG.isKnown("abs")) // registry hit
        assertTrue(!TRINO_FUNCTION_CATALOG.isKnown("read_parquet")) // real hole stays

        // Grammar rules whose names ARE registered at 483 stay out of the grammar set
        // (TRIM, SUBSTRING, NORMALIZE, LISTAGG, CURRENT_DATE) — registry clears them.
        assertTrue("trim" in TRINO_FUNCTION_CATALOG)
        assertTrue("TRIM" !in TRINO_FUNCTION_CATALOG.grammarBuiltins)

        // DuckDB: engine-verified minimal set (see DuckdbGrammarBuiltins.kt).
        assertTrue("coalesce" !in DUCKDB_FUNCTION_CATALOG)
        assertTrue(DUCKDB_FUNCTION_CATALOG.isKnown("coalesce"))
        assertTrue(DUCKDB_FUNCTION_CATALOG.isKnown("grouping"))

        // Doris registers COALESCE/IF/GROUPING/... directly, but parses TIMESTAMPADD/
        // TIMESTAMPDIFF/MOD/SYSDATE/EXTRACT/CAST/CONVERT as grammar special forms / operators
        // (absent from the Nereids registry), wired as grammarBuiltins (DorisGrammarBuiltins.kt).
        assertEquals(DORIS_GRAMMAR_BUILTINS, DORIS_FUNCTION_CATALOG.grammarBuiltins)
        assertTrue("EXTRACT" in DORIS_FUNCTION_CATALOG.grammarBuiltins)
        assertTrue(DORIS_FUNCTION_CATALOG.isKnown("extract")) // grammar-level, not in registry
        assertTrue("extract" !in DORIS_FUNCTION_CATALOG)
        assertTrue("coalesce" in DORIS_FUNCTION_CATALOG)
        assertTrue(DORIS_FUNCTION_CATALOG.isKnown("coalesce"))
    }

    @Test
    fun grammarBuiltinsAreUppercaseAndDisjointFromTheRegistry() {
        for (catalog in listOf(TRINO_FUNCTION_CATALOG, DUCKDB_FUNCTION_CATALOG, DORIS_FUNCTION_CATALOG)) {
            for (name in catalog.grammarBuiltins) {
                assertEquals(name.uppercase(), name, "grammarBuiltins must be uppercase: $name")
                assertTrue(name !in catalog, "grammar builtin '$name' is also registered — drop it")
            }
        }
    }

    @Test
    fun grammarBuiltinsAreNotSerialized() {
        // grammarBuiltins is engine-grammar knowledge, not registry data: toJson stays
        // a pure registry dump (persisted catalogs are unchanged by the field).
        assertTrue("COALESCE" in TRINO_FUNCTION_CATALOG.grammarBuiltins)
        assertTrue("COALESCE" !in TRINO_FUNCTION_CATALOG.toJson())
    }

    // ---------------------------------------------------------------- serde

    @Test
    fun jsonRoundTrip() {
        val json = DORIS_FUNCTION_CATALOG.toJson()
        val decoded = Json.decodeFromString(ListSerializer(FunctionDef.serializer()), json)
        assertEquals(DORIS_FUNCTION_CATALOG.functions, decoded)
        assertEquals(FunctionKind.SCALAR, decoded.first { it.name == "ABS" }.kind)
        // Doris overloads survive the round trip.
        assertEquals(9, decoded.first { it.name == "ABS" }.overloads.size)
        assertTrue(decoded.first { it.name == "CONCAT" }.overloads.all { it.variadic })
    }

    @Test
    fun dorisDocumentationTiersArePinnedAgainstTheDocsSiteClone() {
        // vendor/data/doris-since-versions.json ("first documented in", extracted by
        // tools/extract_doris_since_versions.py from a pinned apache/doris-website
        // clone — see vendor/README.md for the method and its honesty caveats).
        assertEquals("1.2", DORIS_FUNCTION_CATALOG["abs"]!!.firstDocumentedIn)
        assertEquals("4.x", DORIS_FUNCTION_CATALOG["ai_classify"]!!.firstDocumentedIn)
        // SLEEP: registered but never documented at the pinned clone — honest null.
        assertNull(DORIS_FUNCTION_CATALOG["sleep"]!!.firstDocumentedIn)
        // A documentation tier is not a proven introduction.
        assertTrue(DORIS_FUNCTION_CATALOG.functions.all { it.sinceVersion == null })
    }

    @Test
    fun dorisDocumentationTierCoverageIsPinned() {
        // Coverage at the pinned doris-website clone SHA (vendor/README.md): 631 of
        // 713 defs matched to a version tier; a regeneration against a newer clone is
        // EXPECTED to move this — update deliberately.
        assertEquals(631, DORIS_FUNCTION_CATALOG.functions.count { it.firstDocumentedIn != null })
        assertEquals(713, DORIS_FUNCTION_CATALOG.size)
    }

    @Test
    fun aDocumentationTierNeverExcludesADef() {
        val catalog = FunctionCatalog(
            listOf(
                FunctionDef("documented_late", FunctionKind.SCALAR, firstDocumentedIn = "4.x"),
                FunctionDef("introduced", FunctionKind.SCALAR, sinceVersion = "3.0"),
            ),
        )

        assertEquals(listOf("documented_late"), catalog.availableIn("2.1").map { it.name })
        assertEquals(listOf("documented_late", "introduced"), catalog.availableIn("3.0.1").map { it.name })
        assertEquals(DORIS_FUNCTION_CATALOG.functions, DORIS_FUNCTION_CATALOG.availableIn("1.2"))
    }

    @Test
    fun versionFactsRoundTripThroughTheirWireNames() {
        val decoded = Json.decodeFromString(
            ListSerializer(FunctionDef.serializer()),
            DORIS_FUNCTION_CATALOG.toJson(),
        )
        assertEquals("1.2", decoded.first { it.name == "ABS" }.firstDocumentedIn)
        assertNull(decoded.first { it.name == "SLEEP" }.firstDocumentedIn)
        // Wire shape is pinned: snake_case keys.
        val def = FunctionDef("F", FunctionKind.SCALAR, sinceVersion = "2.1", firstDocumentedIn = "2.0")
        val json = Json.encodeToString(FunctionDef.serializer(), def)
        assertTrue("\"since_version\":\"2.1\"" in json)
        assertTrue("\"first_documented_in\":\"2.0\"" in json)
        assertEquals(def, Json.decodeFromString(FunctionDef.serializer(), json))
        // JSON without the fields still decodes: defaults null.
        val legacy = Json.decodeFromString(
            FunctionDef.serializer(),
            """{"name":"F","kind":"SCALAR"}""",
        )
        assertNull(legacy.sinceVersion)
        assertNull(legacy.firstDocumentedIn)
    }

    @Test
    fun jsonRoundTripPreservesOverloadsAndNativeKind() {
        val json = DUCKDB_FUNCTION_CATALOG.toJson()
        val decoded = Json.decodeFromString(ListSerializer(FunctionDef.serializer()), json)
        assertEquals(DUCKDB_FUNCTION_CATALOG.functions, decoded)
        assertEquals("macro", decoded.first { it.name == "pg_has_role" }.nativeKind)
        assertTrue(decoded.first { it.name == "concat" }.overloads.single().variadic)
        // duckdb argNames survive the round trip.
        assertEquals(
            listOf("x", "precision"),
            decoded.first { it.name == "round" }
                .overloads.first { it.argTypes == listOf("DOUBLE", "INTEGER") }.argNames,
        )
    }

    @Test
    fun jsonRoundTripPreservesProfiles() {
        // Catalog-level: doris profiles survive toJson/decode (data-class equality
        // covers nested SemanticProfile).
        val decoded = Json.decodeFromString(
            ListSerializer(FunctionDef.serializer()),
            DORIS_FUNCTION_CATALOG.toJson(),
        )
        assertEquals(
            SemanticProfile(NullPropagation.STRICT),
            decoded.first { it.name == "ABS" }.profile,
        )
        assertEquals(
            SemanticProfile(NullPropagation.UNKNOWN, "doris: custom nullable() override"),
            decoded.first { it.name == "COALESCE" }.profile,
        )
        // Wire shape is pinned: snake_case keys, absent-as-null defaults.
        val def = FunctionDef(
            "F", FunctionKind.SCALAR,
            overloads = listOf(FunctionOverload(listOf("INT"), "INT", argNames = listOf("x"))),
            profile = SemanticProfile(NullPropagation.STRICT, "note"),
        )
        val json = Json.encodeToString(FunctionDef.serializer(), def)
        assertTrue("\"null_propagation\":\"STRICT\"" in json)
        assertTrue("\"notes\":\"note\"" in json)
        assertTrue("\"arg_names\":[\"x\"]" in json)
        assertEquals(def, Json.decodeFromString(FunctionDef.serializer(), json))
        // Pre-profile JSON (no "profile", no "arg_names") still decodes: defaults null.
        val legacy = Json.decodeFromString(
            FunctionDef.serializer(),
            """{"name":"F","kind":"SCALAR","overloads":[{"arg_types":["INT"],"return_type":"INT"}]}""",
        )
        assertNull(legacy.profile)
        assertNull(legacy.overloads.single().argNames)
    }
}
