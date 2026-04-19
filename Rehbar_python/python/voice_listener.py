import speech_recognition as sr
import socket

class VoiceListener:
    def __init__(self):
        self.recognizer = sr.Recognizer()
        self.recognizer.energy_threshold = 400
        self.recognizer.pause_threshold = 0.8
        self.recognizer.dynamic_energy_threshold = True

    def is_connected(self):
        try:
            socket.create_connection(("8.8.8.8", 53), timeout=2)
            return True
        except OSError:
            return False

    def listen(self):
        with sr.Microphone() as source:
            self.recognizer.adjust_for_ambient_noise(source, duration=1.0)
            print('Listening...')

            try:
                audio = self.recognizer.listen(source, timeout=5, phrase_time_limit=10)

                if self.is_connected():
                    text = self.recognizer.recognize_google(audio)
                    print(f'[Online] Heard: {text}')
                else:
                    # OFFLINE: PocketSphinx (Requires: pip install pocketsphinx)
                    text = self.recognizer.recognize_sphinx(audio)
                    print(f'[Offline] Heard: {text}')

                return text.lower()

            except sr.WaitTimeoutError:
                return None
            except sr.UnknownValueError:
                return None
            except Exception as e:
                print(f"Error in recognition: {e}")
                return None


# ─────────────────────────────────────────────────────────────────────────────
# FIX: Guard the test code with __name__ == '__main__' so that importing
#      this module in bridge.py does NOT accidentally call vc.listen()
#      (which would block the process before the socket server even starts).
# ─────────────────────────────────────────────────────────────────────────────
if __name__ == '__main__':
    vc = VoiceListener()
    print(vc.listen())
