"""Research-only cognitive performance model inference."""

from functools import lru_cache
from pathlib import Path
import os
import hashlib

_repo_root = Path(__file__).resolve().parents[3]
MODEL_PATH = Path(os.getenv("COGNITIVE_MODEL_PATH", str(_repo_root / "models/lasi_baseline/lasi_low_recall_calibrated.joblib")))
EXPECTED_SHA256 = os.getenv("COGNITIVE_MODEL_SHA256", "").strip().lower()
DISCLAIMER = "Research cognitive-performance support only; not a dementia diagnosis or clinical decision tool."


@lru_cache(maxsize=1)
def _load_model():
    import joblib

    if not MODEL_PATH.is_file():
        raise FileNotFoundError(f"Cognitive model artifact not found: {MODEL_PATH}")
    digest = hashlib.sha256(MODEL_PATH.read_bytes()).hexdigest()
    if EXPECTED_SHA256 and digest != EXPECTED_SHA256:
        raise ValueError("Cognitive model checksum does not match COGNITIVE_MODEL_SHA256.")
    return joblib.load(MODEL_PATH)


@lru_cache(maxsize=1)
def model_version() -> str:
    if not MODEL_PATH.is_file():
        return "unavailable"
    digest = hashlib.sha256(MODEL_PATH.read_bytes()).hexdigest()
    if EXPECTED_SHA256 and digest != EXPECTED_SHA256:
        return "checksum-mismatch"
    return f"lasi-low-recall-{digest[:12]}"


def predict(features: dict[str, object]) -> dict[str, object]:
    import pandas as pd

    model = _load_model()
    frame = pd.DataFrame([features], columns=["dm005", "dm007", "dm009", "residence", "state"])
    probability = float(model.predict_proba(frame)[0, 1])
    return {
        "low_delayed_recall_probability": probability,
        "model": model_version(),
        "target": "low delayed-word recall (LASI mh056 <= 2)",
        "disclaimer": DISCLAIMER,
    }


def status() -> dict[str, object]:
    return {
        "available": model_version().startswith("lasi-low-recall-"),
        "model": model_version(),
        "disclaimer": DISCLAIMER,
    }
