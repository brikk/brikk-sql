import unittest

import verify_jvm_abi as gate


class JvmAbiTest(unittest.TestCase):
    def api(self, methods):
        return dict(parent="java/lang/Object", interfaces=[], fields={}, methods=methods)

    def test_removed_descriptors_and_static_visibility_final_or_abstract_changes_refuse(self):
        old = self.api({("copy", "(I)LT;"): 1})
        for methods in ({}, {("copy", "()LT;"): 1}, {("copy", "(I)LT;"): 9},
                        {("copy", "(I)LT;"): 4}, {("copy", "(I)LT;"): 17},
                        {("copy", "(I)LT;"): 1025}):
            self.assertTrue(gate.compare(old, self.api(methods)))

    def test_additive_overload_preserves_old_bridge(self):
        old = self.api({("copy$default", "(LT;I)LT;"): 9})
        new = self.api({**old["methods"], ("copy$default", "(LT;II)LT;"): 9})
        self.assertFalse(gate.compare(old, new))

    def test_invalid_class_data_refuses(self):
        with self.assertRaises(ValueError):
            gate.class_api(b"not a class")


if __name__ == "__main__":
    unittest.main()
