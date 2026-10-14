package dev.brikk.house.sql.intellij.testing

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.jetbrains.annotations.ApiStatus

/**
 * Where a [RealSqlContract] runs: a dialect's real files over [RealSqlModel.STANDARD] in a data
 * source. [open] shows `<caret>`-marked SQL in [fixture] as a file of the dialect attached to that
 * data source (its console, or a resolve mapping), with the plugin's inspections, annotators and
 * relation features registered as in the product.
 */
@ApiStatus.Experimental
public interface RealSqlTarget {
    /** The key of the dialect's [RealSqlCase.variants], such as `postgres`, `mysql`, `doris`. */
    public val dialect: String

    /** The syntax the dialect has. */
    public val features: Set<RealSqlFeature>

    public val fixture: CodeInsightTestFixture

    public fun open(sql: String)
}

/**
 * The "no false errors on real SQL" contract: every [RealSqlCases] statement the target's dialect can
 * write highlights with no error or warning, through real highlighting, and offers its completions.
 * Call [check] from a light platform test; a failed case is reported with all others in one
 * [AssertionError].
 */
@ApiStatus.Experimental
public class RealSqlContract(private val target: RealSqlTarget, private val cases: List<RealSqlCase> = RealSqlCases.ALL) {
    /** Checks every applicable case. */
    public fun check() {
        val failures = cases.filter { target.features.containsAll(it.needs) }.flatMap(::failures)
        if (failures.isNotEmpty()) throw AssertionError("${failures.size} false report(s) on real ${target.dialect} SQL:\n" + failures.joinToString("\n"))
    }

    /** What [case] gets wrong, one line each. */
    public fun failures(case: RealSqlCase): List<String> {
        val sql = case.sqlFor(target.dialect)
        target.open(if ("<caret>" in sql) sql else "$sql<caret>")
        val fixture = target.fixture
        DaemonCodeAnalyzer.getInstance(fixture.project).restart()
        val document = fixture.editor.document
        val reports = fixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull { info ->
            val text = document.getText(TextRange(info.startOffset, info.endOffset))
            if (text in case.reported) null else "${case.name}: ${info.severity} '$text' ${info.description}"
        }
        val missing = case.completions.mapNotNull { completion ->
            target.open(completion.sql)
            val offered = fixture.completeBasic()?.map { it.lookupString }?.toSet() ?: emptySet()
            (completion.offered - offered).takeIf { it.isNotEmpty() }?.let { "${case.name}: not offered $it at ${completion.sql}" }
        }
        return reports + missing
    }
}
