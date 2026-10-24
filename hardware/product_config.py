"""Shared product whitelist loader, independent of inference runtimes."""

import json
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PRODUCTS_CONFIG = REPOSITORY_ROOT / "data" / "detectable_products.json"


def resolve_products_config(path=None):
    config_path = Path(path) if path is not None else DEFAULT_PRODUCTS_CONFIG
    if not config_path.is_absolute():
        config_path = REPOSITORY_ROOT / config_path
    return config_path.resolve()


def load_products(config_path, model_names):
    """Return exact configured COCO class names and their model class IDs."""
    path = Path(config_path)
    try:
        with path.open("r", encoding="utf-8") as config_file:
            config = json.load(config_file)
    except FileNotFoundError as exc:
        raise ValueError("Product configuration file not found: {}".format(path)) from exc
    except json.JSONDecodeError as exc:
        raise ValueError("Malformed JSON in product configuration {}: {}".format(path, exc)) from exc
    except UnicodeDecodeError as exc:
        raise ValueError("Product configuration is not valid UTF-8 JSON: {}".format(path)) from exc
    except OSError as exc:
        raise ValueError("Could not read product configuration {}: {}".format(path, exc)) from exc

    if not isinstance(config, dict) or "products" not in config:
        raise ValueError("Product configuration must be a JSON object with a 'products' array.")
    products = config["products"]
    if not isinstance(products, list) or not products:
        raise ValueError("The 'products' value must be a nonempty JSON array.")
    if any(not isinstance(name, str) or not name.strip() for name in products):
        raise ValueError("Every configured product must be a nonempty string.")
    if len(set(products)) != len(products):
        raise ValueError("The 'products' array must not contain duplicate names.")

    if isinstance(model_names, dict):
        name_to_id = {name: int(class_id) for class_id, name in model_names.items()}
    else:
        name_to_id = {name: class_id for class_id, name in enumerate(model_names)}
    invalid = [name for name in products if name not in name_to_id]
    if invalid:
        available = ", ".join(name_to_id)
        raise ValueError(
            "Unsupported product name(s): {}. Available model classes: {}".format(
                ", ".join(repr(name) for name in invalid), available
            )
        )
    return products, [name_to_id[name] for name in products]
