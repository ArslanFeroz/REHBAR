"""
intent_classifier.py  --  Rehbar Intent Classifier

Model choice: TF-IDF (word 1-3gram + char 2-4gram) + LogisticRegression
  - Produces calibrated probabilities natively (softmax) -- no CV wrapper needed.
  - Handles informal speech better than MultinomialNB (no negative-feature
    penalty; better on short asymmetric text).
  - Fast: trains in < 2 s on 10,000 phrases, predicts in < 1 ms.

Training data: loaded from the SQLite training_data table, not hardcoded.
  The DB is seeded with ~500+ phrases per intent by DatabaseManager on first run.
  Cache invalidation: if the DB row count changes, model is retrained automatically.

Confidence thresholding:
  - max_probability < CONFIDENCE_THRESHOLD  -> UNKNOWN (Java gives a nudge)
  - Chat heuristic fires before ML          -> CHAT    (Gemini stub handles it)

Gemini stub:
  predict() can return "CHAT" for conversational inputs.
  The caller (bridge.py) routes CHAT to handle_chat() which is a stub
  -- wire Gemini there when ready.

Text normaliser:
  Runs before classification. Expands abbreviations so the model sees
  clean input regardless of how the user speaks.
"""

import os
import re
import json
import sqlite3
import datetime
from collections import Counter
from typing import Optional

import numpy as np
import joblib
from sklearn.linear_model import LogisticRegression
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.pipeline import Pipeline, FeatureUnion


# ── Paths ──────────────────────────────────────────────────────────────────────
_APPDATA    = os.getenv('APPDATA', os.path.expanduser('~'))
_RAHBAR_DIR = os.path.join(_APPDATA, 'RAHBAR')
DB_PATH     = os.path.join(_RAHBAR_DIR, 'rahbar.db')
MODEL_PATH  = os.path.join(_RAHBAR_DIR, 'intent_model.pkl')
META_PATH   = os.path.join(_RAHBAR_DIR, 'intent_model_meta.json')

os.makedirs(_RAHBAR_DIR, exist_ok=True)

# ── Tuning ─────────────────────────────────────────────────────────────────────
CONFIDENCE_THRESHOLD = 0.42   # below this -> UNKNOWN or CHAT


# ── Text normaliser ────────────────────────────────────────────────────────────
_NORMALISE_RULES = [
    (r'\bhow r u\b',   'how are you'),
    (r'\bwats up\b',   'what is up'),
    (r'\bwassup\b',    'what is up'),
    (r'\bwhats up\b',  'what is up'),
    (r'\bhru\b',       'how are you'),
    (r'\bsup\b',       'what is up'),
    (r'\bwat\b',       'what'),
    (r'\bwanna\b',     'want to'),
    (r'\bgonna\b',     'going to'),
    (r'\bgotta\b',     'got to'),
    (r'\bdunno\b',     'do not know'),
    (r'\byeah\b',      'yes'),
    (r'\byep\b',       'yes'),
    (r'\bnah\b',       'no'),
    (r'\bnope\b',      'no'),
    (r'\bpls\b',       'please'),
    (r'\bplz\b',       'please'),
    (r'\bthx\b',       'thanks'),
    (r'\bty\b',        'thank you'),
    (r'\br\b',         'are'),
    (r'\bu\b',         'you'),
    (r'\bur\b',        'your'),
    (r'\bidk\b',       'i do not know'),
]
_COMPILED_RULES = [
    (re.compile(pat, re.IGNORECASE), repl)
    for pat, repl in _NORMALISE_RULES
]

def _normalise(text: str) -> str:
    for pat, repl in _COMPILED_RULES:
        text = pat.sub(repl, text)
    return text


