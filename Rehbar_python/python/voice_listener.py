"""
voice_listener.py  --  Rehbar Voice Listener

Fixes vs original:
  - adjust_for_ambient_noise duration: 1.0s -> 0.3s  (saves 700ms per command)
  - pause_threshold: 0.8 -> 0.6  (saves 200ms end-of-speech wait)
  - phrase_time_limit: 10 -> 6   (reduces worst-case window)
  - is_connected() cached every 30s in background  (no 2s socket call per listen)
  - Vosk offline fallback replaces PocketSphinx  (much better accuracy)
  - Wake-word stripping on returned text
"""

import os
import re
import socket
import threading
import time
import speech_recognition as sr

# ── Connectivity cache ─────────────────────────────────────────────────────────
_online      = False
_online_lock = threading.Lock()

def _check_conn() -> bool:
    try:
        socket.create_connection(('8.8.8.8', 53), timeout=2)
        return True
    except OSError:
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
    _APPDATA   = os.getenv('APPDATA', os.path.expanduser('~'))
    _VOSK_PATH = os.path.join(_APPDATA, 'RAHBAR', 'vosk-model-small-en-us-0.15')
    if os.path.isdir(_VOSK_PATH):
        vosk.SetLogLevel(-1)
        _VOSK_MODEL    = vosk.Model(_VOSK_PATH)
        VOSK_AVAILABLE = True
        print('[VoiceListener] Vosk offline model loaded.')
    else:
        print('[VoiceListener] Vosk model not found (offline fallback disabled).')
        print(f'[VoiceListener] Expected: {_VOSK_PATH}')
except ImportError:
    print('[VoiceListener] vosk not installed -- offline fallback disabled.')


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


# ── VoiceListener ──────────────────────────────────────────────────────────────

class VoiceListener:

    def __init__(self):
        self.recognizer = sr.Recognizer()
        self.recognizer.energy_threshold         = 400
        self.recognizer.dynamic_energy_threshold = True
        self.recognizer.pause_threshold          = 0.6   # was 0.8

    def listen(self):
        with sr.Microphone() as source:
            self.recognizer.adjust_for_ambient_noise(source, duration=0.3)  # was 1.0
            print('Listening...')
            try:
                audio = self.recognizer.listen(
                    source, timeout=5, phrase_time_limit=6)   # was 10

                if _is_online():
                    text = self.recognizer.recognize_google(audio)
                    print(f'[Online] Heard: {text}')
                elif VOSK_AVAILABLE:
                    text = self._vosk(audio)
                    print(f'[Vosk] Heard: {text}')
                else:
                    print('[VoiceListener] No internet and no Vosk model.')
                    return None

                cleaned = _clean(text.lower())
                if cleaned != text.lower():
                    print(f'[VoiceListener] Cleaned: "{text}" -> "{cleaned}"')
                return cleaned if cleaned else None

            except sr.WaitTimeoutError:
                return None
            except sr.UnknownValueError:
                return None
            except Exception as e:
                print(f'[VoiceListener] Error: {e}')
                return None

    def _vosk(self, audio: sr.AudioData) -> str:
        raw  = audio.get_raw_data(convert_rate=16000, convert_width=2)
        rec  = vosk.KaldiRecognizer(_VOSK_MODEL, 16000)
        rec.AcceptWaveform(raw)
        return _json.loads(rec.FinalResult()).get('text', '')


if __name__ == '__main__':

    vl = VoiceListener()
    print(vl.listen())