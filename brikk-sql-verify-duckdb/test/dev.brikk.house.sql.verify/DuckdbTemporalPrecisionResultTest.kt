package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.optimizer.dataTypeFromStr
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.*

internal class DuckdbTemporalPrecisionResultTest {
    @Test fun precisionTryCastAndNanosecondZonesAgreeWithNativeTypesAndPreserveNullAndRounding() {
        DriverManager.getConnection("jdbc:duckdb:",Properties().apply {
            setProperty("autoload_known_extensions","false");setProperty("autoinstall_known_extensions","false")
        }).use { c ->
            fun execute(sql:String)=c.createStatement().use { it.execute(sql) }
            fun scalar(sql:String):String?=c.createStatement().use { s -> s.executeQuery(sql).use { r -> assertTrue(r.next());r.getString(1) } }
            fun checked(sql:String,expected:String?=null,checkValue:Boolean=false) {
                val tree=parseDuckdbNativeOne(sql)
                val type=SqlFragment(sql,"duckdb").outputShape().columns.single().type!!
                val canonical=dataTypeFromStr(type,nativeDuckdbAnalysisDialect(),udt=true).sql("duckdb")
                var first:String?=null
                for((index,text)in listOf(sql,tree.copy().sql("duckdb"),assertIs<Expression>(Serde.load(Serde.dump(tree))).sql("duckdb")).withIndex()) {
                    c.createStatement().use { s -> s.executeQuery(text).use { r ->
                        assertTrue(r.next())
                        assertEquals(canonical,dataTypeFromStr(r.metaData.getColumnTypeName(1),nativeDuckdbAnalysisDialect(),udt=true).sql("duckdb"),text)
                        val value=r.getString(1)
                        if(index==0)first=value else assertEquals(first,value,text)
                        if(checkValue)assertEquals(expected,value,text)
                    } }
                }
            }
            execute("SET TimeZone='UTC'")
            for(precision in 0..10)for(function in listOf("CAST","TRY_CAST"))
                checked("SELECT $function('2024-01-01 00:00:00.123456789' AS TIMESTAMP($precision)) AS t")
            for(type in listOf("DATE","TIME","TIME_NS","TIMETZ","TIMESTAMP","TIMESTAMP_S","TIMESTAMP_MS","TIMESTAMP_NS","TIMESTAMPTZ","TIMESTAMPTZ_NS"))
                checked("SELECT TRY_CAST('invalid' AS $type) AS t",null,true)
            checked("SELECT TRY_CAST(TIMESTAMP_NS '2024-01-01 12:34:56.123456789' AS TIME_NS) AS t","12:34:56.123456789",true)
            checked("SELECT TRY_CAST(TIME_NS '12:34:56.123456789' AS TIME) AS t","12:34:56.123457",true)
            checked("SELECT TRY_CAST(TIME '12:34:56.123456' AS TIME_NS) AS t","12:34:56.123456",true)
            checked("SELECT TRY_CAST(TIMESTAMPTZ_NS '2024-01-01 12:34:56.123456789+00' AS TIME_NS) AS t",null,true)
            checked("SELECT TRY_CAST(TIMETZ '12:34:56+02' AS TIME_NS) AS t",null,true)
            val cast="CAST(TIMESTAMP_NS '2024-01-01 00:00:00.123456789' AS TIMESTAMPTZ_NS)"
            checked("SELECT $cast AS t","2024-01-01 00:00:00.123456789+00",true)
            assertEquals("1704067200123456789",scalar("SELECT epoch_ns($cast)"))
            execute("SET TimeZone='Etc/GMT-2'")
            assertEquals("1704060000123456789",scalar("SELECT epoch_ns($cast)"))
            execute("SET TimeZone='UTC'")
            checked("SELECT CAST(TIMESTAMP_NS '2024-01-01 00:00:00.123456789' AS TIMESTAMPTZ) AS t")
            // JDBC formats its OffsetDateTime in the client zone. Ask the engine for
            // VARCHAR explicitly when checking native session-zone rendering.
            assertEquals("2024-01-01 00:00:00.123457+00",scalar("SELECT CAST(CAST(TIMESTAMP_NS '2024-01-01 00:00:00.123456789' AS TIMESTAMPTZ) AS VARCHAR)"))
            for(type in listOf("TIMESTAMP(11)","TIMESTAMP(-1)","TIMESTAMP(1.0)","TIMESTAMPTZ(9)","TIME(3)")) {
                val failure=runCatching { scalar("SELECT CAST('2024-01-01' AS $type)") }
                assertTrue(failure.isFailure,type)
            }
        }
    }
}
