import types
import unittest
from unittest.mock import patch

import probe_sqlglot_precedence as probe


class PrecedenceEvidenceTest(unittest.TestCase):
    def result(self, text):
        return types.SimpleNamespace(returncode=0, stdout=text, stderr="")

    def test_frontend_constant_is_labeled_as_a_fold_not_backend_execution(self):
        with patch.object(probe, "run", return_value=self.result("LogicalOneRowRelation ( projects=[FALSE AS `v`#1] )")) as call:
            observed = probe.query("doris", "SELECT !1+1 AS v")
        self.assertEqual("frontend-rewrite-plan", observed["evidence"])
        self.assertEqual("0\n", observed["constant_value"])
        self.assertIn("SET enable_fold_constant_by_be=false; EXPLAIN REWRITTEN PLAN", call.call_args.args[0][-1])

    def test_unfolded_expression_plan_is_never_a_successful_value_comparison(self):
        with patch.object(probe, "run", return_value=self.result("LogicalOneRowRelation ( projects=[(1 | 0) AS `v`#1] )")):
            observed = probe.query("doris", "SELECT 1 | 0 AS v")
        self.assertIn("plan-only", observed["evidence"])
        self.assertIsNone(probe.comparable(observed, "SELECT 1 | 0 AS v"))

    def test_set_plan_does_not_become_a_bag_of_its_literal_projections(self):
        with patch.object(probe, "run", return_value=self.result("LogicalUnion projects=[1 AS v] projects=[2 AS v]")):
            observed = probe.query("doris", "SELECT 1 AS v UNION SELECT 2")
        self.assertIn("plan-only", observed["evidence"])
        self.assertIsNone(probe.comparable(observed, "SELECT 1 AS v UNION SELECT 2"))

    def test_only_unordered_results_are_compared_as_bags_and_errors_are_not_values(self):
        rows = {"returncode": 0, "stdout": "2\n1\n", "evidence": "native-rows"}
        self.assertEqual("1\n2", probe.comparable(rows, "SELECT v FROM t"))
        self.assertEqual("2\n1\n", probe.comparable(rows, "SELECT v FROM t ORDER BY v DESC"))
        self.assertIsNone(probe.comparable({**rows, "returncode": 1}, "SELECT v FROM t"))


if __name__ == "__main__":
    unittest.main()
