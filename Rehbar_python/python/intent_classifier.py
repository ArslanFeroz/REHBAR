
"""
Rehbar Intent Classifier v3.1

Upgrades over v2:
- stronger normalization for ASR misspellings ("cloze" -> "close", "wikipidia" -> "wikipedia")
- explicit out-of-domain safety rules for unsupported tasks -> UNKNOWN
- better factual-question routing so "where is tokyo" becomes WEB_SEARCH
- slightly stricter ML acceptance with OOD lexical penalties
- keeps hybrid design: rule -> chat -> ml -> unknown
"""

from __future__ import annotations

import datetime
import hashlib
import json
import os
import re
import sqlite3
from collections import Counter
from typing import Dict, List, Sequence, Tuple

import joblib
import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report
from sklearn.model_selection import train_test_split
from sklearn.pipeline import FeatureUnion, Pipeline

_APPDATA = os.getenv("APPDATA", os.path.expanduser("~"))
_RAHBAR_DIR = os.path.join(_APPDATA, "RAHBAR")
DB_PATH = os.path.join(_RAHBAR_DIR, "rahbar.db")
MODEL_PATH = os.path.join(_RAHBAR_DIR, "intent_model_v3_1.pkl")
META_PATH = os.path.join(_RAHBAR_DIR, "intent_model_v3_1_meta.json")

os.makedirs(_RAHBAR_DIR, exist_ok=True)

CONFIDENCE_THRESHOLD = 0.58
MARGIN_THRESHOLD = 0.16
OOD_CONFIDENCE_THRESHOLD = 0.72
MIN_SAMPLES_PER_CLASS_FOR_SPLIT = 4

_NORMALISE_RULES = [
    (r"\bhow r u\b", "how are you"),
    (r"\bwats up\b", "what is up"),
    (r"\bwassup\b", "what is up"),
    (r"\bwhats up\b", "what is up"),
    (r"\bhru\b", "how are you"),
    (r"\bsup\b", "what is up"),
    (r"\bwat\b", "what"),
    (r"\bwanna\b", "want to"),
    (r"\bgonna\b", "going to"),
    (r"\bgotta\b", "got to"),
    (r"\bdunno\b", "do not know"),
    (r"\byeah\b", "yes"),
    (r"\byep\b", "yes"),
    (r"\bnah\b", "no"),
    (r"\bnope\b", "no"),
    (r"\bpls\b", "please"),
    (r"\bplz\b", "please"),
    (r"\bthx\b", "thanks"),
    (r"\bty\b", "thank you"),
    (r"\bidk\b", "i do not know"),
    # common ASR / typo variants
    (r"\bcloze\b", "close"),
    (r"\bclosee\b", "close"),
    (r"\bopan\b", "open"),
    (r"\boppen\b", "open"),
    (r"\bserch\b", "search"),
    (r"\bsearh\b", "search"),
    (r"\bgo too\b", "go to"),
    (r"\bdelite\b", "delete"),
    (r"\bdeleet\b", "delete"),
    (r"\brenaim\b", "rename"),
    (r"\brenamee\b", "rename"),
    (r"\ballarm\b", "alarm"),
    (r"\balaram\b", "alarm"),
    (r"\bwut\b", "what"),
    (r"\bwikipidia\b", "wikipedia"),
    (r"\bchrom\b", "chrome"),
    (r"\bkrom\b", "chrome"),
    (r"\bfoulder\b", "folder"),
    (r"\btoo\b", "to"),
]

_COMPILED_RULES = [(re.compile(p, re.IGNORECASE), r) for p, r in _NORMALISE_RULES]

_FILLER_RE = re.compile(
    r"^(please\s+|could\s+you\s+|can\s+you\s+|would\s+you\s+|"
    r"i\s+said\s+|i\s+mean\s+|actually\s+|so\s+|well\s+|"
    r"rehbar\s+|hey\s+rehbar\s+|ok\s+rehbar\s+|okay\s+rehbar\s+)+",
    re.IGNORECASE,
)

_COMMAND_HINTS = {
    "open", "close", "launch", "start", "run", "quit", "exit", "kill",
    "search", "google", "look up", "find", "browse", "research",
    "create", "make", "generate", "delete", "remove", "erase", "trash",
    "rename", "change name", "set", "remind", "timer", "alarm",
    "battery", "cpu", "ram", "memory", "disk", "time", "date",
    "navigate", "visit", "go to",
}

