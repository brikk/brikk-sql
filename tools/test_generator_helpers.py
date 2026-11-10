"""Offline checks for the deliberately distinct generator spelling policies."""

import itertools
import json
from pathlib import Path
import re
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import generator_helpers as helpers
from generate_clickhouse_functions import kstr as clickhouse_string
from generate_duckdb_functions import kstr as duckdb_string
from generate_starrocks_functions import kesc as starrocks_content
from generate_trino_functions import kstr as trino_string


class GeneratorHelpersTest(unittest.TestCase):
    def test_version_uses_the_existing_describe_command_and_strips_stdout(self):
        self.assertEqual(Path(__file__).resolve().parents[1] / "reference" / "sqlglot", helpers.SQLGLOT)
        with patch.object(helpers.subprocess, "run", return_value=SimpleNamespace(stdout=" v30.29.0\n")) as run:
            self.assertEqual("v30.29.0", helpers.sqlglot_version())
        run.assert_called_once_with(
            ["git", "-C", str(helpers.SQLGLOT), "describe", "--tags", "--abbrev=9"],
            capture_output=True, text=True, check=True,
        )

    def test_version_keeps_the_broad_unknown_fallback(self):
        for error in (subprocess.CalledProcessError(1, "git"), FileNotFoundError(), PermissionError(), ValueError()):
            with self.subTest(error=type(error).__name__), patch.object(helpers.subprocess, "run", side_effect=error):
                self.assertEqual("unknown", helpers.sqlglot_version())

    def test_version_does_not_swallow_base_exceptions(self):
        with patch.object(helpers.subprocess, "run", side_effect=KeyboardInterrupt), self.assertRaises(KeyboardInterrupt):
            helpers.sqlglot_version()

    def test_function_generators_escape_c0_controls_and_del(self):
        escaped = {"\n": "\\n", "\r": "\\r", "\t": "\\t"}
        for escape in (duckdb_string, trino_string, clickhouse_string, lambda s: '"' + starrocks_content(s) + '"'):
            for i in (*range(32), 127):
                with self.subTest(escape=escape, code=i):
                    self.assertEqual('"' + escaped.get(chr(i), f"\\u{i:04X}") + '"', escape(chr(i)))

    def test_function_generators_preserve_spelling_without_c0_controls_or_del(self):
        def old_content(s):
            return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")

        values = [''.join(chr(i) for i in range(32, 127)), "é中文😀\x80\x85\u2028\u2029"]
        values.extend(''.join(parts) for parts in itertools.product(['\\', '"', '$', 'a', 'é'], repeat=3))
        for value in values:
            self.assertEqual(old_content(value), starrocks_content(value))
            for escape in (duckdb_string, trino_string, clickhouse_string):
                self.assertEqual('"' + old_content(value) + '"', escape(value))

    def test_text_variant_adds_only_newline_carriage_return_and_tab(self):
        escaped = {"\\": "\\\\", '"': '\\"', "$": "\\$", "\n": "\\n", "\r": "\\r", "\t": "\\t"}
        for i in range(128):
            ch = chr(i)
            self.assertEqual('"' + escaped.get(ch, ch) + '"', helpers.kotlin_text_string(ch))

    def test_full_variant_escapes_c0_controls_and_del_with_uppercase_hex(self):
        escaped = {"\\": "\\\\", '"': '\\"', "$": "\\$", "\n": "\\n", "\r": "\\r", "\t": "\\t"}
        for i in range(128):
            ch = chr(i)
            expected = escaped.get(ch, f"\\u{i:04X}" if i < 32 or i == 127 else ch)
            self.assertEqual('"' + expected + '"', helpers.kotlin_string(ch))

    def test_json_variant_keeps_json_control_spelling_and_null(self):
        self.assertEqual("null", helpers.kotlin_json_string(None))
        self.assertEqual('"\\b\\f\\u000b\\u001f\\n\\r\\t\\$"', helpers.kotlin_json_string("\b\f\v\x1f\n\r\t$"))
        for i in range(128):
            ch = chr(i)
            self.assertEqual(json.dumps(ch, ensure_ascii=False).replace("$", "\\$"), helpers.kotlin_json_string(ch))

    def test_variants_preserve_non_ascii_including_other_controls(self):
        value = "é中文😀\x80\x85\u2028\u2029\uffff"
        for escape in (helpers.kotlin_string, helpers.kotlin_quoted_string, helpers.kotlin_text_string, helpers.kotlin_json_string):
            self.assertEqual('"' + value + '"', escape(value))

    def test_repeated_metacharacters_do_not_reescape_inserted_backslashes(self):
        alphabet = ['\\', '"', '$', '\n', '\r', '\t', '\x00', '\x1b', 'é']
        for escape in (helpers.kotlin_string, helpers.kotlin_quoted_string, helpers.kotlin_text_string, helpers.kotlin_json_string):
            self.assertEqual('""', escape(""))
            for parts in itertools.product(alphabet, repeat=3):
                self.assertEqual('"' + ''.join(escape(ch)[1:-1] for ch in parts) + '"', escape(''.join(parts)))

    def test_doris_properties_old_and_shared_escapers_match_every_output_literal(self):
        # The original kt implementation, kept as an independent equivalence oracle:
        # generating the file needs the absent doris and doris-website clones.
        def old_kt(s):
            return "null" if s is None else json.dumps(s, ensure_ascii=False).replace("$", "\\$")

        path = Path(__file__).resolve().parents[1] / "brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedDorisProperties.kt"
        literals = re.findall(r'"(?:\\.|[^"\\])*"', path.read_text())
        self.assertGreater(len(literals), 1000)
        for literal in literals:
            value = json.loads(literal.replace("\\$", "$"))
            self.assertEqual(literal, old_kt(value))
            self.assertEqual(old_kt(value), helpers.kotlin_json_string(value))
        self.assertEqual(old_kt(None), helpers.kotlin_json_string(None))


if __name__ == "__main__":
    unittest.main()
