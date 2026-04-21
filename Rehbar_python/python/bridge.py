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
    print('[VoiceThread] Started.')
    while True:
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
    print('=' * 54)
    print(f'    Rehbar Python Bridge  --  PID {PROCESS_PID}')
    print('=' * 54)

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

    print('[Bridge] Loading voice listener...')
    listener = VoiceListener()

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
    print('=' * 54)

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print('[Bridge] Shutting down.')


if __name__ == '__main__':
    main()