_GREETING_RE = re.compile(
    r"^(hi|hello|hey|howdy|greetings|good\s+(morning|afternoon|evening|night)|"
    r"bye|goodbye|see\s+you|thanks?|thank\s+you|ok|okay|alright|cool|wow|nice)\b",
    re.IGNORECASE,
)

_SMALL_TALK_RE = re.compile(
    r"^(who\s+are\s+you|what\s+are\s+you|what\s+can\s+you\s+do|"
    r"what(?:'s|\s+is)\s+your\s+name|who\s+(made|built|created)\s+you|"
    r"are\s+you\s+(an?\s+)?ai|how\s+are\s+you|what\s+is\s+up)\b(?:\s+rehbar)?$",
    re.IGNORECASE,
)

HIGH_PRECISION_RULES: List[Tuple[re.Pattern[str], str]] = [
    (re.compile(r"\b(open|launch|start|run|bring up|fire up)\b.+\b(chrome|spotify|discord|notepad|vlc|cmd|terminal|calculator|paint|explorer|vscode|zoom|task manager|taskmgr)\b", re.I), "OPEN_APP"),
    (re.compile(r"\b(close|quit|exit|stop|terminate|kill)\b", re.I), "CLOSE_APP"),
    # allow "shut down" only when it clearly refers to app/window, not machine
    (re.compile(r"\bshut\s+down\b.+\b(app|window|browser|spotify|chrome|discord|notepad|vlc|explorer|terminal)\b", re.I), "CLOSE_APP"),
    (re.compile(r"\b(open|visit|go to|navigate to|browse to|take me to)\b.+\b(youtube|google|github|gmail|reddit|wikipedia|linkedin|netflix|facebook|twitter|instagram|chatgpt|maps)\b", re.I), "OPEN_SITE"),
    (re.compile(r"\b(search|google|look\s+up|browse|research|find\s+information)\b", re.I), "WEB_SEARCH"),
    (re.compile(r"\b(set|create)\b.*\b(alarm|timer|remind|reminder|wake me up)\b|\b(alarm|timer|reminder)\b", re.I), "SET_ALARM"),
    (re.compile(r"\b(what\s+time|tell me the time|what\s+is\s+the\s+date|what day is it|battery|cpu|ram|memory usage|disk space|system information|system stats|storage)\b", re.I), "SYSTEM_INFO"),
    (re.compile(r"\b(create|make|generate|new)\b.*\b(file|folder|directory|document|txt)\b", re.I), "CREATE_FILE"),
    (re.compile(r"\b(delete|remove|erase|trash|wipe|discard)\b.*\b(file|folder|directory|document|backup|log|downloads)\b", re.I), "DELETE_FILE"),
    (re.compile(r"\b(rename|change\s+name|relabel|give.*new name|modify file name|update the file name)\b", re.I), "RENAME_FILE"),
]

FACTUAL_WEB_PATTERNS = [
    re.compile(r"^(who|what|when|where|why|how)\b.+", re.I),
    re.compile(r"^(explain|define|describe)\b.+", re.I),
]

SYSTEM_INFO_EXCLUDE = re.compile(
    r"\b(time|date|day|battery|cpu|ram|memory|disk|storage|system)\b", re.I
)

OOD_RULES: List[re.Pattern[str]] = [
    re.compile(r"\b(book|reserve)\b.+\b(flight|hotel|ticket)\b", re.I),
    re.compile(r"\bsend\b.+\b(message|email|mail|text)\b", re.I),
    re.compile(r"\b(turn on|turn off|enable|disable)\b.+\b(bluetooth|wifi|wi fi|hotspot)\b", re.I),
    re.compile(r"\b(shut down|shutdown|restart|reboot)\b.+\b(computer|pc|laptop|system|windows)\b", re.I),
    re.compile(r"\b(take|capture)\b.+\b(screenshot|screen shot)\b", re.I),
    re.compile(r"\b(increase|decrease|raise|lower|mute|unmute)\b.+\b(volume|brightness)\b", re.I),
    re.compile(r"\b(play|pause|resume|skip)\b.+\b(song|music|track|video)\b", re.I),
]


