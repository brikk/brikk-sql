import unittest

import gen_tokenizer_tables as tokens
import gen_typing_metadata as typing
import gen_ast_nodes as nodes
import gen_qualify_corpus as qualify
from unittest.mock import patch
from pathlib import Path


class CatchupGeneratorTest(unittest.TestCase):
    def test_every_supported_tokenizer_emits_escape_and_comment_policies_without_changing_constructor(self):
        for dialect in tokens.DIALECTS:
            with self.subTest(dialect=dialect):
                _, text = tokens.gen_tables(dialect, "test-pin")
                self.assertIn("val ESCAPE_FOLLOW_CHARS: Set<Char> = emptySet()", text)
                self.assertIn("val NUMERIC_ESCAPES:", text)
                self.assertIn("const val DROP_UNKNOWN_ESCAPES:", text)
                self.assertIn("const val DASH_COMMENT_REQUIRES_BOUNDARY:", text)
                if dialect:
                    self.assertIn(").withStringEscapes(", text)
                    self.assertIn(").withCommentPolicy(", text)
        _, mysql = tokens.gen_tables("mysql", "test-pin")
        self.assertIn("DASH_COMMENT_REQUIRES_BOUNDARY: Boolean = true", mysql)
        _, duckdb = tokens.gen_tables("duckdb", "test-pin")
        self.assertIn("TokenizerConfig.NumericEscape(16, 1, 2, 255)", duckdb)

    def test_target_typing_helpers_are_explicitly_classified_and_unknown_helpers_refuse(self):
        for metadata in (typing.BASE_METADATA, typing.MYSQL_METADATA, typing.PRESTO_METADATA,
                         typing.DUCKDB_METADATA, typing.POSTGRES_METADATA, typing.CLICKHOUSE_METADATA,
                         typing.HIVE_METADATA, typing.SPARK2_METADATA, typing.SPARK_METADATA, typing.BIGQUERY_METADATA):
            self.assertTrue(typing.build_entries(metadata))
        self.assertEqual("AnnotatorRef.NumericArg", typing.classify("def annotate_by_numeric_arg(self, expression): ..."))
        self.assertEqual("AnnotatorRef.AtTimeZoneDuckdb", typing.classify("def _annotate_at_time_zone(self, expression): ..."))
        self.assertEqual("AnnotatorRef.AddSubDuckdb", typing.classify("def _annotate_add_sub(self, expression): ..."))
        with self.assertRaises(SystemExit):
            typing.classify("def completely_new_upstream_helper(): ...")

    def test_target_generation_keeps_exact_legacy_sort_and_lambda_shapes(self):
        self.assertNotIn("SortArray", nodes.NODES)
        self.assertIn("SortArray", nodes.HANDWRITTEN)
        self.assertIn("SortArray", nodes.SERDE_REGISTERED)
        self.assertEqual({"this": True, "expression": False}, nodes.COMPATIBLE_ARG_TYPES["ArraySort"])
        self.assertEqual(["this", "expressions", "colon"], list(nodes.COMPATIBLE_ARG_TYPES["Lambda"]))
        self.assertIn("SortArray", typing.build_entries(typing.BASE_METADATA))

    def test_invalid_qualification_fixture_metadata_stays_with_its_sql(self):
        fixture = "# dialect: duckdb\nSELECT r FROM range(3) AS r;\n# dialect: postgres\nSELECT missing FROM t;\n"
        with patch.object(Path, "read_text", return_value=fixture):
            cases = list(qualify.load_sql_fixtures(Path("fixture.sql")))
        self.assertEqual(2, len(cases))
        sql, meta = qualify._extract_meta(cases[0])
        self.assertEqual("duckdb", meta["dialect"])
        self.assertEqual("SELECT r FROM range(3) AS r;", sql)


if __name__ == "__main__":
    unittest.main()
