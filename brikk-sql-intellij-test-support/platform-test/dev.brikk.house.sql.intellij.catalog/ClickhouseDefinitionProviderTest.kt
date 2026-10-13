package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dialects.clickhouse.model.CHouseRoot
import com.intellij.database.model.DasObject
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.shape.DefinedObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.CopyOnWriteArrayList

/** ClickHouse definitions for a ClickHouse model's objects: the SHOW CREATE each sends, and the definition it shows. */
class ClickhouseDefinitionProviderTest : BasePlatformTestCase() {
    class Provider : ShowCreateDefinitionProvider(
        "clickhouse",
        mapOf(
            ObjectKind.SCHEMA to DefinedObject.SCHEMA,
            ObjectKind.TABLE to DefinedObject.TABLE,
            ObjectKind.VIEW to DefinedObject.VIEW,
            ObjectKind.MAT_VIEW to DefinedObject.MATERIALIZED_VIEW,
        ),
        listOf(ObjectKind.SCHEMA),
    )

    private lateinit var engine: Connection
    private val sent = CopyOnWriteArrayList<String>()

    override fun setUp() {
        super.setUp()
        engine = embeddedDuckdb()
    }

    override fun tearDown() {
        try {
            engine.close()
        } finally {
            super.tearDown()
        }
    }

    /** A connection answering each query as ClickHouse answers SHOW CREATE: one `statement` column. */
    private fun clickhouse(): Connection = proxy(Connection::class.java, engine) { method, args ->
        val result = method.invoke(engine, *args)
        if (result !is Statement) result else proxy(Statement::class.java, result) { call, callArgs ->
            if (call.name == "executeQuery" || call.name == "execute") {
                val sql = callArgs[0] as String
                sent += sql
                callArgs[0] = "SELECT 'CREATE ' || '${sql.removePrefix("SHOW CREATE ").replace("'", "''")}' AS statement"
            }
            call.invoke(result, *callArgs)
        }
    }

    private fun <T : Any> proxy(type: Class<T>, target: T, call: (java.lang.reflect.Method, Array<Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            try {
                call(method, arrayOf(*(args ?: emptyArray())))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        })

    private fun load(obj: DasObject): String {
        val sources = StringBuilder()
        Provider().load(listOf(obj), databaseConnection(clickhouse(), LocalDataSource(), Dbms.CLICKHOUSE, otherRequestor()), sources)
        return sources.toString()
    }

    fun testEachKindShowsItsClickhouseForm() {
        val root = ModelFactory.BLACK_HOLE.createModel(Dbms.CLICKHOUSE).root as CHouseRoot
        val db = root.schemas.createOrGet("my`db")
        assertTrue(load(db.tables.createOrGet("t")), "CREATE TABLE " in load(db.tables.createOrGet("t")))
        load(db.views.createOrGet("v"))
        load(db.matViews.createOrGet("mv"))
        load(db)
        assertEquals(
            listOf("TABLE", "TABLE", "VIEW", "TABLE", "DATABASE"),
            sent.map { it.removePrefix("SHOW CREATE ").substringBefore(' ') },
        )
        assertEquals(sent.toString(), "SHOW CREATE TABLE \"my`db\".\"t\"", sent.first())
        assertEquals("SHOW CREATE DATABASE \"my`db\"", sent.last())
    }
}
