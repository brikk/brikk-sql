import dev.brikk.house.sql.ast.Expression;
import dev.brikk.house.sql.diagnostics.ClickhouseSemanticDiagnostics;
import dev.brikk.house.sql.optimizer.ClickhouseNodeTypeFact;
import dev.brikk.house.sql.optimizer.ClickhouseScriptTypeAnnotation;
import dev.brikk.house.sql.optimizer.ClickhouseTypeEvidence;
import dev.brikk.house.sql.optimizer.ClickhouseTypeProvenanceKt;
import dev.brikk.house.sql.parser.ParserKt;
import kotlin.Unit;

/** Compile and execute on Java 17 against packaged candidate JARs, never build/classes. */
public final class ClickhouseSemanticSmoke {
    private static final String VERSION = "26.10.2.103";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Expression projection(Expression select, int index) {
        return (Expression) select.getExpressionsArg().get(index);
    }

    public static void main(String[] args) throws Exception {
        var ast = ParserKt.parseOne("SELECT 1*2, 1e0*(2e0+3.0), 1%2, unknown_udf(1)", "clickhouse");
        var typed = ClickhouseTypeProvenanceKt.annotateClickhouseSemanticTypesWithProvenance(
            ast, VERSION, null, () -> Unit.INSTANCE);
        check(typed.getExpression() == ast, "statement API must retain AST ownership");
        String[] types = {"UInt16", "Float64"};
        for (int i = 0; i < types.length; i++) {
            ClickhouseNodeTypeFact fact = typed.getProvenance().factFor(projection(ast, i));
            check(fact.getEvidence() == ClickhouseTypeEvidence.NATIVE_CONDITIONAL, "missing native product proof");
            check(types[i].equals(fact.getTypeSql()), "wrong product type: " + fact.getTypeSql());
        }
        check(typed.getProvenance().factFor(projection(ast, 2)).getEvidence()
            == ClickhouseTypeEvidence.GENERIC_FALLBACK, "generic remainder must not be native-certified");
        check(typed.getProvenance().factFor(projection(ast, 3)).getEvidence()
            == ClickhouseTypeEvidence.UNKNOWN, "unknown UDF must stay unknown");
        check(typed.getProvenance().factFor(projection(ast.copy(), 0)) == null, "copy must not inherit provenance");

        String[] cteQueries = {
            "WITH c AS (SELECT CAST(1 AS UInt8) AS x) SELECT x+2 FROM c",
            "WITH c AS (SELECT CAST(1 AS UInt8) AS x), d AS (SELECT x+2 AS y FROM c) SELECT y*3 FROM d",
            "WITH c AS (SELECT CAST([1,2] AS Array(UInt8)) AS xs) SELECT arrayMap(x -> x*2,xs) FROM c",
            "WITH c AS (SELECT CAST(1 AS UInt8) AS x), d AS (SELECT * FROM c) SELECT x FROM d"
        };
        String[] cteTypes = {"UInt16", "UInt32", "Array(UInt16)", "UInt8"};
        for (int i = 0; i < cteQueries.length; i++) {
            var cteAst = ParserKt.parseOne(cteQueries[i], "clickhouse");
            var cteResult = ClickhouseTypeProvenanceKt.annotateClickhouseSemanticTypesWithProvenance(
                cteAst, VERSION, null, () -> Unit.INSTANCE);
            var fact = cteResult.getProvenance().factFor(projection(cteAst, 0));
            check(fact.getEvidence() == ClickhouseTypeEvidence.NATIVE_CONDITIONAL, "missing native CTE proof");
            check(cteTypes[i].equals(fact.getTypeSql()), "wrong CTE type: " + fact.getTypeSql());
        }

        var script = ClickhouseTypeProvenanceKt.annotateClickhouseScriptSemanticTypesWithProvenance(
            "SET sql_compatible_division_for_integers=1; SELECT 1/0", VERSION, null, () -> Unit.INSTANCE);
        check(script.size() == 2, "script framing changed");
        var last = script.get(1);
        check(last.getAnnotation().getDiagnostics().getErrors().get(0).getRule().name().equals("DIVISION_BY_ZERO"),
            "conditional typing must retain semantic errors");
        check(last.getProvenance().factFor(projection(last.getAnnotation().getExpression(), 0)).getEvidence()
            == ClickhouseTypeEvidence.NATIVE_CONDITIONAL, "conditional division proof missing");

        // Separately compiled consumers still link to the original public result shape.
        ClickhouseScriptTypeAnnotation.class.getConstructor(Expression.class, ClickhouseSemanticDiagnostics.class);
        try {
            ClickhouseTypeProvenanceKt.annotateClickhouseSemanticTypesWithProvenance(ast, "26.8.34.13", null, () -> Unit.INSTANCE);
            throw new AssertionError("control pin must not enable native semantic APIs");
        } catch (IllegalArgumentException expected) {
            // Exact-pin rejection remains part of the public API contract.
        }
        System.out.println("Packaged ClickHouse semantic APIs, CTE propagation, proof categories, script diagnostics and old result shape: OK");
    }
}
