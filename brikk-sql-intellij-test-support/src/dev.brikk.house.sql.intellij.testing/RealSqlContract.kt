package dev.brikk.house.sql.intellij.testing

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/**
 * Where a [RealSqlContract] runs: a dialect's real files over [RealSqlModel.STANDARD] in a data
 * source. [open] shows `<caret>`-marked SQL in [fixture] as a file of the dialect attached to that
 * data source (its console, or a resolve mapping), with the plugin's inspections, annotators and
 * relation features registered as in the product.
 */
public interface RealSqlTarget {
    /** The key of the dialect's [RealSqlCase.variants], such as `postgres`, `mysql`, `doris`. */
    public val dialect: String

    /** The syntax the dialect has. */
    public val features: Set<RealSqlFeature>

    public val fixture: CodeInsightTestFixture

    public fun open(sql: String)

    /**
     * Makes [model] the data source's content, for [RealSqlContract.checkModelStates]; files opened
     * afterwards see it. Targets that check only [RealSqlModel.STANDARD] need not override it.
     */
    public fun install(model: RealSqlModel): Unit =
        throw UnsupportedOperationException("Override install to check model states")
}

/**
 * The "no false errors on real SQL" contract: every [RealSqlCases] statement the target's dialect can
 * write highlights with no error or warning, through real highlighting, and offers its completions.
 * Call [check] from a light platform test; a failed case is reported with all others in one
 * [AssertionError].
 */
public class RealSqlContract(private val target: RealSqlTarget, private val cases: List<RealSqlCase> = RealSqlCases.ALL + RealSqlCases.TABLE_FUNCTIONS) {
    /** Checks every applicable case. */
    public fun check() {
        val failures = cases.filter { target.features.containsAll(it.needs) }.flatMap(::failures)
        if (failures.isNotEmpty()) throw AssertionError("${failures.size} false report(s) on real ${target.dialect} SQL:\n" + failures.joinToString("\n"))
    }

    /**
     * Checks [cases] (by default [RealSqlCases.EXTERNAL]) in every model of [states], installing each
     * with [RealSqlTarget.install]: columns that may come from a table nobody has loaded, or that has no
     * columns, are never reported.
     */
    public fun checkModelStates(states: Map<String, RealSqlModel> = RealSqlModel.STATES, cases: List<RealSqlCase> = RealSqlCases.EXTERNAL) {
        val applicable = cases.filter { target.features.containsAll(it.needs) }
        val failures = states.flatMap { (state, model) ->
            target.install(model)
            applicable.flatMap(::failures).map { "$state: $it" }
        }
        if (failures.isNotEmpty()) throw AssertionError("${failures.size} false report(s) on real ${target.dialect} SQL:\n" + failures.joinToString("\n"))
    }

    /** What [case] gets wrong, one line each. */
    public fun failures(case: RealSqlCase): List<String> {
        val sql = case.sqlFor(target.dialect)
        target.open(if ("<caret>" in sql) sql else "$sql<caret>")
        val fixture = target.fixture
        DaemonCodeAnalyzer.getInstance(fixture.project).restart()
        val document = fixture.editor.document
        val allowed = case.reported.flatMap { fragment ->
            generateSequence(document.text.indexOf(fragment).takeIf { it >= 0 }) { at ->
                document.text.indexOf(fragment, at + 1).takeIf { it >= 0 }
            }.map { TextRange(it, it + fragment.length) }
        }
        val highlights = fixture.doHighlighting(HighlightSeverity.WARNING)
        val texts = highlights.map { document.getText(TextRange(it.startOffset, it.endOffset)) }
        val reports = highlights.mapNotNull { info ->
            val range = TextRange(info.startOffset, info.endOffset)
            if (allowed.any { it.contains(range) } || document.getText(range) in case.missed) null
            else "${case.name}: ${info.severity} '${document.getText(range)}' ${info.description}"
        } + case.missed.filter { it !in texts }.map { "${case.name}: real miss '$it' not reported" }
        val missing = case.completions.flatMap { completion ->
            val sql = completion.sqlFor(target.dialect)
            target.open(sql)
            val items = fixture.completeBasic()?.map { it.lookupString }.orEmpty()
            val offered = items.toSet()
            listOfNotNull(
                completion.once.filter { name -> items.count { it == name } > 1 }.takeIf { it.isNotEmpty() }?.let { "${case.name}: offered more than once $it at $sql" },
                (completion.offered - offered).takeIf { it.isNotEmpty() }?.let { "${case.name}: not offered $it at $sql" },
                completion.notOffered.filter { it in offered }.takeIf { it.isNotEmpty() }?.let { "${case.name}: offered $it, which must not be, at $sql" },
            )
        }
        return reports + missing
    }
}
