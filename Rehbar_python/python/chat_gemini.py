"""
chat_gemini.py  --  Gemini chat backend for Rehbar AI mode

Lookup order for API key:
1. config.json in the project directory (same folder as this file)
   Example:
   {
     "gemini_api_key": "YOUR_KEY_HERE"
   }

2. Environment variables:
   - GEMINI_API_KEY
   - GOOGLE_API_KEY

Reads configuration from SQLite settings table:
- ai_model
- ai_system_prompt
- ai_max_output_tokens
- ai_temperature

Requires:
    pip install google-genai
"""

import json
import os
import sqlite3
from pathlib import Path
from typing import Dict, Any

from google import genai
from google.genai import types

_APPDATA = os.getenv('APPDATA', os.path.expanduser('~'))
_RAHBAR_DIR = os.path.join(_APPDATA, 'RAHBAR')
DB_PATH = os.path.join(_RAHBAR_DIR, 'rahbar.db')

# Project config lives beside this file
PROJECT_DIR = Path(__file__).resolve().parent
CONFIG_PATH = PROJECT_DIR / 'config.json'


class GeminiChat:
    def __init__(self):
        self.api_key = self._load_api_key()
        if not self.api_key:
            raise RuntimeError(
                "Missing Gemini API key. Add it to config.json as "
                '{"gemini_api_key": "..."} or set GEMINI_API_KEY / GOOGLE_API_KEY.'
            )

        self.client = genai.Client(api_key=self.api_key)

    def _load_api_key(self) -> str:
        # 1) Project config file
        try:
            if CONFIG_PATH.exists():
                with open(CONFIG_PATH, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                key = str(data.get('gemini_api_key', '')).strip()
                if key:
                    print(f'[Gemini] Loaded API key from config file: {CONFIG_PATH}')
                    return key
        except Exception as e:
            print(f'[Gemini] Could not read config file {CONFIG_PATH}: {e}')

        # 2) Environment variables
        key = os.getenv('GEMINI_API_KEY', '').strip() or os.getenv('GOOGLE_API_KEY', '').strip()
        if key:
            print('[Gemini] Loaded API key from environment variable.')
            return key

        return ''

    def _load_settings(self) -> Dict[str, Any]:
        defaults = {
            'ai_model': 'gemini-3-flash-preview',
            'ai_system_prompt': (
                "You are Rehbar — a sharp, witty desktop voice assistant with a dry sense of humour, "
                "think J.A.R.V.I.S. but with fewer existential crises. "
                "Be helpful, clever, and occasionally amusing. "
                "Keep every spoken answer to 1–2 natural sentences unless the user explicitly asks for more detail. "
                "Never start with 'Certainly!', 'Of course!', or 'Sure!' — just answer directly. "
                "You may drop a light quip if the moment calls for it, but never at the expense of being useful."
            ),
            'ai_max_output_tokens': '220',
            'ai_temperature': '0.6',
        }

        conn = sqlite3.connect(DB_PATH)
        try:
            cur = conn.cursor()
            cur.execute(
                "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)"
            )
            for k, v in defaults.items():
                cur.execute(
                    "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)", (k, v)
                )
            conn.commit()

            cur.execute(
                "SELECT key, value FROM settings WHERE key IN "
                "('ai_model', 'ai_system_prompt', 'ai_max_output_tokens', 'ai_temperature')"
            )
            rows = dict(cur.fetchall())
            for k, v in defaults.items():
                rows.setdefault(k, v)
            return rows
        finally:
            conn.close()

    def reply(self, user_text: str) -> str:
        settings = self._load_settings()
        model = settings['ai_model'].strip() or 'gemini-3-flash-preview'
        system_prompt = settings['ai_system_prompt'].strip()
        max_tokens = int((settings.get('ai_max_output_tokens') or '220').strip())
        temperature = float((settings.get('ai_temperature') or '0.6').strip())

        response = self.client.models.generate_content(
            model=model,
            contents=user_text,
            config=types.GenerateContentConfig(
                system_instruction=system_prompt,
                max_output_tokens=max_tokens,
                temperature=temperature,
            ),
        )

        text = (response.text or '').strip()
        if not text:
            return "I could not think of a response just now."
        return text


if __name__ == '__main__':
    # Small startup test
    bot = GeminiChat()
    print(bot.reply("Say hello in one short sentence."))
