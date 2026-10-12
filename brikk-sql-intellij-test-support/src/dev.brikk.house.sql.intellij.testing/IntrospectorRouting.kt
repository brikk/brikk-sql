package dev.brikk.house.sql.intellij.testing

import com.intellij.database.Dbms
import com.intellij.database.DbmsExtension
import com.intellij.database.introspection.DBIntrospector
import com.intellij.database.introspection.supportedByNativeIntrospector
import com.intellij.database.util.Version
import dev.brikk.house.sql.intellij.catalog.GenericJdbcIntrospectorGate

private val introspectors = DbmsExtension<DBIntrospector.Factory>("com.intellij.database.introspector")

/** Below and at PostgreSQL's native threshold (9), common engine majors, and Trino's (483). */
private val serverMajors = listOf(0, 1, 8, 9, 10, 17, 25, 483, 10_000)

/**
 * Fails unless the platform introspects [dbms] through its generic JDBC introspector at every server
 * version: the dbms's introspector factory is a [GenericJdbcIntrospectorGate] registered for exactly
 * this dbms, not one inherited through `extensionFallback`, and the platform's native-introspection
 * check refuses the dbms at each version.
 */
public fun assertIntrospectorRoutesToGeneric(dbms: Dbms) {
    val factory = introspectors.forDbms(dbms)
    if (factory !is GenericJdbcIntrospectorGate) {
        throw AssertionError("${dbms.name} resolves the introspector ${factory?.javaClass?.name}, not a GenericJdbcIntrospectorGate")
    }
    val native = serverMajors.filter { supportedByNativeIntrospector(dbms, Version.of(it)) }
    if (native.isNotEmpty()) throw AssertionError("${dbms.name} is introspected natively at server majors $native")
}
