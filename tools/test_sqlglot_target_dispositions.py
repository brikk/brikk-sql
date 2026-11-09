import copy
import json
import unittest

import sqlglot_target_dispositions as review


class FrozenTargetDispositionsTest(unittest.TestCase):
    def setUp(self):
        self.document = json.loads(review.OUTPUT.read_text())

    def test_every_frozen_commit_has_an_explicit_owned_disposition(self):
        review.validate(self.document)
        self.assertEqual(review.TARGET, self.document["commits"][-1]["commit"])

    def test_missing_duplicate_reordered_and_unreviewed_commits_fail(self):
        for change in (lambda rows: rows.pop(), lambda rows: rows.append(rows[0]),
                       lambda rows: rows.reverse(), lambda rows: rows[0].update(review="unknown")):
            with self.subTest(change=change):
                altered = copy.deepcopy(self.document)
                change(altered["commits"])
                with self.assertRaises(ValueError):
                    review.validate(altered)

    def test_original_probe_limits_are_not_relabeled_as_ports(self):
        groups = self.document["groups"]
        self.assertEqual("deferred", groups["copy-progress"]["disposition"])
        self.assertIn("did not reproduce a hang", groups["copy-progress"]["reason"])
        self.assertEqual("already equivalent", groups["bounded-integer-classification"]["disposition"])
        self.assertIn("toLongOrNull", groups["bounded-integer-classification"]["reason"])
        self.assertEqual("intentionally divergent", groups["replace-tables-api"]["disposition"])
        self.assertEqual("intentionally divergent", groups["sort-compatibility"]["disposition"])
        self.assertNotEqual(groups["regex-count-qualification"]["owner"], "")


if __name__ == "__main__":
    unittest.main()
