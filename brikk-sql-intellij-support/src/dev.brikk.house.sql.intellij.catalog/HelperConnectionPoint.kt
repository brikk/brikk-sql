package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.dataSource.DatabaseConnectionPoint
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Properties

/**
 * [point] as a helper connection (a verification, a catalog refresh, a text explain) connects to it:
 * without its startup script (the data source's and its driver's), which is for the user's sessions
 * and would run again on every helper connection, and with [properties] over its driver properties.
 * A URL query parameter of the same name takes the helper's value too, since some drivers let the URL
 * win over properties (ClickHouse 0.9.8 with `compress=true`). Its data source stays [point]'s, unchanged.
 */
public class HelperConnectionPoint(
    point: DatabaseConnectionPoint,
    private val properties: Map<String, String> = emptyMap(),
) : DatabaseConnectionPoint.Adapter(point) {
    override fun getInitScript(): String? = null

    override fun getConnectionProperties(): Properties = Properties().also { shaped ->
        super.getConnectionProperties()?.let(shaped::putAll)
        shaped.putAll(properties)
    }

    override fun getUrl(): String? = super.getUrl()?.let { withProperties(it, properties) }
}

/** [url] with each query parameter named in [properties] set to its value there; anything else as written. */
private fun withProperties(url: String, properties: Map<String, String>): String {
    val query = url.indexOf('?')
    if (properties.isEmpty() || query < 0) return url
    val end = url.indexOf('#', query).takeIf { it >= 0 } ?: url.length
    val parameters = url.substring(query + 1, end).split('&').map { parameter ->
        val key = parameter.substringBefore('=')
        val name = runCatching { URLDecoder.decode(key, Charsets.UTF_8) }.getOrDefault(key)
        properties[name]?.let { value -> "${URLEncoder.encode(name, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}" } ?: parameter
    }
    return url.substring(0, query + 1) + parameters.joinToString("&") + url.substring(end)
}
