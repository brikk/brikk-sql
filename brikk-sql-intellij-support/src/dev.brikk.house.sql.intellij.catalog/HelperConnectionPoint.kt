package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.dataSource.DatabaseConnectionPoint
import java.util.Properties
import org.jetbrains.annotations.ApiStatus

/**
 * [point] as a helper connection (a verification, a catalog refresh, a text explain) connects to it:
 * without its startup script (the data source's and its driver's), which is for the user's sessions
 * and would run again on every helper connection, and with [properties] over its driver properties.
 * Its data source stays [point]'s, unchanged.
 */
@ApiStatus.Experimental
public class HelperConnectionPoint(
    point: DatabaseConnectionPoint,
    private val properties: Map<String, String> = emptyMap(),
) : DatabaseConnectionPoint.Adapter(point) {
    override fun getInitScript(): String? = null

    override fun getConnectionProperties(): Properties = Properties().also { shaped ->
        super.getConnectionProperties()?.let(shaped::putAll)
        shaped.putAll(properties)
    }
}
