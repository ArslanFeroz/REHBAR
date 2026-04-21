"""
intent_classifier_tighter.py  --  Rehbar tighter intent classifier

Included:
- DB-driven AI mode:
    settings.ai_enabled
    settings.ai_enable_phrase
    settings.ai_disable_phrase
- Strong command-first rules
- Better alarm/time handling
- Better ASR normalization
- Safer UNKNOWN fallback with confidence + margin
- Chat handled cleanly, but command intents still survive

Returned intents can include:
- OPEN_APP, CLOSE_APP, CREATE_FILE, DELETE_FILE, RENAME_FILE
- OPEN_SITE, WEB_SEARCH, SET_ALARM, SYSTEM_INFO
- CHAT, UNKNOWN
- AI_ENABLE, AI_DISABLE
"""

import os
import re
import json
import hashlib
import sqlite3
import datetime
from typing import Dict, Any, Tuple, List

import joblib
from sklearn.linear_model import LogisticRegression
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.pipeline import Pipeline, FeatureUnion

# ── Paths ──────────────────────────────────────────────────────────────────────
_APPDATA = os.getenv('APPDATA', os.path.expanduser('~'))
_RAHBAR_DIR = os.path.join(_APPDATA, 'RAHBAR')
DB_PATH = os.path.join(_RAHBAR_DIR, 'rahbar.db')
MODEL_PATH = os.path.join(_RAHBAR_DIR, 'intent_model_tighter.pkl')
META_PATH = os.path.join(_RAHBAR_DIR, 'intent_model_tighter_meta.json')

os.makedirs(_RAHBAR_DIR, exist_ok=True)

CONFIDENCE_THRESHOLD = 0.58
MARGIN_THRESHOLD = 0.20

# ── Normalization ──────────────────────────────────────────────────────────────
_NORMALISE_RULES = [
    (r'\bhow r u\b', 'how are you'),
    (r'\bhru\b', 'how are you'),
    (r'\bwats up\b', 'what is up'),
    (r'\bwassup\b', 'what is up'),
    (r'\bwhats up\b', 'what is up'),
    (r'\bwanna\b', 'want to'),
    (r'\bgonna\b', 'going to'),
    (r'\bgotta\b', 'got to'),
    (r'\bdunno\b', 'do not know'),
    (r'\byeah\b', 'yes'),
    (r'\byep\b', 'yes'),
    (r'\bnah\b', 'no'),
    (r'\bnope\b', 'no'),
    (r'\bpls\b', 'please'),
    (r'\bplz\b', 'please'),
    (r'\bthx\b', 'thanks'),
    (r'\bty\b', 'thank you'),
    (r'\br\b', 'are'),
    (r'\bu\b', 'you'),
    (r'\bur\b', 'your'),
    (r'\bidk\b', 'i do not know'),
    (r'\bp\.m\.\b', 'pm'),
    (r'\ba\.m\.\b', 'am'),
    (r'\bp\.m\b', 'pm'),
    (r'\ba\.m\b', 'am'),
    (r'\bo clock\b', ''),
    (r'\bcloze\b', 'close'),
    (r'\bchrom\b', 'chrome'),
    (r'\bkrom\b', 'chrome'),
    (r'\bserch\b', 'search'),
    (r'\bwikipidia\b', 'wikipedia'),
    (r'\ballarm\b', 'alarm'),
    (r'\bfoulder\b', 'folder'),
    (r'\bbakup\b', 'backup'),
    (r'\brenaim\b', 'rename'),
    (r'\bdelite\b', 'delete'),
]
_COMPILED_RULES = [(re.compile(pat, re.IGNORECASE), repl) for pat, repl in _NORMALISE_RULES]

def _normalise(text: str) -> str:
    text = (text or '').strip().lower()
    for pat, repl in _COMPILED_RULES:
        text = pat.sub(repl, text)
    text = re.sub(r'\s+', ' ', text).strip()
    return text

# ── Patterns ───────────────────────────────────────────────────────────────────
_CHAT_PATTERNS = [
    re.compile(r'^\s*(hi|hello|hey|good morning|good afternoon|good evening|good night|thanks|thank you|okay|ok|cool|alright)\s*$', re.I),
    re.compile(r'^\s*(how are you|what is your name|who are you|who made you)(\s+rehbar)?\s*$', re.I),
]

