package dev.brikk.house.sql.intellij.testing

import com.intellij.database.Dbms
import com.intellij.database.DbmsExtension
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException

/**
 * Fails unless every Database Tools extension that [dbms] inherits through its `extensionFallback`
 * chain is overridden (registered for [dbms] itself) or listed in [allowed] with the reason that
 * inheriting it is right. Keys are extension point names, such as `com.intellij.database.dialect`.
 * Blank reasons and listed points the dbms no longer inherits fail too, so the list stays current.
 *
 * A dbms resolves an extension point to its fallback's extensions only when it has none of its own,
 * so a new platform extension point is inherited silently; this audit makes that a test failure.
 */
public fun assertFallbackExtensionsAudited(dbms: Dbms, allowed: Map<String, String>) {
    val inherited = inheritedDbmsExtensions(dbms)
    val problems = buildList {
        inherited.filterKeys { it !in allowed }.forEach { (point, from) -> add("$point is inherited from $from without an override or a reason") }
        allowed.filterValues(String::isBlank).keys.forEach { add("$it has a blank reason") }
        (allowed.keys - inherited.keys).forEach { add("$it is allowed but not inherited") }
    }
    if (problems.isNotEmpty()) throw AssertionError("${dbms.name} fallback extensions:\n" + problems.sorted().joinToString("\n"))
}

/**
 * The dbms-keyed extension points [dbms] resolves only through its fallback chain, each mapped to the
 * name of the dbms it inherits from: the starting point for an allow-list. The chain is the platform's:
 * each dbms's first `extensionFallback`, ending with `UNKNOWN` (the generic dbms), which every dbms
 * falls back to last. Points whose bean class cannot load are skipped.
 */
public fun inheritedDbmsExtensions(dbms: Dbms): Map<String, String> {
    val area = ApplicationManager.getApplication().extensionArea
    val fallbacks = HashMap<String, String>()
    for (bean in area.getExtensionPoint<DbmsExtension.FallbackBean>("com.intellij.database.extensionFallback").extensionList) {
        val from = bean.dbmsStr ?: continue
        val to = bean.fallbackDbmsStr ?: continue
        fallbacks.putIfAbsent(from, to)
    }
    fun fallbackOf(name: String): String? = (fallbacks[name] ?: Dbms.UNKNOWN.name).takeIf { it != name }
    val chain = generateSequence(fallbackOf(dbms.name), ::fallbackOf).take(fallbacks.size + 1).toList()
    return area.nameToPointMap.values.mapNotNull { point ->
        try {
            if (!DbmsExtension.Bean::class.java.isAssignableFrom(point.getExtensionClass())) return@mapNotNull null
            val keys = point.extensionList.mapNotNullTo(HashSet()) { (it as? DbmsExtension.Bean<*>)?.key }
            if (dbms.name in keys) null else chain.firstOrNull { it in keys }?.let { point.name to it }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }.toMap(sortedMapOf())
}
