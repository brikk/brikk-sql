package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.*
import dev.brikk.house.sql.metadata.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntUnaryOperator
import org.duckdb.*
import kotlin.test.*

internal class DuckdbJavaUdfResultTest {
    private fun connect()=DriverManager.getConnection("jdbc:duckdb:",Properties().apply {
        setProperty("enable_external_access","false");setProperty("autoload_known_extensions","false");setProperty("autoinstall_known_extensions","false")
    }) as DuckDBConnection
    private fun Connection.capture()=createStatement().use { s -> s.executeQuery(DUCKDB_FUNCTIONS.sql).use { r -> buildList {
        while(r.next())add(DUCKDB_FUNCTIONS.decode(DUCKDB_FUNCTIONS.columns.indices.map { r.getString(it+1) }))
    } } }

    @Test fun realJavaRegistrationsRetainKindsAndInstanceOwnershipWithoutParseTimeCallbacks() {
        val binds=AtomicInteger();val inits=AtomicInteger();val applies=AtomicInteger();val scalars=AtomicInteger()
        connect().use { owner ->
            val sql="SELECT * FROM audit_java(7,flag => true)"
            assertIs<SerializedSql.Parsed>(owner.serializeSql(sql))
            DuckDBFunctions.tableFunction().use { builder ->
                val registered=builder.withName("audit_java").withParameter(Int::class.javaObjectType)
                    .withNamedParameter("flag",Boolean::class.javaObjectType)
                    .withFunction(object: DuckDBTableFunction<Unit,Unit,Unit> {
                        override fun bind(info:DuckDBTableFunctionBindInfo) {
                            binds.incrementAndGet()
                            info.getParameter(0).use { assertEquals(7,it.int) }
                            info.getNamedParameter("flag").use { assertTrue(it.boolean) }
                            info.addResultColumn("value",Int::class.javaObjectType)
                        }
                        override fun init(info:DuckDBTableFunctionInitInfo) { inits.incrementAndGet() }
                        override fun apply(info:DuckDBTableFunctionCallInfo,output:DuckDBDataChunkWriter):Long { applies.incrementAndGet();return 0 }
                    }).register(owner)
                assertEquals("audit_java",registered.name())
                assertFailsWith<SQLException> { builder.register(owner) }
            }
            DuckDBFunctions.scalarFunction().use { builder ->
                builder.withName("audit_java").withParameter(Int::class.javaObjectType).withReturnType(Int::class.javaObjectType)
                    .withIntFunction(IntUnaryOperator { scalars.incrementAndGet();it+1 }).register(owner)
            }
            val captured=owner.capture().filter { it.name=="audit_java" }
            assertEquals(setOf("scalar","table"),captured.map { it.type }.toSet())
            assertTrue(captured.all { it.internal && it.database=="system" && it.schema=="main" })
            val live=DuckdbDump("2.0.0",captured).toSnapshot().functions
            assertEquals(setOf(FunctionKind.SCALAR,FunctionKind.TABLE_VALUED),live.getAll("audit_java").map { it.kind }.toSet())
            assertNull(live.getAll("audit_java").single { it.kind==FunctionKind.TABLE_VALUED }.overloads.single().positionalCount)
            // Generic col0/flag labels do not prove a named/default boundary in a live row.
            for(text in listOf(sql,"SELECT audit_java(7)","SELECT * FROM audit_java(nextval('never_bound'),flag => true)",
                "SELECT * FROM audit_java(7,unregistered_option => missing_column)")) {
                assertIs<SerializedSql.Parsed>(owner.serializeSql(text),text)
                val tree=parseDuckdbNativeOne(text)
                assertIs<SerializedSql.Parsed>(owner.serializeSql(assertIs<Expression>(Serde.load(Serde.dump(tree))).sql("duckdb")))
            }
            assertEquals(listOf(0,0,0,0),listOf(binds.get(),inits.get(),applies.get(),scalars.get()))
            owner.duplicate().use { sibling ->
                assertEquals(captured,sibling.capture().filter { it.name=="audit_java" })
                // Closing builders and the registering connection must not destroy callbacks
                // while another connection still owns the same database instance.
                owner.close()
                sibling.createStatement().use { s -> s.executeQuery(sql).use { r -> assertEquals("INTEGER",r.metaData.getColumnTypeName(1));assertFalse(r.next()) } }
                sibling.createStatement().use { s -> s.executeQuery("SELECT audit_java(7)").use { r -> assertTrue(r.next());assertEquals(8,r.getInt(1)) } }
                assertTrue(binds.get()>0);assertTrue(inits.get()>0);assertTrue(applies.get()>0);assertTrue(scalars.get()>0)
            }
        }
        connect().use { independent ->
            assertTrue(independent.capture().none { it.name=="audit_java" })
            assertFailsWith<SQLException> { independent.createStatement().use { it.executeQuery("SELECT audit_java(7)") } }
            assertIs<SerializedSql.Parsed>(independent.serializeSql("SELECT * FROM audit_java(7,flag => true)"))
        }
        assertNull(DUCKDB_METADATA.default.functions["audit_java"])
    }
}
