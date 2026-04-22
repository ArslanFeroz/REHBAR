"""
tts_engine_fixed.py  --  Rehbar TTS Engine (diagnostic + more reliable online mode)

Main fixes:
1. Connectivity check no longer depends only on raw socket to 8.8.8.8:53
   - that can fail on some networks/firewalls even when normal internet works
2. Better error logging so you can see WHY it fell back
3. Event loop is created and used safely with a lock
4. Temporary mp3 filename is unique per call
5. edge-tts failures are separated from playback failures
"""

import asyncio
import contextlib
import os
import sqlite3
import tempfile
import threading
import time
import uuid
from pathlib import Path

import pygame
import edge_tts

VOICE = 'en-GB-ThomasNeural'

# Runtime-adjustable settings (updated by reload_settings())
_tts_rate = 160
_tts_volume = 0.9  # 0.0–1.0


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

_online = False
_online_lock = threading.Lock()


def _check_conn() -> bool:
    """
    More practical connectivity test for edge-tts:
    try opening a TCP connection to Microsoft's Edge TTS host.
    """
    import socket
    targets = [
        ('api.msedgeservices.com', 443),
        ('www.microsoft.com', 443),
        ('8.8.8.8', 53),
    ]

    for host, port in targets:
        try:
            with socket.create_connection((host, port), timeout=3):
                return True
        except OSError:
            continue
    return False


def _conn_monitor():
    global _online
    while True:
        try:
            status = _check_conn()
            with _online_lock:
                _online = status
        except Exception as e:
            print(f'[TTS] Connectivity monitor error: {e}')
        time.sleep(15)


def _is_online() -> bool:
    with _online_lock:
        return _online


class TTSEngine:
    def __init__(self):
        if not pygame.mixer.get_init():
            pygame.mixer.init()

        self._loop = asyncio.new_event_loop()
        self._loop_lock = threading.Lock()

        global _online
        _online = _check_conn()
        threading.Thread(target=_conn_monitor, daemon=True, name='TTSConnMon').start()

        print(f'[TTS] Ready. Voice={VOICE!r}, online={_online}')

    def speak(self, text: str) -> None:
        if not text or not text.strip():
            return

        text = text.strip()
        print(f'[TTS] Speaking: {text[:80]}{"..." if len(text) > 80 else ""}')
        print(f'[TTS] Connectivity status before speak: {_is_online()}')

        if _is_online():
            try:
                self._speak_online(text)
                print('[TTS] Online speech succeeded.')
                return
            except Exception as e:
                print(f'[TTS] Online failed: {type(e).__name__}: {e}')
                print('[TTS] Falling back to offline.')

        self._speak_offline(text)

    def _speak_online(self, text: str) -> None:
        tmp_path = Path(tempfile.gettempdir()) / f'rehbar_tts_{uuid.uuid4().hex}.mp3'

        try:
            with self._loop_lock:
                self._loop.run_until_complete(self._fetch(text, str(tmp_path)))

            if not tmp_path.exists():
                raise RuntimeError(f'TTS file not created at {tmp_path}')

            if tmp_path.stat().st_size == 0:
                raise RuntimeError(f'TTS file is empty at {tmp_path}')

            try:
                pygame.mixer.music.load(str(tmp_path))
            except Exception as e:
                raise RuntimeError(f'pygame load failed for {tmp_path}: {e}') from e

            pygame.mixer.music.play()
            while pygame.mixer.music.get_busy():
                time.sleep(0.05)

            with contextlib.suppress(Exception):
                pygame.mixer.music.unload()

        finally:
            with contextlib.suppress(Exception):
                if tmp_path.exists():
                    tmp_path.unlink()

    @staticmethod
    async def _fetch(text: str, path: str) -> None:
        print(f'[TTS] Fetching online audio -> {path}')
        communicator = edge_tts.Communicate(text, VOICE)

        wrote_audio = False
        with open(path, 'wb') as f:
            async for chunk in communicator.stream():
                chunk_type = chunk.get('type')
                if chunk_type == 'audio':
                    data = chunk.get('data', b'')
                    if data:
                        f.write(data)
                        wrote_audio = True
                elif chunk_type == 'WordBoundary':
                    continue

        if not wrote_audio:
            raise RuntimeError('edge-tts stream returned no audio data')

    def reload_settings(self):
        """Re-read rate/volume from DB. Called by /settings/reload."""
        global _tts_rate, _tts_volume
        try:
            _tts_rate = int(_db_get('voice_speed', '160'))
        except ValueError:
            _tts_rate = 160
        try:
            vol_pct = int(_db_get('voice_volume', '90'))
            _tts_volume = max(0.0, min(1.0, vol_pct / 100.0))
        except ValueError:
            _tts_volume = 0.9
        print(f'[TTS] Settings reloaded: rate={_tts_rate}, volume={_tts_volume:.2f}')

    def _speak_offline(self, text: str) -> None:
        try:
            import pyttsx3

            engine = pyttsx3.init()
            engine.setProperty('rate', _tts_rate)
            engine.setProperty('volume', _tts_volume)
            engine.say(text)
            engine.runAndWait()
            engine.stop()
            print('[TTS] Offline speech succeeded.')
        except Exception as e:
            print(f'[TTS] Offline TTS failed: {type(e).__name__}: {e}')

    def close(self) -> None:
        try:
            with self._loop_lock:
                self._loop.close()
        except Exception as e:
            print(f'[TTS] Loop close error: {e}')


if __name__ == '__main__':
    tts = TTSEngine()
    tts.speak("Hello. This is a test of the online text to speech engine.")
