"""
voice_listener_final.py  --  Rehbar voice listener final

Minimal-change listener based on your original architecture.

Main goals:
- fix the "deaf after TTS" problem by calibrating once, not every listen()
- keep your original synchronous flow so it remains easy to integrate
- small tuning for lower latency
"""

import os
import re
import socket
import sqlite3
import threading
import time
import speech_recognition as sr


def _db_get(key: str, default: str) -> str:
    try:
        appdata = os.getenv('APPDATA', os.path.expanduser('~'))
        db_path = os.path.join(appdata, 'RAHBAR', 'rahbar.db')
        conn = sqlite3.connect(db_path)
        cur = conn.cursor()
        cur.execute("SELECT value FROM settings WHERE key=?", (key,))
        row = cur.fetchone()
        conn.close()
        return row[0] if row else default
    except Exception:
        return default

# ── Connectivity cache ─────────────────────────────────────────────────────────
_online = False
_online_lock = threading.Lock()

def _check_conn() -> bool:
    targets = [
        ('www.google.com', 443),
        ('8.8.8.8', 53),
    ]
    for host, port in targets:
        try:
            with socket.create_connection((host, port), timeout=2):
                return True
        except OSError:
            continue
    return False

def _conn_monitor():
    global _online
    while True:
        with _online_lock:
            _online = _check_conn()
        time.sleep(30)

_online = _check_conn()
threading.Thread(target=_conn_monitor, daemon=True, name='VoiceConnMon').start()

def _is_online() -> bool:
    with _online_lock:
        return _online


# ── Vosk offline fallback ──────────────────────────────────────────────────────
VOSK_AVAILABLE = False
try:
    import vosk
    import json as _json
    _APPDATA = os.getenv('APPDATA', os.path.expanduser('~'))
    _VOSK_PATH = os.path.join(_APPDATA, 'RAHBAR', 'vosk-model-small-en-us-0.15')
    if os.path.isdir(_VOSK_PATH):
        vosk.SetLogLevel(-1)
        _VOSK_MODEL = vosk.Model(_VOSK_PATH)
        VOSK_AVAILABLE = True
        print('[VoiceFinal] Vosk offline model loaded.')
    else:
        print('[VoiceFinal] Vosk model not found (offline fallback disabled).')
        print(f'[VoiceFinal] Expected: {_VOSK_PATH}')
except ImportError:
    print('[VoiceFinal] vosk not installed -- offline fallback disabled.')

# ── Wake-word stripping ────────────────────────────────────────────────────────
_WAKE_START = re.compile(
    r'^\s*(rehbar|rabar|rebar|ribar|raybar|ray\s+bar|re\s+bar'
    r'|hey\s+rehbar|ok\s+rehbar|okay\s+rehbar)\s*',
    re.IGNORECASE,
)
_WAKE_END = re.compile(
    r'\s*(rehbar|rabar|rebar|ribar|raybar)\s*$',
    re.IGNORECASE,
)

def _clean(text: str) -> str:
    text = _WAKE_START.sub('', text).strip()
    text = _WAKE_END.sub('', text).strip()
    return text


class VoiceListener:
    def __init__(self):
        self.recognizer = sr.Recognizer()
        self.microphone = sr.Microphone()
        self._apply_settings()

        with self.microphone as source:
            print('[VoiceFinal] Calibrating once...')
            self.recognizer.adjust_for_ambient_noise(source, duration=0.25)
            print(f'[VoiceFinal] Ready. Energy threshold={self.recognizer.energy_threshold:.1f}')

    def _apply_settings(self):
        """Apply settings from DB (or use hardcoded defaults)."""
        try:
            sensitivity = float(_db_get('voice_sensitivity', '280'))
            pause = float(_db_get('voice_pause', '0.45'))
        except ValueError:
            sensitivity, pause = 280.0, 0.45
        self.recognizer.energy_threshold = sensitivity
        self.recognizer.dynamic_energy_threshold = True
        self.recognizer.pause_threshold = pause
        self.recognizer.non_speaking_duration = 0.20
        self.recognizer.phrase_threshold = 0.20
        print(f'[VoiceFinal] Settings applied: sensitivity={sensitivity}, pause={pause}')

    def reload_settings(self):
        """Re-read settings from DB and apply. Called by /settings/reload."""
        self._apply_settings()
        print('[VoiceFinal] Settings reloaded.')

    def listen(self):
        with self.microphone as source:
            print('Listening...')
            try:
                audio = self.recognizer.listen(
                    source,
                    timeout=5,
                    phrase_time_limit=4.5
                )

                if _is_online():
                    text = self.recognizer.recognize_google(audio)
                    print(f'[Online] Heard: {text}')
                elif VOSK_AVAILABLE:
                    text = self._vosk(audio)
                    print(f'[Vosk] Heard: {text}')
                else:
                    print('[VoiceFinal] No internet and no Vosk model.')
                    return None

                cleaned = _clean(text.lower())
                if cleaned != text.lower():
                    print(f'[VoiceFinal] Cleaned: "{text}" -> "{cleaned}"')
                return cleaned if cleaned else None

            except sr.WaitTimeoutError:
                return None
            except sr.UnknownValueError:
                return None
            except Exception as e:
                print(f'[VoiceFinal] Error: {e}')
                return None

    def _vosk(self, audio: sr.AudioData) -> str:
        raw = audio.get_raw_data(convert_rate=16000, convert_width=2)
        rec = vosk.KaldiRecognizer(_VOSK_MODEL, 16000)
        return _json.loads(rec.FinalResult()).get('text', '')


if __name__ == '__main__':
    vl = VoiceListener()
    while True:
        print(vl.listen())
