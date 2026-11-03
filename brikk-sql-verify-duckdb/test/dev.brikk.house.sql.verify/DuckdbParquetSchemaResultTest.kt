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

internal class DuckdbParquetSchemaResultTest {
    private fun render(sql:String)=assertIs<Expression>(Serde.load(Serde.dump(Dialects.DUCKDB.parseOne(sql)))).sql("duckdb")
    private fun quote(path:Path)="'${path.toAbsolutePath().toString().replace("'","''")}'"
    private fun Connection.exec(sql:String)=createStatement().use { if(it.execute(sql))it.resultSet.use { r -> while(r.next()){} };Unit }
    private fun Connection.rows(sql:String)=createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while(r.next())add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    private fun scratch(block:(Path)->Unit) {
        val parent=Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"),"opencode"))
        val root=Files.createTempDirectory(parent,"duckdb-parquet-schema-")
        try { block(root) } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun idAndNameMapsRenameCastDefaultReorderAndPreserveNullability()=scratch { root ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val ids=root.resolve("ids.parquet");val plain=root.resolve("plain.parquet")
            c.exec("COPY (SELECT 42::INTEGER i,NULL::INTEGER j) TO ${quote(ids)} (FORMAT parquet,FIELD_IDS {i:0,j:2})")
            c.exec("COPY (SELECT 42::INTEGER i,NULL::INTEGER j) TO ${quote(plain)} (FORMAT parquet)")
            for((path,key,missing)in listOf(Triple(ids,"0","1"),Triple(plain,"'i'","'missing'"))) {
                for(function in listOf("read_parquet","parquet_scan")) {
                    val sql="SELECT * FROM $function(${quote(path)},schema:=MAP {$missing:{name:'added',type:'UTINYINT',default_value:43},$key:{name:'renamed',type:'BIGINT',default_value:NULL}})"
                    for(text in listOf(sql,render(sql)))c.createStatement().use { s -> s.executeQuery(text).use { r ->
                        assertEquals(listOf("added","renamed"),(1..2).map { r.metaData.getColumnName(it) })
                        assertEquals(listOf("UTINYINT","BIGINT"),(1..2).map { r.metaData.getColumnTypeName(it) })
                        assertTrue(r.next());assertEquals(43,r.getInt(1));assertEquals(42,r.getInt(2));assertFalse(r.next())
                    } }
                }
            }
            val absentIds="SELECT * FROM read_parquet(${quote(plain)},schema:=MAP {0:{name:'renamed',type:'BIGINT',default_value:NULL}})"
            assertEquals(listOf(listOf(null)),c.rows(render(absentIds)))
            val nullable="SELECT * FROM read_parquet(${quote(ids)},schema:=MAP {2:{name:'still_null',type:'BIGINT',default_value:99}})"
            assertEquals(listOf(listOf(null)),c.rows(render(nullable))) // Defaults fill missing fields, not existing SQL NULLs.
            val bound="SELECT * FROM read_parquet(${quote(plain)},bound_schema:={'i':'DOUBLE'},hive_partitioning:=false)"
            assertEquals(c.rows(bound),c.rows(render(bound)))
            val missing="SELECT missing FROM read_parquet(${quote(plain)},bound_schema:={'missing':'BIGINT','i':'INTEGER'},hive_partitioning:=false)"
            assertFailsWith<SQLException> { c.rows(render(missing)) }
            val counted="SELECT count(*) FROM read_parquet(${quote(plain)},bound_schema:={'missing':'BIGINT','i':'INTEGER'},hive_partitioning:=false)"
            assertEquals(listOf(listOf("1")),c.rows(render(counted))) // Required roots are validated on demand, not invented defaults.
        }
    }
    @Test fun virtualRowGroupColumnIsUnsignedHiddenFileRelativeAndFilterable()=scratch { root ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.exec("SET threads=1")
            val a=root.resolve("a.parquet");val b=root.resolve("b.parquet")
            for((path,count)in listOf(a to 5000,b to 3000))c.exec("COPY (SELECT i FROM range($count)t(i)) TO ${quote(path)} (FORMAT parquet,ROW_GROUP_SIZE 2048)")
            val from="read_parquet([${quote(a)},${quote(b)}])"
            val sql="SELECT file_row_group_number,count(*) FROM $from GROUP BY file_row_group_number ORDER BY file_row_group_number"
            val expected=listOf(listOf("0","4096"),listOf("1","3000"),listOf("2","904"))
            assertEquals(expected,c.rows(sql));assertEquals(expected,c.rows(render(sql)))
            assertEquals(listOf(listOf("904","4096","4999")),c.rows(render("SELECT count(*),min(i),max(i) FROM $from WHERE file_row_group_number=2")))
            assertEquals(listOf(listOf("0")),c.rows(render("SELECT count(*) FROM $from WHERE file_row_group_number IS NULL")))
            c.createStatement().use { s -> s.executeQuery(render("SELECT * FROM $from LIMIT 1")).use { r -> assertEquals(1,r.metaData.columnCount) } }
            c.createStatement().use { s -> s.executeQuery(render("SELECT file_row_group_number FROM $from LIMIT 1")).use { r -> assertEquals("UBIGINT",r.metaData.getColumnTypeName(1)) } }
            val bad="SELECT * FROM read_parquet(${quote(a)},file_row_group_number:=true)"
            assertFailsWith<SQLException> { c.rows(render(bad)) }
            val shadow=root.resolve("shadow.parquet")
            c.exec("COPY (SELECT 'physical'::VARCHAR file_row_group_number) TO ${quote(shadow)} (FORMAT parquet)")
            assertEquals(listOf(listOf("physical")),c.rows("SELECT file_row_group_number FROM read_parquet(${quote(shadow)})"))
            assertEquals(listOf(listOf("0","physical")),c.rows(render("SELECT p.file_row_group_number,p.physical FROM read_parquet(${quote(shadow)})p(physical)")))
        }
    }
    @Test fun schemaValidationErrorsStayNativeAndPreparedParametersKeepTheirOrder()=scratch { root ->
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val file=root.resolve("x.parquet");c.exec("COPY (SELECT 42 i) TO ${quote(file)} (FORMAT parquet,FIELD_IDS {i:0})")
            for(options in listOf("schema:=MAP{}","schema:=MAP{0:{name:'x',type:'BIGINT',default_value:NULL}},union_by_name:=true",
                "schema:=MAP{0:{name:'x',type:'BIGINT',default_value:NULL}},hive_partitioning:=true",
                "schema:=MAP{0:{name:'x',type:'BIGINT',default_value:NULL},1:{name:'x',type:'BIGINT',default_value:NULL}}",
                "schema:=MAP{0:{name:'x',type:'BIGINT',default_value:'bad'}}")) {
                val sql="SELECT * FROM read_parquet(${quote(file)},$options)"
                assertIs<SerializedSql.Parsed>(c.serializeSql(sql))
                assertFailsWith<SQLException> { c.rows(sql) };assertFailsWith<SQLException> { c.rows(render(sql)) }
            }
            val sql="SELECT x FROM read_parquet(?,schema:=MAP{0:{name:'x',type:'BIGINT',default_value:?}}) WHERE x>?"
            c.prepareStatement(render(sql)).use { p ->
                assertEquals(3,p.parameterMetaData.parameterCount);p.setString(1,file.toString());p.setLong(2,9);p.setLong(3,40)
                p.executeQuery().use { r -> assertTrue(r.next());assertEquals(42,r.getLong(1)) }
            }
        }
    }
    @Test fun capturedNamedOptionsMatchNativeAndAnalysisNeverReadsPathsOrEvaluatesDefaults()=scratch { root ->
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { c ->
            c.exec("CREATE SEQUENCE audit_seq")
            val file=root.resolve("not-present.parquet")
            val sql="SELECT x,file_row_group_number FROM read_parquet(${quote(file)},schema:=MAP{0:{name:'x',type:'BIGINT',default_value:nextval('audit_seq')}})"
            RelationScope.analyze(sql,"duckdb",RelationFacts());SqlFragment(sql,"duckdb").outputShape();SqlFragment(sql,"duckdb").toExecutable("duckdb")
            val verifier=ConnectionDuckdbVerifier({ JdbcDuckdbQueryConnection(c.duplicate()) })
            val result=verifier.verify(sql);assertTrue(result is VerifyResult.Accepted || result is VerifyResult.Unverified,result.toString())
            assertFalse(Files.exists(file));assertEquals(listOf(listOf("1")),c.rows("SELECT nextval('audit_seq')"))
            for(name in listOf("read_parquet","parquet_scan")) {
                c.createStatement().use { s -> s.executeQuery("SELECT parameters,parameter_types FROM duckdb_functions() WHERE function_name='$name' AND internal").use { r ->
                    val expected=DUCKDB_METADATA.default.functions.getAll(name).single { it.kind==dev.brikk.house.sql.metadata.FunctionKind.TABLE_VALUED }.overloads
                    var count=0
                    while(r.next()) { count++;val names=(r.getArray(1).array as Array<*>).map { it.toString() };val types=(r.getArray(2).array as Array<*>).map { it.toString() }
                        assertTrue(expected.any { it.argNames==names && it.argTypes==types });assertFalse("file_row_group_number" in names)
                    };assertEquals(expected.size,count)
                } }
            }
        }
    }
}
