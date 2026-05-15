import os
import re
import sqlite3
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
            # Default pause_threshold lowered to 0.3s (was 0.45s) — shaves ~150ms
            # off every command by detecting end-of-speech faster.
            pause = float(_db_get('voice_pause', '0.30'))
        except ValueError:
            sensitivity, pause = 280.0, 0.30
        self.recognizer.energy_threshold        = sensitivity
        self.recognizer.dynamic_energy_threshold = True
        self.recognizer.pause_threshold          = pause
        self.recognizer.non_speaking_duration    = 0.15   # was 0.20
        self.recognizer.phrase_threshold         = 0.20
        print(f'[VoiceFinal] Settings applied: sensitivity={sensitivity}, pause={pause}')

    def reload_settings(self):
        """Re-read settings from DB and apply. Called by /settings/reload."""
        self._apply_settings()
        print('[VoiceFinal] Settings reloaded.')

    def listen(self):
        # Cap dynamic threshold: if ambient-noise calibration drove it very high,
        # the mic becomes deaf.  Clamp to a sensible maximum.
        if self.recognizer.dynamic_energy_threshold:
            self.recognizer.energy_threshold = min(
                self.recognizer.energy_threshold, 600)

        with self.microphone as source:
            print('Listening...')
            try:
                audio = self.recognizer.listen(
                    source,
                    timeout=5,
                    phrase_time_limit=5,
                )

                text = self.recognizer.recognize_google(
                    audio, language='en-US')
                print(f'[Online] Heard: {text}')

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

if __name__ == '__main__':
    vl = VoiceListener()
    while True:
        print(vl.listen())
