"""
bridge_gemini.py  --  Rehbar Python bridge with Gemini-backed AI mode

Uses:
- voice_listener.py
- intent_classifier.py
- tts_engine.py
- chat_gemini.py

Behavior:
- AI_ENABLE / AI_DISABLE handled in Python
- CHAT handled by Gemini
- system commands forwarded to Java as before
"""

import os
import sys
import re
import signal
import sqlite3
import threading
import queue
import time

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

from flask import Flask, request, jsonify

from voice_listener import VoiceListener
from tts_engine import TTSEngine
from intent_classifier import IntentClassifier
from chat_gemini import GeminiChat

HOST = '127.0.0.1'
PORT = 5000
COMMAND_TIMEOUT = 8

command_queue: queue.Queue = queue.Queue(maxsize=10)
tts_queue: queue.Queue = queue.Queue(maxsize=20)

_ready = False
_ready_lock = threading.Lock()
PROCESS_PID = os.getpid()

# ── Listen gate: Java controls when we actually capture voice ─────────────────
# Starts CLEAR (not listening). Java calls /listen/start when widget is clicked.
_listen_event = threading.Event()

# Global references so endpoints can update settings at runtime
_listener_ref: 'VoiceListener | None' = None
_tts_ref: 'TTSEngine | None' = None

app = Flask(__name__)

import logging
logging.getLogger('werkzeug').setLevel(logging.WARNING)


def _db_get_setting(key: str, default: str) -> str:
    """Read a single setting from the RAHBAR SQLite DB."""
    try:
        appdata = os.getenv('APPDATA', os.path.expanduser('~'))
        db_path = os.path.join(appdata, 'RAHBAR', 'rahbar.db')
        conn = sqlite3.connect(db_path)
        cur = conn.cursor()
        cur.execute("SELECT value FROM settings WHERE key=?", (key,))
        row = cur.fetchone()
        conn.close()
        return row[0] if row else default
    except Exception as e:
        print(f'[Bridge] DB read error ({key}): {e}')
        return default


def _is_ready() -> bool:
    with _ready_lock:
        return _ready

def _set_ready() -> None:
    global _ready
    with _ready_lock:
        _ready = True


@app.route('/health', methods=['GET'])
def health():
    if _is_ready():
        return jsonify({'status': 'ok', 'pid': PROCESS_PID}), 200
    return jsonify({'status': 'loading', 'pid': PROCESS_PID}), 503


@app.route('/command', methods=['GET'])
def get_command():
    if not _is_ready():
        return ('', 503)
    try:
        cmd = command_queue.get(timeout=COMMAND_TIMEOUT)
        print(f'[Bridge -> Java] {cmd}')
        return jsonify(cmd), 200
    except queue.Empty:
        return ('', 204)


@app.route('/speak', methods=['POST'])
def speak():
    data = request.get_json(silent=True)
    if not data or 'text' not in data:
        return jsonify({'error': 'Missing text field'}), 400
    text = data['text'].strip()
    if text:
        tts_queue.put(text)
    return jsonify({'status': 'queued'}), 200


@app.route('/listen/start', methods=['POST'])
def listen_start():
    _listen_event.set()
    print('[Bridge] Listening ENABLED by Java.')
    return jsonify({'status': 'listening'}), 200


@app.route('/listen/stop', methods=['POST'])
def listen_stop():
    _listen_event.clear()
    print('[Bridge] Listening DISABLED by Java.')
    return jsonify({'status': 'stopped'}), 200


@app.route('/settings/reload', methods=['POST'])
def settings_reload():
    """Reload voice and TTS settings from the DB at runtime."""
    try:
        if _listener_ref is not None:
            _listener_ref.reload_settings()
        if _tts_ref is not None:
            _tts_ref.reload_settings()
        print('[Bridge] Settings reloaded from DB.')
        return jsonify({'status': 'reloaded'}), 200
    except Exception as e:
        print(f'[Bridge] Settings reload error: {e}')
        return jsonify({'error': str(e)}), 500


def _strip_wake_word(text: str) -> str:
    text = re.sub(
        r'^\s*(rehbar|rabar|rebar|ribar|raybar|ray\s+bar|re\s+bar'
        r'|hey\s+rehbar|ok\s+rehbar|okay\s+rehbar)\s*',
        '', text, flags=re.IGNORECASE).strip()
    text = re.sub(
        r'\s*(rehbar|rabar|rebar|ribar|raybar)\s*$',
        '', text, flags=re.IGNORECASE).strip()
    return text


