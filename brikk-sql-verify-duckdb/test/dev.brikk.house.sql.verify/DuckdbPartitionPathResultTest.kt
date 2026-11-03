package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.shape.SqlFragment
import org.duckdb.DuckDBConnection
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

internal class DuckdbPartitionPathResultTest {
    private fun render(sql: String) = assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql).copy()))).sql("duckdb")
    private fun quote(value: String) = "'${value.replace("'","''")}'"
    private fun literal(path: Path) = quote(path.toAbsolutePath().toString())
    private fun Connection.execute(sql: String) = createStatement().use { s ->
        s.queryTimeout=10
        if (s.execute(sql)) s.resultSet.use { r -> while(r.next()) { /* drain once; never retry a write */ } }
    }
    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { s ->
        s.queryTimeout=10
        s.executeQuery(sql).use { r -> buildList { while(r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) } }
    }
    private fun scratch(block: (Path)->Unit) {
        val parent=Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"),"opencode").toAbsolutePath())
        val directory=Files.createTempDirectory(parent,"duckdb-partition-path-")
        try { block(directory) } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    private fun fixture(c: Connection) {
        c.execute("SET threads=1")
        c.execute("CREATE TABLE sales(id INTEGER,region VARCHAR,day INTEGER,v INTEGER)")
        c.execute("INSERT INTO sales VALUES (1,'eu',1,3),(2,'eu',1,1),(3,'us',2,2),(4,NULL,1,4),(5,NULL,1,0),(6,'us',2,NULL)")
    }
    private fun files(root: Path,extension: String="parquet"): List<Path> =
        if (!Files.exists(root)) emptyList() else Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".$extension") }.sorted().toList() }
    private fun relative(root: Path,file: Path) = root.relativize(file).joinToString("/")
    private fun paths(root: Path) = files(root).map { relative(root,it) }.toSet()
    private fun read(root: Path) = "read_parquet(${literal(root.resolve("**/*.parquet"))},hive_partitioning=false,filename=true,file_row_number=true)"

    @Test
    fun caseAndFunctionPathsOverMultipleOutputAliasesWriteTheExpectedRowsAndKeepCopyContracts() = scratch { root ->
        for (generated in listOf(false,true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            val directory=root.resolve("case-$generated")
            val sql="WITH copied AS (COPY (SELECT id,region AS area,day,v FROM sales) TO ${literal(directory)} " +
                "(PARTITION_PATH CASE WHEN area='eu' THEN 'europe' ELSE path_join('region',coalesce(area,'none'),day::VARCHAR) END," +
                "FORMAT parquet,PARTITION_BY (area,day),ORDER_BY (v DESC NULLS LAST,id),RETURN_FILES)) SELECT Count,len(Files) FROM copied"
            assertEquals(listOf(listOf("6","3")),c.rows(if(generated)render(sql)else sql))
            val expected=mapOf("europe" to listOf("1","2"),"region/none/1" to listOf("4","5"),"region/us/2" to listOf("3","6"))
            assertEquals(expected.keys,files(directory).map { relative(directory,it.parent) }.toSet())
            for(file in files(directory)) {
                assertEquals(expected.getValue(relative(directory,file.parent)),c.rows("SELECT id FROM read_parquet(${literal(file)},hive_partitioning=false,file_row_number=true) ORDER BY file_row_number").map { it.single()!! })
                assertEquals(listOf("id","v"),c.rows("DESCRIBE SELECT * FROM read_parquet(${literal(file)},hive_partitioning=false)").map { it.first() })
            }
            val stats=root.resolve("stats-$generated")
            val statistics="WITH copied AS (COPY sales TO ${literal(stats)} (FORMAT parquet,PARTITION_BY (region,day),PARTITION_PATH path_join(coalesce(region,'none'),day::VARCHAR),RETURN_STATS)) SELECT count(*),sum(count) FROM copied"
            assertEquals(listOf(listOf("3","6")),c.rows(if(generated)render(statistics)else statistics))
        }
    }

    @Test
    fun helperGeneratedHivePathsMatchNativeDefaultEscapingIncludingNullMarkerCollisions() = scratch { root ->
        for(generated in listOf(false,true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("SET threads=1")
            c.execute("CREATE TABLE t AS SELECT * FROM (VALUES (1,'a/b',1),(2,'c d%',NULL),(3,NULL,2),(4,'__HIVE_DEFAULT_PARTITION__',3),(5,'🦆',1),(6,'a/b',1))v(id,k,n)")
            val native=root.resolve("default-$generated")
            val custom=root.resolve("helpers-$generated")
            c.execute("COPY t TO ${literal(native)} (FORMAT parquet,PARTITION_BY (k,n))")
            val sql="COPY t TO ${literal(custom)} (FORMAT parquet,PARTITION_BY (k,n),PARTITION_PATH path_join(hive_partition_component('k',k),hive_partition_component('n',n)))"
            c.execute(if(generated)render(sql)else sql)
            assertEquals(paths(native),paths(custom))
            assertEquals(5,files(custom).size)
            assertTrue(paths(custom).any { "k=%5F_HIVE_DEFAULT_PARTITION__" in it })
            assertTrue(paths(custom).any { "k=__HIVE_DEFAULT_PARTITION__" in it })
            assertTrue(paths(custom).any { "k=%F0%9F%A6%86" in it })
            assertEquals(c.rows("SELECT id,k,n FROM t ORDER BY id"),c.rows("SELECT id,k,n FROM read_parquet(${literal(custom.resolve("*/*/*.parquet"))},hive_partitioning=true) ORDER BY id"))
        }
    }

    @Test
    fun csvJsonNumericCastsStructExtractionAndNestedPartitionDirectoriesStayNative() = scratch { root ->
        for(generated in listOf(false,true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            for(format in listOf("csv","json")) {
                val directory=root.resolve("$format-$generated")
                val sql="COPY sales TO ${literal(directory)} (FORMAT $format,PARTITION_BY region,PARTITION_PATH coalesce(region,'none'),WRITE_PARTITION_COLUMNS)"
                c.execute(if(generated)render(sql)else sql)
                assertEquals(3,files(directory,format).size)
                assertEquals(c.rows("SELECT id,region,day,v FROM sales ORDER BY id"),c.rows("SELECT id,region,day,v FROM read_$format(${literal(directory.resolve("*/*.$format"))},hive_partitioning=false) ORDER BY id"))
            }
            for((index,path)in listOf("(day)","struct_extract({'x':day},'x')").withIndex()) {
                val directory=root.resolve("numeric-$generated-$index")
                val sql="COPY sales TO ${literal(directory)} (FORMAT parquet,PARTITION_BY day,PARTITION_PATH $path)"
                c.execute(if(generated)render(sql)else sql)
                assertEquals(setOf("1","2"),files(directory).map { it.parent.fileName.toString() }.toSet())
            }
            val directory=root.resolve("nested-$generated")
            val sql="COPY (SELECT * FROM (VALUES (1,'eu',NULL),(2,'eu','de'),(3,'europe',NULL),(4,'eu-west',NULL))v(id,region,country)) " +
                "TO ${literal(directory)} (FORMAT parquet,PARTITION_BY (region,country),PARTITION_PATH CASE WHEN country IS NULL THEN region ELSE path_join(region,country) END)"
            c.execute(if(generated)render(sql)else sql)
            assertEquals(setOf("eu","eu/de","europe","eu-west"),files(directory).map { relative(directory,it.parent) }.toSet())
            assertEquals(listOf(listOf("4","4")),c.rows("SELECT count(*),count(DISTINCT id) FROM ${read(directory)}"))
        }
    }

    @Test
    fun nativeBindingRejectsMissingPartitioningHivePatternAndNonPartitionExpressionsBeforeWriting() = scratch { root ->
        for(generated in listOf(false,true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            val cases=listOf("PARTITION_PATH 'x'" to "requires PARTITION_BY",
                "PARTITION_BY region,PARTITION_PATH 'x',HIVE_FILE_PATTERN false" to "Can't combine",
                "PARTITION_BY region,PARTITION_PATH 'x',HIVE_FILE_PATTERN true" to "Can't combine",
                "PARTITION_BY region,PARTITION_PATH id::VARCHAR" to "not a PARTITION_BY column",
                "PARTITION_BY region,PARTITION_PATH sales.region" to "not a PARTITION_BY column",
                "PARTITION_BY region,PARTITION_PATH max(region)" to "aggregate functions",
                "PARTITION_BY region,PARTITION_PATH row_number() OVER ()" to "window functions",
                "PARTITION_BY region,PARTITION_PATH (SELECT 'x')" to "subquery",
                "PARTITION_BY region,PARTITION_PATH list_transform([region],lambda x: upper(x))[1]" to "lambda functions")
            for((index,case)in cases.withIndex()) {
                val directory=root.resolve("binder-$generated-$index")
                val sql="COPY sales TO ${literal(directory)} (FORMAT parquet,${case.first})"
                val text=if(generated)render(sql)else sql
                assertEquals("not implemented",assertIs<SerializedSql.Failed>(c.serializeSql(text)).type)
                val error=assertFailsWith<SQLException>(text) { c.execute(text) }
                assertTrue(case.second in error.message.orEmpty(),error.message)
                assertFalse(Files.exists(directory))
            }
        }
    }

    @Test
    fun malformedPathValuesAreNativeParserErrorsNotOptionListsOrRawFallbacks() = scratch { root ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            for(value in listOf("","(region,day)","(region DESC)","(region NULLS FIRST)")) {
                val sql="COPY sales TO ${literal(root.resolve("not-written"))} (PARTITION_BY region,PARTITION_PATH $value)"
                assertEquals("parser",assertIs<SerializedSql.Failed>(c.serializeSql(sql)).type)
            }
            assertTrue(files(root).isEmpty())
        }
    }

    @Test
    fun runtimePathValidationRejectsNullEmptyAbsoluteRemoteAndParentSegmentsWithoutEscapingScratch() = scratch { root ->
        for(generated in listOf(false,true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            val escaped=root.resolve("must-not-write-outside-target")
            val cases=listOf("NULL" to "evaluated to NULL","''" to "empty path","'./'" to "empty path",
                literal(escaped) to "must be relative","'s3://example.invalid/not-a-real-resource'" to "must be relative",
                "'../must-not-write-outside-target'" to "cannot contain","'safe/../must-not-write-outside-target'" to "cannot contain")
            for((index,case)in cases.withIndex()) {
                val directory=root.resolve("invalid-$generated-$index")
                val sql="COPY (SELECT * FROM sales WHERE id=1) TO ${literal(directory)} (FORMAT parquet,PARTITION_BY region,PARTITION_PATH ${case.first})"
                val error=assertFailsWith<SQLException> { c.execute(if(generated)render(sql)else sql) }
                assertTrue(case.second in error.message.orEmpty(),error.message)
                assertTrue(files(directory).isEmpty())
                assertFalse(Files.exists(escaped))
            }
            // The helper normalizes before the COPY path is checked; the library must not
            // apply that normalization to an authored raw '..' path expression itself.
            val directory=root.resolve("normalized-$generated")
            val sql="COPY (SELECT * FROM sales WHERE id=1) TO ${literal(directory)} (FORMAT parquet,PARTITION_BY region,PARTITION_PATH path_join('safe','../sibling'))"
            c.execute(if(generated)render(sql)else sql)
            assertEquals(setOf("sibling"),files(directory).map { relative(directory,it.parent) }.toSet())
        }
    }

    @Test
    fun distinctPartitionsCannotShareEvenNormalizedDirectoriesAndNoAtomicityIsPromised() = scratch { root ->
        for(generated in listOf(false,true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            for((index,path)in listOf("'same'","CASE WHEN region='eu' THEN 'same//child' ELSE 'same/./child' END").withIndex()) {
                val directory=root.resolve("collision-$generated-$index")
                val sql="COPY sales TO ${literal(directory)} (FORMAT parquet,PARTITION_BY region,PARTITION_PATH $path)"
                val error=assertFailsWith<SQLException> { c.execute(if(generated)render(sql)else sql) }
                assertTrue("same directory" in error.message.orEmpty(),error.message)
                // A runtime error may follow writes for earlier partitions. No atomicity promise,
                // retries, SQL rollback emulation or cleanup of user output is added.
                assertTrue(files(directory).all { it.startsWith(directory) })
            }
            assertEquals(listOf(listOf("6")),c.rows("SELECT count(*) FROM sales"))
        }
    }

    @Test
    fun emptyInputsNeverEvaluatePathsAndDotSubstringsAreNotParentDirectorySegments() = scratch { root ->
        for(generated in listOf(false,true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c);c.execute("CREATE SEQUENCE empty_seq")
            for((index,path)in listOf("nextval('empty_seq')::VARCHAR","NULL","'../not-a-partition'").withIndex()) {
                val directory=root.resolve("empty-$generated-$index")
                val sql="WITH copied AS (COPY (SELECT * FROM sales WHERE false) TO ${literal(directory)} (FORMAT parquet,PARTITION_BY region,PARTITION_PATH $path)) SELECT Count FROM copied"
                // The pinned JDBC driver classifies this empty COPY CTE as an update
                // count, despite the outer SELECT. Execute exactly once; do not retry it.
                c.createStatement().use { s ->
                    assertFalse(s.execute(if(generated)render(sql)else sql))
                    assertEquals(0,s.updateCount)
                }
                assertTrue(files(directory).isEmpty())
            }
            assertEquals(listOf(listOf("1")),c.rows("SELECT nextval('empty_seq')"))
            val directory=root.resolve("dots-$generated")
            val sql="COPY (SELECT * FROM sales WHERE id=1) TO ${literal(directory)} (FORMAT parquet,PARTITION_BY region,PARTITION_PATH './safe..name//child/.')"
            c.execute(if(generated)render(sql)else sql)
            assertEquals(setOf("safe..name/child"),files(directory).map { relative(directory,it.parent) }.toSet())
        }
    }

    @Test
    fun preparedParametersAndVolatilePathExpressionsPreserveSourceAndPerPartitionEvaluation() = scratch { root ->
        for(generated in listOf(false,true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            fixture(c)
            c.execute("CREATE SEQUENCE source_seq")
            c.execute("CREATE SEQUENCE path_seq")
            val sql="WITH copied AS (COPY (SELECT region,day,id+? AS id,v,nextval('source_seq') AS invocation FROM sales WHERE id>?) TO (?) " +
                "(FORMAT parquet,PARTITION_BY (region,day),PARTITION_PATH path_join(?,coalesce(region,?),day::VARCHAR,nextval('path_seq')::VARCHAR),ORDER_BY (id),RETURN_FILES)) SELECT Count,len(Files) FROM copied"
            c.prepareStatement(if(generated)render(sql)else sql).use { p ->
                assertEquals(5,p.parameterMetaData.parameterCount)
                for(iteration in 1..2) {
                    val directory=root.resolve("prepared-$generated-$iteration")
                    p.setInt(1,100);p.setInt(2,1);p.setString(3,directory.toString());p.setString(4,"prefix");p.setString(5,"missing")
                    p.executeQuery().use { r -> assertTrue(r.next());assertEquals(5,r.getInt(1));assertEquals(3,r.getInt(2));assertFalse(r.next()) }
                    assertEquals(listOf(listOf((iteration*5).toString(),(iteration*3).toString())),c.rows("SELECT currval('source_seq'),currval('path_seq')"))
                    assertEquals(listOf(listOf("5","5","102","106")),c.rows("SELECT count(*),count(DISTINCT invocation),min(id),max(id) FROM ${read(directory)}"))
                    assertTrue(files(directory).all { relative(directory,it).startsWith("prefix/") })
                }
            }
        }
    }

    @Test
    fun pathHelperResultsNullsMetadataAndQualifiedUdfsMatchThePinnedEngine() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val sql="SELECT hive_partition_component(NULL,NULL),hive_partition_component('p','__HIVE_DEFAULT_PARTITION__'),hive_partition_component('a/b','c d%')," +
                "hive_partition_component('p','🦆'),hive_partition_component(value:=NULL,name:='p'),path_join('dir//','./file'),path_join('dir','../sibling'),path_join(''),path_join(NULL),path_join('a',NULL),path_join(path:='a')"
            val expected=listOf(listOf("NULL=__HIVE_DEFAULT_PARTITION__","p=%5F_HIVE_DEFAULT_PARTITION__","a%2Fb=c%20d%25","p=%F0%9F%A6%86","p=__HIVE_DEFAULT_PARTITION__","dir/file","sibling",".",null,null,"a"))
            assertEquals(expected,c.rows(sql));assertEquals(expected,c.rows(render(sql)))
            val vector="SELECT count(hive_partition_component('p',CASE WHEN i%2=0 THEN NULL ELSE i END)),count(path_join('p',CASE WHEN i%2=0 THEN NULL ELSE i::VARCHAR END)) FROM range(2000)t(i)"
            assertEquals(listOf(listOf("2000","1000")),c.rows(render(vector)))
            for(name in listOf("path_join","hive_partition_component")) {
                c.createStatement().use { s -> s.executeQuery("SELECT parameter_types,return_type,varargs FROM duckdb_functions() WHERE function_name='$name' AND database_name='system' AND internal").use { r ->
                    assertTrue(r.next());val overload=DUCKDB_METADATA.default.functions[name]!!.overloads.single()
                    assertEquals(overload.argTypes,(r.getArray(1).array as Array<*>).map { it.toString() })
                    assertEquals(overload.returnType,r.getString(2));assertEquals(if(overload.variadic)"VARCHAR" else null,r.getString(3));assertFalse(r.next())
                } }
            }
            for(sql in listOf("SELECT path_join()","SELECT path_join(42)","SELECT hive_partition_component('p')")) {
                assertTrue("Binder Error" in assertFailsWith<SQLException> { c.rows(render(sql)) }.message.orEmpty())
            }
            assertTrue("incompatible paths" in assertFailsWith<SQLException> { c.rows(render("SELECT path_join('dir','/abs/path')")) }.message.orEmpty())
            c.execute("CREATE SCHEMA custom");c.execute("CREATE MACRO custom.path_join(a) AS 42")
            assertEquals(listOf(listOf("42")),c.rows(render("SELECT custom.path_join('a')")))
            assertEquals("UNKNOWN",SqlFragment("SELECT custom.path_join('a') AS result","duckdb").outputShape().columns.single().type)
        }
    }

    @Test
    fun rotationAndParallelWritesKeepCustomDirectoriesAndAllRowsWithoutInventingGlobalOrder() = scratch { root ->
        for(generated in listOf(false,true)) DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.execute("SET threads=4")
            val directory=root.resolve("rotated-$generated")
            val sql="COPY (SELECT i%2 AS p,(i*7919)%24000 AS v,i AS id FROM range(24000)t(i)) TO ${literal(directory)} " +
                "(FORMAT parquet,PARTITION_BY p,PARTITION_PATH 'part_'||p::VARCHAR,ORDER_BY (v DESC),ROW_GROUP_SIZE 2048,ROW_GROUPS_PER_FILE 1)"
            c.execute(if(generated)render(sql)else sql)
            assertTrue(files(directory).size>2)
            assertEquals(setOf("part_0","part_1"),files(directory).map { relative(directory,it.parent) }.toSet())
            assertEquals(listOf(listOf("0","24000")),c.rows("SELECT count(*) FILTER(WHERE previous<v),count(*) FROM (SELECT v,lag(v) OVER(PARTITION BY filename ORDER BY file_row_number) previous FROM ${read(directory)})"))
            assertEquals(listOf(listOf("24000","24000")),c.rows("SELECT count(*),count(DISTINCT id) FROM ${read(directory)}"))
        }
    }

    @Test
    fun analysisGenerationAndNativeParseOnlyValidationNeverEvaluateThePartitionExpression() = scratch { root ->
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { c ->
            fixture(c);c.execute("CREATE SEQUENCE audit_seq")
            val directory=root.resolve("not-written")
            val sql="COPY sales TO ${literal(directory)} (FORMAT parquet,PARTITION_BY region,PARTITION_PATH path_join(coalesce(region,'none'),nextval('audit_seq')::VARCHAR))"
            RelationScope.analyze(sql,"duckdb",RelationFacts())
            SqlFragment(sql,"duckdb").toExecutable("duckdb")
            val verifier=ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(c.duplicate()) })
            val result=verifier.verify(sql)
            assertTrue(result is VerifyResult.Accepted || result is VerifyResult.Unverified,result.toString())
            assertFalse(Files.exists(directory))
            assertEquals(listOf(listOf("1")),c.rows("SELECT nextval('audit_seq')"))
        }
    }
}