_UNSUPPORTED_PATTERNS = [
    re.compile(r'\b(book|reserve)\b.*\b(flight|hotel|ticket)\b', re.I),
    re.compile(r'\bturn on\b.*\b(bluetooth|wifi)\b', re.I),
    re.compile(r'\b(shut down|shutdown|restart)\b.*\b(computer|pc|system)\b', re.I),
]

_TIME_COLON_RE = re.compile(r'\b([01]?\d|2[0-3])[:.]([0-5]\d)\s*(am|pm)?\b', re.I)
_TIME_SHORT_RE = re.compile(r'\b([1-9]|1[0-2])\s*(am|pm)\b', re.I)
_TIME_COMPACT_RE = re.compile(r'\b([0-1]?\d{3}|[0-2]\d{3})\s*(am|pm)\b', re.I)

_SITE_KEYWORDS = (
    'youtube', 'github', 'gmail', 'reddit', 'wikipedia',
    'linkedin', 'google', 'netflix', 'google maps',
    'google docs', 'google sheets'
)

def _contains_time(text: str) -> bool:
    return bool(_TIME_COLON_RE.search(text) or _TIME_SHORT_RE.search(text) or _TIME_COMPACT_RE.search(text))

def _looks_like_chat(text: str) -> bool:
    return any(pat.match(text) for pat in _CHAT_PATTERNS)

# ── Pipeline ───────────────────────────────────────────────────────────────────
def _build_pipeline() -> Pipeline:
    word_tfidf = TfidfVectorizer(
        analyzer='word',
        ngram_range=(1, 3),
        sublinear_tf=True,
        min_df=1
    )
    char_tfidf = TfidfVectorizer(
        analyzer='char_wb',
        ngram_range=(2, 5),
        sublinear_tf=True,
        min_df=1
    )
    clf = LogisticRegression(
        C=4.0,
        max_iter=1200,
        class_weight='balanced',
        solver='lbfgs'
    )
    return Pipeline([
        ('features', FeatureUnion([
            ('word', word_tfidf),
            ('char', char_tfidf),
        ])),
        ('clf', clf),
    ])


