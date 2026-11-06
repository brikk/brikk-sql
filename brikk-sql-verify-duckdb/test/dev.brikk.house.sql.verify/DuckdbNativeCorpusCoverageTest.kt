package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.parseDuckdbNativeOne
import dev.brikk.house.sql.generator.UnsupportedError
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

/** Native syntax inventory, independent of parity, type, scope and result equivalence. */
internal class DuckdbNativeCorpusCoverageTest {
    @Test fun everyApplicableFixtureHasANativeOutcomeOrAnExplicitLocalClassification() {
        val inputs=mutableListOf<NativeCorpusAudit.Input>()
        val local=mutableListOf<JsonObject>()
        val inventory=mutableListOf<JsonObject>()
        val supported=NativeCorpusAudit.json("corpus-policy.json").getValue("sqlglot_dialects").jsonArray.map { it.jsonPrimitive.content }.toSet()
        fun record(origin:String,status:String,detail:String)=local.add(buildJsonObject {
            put("origin",origin);put("status",status);put("detail",detail)
        })
        fun raw(origin:String,sql:String) { inputs+=NativeCorpusAudit.Input(origin,sql) }
        fun emit(origin:String,source:String,build:()->Expression) {
            try {
                val tree=build()
                val generator=Dialects.DUCKDB.generator(sourceDialect=source)
                val sql=generator.generate(tree)
                // Only an explicitly expression-shaped root gets the SELECT entry point.
                val expression=tree is Condition || tree is Alias || tree is Paren || tree is Bracket || tree is DataType ||
                    tree is Filter || tree is WithinGroup || tree is AtTimeZone || tree is Tuple || tree is Lambda
                raw("$origin/${if(expression)"expression"else"raw"}",if(expression)"SELECT $sql"else sql)
                if(tree.walk().any { it is Command })record(origin,"opaque-command","native serialization is still checked; no executable or modeled-AST claim")
                if(generator.unsupportedMessages.isNotEmpty())record(origin,"unsupported-warning",generator.unsupportedMessages.joinToString("; "))
            }catch(e:UnsupportedError) { record(origin,"unsupported",e.message.orEmpty()) }
            catch(e:StackOverflowError) { record(origin,"generation-stack-limit","not a native verdict") }
            catch(e:Exception) { record(origin,"local-failure",e::class.simpleName.orEmpty()+": "+e.message.orEmpty().take(200)) }
        }
        val files=Files.walk(NativeCorpusAudit.resources).use { paths -> paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }.sorted().toList() }
        for(file in files) {
            val relative=NativeCorpusAudit.resources.relativize(file).toString()
            val family=relative.substringBefore('/');val name=file.fileName.toString()
            val root=Json.parseToJsonElement(Files.readString(file)).jsonObject
            val before=inputs.size;val localBefore=local.size
            val classification=when {
                "known-failures" in name || family=="annotate-corpus" -> "parity ledger; independent, never rewritten"
                relative=="corpus-policy.json" -> "coverage policy, not SQL"
                family=="semantics" -> "historical cross-engine result evidence; DDB20-063, not a syntax corpus"
                family=="scanner-corpus" -> "Doris-only scanner evidence, not DuckDB SQL"
                family=="ast-corpus" && name=="arg-types-manifest.json" -> "AST schema manifest, not SQL"
                family=="ast-corpus" -> {
                    val source=NativeCorpusAudit.text(root["dialect"]) ?: ""
                    assertTrue(source in supported,relative)
                    for((index,value)in root.getValue("cases").jsonArray.withIndex()) {
                        val case=value.jsonObject;val origin="$relative/$index"
                        emit("$origin/loaded-dump",source) { Serde.loadExpression(case.getValue("dump").jsonArray) }
                        NativeCorpusAudit.text(case["sql"])?.let { sql ->
                            emit("$origin/current-parser",source) { if(source=="duckdb")parseDuckdbNativeOne(sql)else Dialects.forName(source).parseOne(sql) }
                            if(source in setOf("","duckdb"))raw("$origin/raw",sql)
                        }
                    }
                    "all serde/annotated/handbuilt dumps and current parser outputs emitted to DuckDB; annotation is not native typing proof"
                }
                family=="parser-corpus" || family=="token-corpus" -> {
                    if(name in setOf("base.json","duckdb.json")) {
                        for((index,value)in root.getValue("cases").jsonArray.withIndex())raw("$relative/$index/raw",value.jsonObject.getValue("sql").jsonPrimitive.content)
                        "raw base/DuckDB lexical or parser inputs"
                    }else "foreign lexer only; no DuckDB token equivalence claimed"
                }
                family=="generator-corpus" -> error("Unclassified generator resource $relative")
                family=="dialect-corpus" -> {
                    if(name.startsWith("datafusion")) {
                        assertTrue(name.startsWith("datafusion"),relative)
                        "DataFusion-native fixture, not DuckDB SQL"
                    }else {
                        val source=root.getValue("dialect").jsonPrimitive.content
                        if(source in supported) {
                            for((index,value)in root.getValue("identity").jsonArray.withIndex()) {
                                val case=value.jsonObject;val sql=case.getValue("sql").jsonPrimitive.content
                                emit("$relative/identity/$index/current",source) { if(source=="duckdb")parseDuckdbNativeOne(sql)else Dialects.forName(source).parseOne(sql) }
                                if(source=="duckdb")for(field in listOf("sql","expected"))NativeCorpusAudit.text(case[field])?.let { raw("$relative/identity/$index/$field",it) }
                            }
                            for((index,value)in root.getValue("transpile").jsonArray.withIndex()) {
                                val case=value.jsonObject;val sql=case.getValue("sql").jsonPrimitive.content
                                if(source=="duckdb")for((reader,readValue)in case["read"]?.jsonObject.orEmpty()) {
                                    val base=reader.substringBefore(',').trim()
                                    val read=NativeCorpusAudit.text(readValue)
                                    if(base in supported && read!=null)emit("$relative/read/$index/$reader",base) { Dialects.forName(base).parseOne(read) }
                                    else record("$relative/read/$index/$reader","excluded-or-non-sql-expectation","no native SQL to check")
                                }
                                for((writer,writeValue)in case["write"]?.jsonObject.orEmpty())if(writer.substringBefore(',').trim()=="duckdb") {
                                    emit("$relative/write/$index/current",source) { Dialects.forName(source).parseOne(sql) }
                                    val expected=NativeCorpusAudit.text(writeValue)
                                    if(expected!=null)raw("$relative/write/$index/expected",expected)
                                    else record("$relative/write/$index/expected","expected-unsupported","not SQL")
                                }
                            }
                            "registered-source identities emitted to DuckDB, plus every DuckDB-target transpile direction and SQL expectation"
                        }else "excluded source dialect; no conversion or equivalence claim"
                    }
                }
                family in setOf("scope-corpus","lineage-corpus","qualify-corpus") -> {
                    for((index,value)in root.getValue("cases").jsonArray.withIndex()) {
                        val case=value.jsonObject;val source=NativeCorpusAudit.text(case["dialect"]) ?: ""
                        if(source in setOf("","duckdb"))for(field in listOf("sql","expected"))NativeCorpusAudit.text(case[field])?.let { raw("$relative/$index/$field",it) }
                        else record("$relative/$index","foreign-analysis-input","not a DuckDB syntax assertion")
                    }
                    "base/DuckDB top-level SQL only; schema, lineage nodes and scope/type expectations are not native syntax assertions"
                }
                else -> error("Unclassified corpus resource $relative")
            }
            inventory+=buildJsonObject {
                put("file",relative);put("sha256",NativeCorpusAudit.hash(Files.readAllBytes(file)));put("classification",classification)
                put("native_inputs",inputs.size-before);put("local_classifications",local.size-localBefore)
            }
        }
        val run=NativeCorpusAudit.probe("coverage-200",NativeCorpusAudit.currentJar(),inputs,"v2.0.0","d54cbbe3df")
        assertTrue(run.verdicts.values.none { it.status=="unavailable" },"native transport failure is not coverage")
        val byOrigin=inputs.associateBy { it.origin }
        for(input in inputs)if(input.origin.startsWith("ast-corpus/duckdb-") && "/raw" in input.origin) {
            val prefix=input.origin.substringBeforeLast('/')
            val authored=byOrigin["$prefix/raw"] ?: continue
            if(run.verdicts.getValue(authored.sql).status=="accepted")for(phase in listOf("loaded-dump","current-parser")) {
                val emitted=byOrigin["$prefix/$phase/raw"] ?: byOrigin["$prefix/$phase/expression"] ?: continue
                assertNotEquals("rejected",run.verdicts.getValue(emitted.sql).status,"native-accepted DuckDB input became rejected: ${emitted.origin}")
            }
        }
        val summary=buildJsonObject {
            put("files",inventory.size);put("inputs",inputs.size);put("unique_sql",run.verdicts.size)
            put("native_counts",NativeCorpusAudit.counts(inputs,run));put("native_fingerprint",NativeCorpusAudit.fingerprint(inputs,run))
            put("local_counts",buildJsonObject { local.groupingBy { it.getValue("status").jsonPrimitive.content }.eachCount().toSortedMap().forEach { (k,v)->put(k,v) } })
            put("local_fingerprint",NativeCorpusAudit.hash(JsonArray(local).toString().toByteArray()))
            put("inventory_fingerprint",NativeCorpusAudit.hash(JsonArray(inventory).toString().toByteArray()))
        }
        NativeCorpusAudit.report("native-coverage.json",buildJsonObject {
            put("summary",summary);put("engine",NativeCorpusAudit.metadata(run));put("inventory",JsonArray(inventory));put("local",JsonArray(local))
            put("outcomes",buildJsonArray { for(input in inputs)add(buildJsonObject {
                val v=run.verdicts.getValue(input.sql);put("origin",input.origin);put("sql",input.sql);put("status",v.status);put("type",v.type);put("message",v.message)
            }) })
        })
        // SG29-B: exactly 11 generated inputs in four reviewed forms changed; nested
        // logical XOR no longer emits unsupported DuckDB infix XOR (five rejections
        // become accepted). Local dispositions/other 24595 origins are unchanged.
        // The scope ledger's one exact SG29-006 correction accounts for inventory drift.
        // Published 0.23.0 replay reproduced the old fingerprints under identical engine,
        // JDBC and complete settings. Keep historical baselines and the per-origin review
        // in vendor/data/sqlglot-30.29-batch-b/native-coverage-delta.json. Still enforce ONE
        // exact current summary, not a count allowance or an old/new fingerprint whitelist.
        // SG29-009 changes ONLY the inventory hash for its exact MySQL transpile
        // ledger row. Every one of the 24606 native SQL/status/type tuples, all local
        // dispositions, engine/JDBC/settings and all other inventory rows are identical.
        // Restoring that one file hash reproduces the complete prior inventory hash.
        // See vendor/data/sqlglot-sg29-009/native-coverage-review.json. Keep B's baseline.
        // SG29-011: three current-parser SHA384 origins now emit binary UNHEX(SHA256)
        // previews instead of hex SHA256. They retain the SAME unsupported-size warning;
        // strict SHA384/512 execution is refused, not qualified by syntax acceptance.
        // Other 24603 native origins and every local disposition are unchanged. Four
        // exact ledger file hashes account for inventory drift. See sg29-011 audit review.
        // SG29-014 remainder: native SQL/status/type tuples are ALL unchanged.
        // Exactly three extra warnings attach to the same already-opaque ALTER
        // origin in the current parser. Three ledger hashes and those three derived
        // warning counters account for inventory changes. No native acceptance or
        // exclusion allowance; see sg29-014-rest/native-coverage-review.json.
        // SG29-016: every native tuple is unchanged. Exactly five weighted Presto
        // percentile origins now warn instead of silently discarding a weight.
        // Corpus hashes/old dispositions unchanged; three inventory counters only.
        // See vendor/data/sqlglot-sg29-016/native-coverage-review.json.
        // SG29-017: only the exact reviewed BigQuery UNKNOWN annotation ledger hash
        // changes. All native tuples, local dispositions and engine settings stay
        // identical. See sg29-017/native-coverage-review.json; retain old baselines.
        // SG29-018: exactly two additional unsupported comparator warnings on
        // existing Presto/Spark write origins; no native tuple, file hash or old
        // local classification changes. See sg29-018/native-coverage-review.json.
        val baseline=NativeCorpusAudit.root.resolve("brikk-sql-verify-duckdb/testResources/native-coverage-baseline-sg29-018.json")
        assertTrue(Files.isRegularFile(baseline),"Review build/duckdb-parser-audit/native-coverage.json before adding a baseline")
        assertEquals(Json.parseToJsonElement(Files.readString(baseline)),summary,"Investigate coverage changes; never refresh parity ledgers from native syntax outcomes")
        println("DDB20-050 $summary")
    }
}
