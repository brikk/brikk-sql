package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.Dbms
import com.intellij.database.introspection.DBIntrospectionContext
import com.intellij.database.introspection.DBIntrospector
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.util.Version
import org.jetbrains.annotations.ApiStatus

/**
 * Routes a dbms to the platform's generic JDBC introspector. The platform introspects natively only
 * when the dbms's introspector factory is native and supports the server version; otherwise it uses
 * JDBC metadata. Without this gate the factory comes from the `extensionFallback` dbms, and that
 * factory judges an unrelated engine by its version number: PostgreSQL's accepts any major from 9,
 * so a Trino server (483) would be introspected with `pg_catalog` queries. Registered for the exact
 * dbms, this factory shadows the fallback's and supports no version.
 *
 * The per-data-source "Use JDBC-based introspector" flag is no substitute: the platform resets it
 * when the IDE build changes.
 *
 * Register a no-arg subclass as `<introspector dbms="X" implementationClass="..."/>`.
 * `DBIntrospector.Factory` is internal Database Tools API, which the platform offers no public
 * alternative to.
 */
@ApiStatus.Experimental
public abstract class GenericJdbcIntrospectorGate : DBIntrospector.Factory {
    final override fun isNative(): Boolean = true

    final override fun isSupported(version: Version): Boolean = false

    /** The platform asks the generic factory instead, since no version is supported. */
    final override fun createIntrospector(context: DBIntrospectionContext, dbms: Dbms, modelFactory: ModelFactory): DBIntrospector =
        throw IllegalStateException("Native introspection of ${dbms.name} is vetoed; the platform must select the generic JDBC introspector")

    final override fun getVersion(kind: ObjectKind): Int = 0

    final override fun isOutdatedCheckSupported(e: BasicElement?): Boolean = false
}
