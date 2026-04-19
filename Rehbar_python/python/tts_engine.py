import pyttsx3
import edge_tts
import asyncio
import socket
import os
import pygame  # Used to play the edge-tts file

class TTSEngine:
    def __init__(self):
        # Initialize the offline backup
        self.offline_engine = pyttsx3.init()
        self.offline_engine.setProperty('rate', 160)

        # Initialize pygame mixer for playing edge-tts audio files
        if not pygame.mixer.get_init():
            pygame.mixer.init()

    def is_connected(self):
        """Check if internet is available."""
        try:
            # Try connecting to Google's DNS
            socket.create_connection(("8.8.8.8", 53), timeout=2)
            return True
        except OSError:
            return False

    async def _speak_online(self, text):
        """Uses Microsoft Edge's Neural voices."""
        voice = "en-US-AvaNeural" # A very natural sounding female voice
        output_file = "speech_temp.mp3"

        communicate = edge_tts.Communicate(text, voice)
        await communicate.save(output_file)

        # Play the file using pygame
        pygame.mixer.music.load(output_file)
        pygame.mixer.music.play()
        while pygame.mixer.music.get_busy():
            await asyncio.sleep(0.1)

        pygame.mixer.music.unload() # Free the file
        if os.path.exists(output_file):
            os.remove(output_file)

    def speak(self, text):
        if not text or not text.strip():
            return

        print(f'Speaking: {text}')

        if self.is_connected():
            try:
                # Run the async edge-tts code
                asyncio.run(self._speak_online(text))
            except Exception as e:
                print(f"Online TTS failed, falling back: {e}")
                self._speak_offline(text)
        else:
            print("No internet detected. Using offline voice.")
            self._speak_offline(text)

    def _speak_offline(self, text):
        """Standard pyttsx3 fallback."""
        self.offline_engine.say(text)
        self.offline_engine.runAndWait()

# Example usage:
# tts = TTSEngine()
# tts.speak("Hello! I am checking my connection before speaking.")