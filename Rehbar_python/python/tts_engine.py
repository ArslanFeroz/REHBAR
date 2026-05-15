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
import io
import os
import sqlite3
import tempfile
import threading
import time
import uuid
from pathlib import Path

import pygame
import edge_tts

# Default voice
VOICE = 'en-GB-SoniaNeural'
VOICE_FALLBACK = 'en-US-GuyNeural'

_VOICE_MAP = {
    'Microsoft David (Male)':  'en-US-GuyNeural',
    'Microsoft Zira (Female)': 'en-US-JennyNeural',
    'Microsoft Mark (Male)':   'en-US-ChristopherNeural',
}

_tts_rate   = 0
_tts_volume = 0
_tts_voice  = VOICE   # active edge-tts voice name


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
        self._offline_rate   = 175
        self._offline_volume = 0.9

        global _online
        _online = _check_conn()
        threading.Thread(target=_conn_monitor, daemon=True, name='TTSConnMon').start()

        self.reload_settings()  # load voice/rate/volume from DB at startup
        print(f'[TTS] Ready. Voice={_tts_voice!r}, online={_online}')

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
        # Fetch audio directly into a BytesIO buffer — no temp-file round-trip.
        with self._loop_lock:
            audio_bytes = self._loop.run_until_complete(self._fetch_bytes(text))

        if not audio_bytes:
            raise RuntimeError('edge-tts returned no audio data')

        # pygame 2.x can load from a file-like object; give it an mp3 name hint.
        buf = io.BytesIO(audio_bytes)
        buf.name = 'rehbar.mp3'
        try:
            pygame.mixer.music.load(buf)
        except Exception:
            # Fallback: write to disk if pygame can't stream from BytesIO
            tmp_path = Path(tempfile.gettempdir()) / f'rehbar_{uuid.uuid4().hex}.mp3'
            try:
                tmp_path.write_bytes(audio_bytes)
                pygame.mixer.music.load(str(tmp_path))
            except Exception as e:
                with contextlib.suppress(Exception):
                    tmp_path.unlink()
                raise RuntimeError(f'pygame load failed: {e}') from e

        pygame.mixer.music.play()
        while pygame.mixer.music.get_busy():
            time.sleep(0.05)
        with contextlib.suppress(Exception):
            pygame.mixer.music.unload()

    @staticmethod
    async def _fetch_bytes(text: str) -> bytes:
        """Stream edge-tts audio into memory — faster than writing to disk.

        Applies the user-configured rate and volume as edge-tts parameters.
        Rate is passed as e.g. '+5%' (delta from edge-tts default).
        Volume is passed as e.g. '-20%' (delta from edge-tts default).
        """
        print(f'[TTS] Streaming online audio for: {text[:60]}...' if len(text) > 60 else f'[TTS] Streaming: {text}')
        buf = io.BytesIO()
        wrote_audio = False

        # Build rate/volume strings for edge-tts (+X% format)
        rate_str   = f'{_tts_rate:+d}%'    # e.g. '+5%' or '-10%'
        volume_str = f'{_tts_volume:+d}%'  # e.g. '+0%' or '-20%'

        # Try user-selected voice first, then built-in fallbacks
        voices_to_try = [_tts_voice]
        if _tts_voice not in (VOICE, VOICE_FALLBACK):
            voices_to_try += [VOICE, VOICE_FALLBACK]
        else:
            voices_to_try += [VOICE_FALLBACK] if _tts_voice == VOICE else [VOICE]

        for voice in voices_to_try:
            buf.seek(0); buf.truncate()
            try:
                communicate = edge_tts.Communicate(text, voice, rate=rate_str, volume=volume_str)
                async for chunk in communicate.stream():
                    if chunk.get('type') == 'audio':
                        data = chunk.get('data', b'')
                        if data:
                            buf.write(data)
                            wrote_audio = True
                if wrote_audio:
                    print(f'[TTS] Voice={voice!r}, rate={rate_str}, volume={volume_str}')
                    break
            except Exception as e:
                print(f'[TTS] Voice {voice!r} failed: {e}. Trying next...')

        if not wrote_audio:
            raise RuntimeError('All edge-tts voices failed to return audio data')
        return buf.getvalue()

    def reload_settings(self):
        """Re-read voice/rate/volume from DB. Called by /settings/reload and at startup."""
        global _tts_rate, _tts_volume, _tts_voice

        # ── Voice ─────────────────────────────────────────────────────────────
        voice_name = _db_get('voice_name', '')
        if voice_name and voice_name in _VOICE_MAP:
            _tts_voice = _VOICE_MAP[voice_name]
        else:
            _tts_voice = VOICE  # default Sonia neural

        # ── Rate (words per minute → edge-tts delta %) ────────────────────────
        # User sets WPM (100–300), default 175.
        # edge-tts default is roughly 175 wpm → delta = (wpm - 175) / 1.75
        try:
            wpm = int(_db_get('voice_speed', '175'))
            _tts_rate = round((wpm - 175) / 1.75)   # e.g. 210 wpm → +20%
        except ValueError:
            _tts_rate = 0

        # ── Volume (0–100 → edge-tts delta %) ────────────────────────────────
        # User sets 0–100, default 90.
        # edge-tts default is 100 → delta = vol - 100 (so 90 → -10%, 100 → +0%)
        try:
            vol_pct = int(_db_get('voice_volume', '90'))
            _tts_volume = max(-100, min(0, vol_pct - 100))
        except ValueError:
            _tts_volume = -10

        # Also apply to pyttsx3 offline engine (kept as float 0.0–1.0 internally)
        # We store offline_volume separately so _speak_offline can read it
        self._offline_volume = max(0.0, min(1.0, int(_db_get('voice_volume', '90')) / 100.0))
        self._offline_rate   = int(_db_get('voice_speed', '175'))

        print(f'[TTS] Settings reloaded: voice={_tts_voice!r}, rate={_tts_rate:+d}%, volume={_tts_volume:+d}%')

    def _speak_offline(self, text: str) -> None:
        try:
            import pyttsx3

            engine = pyttsx3.init()
            offline_rate   = getattr(self, '_offline_rate',   175)
            offline_volume = getattr(self, '_offline_volume', 0.9)
            engine.setProperty('rate',   offline_rate)
            engine.setProperty('volume', offline_volume)
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