# ── Chat heuristic ─────────────────────────────────────────────────────────────
_CHAT_RE = re.compile(
    r'^('
    r'hi\b|hello\b|hey\b|howdy\b|greetings\b|'
    r'good\s+(morning|afternoon|evening|night)\b|'
    r'bye\b|goodbye\b|see\s+you|'
    r'thanks?\b|thank\s+you|'
    r'who\s+are\s+you|what\s+are\s+you|what\s+can\s+you\s+do|'
    r'are\s+you\s+(an?\s+)?ai\b|'
    r'what(\'s|\s+is)\s+your\s+name|who\s+(made|built|created)\s+you|'
    r'what\s+do\s+you\s+think|do\s+you\s+(like|prefer|enjoy|know|think)\b|'
    r'can\s+you\s+(help|tell|explain|describe|suggest|recommend)\b|'
    r'what(\'s|\s+is|\s+are)\s+\w|who(\'s|\s+is|\s+are)\s+\w|'
    r'why\s+(is|are|do|does|did)\s+\w|'
    r'how\s+(does|do|did|can|would|should|is|are)\s+\w|'
    r'when\s+(was|is|did|does)\s+\w|where\s+(is|are|was)\s+\w|'
    r'explain\s+\w|define\s+\w|describe\s+\w|'
    r'ok\b|okay\b|sure\b|alright\b|hmm\b|cool\b|wow\b'
    r')',
    re.IGNORECASE,
)

_COMMAND_KW = frozenset({
    'open', 'close', 'create', 'delete', 'rename', 'search', 'find',
    'alarm', 'remind', 'timer', 'time', 'date', 'battery', 'cpu',
    'ram', 'memory', 'disk', 'launch', 'start', 'run', 'set', 'make',
    'remove', 'navigate', 'go to', 'visit',
})

# Leading filler voice recognition often prepends
_FILLER_RE = re.compile(
    r'^(i\s+said\s+|i\s+mean\s+|actually\s+|so\s+|well\s+|'
    r'rehbar\s+|hey\s+rehbar\s+|ok\s+rehbar\s+)+',
    re.IGNORECASE,
)

def _looks_like_chat(text: str) -> bool:
    t = _FILLER_RE.sub('', text.strip().lower()).strip()
    if _CHAT_RE.match(t):
        return True
    words = t.split()
    if len(words) <= 3 and not any(kw in t for kw in _COMMAND_KW):
        return True
    return False


# ── ML pipeline ────────────────────────────────────────────────────────────────

def _build_pipeline() -> Pipeline:
    """
    TF-IDF (word 1-3gram + char 2-4gram) -> LogisticRegression.

    Two TF-IDF vectorisers in a FeatureUnion:
      word n-grams: capture whole-word patterns ("open chrome", "set alarm")
      char n-grams: capture subword patterns, robust to typos/mishearings

    LogisticRegression with class_weight='balanced' handles the fact that
    some intents have fewer training phrases than others.
    """
    word_tfidf = TfidfVectorizer(
        analyzer='word', ngram_range=(1, 3),
        sublinear_tf=True, min_df=1)
    char_tfidf = TfidfVectorizer(
        analyzer='char_wb', ngram_range=(2, 4),
        sublinear_tf=True, min_df=1)
    clf = LogisticRegression(
        C=5.0, max_iter=1000,
        class_weight='balanced', solver='lbfgs')
    return Pipeline([
        ('features', FeatureUnion([('word', word_tfidf), ('char', char_tfidf)])),
        ('clf', clf),
    ])


# ── Classifier ──────────────────────────────────────────────────────────────────

