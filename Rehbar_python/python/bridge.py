"""
bridge.py  --  Rehbar Python Bridge  (Flask REST API)

Why Flask instead of raw TCP sockets:
  - HTTP is self-framing; no newline-delimiter bugs or makefile() deadlocks.
  - Java polls /health until ready — no Thread.sleep() timing hacks.
  - /health returns 503 while loading, 200 only when every component is ready.
    Java therefore never enters its command loop before Python is fully up.
  - Each request is independent; a transient error never corrupts the session.

Startup order (critical):
  1. Flask thread starts  -> port 5000 open, /health returns 503
  2. Heavy init on main thread  (TTS, VoiceListener, Classifier)
  3. Worker threads start
  4. _ready = True  -> /health returns 200
  5. Java sees 200 and enters its command loop

Gemini stub:
  The classifier returns intent="CHAT" for conversational inputs.
  handle_chat() is a stub that returns a placeholder — wire Gemini here later.
"""

import os
import sys
import re
import socket
import threading
import queue
import time

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

from flask import Flask, request, jsonify

from voice_listener import VoiceListener
from tts_engine     import TTSEngine
from intent_classifier import IntentClassifier

HOST            = '127.0.0.1'
PORT            = 5000
COMMAND_TIMEOUT = 8   # seconds Flask waits on /command before returning 204

# Only system-command payloads reach this queue; CHAT is handled here.
command_queue: queue.Queue = queue.Queue(maxsize=10)
tts_queue:     queue.Queue = queue.Queue(maxsize=20)

_ready      = False
_ready_lock = threading.Lock()
PROCESS_PID = os.getpid()

app = Flask(__name__)

import logging
logging.getLogger('werkzeug').setLevel(logging.WARNING)


def _is_ready() -> bool:
    with _ready_lock:
        return _ready

def _set_ready() -> None:
    global _ready
    with _ready_lock:
        _ready = True


# ── Endpoints ──────────────────────────────────────────────────────────────────

@app.route('/health', methods=['GET'])
def health():
    """
    503 while loading, 200 when all components are ready.
    Java's waitForHealth() keeps retrying until it gets 200.
    """
    if _is_ready():
        return jsonify({'status': 'ok',      'pid': PROCESS_PID}), 200
    return     jsonify({'status': 'loading', 'pid': PROCESS_PID}), 503


@app.route('/command', methods=['GET'])
def get_command():
    """
    Long-poll: Java calls this in a tight loop.
    Returns the next voice command as JSON, or 204 if nothing arrived
    within COMMAND_TIMEOUT seconds (Java will retry immediately).
    """
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
    """Java POSTs {"text": "..."} here after executing a command."""
    data = request.get_json(silent=True)
    if not data or 'text' not in data:
        return jsonify({'error': 'Missing text field'}), 400
    text = data['text'].strip()
    if text:
        tts_queue.put(text)
    return jsonify({'status': 'queued'}), 200


# ── Gemini stub ────────────────────────────────────────────────────────────────

def handle_chat(text: str) -> str:
    """
    Stub for conversational AI.  Wire Gemini here when ready.
    Returns a placeholder so the app doesn't crash on CHAT intents.
    """
    print(f'[CHAT stub] "{text}" -- Gemini not yet configured.')
    return "I am still learning to chat. Please give me a command for now."


# ── Worker threads ─────────────────────────────────────────────────────────────

def _strip_wake_word(text: str) -> str:
    """Remove 'rehbar' and common mishearings from start/end of recognised text."""
    text = re.sub(
        r'^\s*(rehbar|rabar|rebar|ribar|raybar|ray\s+bar|re\s+bar'
        r'|hey\s+rehbar|ok\s+rehbar|okay\s+rehbar)\s*',
        '', text, flags=re.IGNORECASE).strip()
    text = re.sub(
        r'\s*(rehbar|rabar|rebar|ribar|raybar)\s*$',
        '', text, flags=re.IGNORECASE).strip()
    return text


def voice_capture_loop(listener: VoiceListener,
                       classifier: IntentClassifier) -> None:
    print('[VoiceThread] Started.')
    while True:
        try:
            text = listener.listen()
            if not text:
                continue

            # Strip wake-word before classification
            cleaned = _strip_wake_word(text)
            if not cleaned:
                continue

            if cleaned != text:
                print(f'[VoiceThread] Wake-word stripped: "{text}" -> "{cleaned}"')

            intent = classifier.predict(cleaned)

            # CHAT: handle entirely in Python, never reach Java
            if intent == 'CHAT':
                print(f'[VoiceThread] CHAT: "{cleaned}"')
                reply = handle_chat(cleaned)
                try:
                    tts_queue.put_nowait(reply)
                except queue.Full:
                    print('[VoiceThread] WARN: tts_queue full.')
                continue

            # System command: forward to Java
            try:
                command_queue.put_nowait({'text': cleaned, 'intent': intent})
            except queue.Full:
                print('[VoiceThread] WARN: command_queue full.')

        except Exception as exc:
            print('[VoiceThread] ERROR: ' + repr(str(exc)))
            time.sleep(1)


def tts_loop(tts: TTSEngine) -> None:
    print('[TTSThread] Started.')
    while True:
        try:
            text = tts_queue.get()
            tts.speak(text)
        except Exception as exc:
            print('[TTSThread] ERROR: ' + repr(str(exc)))
            time.sleep(0.5)


# ── Entry point ────────────────────────────────────────────────────────────────

def main():
    print('=' * 54)
    print(f'    Rehbar Python Bridge  --  PID {PROCESS_PID}')
    print('=' * 54)

    # Step 1: Flask starts first so Java can begin polling /health.
    # /health returns 503 until _set_ready() is called below.
    flask_thread = threading.Thread(
        target=lambda: app.run(
            host=HOST, port=PORT,
            debug=False, use_reloader=False, threaded=True),
        daemon=True, name='FlaskServer')
    flask_thread.start()
    time.sleep(1.0)   # give Flask time to bind the port
    print(f'[Bridge] Flask on http://{HOST}:{PORT}  (returning 503 until ready)')

    # Step 2: Heavy init — Java waits on /health during this whole section.
    print('[Bridge] Loading TTS engine...')
    tts = TTSEngine()

    print('[Bridge] Loading voice listener...')
    listener = VoiceListener()

    print('[Bridge] Loading intent classifier...')
    classifier = IntentClassifier()
    classifier.train()

    # Step 3: Start worker threads.
    threading.Thread(
        target=voice_capture_loop, args=(listener, classifier),
        daemon=True, name='VoiceCapture').start()

    threading.Thread(
        target=tts_loop, args=(tts,),
        daemon=True, name='TTSOutput').start()

    # Step 4: Signal Java — /health now returns 200.
    _set_ready()
    print('[Bridge] All systems ready  --  /health -> 200')
    print('=' * 54)

    # Keep main thread alive (all workers are daemon threads).
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print('[Bridge] Shutting down.')


if __name__ == '__main__':
    main()