def _normalise(text: str) -> str:
    text = text.strip().lower()
    text = _FILLER_RE.sub("", text).strip()
    for pat, repl in _COMPILED_RULES:
        text = pat.sub(repl, text)
    text = re.sub(r"[^a-z0-9\s:/.-]", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def _contains_command_hint(text: str) -> bool:
    return any(h in text for h in _COMMAND_HINTS)


def _looks_like_chat(text: str) -> bool:
    if not text:
        return True
    if _SMALL_TALK_RE.match(text):
        return True
    if _GREETING_RE.match(text) and not _contains_command_hint(text):
        return True
    return len(text.split()) <= 2 and not _contains_command_hint(text)


def _rule_intent(text: str) -> str | None:
    if _looks_like_chat(text):
        return None
    for pattern in OOD_RULES:
        if pattern.search(text):
            return "UNKNOWN"
    for pattern, intent in HIGH_PRECISION_RULES:
        if pattern.search(text):
            return intent
    # factual/search-like questions that are not local system-info questions
    if any(p.match(text) for p in FACTUAL_WEB_PATTERNS) and not SYSTEM_INFO_EXCLUDE.search(text):
        return "WEB_SEARCH"
    return None


def _has_ood_language(text: str) -> bool:
    ood_terms = {
        "flight", "hotel", "ticket", "message", "email", "bluetooth", "wifi",
        "screenshot", "brightness", "volume", "shutdown", "restart", "reboot"
    }
    return any(t in text for t in ood_terms)


def _build_pipeline() -> Pipeline:
    word_tfidf = TfidfVectorizer(analyzer="word", ngram_range=(1, 3), sublinear_tf=True, min_df=1)
    char_tfidf = TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), sublinear_tf=True, min_df=1)
    clf = LogisticRegression(
        C=3.5,
        max_iter=1800,
        class_weight="balanced",
        solver="lbfgs",
        multi_class="auto",
    )
    return Pipeline([
        ("features", FeatureUnion([("word", word_tfidf), ("char", char_tfidf)])),
        ("clf", clf),
    ])


