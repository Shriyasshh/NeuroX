"""Train a non-diagnostic LASI cognitive-performance baseline.

The target is low delayed-word recall (mh056 <= 2 correct words). This is a
research proxy for cognitive performance, not a dementia diagnosis.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import pyreadstat
from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    average_precision_score,
    balanced_accuracy_score,
    brier_score_loss,
    classification_report,
    confusion_matrix,
    roc_auc_score,
)
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import StratifiedKFold, cross_val_predict, train_test_split
from sklearn.calibration import CalibratedClassifierCV
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler


FEATURES = [
    "dm005",  # age
    "dm007",  # years of schooling
    "dm009",  # can read and write
    "residence",
    "state",
]
GROUP_COLUMNS = ["dm003", "dm005", "dm007", "state"]
TARGET = "mh056"


def load_data(path: Path) -> pd.DataFrame:
    columns = ["prim_key", *FEATURES, *[c for c in GROUP_COLUMNS if c not in FEATURES], TARGET]
    frame, _ = pyreadstat.read_sav(path, usecols=columns)
    frame = frame.replace({"": np.nan})
    frame[TARGET] = pd.to_numeric(frame[TARGET], errors="coerce")
    frame = frame.dropna(subset=[TARGET]).copy()
    frame["low_delayed_recall"] = (frame[TARGET] <= 2).astype("int8")
    return frame


def build_preprocessor() -> ColumnTransformer:
    numeric = ["dm005", "dm007"]
    categorical = ["dm009", "residence", "state"]
    return ColumnTransformer(
        transformers=[
            ("numeric", Pipeline([
                ("impute", SimpleImputer(strategy="median")),
                ("scale", StandardScaler()),
            ]), numeric),
            ("categorical", Pipeline([
                ("impute", SimpleImputer(strategy="most_frequent")),
                ("one_hot", OneHotEncoder(handle_unknown="ignore")),
            ]), categorical),
        ]
    )


def logistic_pipeline() -> Pipeline:
    return Pipeline([
        ("preprocess", build_preprocessor()),
        ("classifier", LogisticRegression(max_iter=1000, class_weight="balanced")),
    ])


def random_forest_pipeline() -> Pipeline:
    return Pipeline([
        ("preprocess", build_preprocessor()),
        ("classifier", RandomForestClassifier(
            n_estimators=300, min_samples_leaf=5, class_weight="balanced",
            random_state=42, n_jobs=-1,
        )),
    ])


def binary_metrics(y_true: pd.Series, probabilities: np.ndarray, threshold: float = 0.5) -> dict:
    predictions = (probabilities >= threshold).astype("int8")
    tn, fp, fn, tp = confusion_matrix(y_true, predictions, labels=[0, 1]).ravel()
    return {
        "roc_auc": float(roc_auc_score(y_true, probabilities)),
        "average_precision": float(average_precision_score(y_true, probabilities)),
        "balanced_accuracy": float(balanced_accuracy_score(y_true, predictions)),
        "brier_score": float(brier_score_loss(y_true, probabilities)),
        "sensitivity": float(tp / (tp + fn)) if tp + fn else None,
        "specificity": float(tn / (tn + fp)) if tn + fp else None,
        "support": int(len(y_true)),
    }


def train(input_path: Path, output_dir: Path) -> dict:
    data = load_data(input_path)
    x = data[FEATURES]
    y = data["low_delayed_recall"]
    x_train, x_test, y_train, y_test = train_test_split(
        x, y, test_size=0.2, random_state=42, stratify=y
    )

    model = logistic_pipeline()
    model.fit(x_train, y_train)
    probabilities = model.predict_proba(x_test)[:, 1]
    predictions = (probabilities >= 0.5).astype("int8")

    metrics = {
        "target": "low_delayed_recall",
        "target_definition": "mh056 <= 2 correct delayed-recall words",
        "disclaimer": "Research proxy only; not a dementia diagnosis or clinical decision tool.",
        "rows_total": int(len(data)),
        "rows_train": int(len(x_train)),
        "rows_test": int(len(x_test)),
        "positive_rate": float(y.mean()),
        **binary_metrics(y_test, probabilities),
        "classification_report": classification_report(y_test, predictions, output_dict=True),
        "features": FEATURES,
        "random_state": 42,
    }
    cv = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)
    cv_probabilities = cross_val_predict(model, x, y, cv=cv, method="predict_proba", n_jobs=1)[:, 1]
    calibrated = CalibratedClassifierCV(estimator=logistic_pipeline(), method="sigmoid", cv=3)
    calibrated.fit(x_train, y_train)
    calibrated_probabilities = calibrated.predict_proba(x_test)[:, 1]
    forest = random_forest_pipeline()
    forest.fit(x_train, y_train)
    forest_probabilities = forest.predict_proba(x_test)[:, 1]
    metrics["cross_validation_5_fold"] = binary_metrics(y, cv_probabilities)
    metrics["calibrated_holdout"] = binary_metrics(y_test, calibrated_probabilities)
    metrics["random_forest_holdout"] = binary_metrics(y_test, forest_probabilities)

    groups = data.loc[x_test.index].copy()
    groups["age_band"] = pd.cut(groups["dm005"], [0, 54, 64, 74, np.inf], labels=["<55", "55-64", "65-74", "75+"])
    groups["education_band"] = groups["dm007"].map(lambda v: "missing" if pd.isna(v) else ("none" if v == 0 else "1-5" if v <= 5 else "6+"))
    fairness = {}
    for column in ["dm003", "state", "age_band", "education_band"]:
        fairness[column] = {}
        for value, indices in groups.groupby(column, dropna=False).groups.items():
            idx = list(indices)
            if len(idx) >= 50 and y_test.loc[idx].nunique() == 2:
                fairness[column][str(value)] = binary_metrics(y_test.loc[idx], probabilities[[x_test.index.get_loc(i) for i in idx]])
    metrics["fairness_subgroups_min_n_50"] = fairness

    external_path = Path("data/archive(6)/dementia_dataset.csv")
    if external_path.exists():
        external = pd.read_csv(external_path)
        external_x = pd.DataFrame({"dm005": pd.to_numeric(external["Age"], errors="coerce"), "dm007": pd.to_numeric(external["EDUC"], errors="coerce"), "dm009": np.nan, "residence": np.nan, "state": np.nan})
        external_y = (pd.to_numeric(external["MMSE"], errors="coerce") < 24).astype("int8")
        valid = external_y.notna() & external_x["dm005"].notna()
        external_probabilities = model.predict_proba(external_x.loc[valid])[:, 1]
        metrics["external_oasis_proxy"] = {
            **binary_metrics(external_y.loc[valid], external_probabilities),
            "target_definition": "MMSE < 24; not equivalent to LASI mh056 <= 2",
            "warning": "Exploratory external proxy only; labels, cohorts, and measurement instruments differ.",
        }
    output_dir.mkdir(parents=True, exist_ok=True)
    joblib.dump(model, output_dir / "lasi_low_recall_logistic.joblib")
    calibrated_path = output_dir / "lasi_low_recall_calibrated.joblib"
    joblib.dump(calibrated, calibrated_path)
    joblib.dump(forest, output_dir / "lasi_low_recall_random_forest.joblib")
    manifest = {
        "artifact": calibrated_path.name,
        "sha256": hashlib.sha256(calibrated_path.read_bytes()).hexdigest(),
        "features": FEATURES,
        "target": metrics["target_definition"],
        "research_only": True,
    }
    (output_dir / "model_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (output_dir / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n")
    return metrics


def discover_default_input() -> Path:
    candidates = sorted(Path("data").glob("*/3_LASI_W1_Individual_v4.sav"))
    if len(candidates) == 1:
        return candidates[0]
    if not candidates:
        raise SystemExit("Could not find LASI individual data. Pass --input /path/to/3_LASI_W1_Individual_v4.sav")
    raise SystemExit(f"Found multiple LASI individual files; pass --input explicitly: {candidates}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=Path("models/lasi_baseline"))
    args = parser.parse_args()
    metrics = train(args.input or discover_default_input(), args.output)
    print(json.dumps({k: v for k, v in metrics.items() if k != "classification_report"}, indent=2))


if __name__ == "__main__":
    main()
