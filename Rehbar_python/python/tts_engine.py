"""
tts_engine.py  --  Rehbar TTS Engine

Fixes vs original:
  1. Absolute temp path  -- speech_temp.mp3 was relative; pygame couldn't
     find it when the working directory wasn't exactly right.
     Fix: tempfile.gettempdir() always gives an absolute writable path.

  2. pyttsx3 per-thread init  -- init() was called on the main thread but
     runAndWait() was called on the tts_loop daemon thread. On Windows,
     SAPI5 COM objects are apartment-threaded and CANNOT cross threads.
     This caused completely silent failures.
     Fix: init pyttsx3 inside _speak_offline() on the calling thread.

  3. Persistent asyncio loop  -- asyncio.run() creates+destroys a loop each
     call. edge_tts uses aiohttp which caches connectors tied to the loop;
     on the second call it raises 'Event loop is closed'.
     Fix: one persistent loop, reused via loop.run_until_complete().

  4. Connectivity cached  -- is_connected() socket call removed from the
     hot path. Checked once at init, refreshed every 30s in background.
"""

import asyncio
import os
import socket
import tempfile
import threading
import time

import pygame
import edge_tts

VOICE    = 'en-US-EmmaNeural'
_TMP_MP3 = os.path.join(tempfile.gettempdir(), 'rehbar_speech.mp3')

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
        time.sleep(30)
        with _online_lock:
            _online = _check_conn()

def _is_online() -> bool:
    with _online_lock:
        return _online


class TTSEngine:

    def __init__(self):
        if not pygame.mixer.get_init():
            pygame.mixer.init()

        # Persistent event loop for edge-tts -- avoids aiohttp 'loop closed' errors
        self._loop = asyncio.new_event_loop()

        global _online
        _online = _check_conn()
        threading.Thread(
            target=_conn_monitor, daemon=True, name='TTSConnMon').start()

        print(f'[TTS] Ready. Voice={VOICE!r}, online={_online}')

    def speak(self, text: str) -> None:
        if not text or not text.strip():
            return
        print(f'[TTS] Speaking: {text[:80]}{"..." if len(text) > 80 else ""}')

        if _is_online():
            try:
                self._speak_online(text)
                return
            except Exception as e:
                print(f'[TTS] Online failed ({e}), falling back.')

        self._speak_offline(text)

    def _speak_online(self, text: str) -> None:
        if os.path.exists(_TMP_MP3):
            try: os.remove(_TMP_MP3)
            except Exception: pass

        self._loop.run_until_complete(self._fetch(text, _TMP_MP3))

        if not os.path.exists(_TMP_MP3):
            raise RuntimeError(f'TTS file not created at {_TMP_MP3}')

        pygame.mixer.music.load(_TMP_MP3)
        pygame.mixer.music.play()
        while pygame.mixer.music.get_busy():
            time.sleep(0.05)
        pygame.mixer.music.unload()
        try: os.remove(_TMP_MP3)
        except Exception: pass

    @staticmethod
    async def _fetch(text: str, path: str) -> None:
        communicator = edge_tts.Communicate(text, VOICE)
        with open(path, 'wb') as f:
            async for chunk in communicator.stream():
                if chunk['type'] == 'audio':
                    f.write(chunk['data'])

    def _speak_offline(self, text: str) -> None:
        """Init pyttsx3 HERE on the calling thread (Windows COM requirement)."""
        try:
            import pyttsx3
            engine = pyttsx3.init()
            engine.setProperty('rate', 160)
            engine.say(text)
            engine.runAndWait()
            engine.stop()
        except Exception as e:
            print(f'[TTS] Offline TTS failed: {e}')