def voice_capture_loop(listener: VoiceListener,
                       classifier: IntentClassifier,
                       chat_backend: GeminiChat) -> None:
    print('[VoiceThread] Started. Waiting for Java to enable listening...')
    while True:
        # Block here when Java has not activated listening (widget not in LISTENING state)
        if not _listen_event.wait(timeout=0.2):
            continue
        try:
            text = listener.listen()
            if not text:
                continue

            cleaned = _strip_wake_word(text)
            if not cleaned:
                continue

            if cleaned != text:
                print(f'[VoiceThread] Wake-word stripped: "{text}" -> "{cleaned}"')

            result = classifier.predict_debug(cleaned)
            intent = result['intent']
            print(f'[VoiceThread] Result: {result}')

            if intent == 'AI_ENABLE':
                try:
                    tts_queue.put_nowait('AI mode enabled.')
                except queue.Full:
                    print('[VoiceThread] WARN: tts_queue full.')
                continue

            if intent == 'AI_DISABLE':
                try:
                    tts_queue.put_nowait('AI mode disabled.')
                except queue.Full:
                    print('[VoiceThread] WARN: tts_queue full.')
                continue

            if intent == 'CHAT':
                try:
                    reply = chat_backend.reply(cleaned)
                except Exception as e:
                    print(f'[CHAT] Gemini error: {type(e).__name__}: {e}')
                    reply = "I could not reach Gemini right now."
                try:
                    tts_queue.put_nowait(reply)
                except queue.Full:
                    print('[VoiceThread] WARN: tts_queue full.')
                continue

            try:
                command_queue.put_nowait({'text': cleaned, 'intent': intent})
            except queue.Full:
                print('[VoiceThread] WARN: command_queue full.')

        except Exception as exc:
            print('[VoiceThread] ERROR: ' + repr(str(exc)))
            time.sleep(0.5)


def tts_loop(tts: TTSEngine) -> None:
    print('[TTSThread] Started.')
    while True:
        try:
            text = tts_queue.get()
            tts.speak(text)
        except Exception as exc:
            print('[TTSThread] ERROR: ' + repr(str(exc)))
            time.sleep(0.5)


def main():
    global _listener_ref, _tts_ref

    print('=' * 54)
    print(f'    Rehbar Python Bridge  --  PID {PROCESS_PID}')
    print('=' * 54)

    # Graceful shutdown on SIGTERM (sent by Java destroyForcibly / taskkill)
    def _handle_sigterm(signum, frame):
        print('[Bridge] SIGTERM received — shutting down.')
        sys.exit(0)
    try:
        signal.signal(signal.SIGTERM, _handle_sigterm)
    except (AttributeError, OSError):
        pass  # Windows may not support all signals

    flask_thread = threading.Thread(
        target=lambda: app.run(
            host=HOST, port=PORT,
            debug=False, use_reloader=False, threaded=True),
        daemon=True, name='FlaskServer')
    flask_thread.start()
    time.sleep(1.0)
    print(f'[Bridge] Flask on http://{HOST}:{PORT}  (returning 503 until ready)')

    print('[Bridge] Loading TTS engine...')
    tts = TTSEngine()
    _tts_ref = tts

    print('[Bridge] Loading voice listener...')
    listener = VoiceListener()
    _listener_ref = listener

    print('[Bridge] Loading intent classifier...')
    classifier = IntentClassifier()
    classifier.train()

    print('[Bridge] Loading Gemini chat backend...')
    chat_backend = GeminiChat()

    threading.Thread(
        target=voice_capture_loop, args=(listener, classifier, chat_backend),
        daemon=True, name='VoiceCapture').start()

    threading.Thread(
        target=tts_loop, args=(tts,),
        daemon=True, name='TTSOutput').start()

    _set_ready()
    print('[Bridge] All systems ready  --  /health -> 200')
    print('[Bridge] Listening PAUSED — waiting for Java widget click.')
    print('=' * 54)

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print('[Bridge] Shutting down.')


if __name__ == '__main__':
    main()
