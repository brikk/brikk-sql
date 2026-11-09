import unittest
import json

import review_sqlglot_refresh as gate


class RefreshReviewTest(unittest.TestCase):
    def test_every_integration_owned_disposition_has_a_followup_and_evidence(self):
        original = json.loads((gate.ROOT / "vendor/data/sqlglot-sg29-019/dispositions.json").read_text())
        followup = json.loads((gate.ROOT / "vendor/data/sqlglot-sg29-020/disposition-followup.json").read_text())
        expected = {name for name, group in original["groups"].items()
                    if group["disposition"] == "deferred" and "SG29-020" in group["owner"]}
        actual = []
        for item in followup["resolutions"]:
            actual.extend(item["groups"])
            self.assertTrue(item["disposition"] and item["reason"])
            self.assertTrue((gate.ROOT / item["evidence"]).is_file())
            if "deferred" in item["disposition"] or "deferrals" in item["disposition"]:
                self.assertTrue(item["next_work"])
        self.assertEqual(len(actual), len(set(actual)))
        self.assertEqual(expected, set(actual))

    def test_same_label_with_different_payload_is_not_a_rekey(self):
        old = {"cases": [{"id": "old", "sql": "SELECT 1", "signature": "before", "reason": "curated"}]}
        new = {"cases": [{"id": "new", "sql": "SELECT 1", "signature": "after", "reason": "observed"}]}
        delta = gate.changes(old, new)
        self.assertFalse(delta["rekeyed"])
        self.assertEqual(old["cases"], delta["removed"])
        self.assertEqual(new["cases"], delta["added"])

    def test_identical_observation_can_rekey_but_keeps_the_old_reason(self):
        old = {"cases": [{"id": "old", "sql": "SELECT 1", "signature": "same", "reason": "curated"}]}
        new = {"cases": [{"id": "new", "sql": "SELECT 1", "signature": "same", "reason": "observed"}]}
        delta = gate.changes(old, new)
        self.assertEqual("curated", delta["rekeyed"][0]["before"]["reason"])
        self.assertFalse(delta["removed"] or delta["added"] or delta["changed"])

    def test_changed_signature_and_incomplete_or_stale_approvals_refuse(self):
        old = {"cases": [{"id": "same", "sql": "SELECT 1", "signature": "before"}]}
        new = {"cases": [{"id": "same", "sql": "SELECT 1", "signature": "after"}]}
        document = {"ledgers": {"test.json": gate.changes(old, new)}}
        self.assertEqual({"test.json#change#same"}, gate.required_decisions(document))
        for approval in ({"review_sha256": "stale", "decisions": {}},
                         {"review_sha256": gate.fingerprint(document), "decisions": {}}):
            with self.assertRaises(ValueError):
                gate.validate_approval(document, approval)


if __name__ == "__main__":
    unittest.main()