class IntentClassifier:
    def __init__(self, db_path: str = DB_PATH):
        self._db_path = db_path
        self._pipeline = _build_pipeline()
        self._is_trained = False

    def train(self, force: bool = False, evaluate: bool = True) -> None:
        try:
            if not force and self._try_load_cache():
                return

            phrases, labels, fingerprint = self._load_db()
            if not phrases:
                print("[ClassifierV3.1] No training data in DB.")
                self._is_trained = False
                return

            dist = Counter(labels)
            print(f"[ClassifierV3.1] Training on {len(phrases)} samples across {len(dist)} classes.")
            print(f"[ClassifierV3.1] Distribution: {dict(sorted(dist.items()))}")

            self._pipeline = _build_pipeline()

            if evaluate and self._can_split(labels):
                x_train, x_val, y_train, y_val = train_test_split(
                    phrases, labels, test_size=0.2, random_state=42, stratify=labels
                )
                self._pipeline.fit(x_train, y_train)
                y_pred = self._pipeline.predict(x_val)
                print("[ClassifierV3.1] Validation report:")
                print(classification_report(y_val, y_pred, zero_division=0))
                self._pipeline = _build_pipeline()

            self._pipeline.fit(phrases, labels)
            self._is_trained = True
            self._save_cache(len(phrases), fingerprint)
            print("[ClassifierV3.1] Training complete.")

        except Exception as exc:
            print("[ClassifierV3.1] Training failed: " + repr(str(exc)))
            self._is_trained = False

    def predict(self, text: str) -> str:
        return self.predict_debug(text)["intent"]

    def predict_debug(self, text: str) -> Dict[str, object]:
        raw = text or ""
        norm = _normalise(raw)

        result: Dict[str, object] = {
            "raw": raw,
            "normalized": norm,
            "intent": "UNKNOWN",
            "route": "fallback",
            "confidence": 0.0,
            "top2": [],
        }

        if not norm:
            result["route"] = "empty"
            return result

        if _looks_like_chat(norm):
            result.update({"intent": "CHAT", "route": "chat_heuristic", "confidence": 0.95})
            return result

        rule_hit = _rule_intent(norm)
        if rule_hit is not None:
            result.update({"intent": rule_hit, "route": "rule", "confidence": 0.99})
            return result

        if not self._is_trained:
            result["route"] = "not_trained"
            return result

        try:
            proba = self._pipeline.predict_proba([norm])[0]
            classes = list(self._pipeline.classes_)
            order = np.argsort(proba)[::-1]
            best_i = int(order[0])
            second_i = int(order[1]) if len(order) > 1 else best_i

            best_intent = str(classes[best_i])
            best_prob = float(proba[best_i])
            second_prob = float(proba[second_i])
            margin = best_prob - second_prob

            top2 = [{"intent": str(classes[i]), "prob": float(proba[i])} for i in order[:2]]
            result["top2"] = top2
            result["confidence"] = best_prob

            if _has_ood_language(norm) and best_prob < OOD_CONFIDENCE_THRESHOLD:
                result.update({"intent": "UNKNOWN", "route": "ood_guard_unknown"})
                return result

            if best_prob >= CONFIDENCE_THRESHOLD and margin >= MARGIN_THRESHOLD:
                result.update({"intent": best_intent, "route": "ml"})
                return result

            if _looks_like_chat(norm):
                result.update({"intent": "CHAT", "route": "ml_low_conf_chat"})
            else:
                result.update({"intent": "UNKNOWN", "route": "ml_low_conf_unknown"})
            return result

        except Exception as exc:
            print("[ClassifierV3.1] predict() error: " + repr(str(exc)))
            result["route"] = "error"
            return result

    def _load_db(self) -> Tuple[List[str], List[str], str]:
        try:
            conn = sqlite3.connect(self._db_path)
            cur = conn.cursor()
            cur.execute("SELECT phrase, intent FROM training_data ORDER BY id")
            rows = cur.fetchall()
            conn.close()

            if not rows:
                return [], [], ""

            cleaned: List[Tuple[str, str]] = []
            for phrase, intent in rows:
                phrase = _normalise(str(phrase))
                intent = str(intent).strip().upper()
                if not phrase or not intent:
                    continue
                if intent == "UNKNOWN":
                    continue
                cleaned.append((phrase, intent))

            fingerprint = hashlib.sha256(
                "\n".join(f"{p}\t{i}" for p, i in cleaned).encode("utf-8")
            ).hexdigest()
            phrases = [p for p, _ in cleaned]
            labels = [i for _, i in cleaned]
            return phrases, labels, fingerprint

        except Exception as exc:
            print("[ClassifierV3.1] DB read error: " + repr(str(exc)))
            return [], [], ""

    def _try_load_cache(self) -> bool:
        if not (os.path.exists(MODEL_PATH) and os.path.exists(META_PATH)):
            return False
        try:
            with open(META_PATH, "r", encoding="utf-8") as f:
                meta = json.load(f)

            phrases, labels, fingerprint = self._load_db()
            if not phrases:
                return False

            if meta.get("trained_on_rows") != len(phrases):
                print("[ClassifierV3.1] Row count changed, retraining.")
                return False
            if meta.get("data_fingerprint") != fingerprint:
                print("[ClassifierV3.1] Training data changed, retraining.")
                return False

            self._pipeline = joblib.load(MODEL_PATH)
            self._is_trained = True
            print(f"[ClassifierV3.1] Loaded cached model ({len(phrases)} rows).")
            return True
        except Exception as exc:
            print("[ClassifierV3.1] Cache load failed: " + repr(str(exc)))
            return False

    def _save_cache(self, row_count: int, fingerprint: str) -> None:
        try:
            joblib.dump(self._pipeline, MODEL_PATH)
            meta = {
                "trained_on_rows": row_count,
                "data_fingerprint": fingerprint,
                "trained_at": datetime.datetime.now().isoformat(timespec="seconds"),
                "confidence_threshold": CONFIDENCE_THRESHOLD,
                "margin_threshold": MARGIN_THRESHOLD,
                "ood_confidence_threshold": OOD_CONFIDENCE_THRESHOLD,
            }
            with open(META_PATH, "w", encoding="utf-8") as f:
                json.dump(meta, f, indent=2)
            print(f"[ClassifierV3.1] Model cached -> {MODEL_PATH}")
        except Exception as exc:
            print("[ClassifierV3.1] Cache save failed: " + repr(str(exc)))

    @staticmethod
    def _can_split(labels: Sequence[str]) -> bool:
        counts = Counter(labels)
        return all(v >= MIN_SAMPLES_PER_CLASS_FOR_SPLIT for v in counts.values())


if __name__ == "__main__":
    clf = IntentClassifier()
    clf.train(force=True, evaluate=True)
    tests = [
        "look up wikipidia",
        "search for donald trump",
        "cloze chrome",
        "where is tokyo",
        "book a flight to dubai",
        "turn on bluetooth",
        "shut down the computer",
        "how are you rehbar",
        "what is your name rehbar",
        "who made you rehbar",
        "what time is it",
        "where is tokyo",
        "hello open chrome"
    ]
    for t in tests:
        print("-" * 72)
        print(clf.predict_debug(t))

