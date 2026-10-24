import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from hardware.test_live_yolo import load_products, predict_products, resolve_products_config


MODEL_NAMES = {46: "banana", 47: "apple", 49: "orange", 39: "bottle"}


class ProductConfigTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.config_path = Path(self.temp_dir.name) / "products.json"

    def write_config(self, contents):
        self.config_path.write_text(contents, encoding="utf-8")

    def test_maps_valid_product_names_to_model_ids(self):
        self.write_config(json.dumps({"products": ["apple", "bottle"]}))
        names, class_ids = load_products(self.config_path, MODEL_NAMES)
        self.assertEqual(names, ["apple", "bottle"])
        self.assertEqual(class_ids, [47, 39])

    def test_invalid_product_lists_invalid_and_available_classes(self):
        self.write_config(json.dumps({"products": ["pear"]}))
        with self.assertRaisesRegex(ValueError, "pear.*Available model classes.*apple"):
            load_products(self.config_path, MODEL_NAMES)

    def test_rejects_empty_product_list(self):
        self.write_config('{"products": []}')
        with self.assertRaisesRegex(ValueError, "nonempty"):
            load_products(self.config_path, MODEL_NAMES)

    def test_rejects_duplicate_products(self):
        self.write_config('{"products": ["apple", "apple"]}')
        with self.assertRaisesRegex(ValueError, "duplicate"):
            load_products(self.config_path, MODEL_NAMES)

    def test_reports_malformed_and_missing_json(self):
        self.write_config('{not json')
        with self.assertRaisesRegex(ValueError, "Malformed JSON"):
            load_products(self.config_path, MODEL_NAMES)
        with self.assertRaisesRegex(ValueError, "not found"):
            load_products(self.config_path.with_name("missing.json"), MODEL_NAMES)

    def test_rejects_non_string_and_whitespace_product_values(self):
        for raw in ('{"products": [4]}', '{"products": ["   "]}'):
            with self.subTest(raw=raw):
                self.write_config(raw)
                with self.assertRaisesRegex(ValueError, "nonempty string"):
                    load_products(self.config_path, MODEL_NAMES)

    def test_predict_passes_configured_class_ids(self):
        model = Mock()
        model.predict.return_value = ["result"]
        result = predict_products(model, "frame", 0.4, [47, 39])
        self.assertEqual(result, ["result"])
        model.predict.assert_called_once_with(
            source="frame", conf=0.4, classes=[47, 39], verbose=False
        )

    def test_default_config_is_resolved_from_repository_root(self):
        self.assertEqual(
            resolve_products_config(),
            (Path(__file__).resolve().parents[2] / "data" / "detectable_products.json").resolve(),
        )

    def test_relative_config_path_is_resolved_from_repository_root(self):
        self.assertEqual(
            resolve_products_config("data/other-products.json"),
            (Path(__file__).resolve().parents[2] / "data" / "other-products.json").resolve(),
        )


if __name__ == "__main__":
    unittest.main()