class IntentClassifier:
    """
    Loads training phrases from the SQLite DB, trains a TF-IDF+LR model,
    caches it to disk, and classifies voice input.

    predict() returns one of:
      system intent string  ->  Java CommandRouter handles it
      "CHAT"                ->  Gemini stub in bridge.py handles it
      "UNKNOWN"             ->  Java returns a gentle nudge
    """

    def __init__(self):
        self._pipeline    = _build_pipeline()
        self._is_trained  = False
        self._db_path     = DB_PATH

    # ── Public API ─────────────────────────────────────────────────────────────

    def train(self, force: bool = False) -> None:
        """Train or load from cache. Never raises — stays alive on any error."""
        try:
            if not force and self._try_load_cache():
                return

            phrases, labels = self._load_db()
            if not phrases:
                print('[Classifier] No training data in DB -- model not trained.')
                return

            dist = Counter(labels)
            min_cls, min_cnt = min(dist.items(), key=lambda x: x[1])
            print(f'[Classifier] Training on {len(phrases)} samples, '
                  f'{len(dist)} classes.  Smallest: {min_cls} ({min_cnt})')

            self._pipeline = _build_pipeline()
            self._pipeline.fit(phrases, labels)
            self._is_trained = True
            self._save_cache(len(phrases))
            print('[Classifier] Training complete.')

        except Exception as exc:
            print('[Classifier] Training failed: ' + repr(str(exc)))
            self._is_trained = False

    def predict(self, text: str) -> str:
        """
        Classify text.  Returns system intent, "CHAT", or "UNKNOWN".

        Pipeline:
          1. Normalise abbreviations
          2. Chat heuristic on normalised text  -> CHAT (skips ML)
          3. ML classifier
               high-confidence system intent    -> return intent
               low confidence + chat heuristic  -> CHAT
               low confidence + not chat        -> UNKNOWN
        """
        text = text.strip().lower()
        norm = _normalise(text)

        if norm != text:
            print(f'[Classifier] Normalised: "{text}" -> "{norm}"')

        # Chat heuristic fires before ML -- fast path for greetings etc.
        if _looks_like_chat(norm):
            print(f'[Classifier] Chat heuristic: "{norm}" -> CHAT')
            return 'CHAT'

        if not self._is_trained:
            return 'UNKNOWN'

        try:
            proba    = self._pipeline.predict_proba([norm])[0]
            max_prob = float(np.max(proba))
            intent   = str(self._pipeline.classes_[int(np.argmax(proba))])

            print(f'[Classifier] "{norm}" -> {intent} ({max_prob:.2f})')

            if max_prob >= CONFIDENCE_THRESHOLD and intent != 'UNKNOWN':
                return intent

            # Low confidence -- try chat heuristic one more time
            if _looks_like_chat(norm):
                return 'CHAT'
            return 'UNKNOWN'

        except Exception as exc:
            print('[Classifier] predict() error: ' + repr(str(exc)))
            return 'UNKNOWN'

    # ── Private helpers ────────────────────────────────────────────────────────

    def _load_db(self):
        try:
            conn   = sqlite3.connect(self._db_path)
            cursor = conn.cursor()
            cursor.execute('SELECT phrase, intent FROM training_data')
            rows   = cursor.fetchall()
            conn.close()
            if not rows:
                return [], []
            # Normalise training phrases so model learns clean patterns
            phrases = [_normalise(r[0].lower()) for r in rows]
            labels  = [r[1] for r in rows]
            return phrases, labels
        except Exception as exc:
            print('[Classifier] DB read error: ' + repr(str(exc)))
            return [], []

    def _try_load_cache(self) -> bool:
        if not (os.path.exists(MODEL_PATH) and os.path.exists(META_PATH)):
            return False
        try:
            with open(META_PATH) as f:
                meta = json.load(f)
            conn   = sqlite3.connect(self._db_path)
            cursor = conn.cursor()
            cursor.execute('SELECT COUNT(*) FROM training_data')
            current = cursor.fetchone()[0]
            conn.close()
            if meta.get('trained_on_rows', -1) != current:
                print(f'[Classifier] DB changed ({current} vs '
                      f'{meta["trained_on_rows"]}) -- retraining.')
                return False
            self._pipeline   = joblib.load(MODEL_PATH)
            self._is_trained = True
            print(f'[Classifier] Loaded cached model '
                  f'({current} samples, {meta.get("trained_at", "?")})')
            return True
        except Exception as exc:
            print('[Classifier] Cache load failed: ' + repr(str(exc)))
            return False

    def _save_cache(self, row_count: int) -> None:
        try:
            joblib.dump(self._pipeline, MODEL_PATH)
            meta = {
                'trained_on_rows': row_count,
                'trained_at': datetime.datetime.now().isoformat(timespec='seconds'),
            }
            with open(META_PATH, 'w') as f:
                json.dump(meta, f, indent=2)
            print(f'[Classifier] Model cached -> {MODEL_PATH}')
        except Exception as exc:
            print('[Classifier] Cache save failed: ' + repr(str(exc)))