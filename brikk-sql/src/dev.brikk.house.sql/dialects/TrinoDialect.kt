package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.ast.AtLocal
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.GeneratedTypingMetadata
import dev.brikk.house.sql.ast.MatchPredicate
import dev.brikk.house.sql.ast.TypingSpec
import dev.brikk.house.sql.ast.UniquePredicate
import dev.brikk.house.sql.ast.TrinoJsonConstructor
import dev.brikk.house.sql.ast.TrinoJsonScalar
import dev.brikk.house.sql.ast.TrinoIntervalValue
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.GrantPrincipal
import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.TrinoJsonPathCall
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.metadata.TRINO_FUNCTION_CATALOG
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.PIPE_STAGES_RUN_EVERYWHERE
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.TrinoTokenizerTables

/**
 * Port of sqlglot's Trino dialect umbrella (reference/sqlglot/sqlglot/dialects/trino.py
 * class Trino(Presto)). Tokenizer tables are generated (TrinoTokenizerTables); the
 * parser and generator subclasses live in TrinoParser.kt / TrinoGenerator.kt.
 */
// sqlglot: dialects.trino.Trino
class TrinoDialect : PrestoDialect() {
    // brikk-native PIPE evidence (Trino 483 ran the lowered stages): ASOF JOIN reads as an alias,
    // NATURAL JOIN is not supported, CALL needs TABLE(...), DROP and SET have no `* EXCEPT` /
    // `* REPLACE`, and core refuses or blocks RENAME, PIVOT and UNPIVOT.
    override val pipeRunnableStages: Set<String> get() = PIPE_STAGES_RUN_EVERYWHERE + setOf("TABLESAMPLE", "UNION")

    override val name: String get() = "trino"

    override fun <E : Expression> normalizeIdentifier(expression: E): E {
        val passing = expression.parent as? Alias
        if (expression is Identifier && expression.quoted && expression.argKey == "alias" &&
            passing?.argKey == "passing" && passing.parent is TrinoJsonPathCall) {
            return expression
        }
        val principal = expression.parent as? GrantPrincipal
        if (expression is Identifier && principal != null) {
            // Trino 484 compares user names case-sensitively, including unquoted names.
            // Role names remain lowercase, even when quoted.
            if (principal.text("kind").equals("ROLE", ignoreCase = true)) {
                expression.set("this", expression.name.lowercase())
            }
            return expression
        }
        return super.normalizeIdentifier(expression)
    }
    override val qualifiedStarUsing: QualifiedStarUsing get() = QualifiedStarUsing.OMIT

    // Generated from Trino 484's SHOW FUNCTIONS registry. Presto's registry differs.
    override val functionCatalog: FunctionCatalog get() = TRINO_FUNCTION_CATALOG

    override val tokenizerConfig: TokenizerConfig get() = TrinoTokenizerTables.CONFIG

    override val expressionMetadata get() = TRINO_EXPRESSION_METADATA

    override val coercesTo: Map<DType, Set<DType>> get() = TRINO_COERCES_TO

    override fun parser(errorLevel: ErrorLevel?): Parser =
        TrinoParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean, sourceDialect: String?): Generator =
        TrinoGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig, sourceDialect = sourceDialect)

    companion object {
        private val TRINO_EXPRESSION_METADATA = GeneratedTypingMetadata.PRESTO + mapOf(
            AtLocal::class to TypingSpec.Returns(DType.TIMESTAMP),
            MatchPredicate::class to TypingSpec.Returns(DType.BOOLEAN),
            UniquePredicate::class to TypingSpec.Returns(DType.BOOLEAN),
            TrinoJsonConstructor::class to TypingSpec.Returns(DType.JSON),
            TrinoJsonScalar::class to TypingSpec.Returns(DType.JSON),
            TrinoIntervalValue::class to TypingSpec.Returns(DType.INTERVAL),
        )

        private val TRINO_COERCES_TO: Map<DType, Set<DType>> = buildMap {
            for ((dtype, targets) in GeneratedTypingMetadata.COERCES_TO) {
                put(
                    dtype,
                    if (dtype in DataType.NUMERIC_TYPES) targets + DType.NUMBER else targets,
                )
            }
            put(DType.NUMBER, emptySet())
        }
    }
}