class IntentClassifier:
    def __init__(self):
        self._pipeline = _build_pipeline()
        self._is_trained = False
        self._db_path = DB_PATH

    # ── Public API ─────────────────────────────────────────────────────────────
    def train(self, force: bool = False) -> None:
        try:
            self._ensure_settings_defaults()

            if not force and self._try_load_cache():
                return

            phrases, labels = self._load_db()
            if not phrases:
                print('[ClassifierTight] No training data in DB -- model not trained.')
                return

            self._pipeline = _build_pipeline()
            self._pipeline.fit(phrases, labels)
            self._is_trained = True
            self._save_cache()
            print(f'[ClassifierTight] Training on {len(phrases)} samples across {len(set(labels))} classes.')
            print('[ClassifierTight] Training complete.')

        except Exception as exc:
            print('[ClassifierTight] Training failed: ' + repr(str(exc)))
            self._is_trained = False

    def predict(self, text: str) -> str:
        return self.predict_debug(text)['intent']

    def predict_debug(self, text: str) -> Dict[str, Any]:
        raw = text or ''
        norm = _normalise(raw)

        ai_enabled, enable_phrase, disable_phrase = self._load_ai_mode_settings()

        # Mode toggles have highest priority
        if norm == enable_phrase:
            self._set_ai_enabled(True)
            return {
                'raw': raw,
                'normalized': norm,
                'intent': 'AI_ENABLE',
                'route': 'ai_mode_toggle',
                'confidence': 1.0,
                'top2': []
            }

        if norm == disable_phrase:
            self._set_ai_enabled(False)
            return {
                'raw': raw,
                'normalized': norm,
                'intent': 'AI_DISABLE',
                'route': 'ai_mode_toggle',
                'confidence': 1.0,
                'top2': []
            }

        # Command-first rules
        rule_intent = self._rule_intent(norm)

        # In AI mode, keep hard commands as commands, everything else becomes chat
        if ai_enabled:
            if rule_intent in {
                'SET_ALARM', 'OPEN_APP', 'CLOSE_APP',
                'CREATE_FILE', 'DELETE_FILE', 'RENAME_FILE',
                'OPEN_SITE', 'SYSTEM_INFO'
            }:
                return {
                    'raw': raw,
                    'normalized': norm,
                    'intent': rule_intent,
                    'route': 'ai_mode_command_passthrough',
                    'confidence': 0.99,
                    'top2': []
                }
            return {
                'raw': raw,
                'normalized': norm,
                'intent': 'CHAT',
                'route': 'ai_mode_chat',
                'confidence': 1.0,
                'top2': []
            }

        # Outside AI mode, clean chat heuristic comes before generic ML
        if _looks_like_chat(norm):
            return {
                'raw': raw,
                'normalized': norm,
                'intent': 'CHAT',
                'route': 'chat_heuristic',
                'confidence': 0.95,
                'top2': []
            }

        if rule_intent:
            return {
                'raw': raw,
                'normalized': norm,
                'intent': rule_intent,
                'route': 'rule',
                'confidence': 0.99,
                'top2': []
            }

        if not self._is_trained:
            return {
                'raw': raw,
                'normalized': norm,
                'intent': 'UNKNOWN',
                'route': 'untrained',
                'confidence': 0.0,
                'top2': []
            }

        try:
            proba = self._pipeline.predict_proba([norm])[0]
            classes = list(self._pipeline.classes_)
            pairs = sorted(zip(classes, proba), key=lambda x: x[1], reverse=True)

            best_intent, best_prob = pairs[0]
            second_prob = pairs[1][1] if len(pairs) > 1 else 0.0
            margin = best_prob - second_prob
            top2 = [{'intent': intent, 'prob': float(prob)} for intent, prob in pairs[:2]]

            if best_prob >= CONFIDENCE_THRESHOLD and margin >= MARGIN_THRESHOLD:
                return {
                    'raw': raw,
                    'normalized': norm,
                    'intent': str(best_intent),
                    'route': 'ml',
                    'confidence': float(best_prob),
                    'top2': top2
                }

            return {
                'raw': raw,
                'normalized': norm,
                'intent': 'UNKNOWN',
                'route': 'ml_low_conf_unknown',
                'confidence': float(best_prob),
                'top2': top2
            }

        except Exception as exc:
            print('[ClassifierTight] predict() error: ' + repr(str(exc)))
            return {
                'raw': raw,
                'normalized': norm,
                'intent': 'UNKNOWN',
                'route': 'predict_error',
                'confidence': 0.0,
                'top2': []
            }

    # ── Rules ──────────────────────────────────────────────────────────────────
    def _rule_intent(self, text: str) -> str | None:
        for pat in _UNSUPPORTED_PATTERNS:
            if pat.search(text):
                return 'UNKNOWN'

        # Alarm/time rules first
        if any(x in text for x in ('alarm', 'timer', 'remind', 'wake me')):
            return 'SET_ALARM'

        if _contains_time(text):
            if text.startswith('set alarm') or text.startswith('alarm for'):
                return 'SET_ALARM'
            if text.startswith('wake me') or text.startswith('remind me'):
                return 'SET_ALARM'
            if ('set ' in text or text.startswith('set')) and any(x in text for x in ('alarm', 'timer', 'reminder')):
                return 'SET_ALARM'

        # System info rules
        if text in ('what time is it', 'tell me the time', 'check the time'):
            return 'SYSTEM_INFO'
        if any(x in text for x in (
                'battery', 'cpu', 'ram', 'memory usage', 'disk space',
                'what is the date', 'what day is it', 'system information',
                'running processes', 'storage'
        )):
            return 'SYSTEM_INFO'

        # File rules
        if re.search(r'\b(rename|change name|relabel|update the file name)\b', text):
            return 'RENAME_FILE'
        if re.search(r'\b(delete|remove|erase|trash|wipe|get rid of)\b', text):
            return 'DELETE_FILE'
        if re.search(r'\b(create|make|generate|new folder|new file)\b', text):
            return 'CREATE_FILE'

        # Site / app rules
        if re.search(r'\b(go to|visit|navigate to|browse to|take me to)\b', text):
            return 'OPEN_SITE'

        if re.search(r'\b(open|launch|start|run|bring up|fire up)\b', text):
            if any(site_kw in text for site_kw in _SITE_KEYWORDS):
                return 'OPEN_SITE'
            return 'OPEN_APP'

        if re.search(r'\b(close|quit|exit|stop|terminate|kill)\b', text):
            return 'CLOSE_APP'

        # Search rules after chat/system/alarm checks
        if re.search(r'\b(search for|look up|find information about|google|search the web for)\b', text):
            return 'WEB_SEARCH'
        if re.search(r'\b(where is|who is|how to)\b', text):
            return 'WEB_SEARCH'
        if text.startswith('what is ') and 'your name' not in text:
            return 'WEB_SEARCH'

        return None

    # ── DB helpers ─────────────────────────────────────────────────────────────
    def _ensure_settings_defaults(self) -> None:
        conn = sqlite3.connect(self._db_path)
        try:
            cur = conn.cursor()
            cur.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)")
            defaults = {
                'ai_enabled': 'false',
                'ai_enable_phrase': 'enable ai',
                'ai_disable_phrase': 'disable ai',
            }
            for k, v in defaults.items():
                cur.execute("INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)", (k, v))
            conn.commit()
        finally:
            conn.close()

    def _load_ai_mode_settings(self) -> Tuple[bool, str, str]:
        self._ensure_settings_defaults()
        conn = sqlite3.connect(self._db_path)
        try:
            cur = conn.cursor()
            cur.execute("SELECT key, value FROM settings WHERE key IN ('ai_enabled', 'ai_enable_phrase', 'ai_disable_phrase')")
            rows = dict(cur.fetchall())
            ai_enabled = rows.get('ai_enabled', 'false').strip().lower() == 'true'
            enable_phrase = _normalise(rows.get('ai_enable_phrase', 'enable ai'))
            disable_phrase = _normalise(rows.get('ai_disable_phrase', 'disable ai'))
            return ai_enabled, enable_phrase, disable_phrase
        finally:
            conn.close()

    def _set_ai_enabled(self, enabled: bool) -> None:
        self._ensure_settings_defaults()
        conn = sqlite3.connect(self._db_path)
        try:
            cur = conn.cursor()
            cur.execute(
                "INSERT INTO settings (key, value) VALUES ('ai_enabled', ?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                ('true' if enabled else 'false',)
            )
            conn.commit()
        finally:
            conn.close()

    def _load_db(self) -> Tuple[List[str], List[str]]:
        conn = sqlite3.connect(self._db_path)
        try:
            cur = conn.cursor()
            cur.execute("""
                        SELECT phrase, intent
                        FROM training_data
                        WHERE intent NOT IN ('UNKNOWN', 'CHAT', 'AI_ENABLE', 'AI_DISABLE')
                        """)
            rows = cur.fetchall()
            phrases = [_normalise(p) for p, _ in rows]
            labels = [intent for _, intent in rows]
            return phrases, labels
        finally:
            conn.close()

    def _db_fingerprint(self) -> str:
        conn = sqlite3.connect(self._db_path)
        try:
            cur = conn.cursor()
            cur.execute("SELECT phrase, intent FROM training_data ORDER BY intent, phrase")
            rows = cur.fetchall()
            cur.execute("SELECT key, value FROM settings WHERE key IN ('ai_enable_phrase', 'ai_disable_phrase') ORDER BY key")
            settings = cur.fetchall()
            payload = json.dumps({'rows': rows, 'settings': settings}, ensure_ascii=False)
            return hashlib.sha256(payload.encode('utf-8')).hexdigest()
        finally:
            conn.close()

    def _try_load_cache(self) -> bool:
        if not (os.path.exists(MODEL_PATH) and os.path.exists(META_PATH)):
            return False
        try:
            with open(META_PATH, 'r', encoding='utf-8') as f:
                meta = json.load(f)
            if meta.get('db_fingerprint') != self._db_fingerprint():
                return False
            self._pipeline = joblib.load(MODEL_PATH)
            self._is_trained = True
            print(f"[ClassifierTight] Loaded cached model ({meta.get('trained_at', '?')})")
            return True
        except Exception as exc:
            print('[ClassifierTight] Cache load failed: ' + repr(str(exc)))
            return False

    def _save_cache(self) -> None:
        try:
            joblib.dump(self._pipeline, MODEL_PATH)
            meta = {
                'trained_at': datetime.datetime.now().isoformat(timespec='seconds'),
                'db_fingerprint': self._db_fingerprint(),
            }
            with open(META_PATH, 'w', encoding='utf-8') as f:
                json.dump(meta, f, indent=2)
            print(f'[ClassifierTight] Model cached -> {MODEL_PATH}')
        except Exception as exc:
            print('[ClassifierTight] Cache save failed: ' + repr(str(exc)))


if __name__ == '__main__':
    clf = IntentClassifier()
    clf.train(force=True)

    tests = [
        'set alarm for 1:37 pm',
        'set alarm for 137 p.m.',
        'wake me at 6 pm',
        'enable ai',
        'what is the capital of japan',
        'open chrome',
        'disable ai',
        'how are you rehbar',
        'look up wikipedia',
        'close chrome',
    ]

    for t in tests:
        print('-' * 72)
        print(clf.predict_debug(t))
