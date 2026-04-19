import socket
import json
import sys
from voice_listener import VoiceListener
from tts_engine import TTSEngine
from intent_classifier import IntentClassifier

HOST = 'localhost'
PORT = 9999

# How long (seconds) to wait for Java's response before giving up
RESPONSE_TIMEOUT = 15

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    # Allow the port to be reused immediately after restart
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        tts = TTSEngine()
        listener = VoiceListener()
        classifier = IntentClassifier()
        classifier.train()

        server.bind((HOST, PORT))
        server.listen(1)
        print(f'Python bridge ready on {PORT}, waiting for Java...')

        conn, addr = server.accept()
        print(f'Java connected from {addr}')

        # ─────────────────────────────────────────────────────────────
        # FIX 1: Use SEPARATE reader / writer objects, not 'rw' mode.
        #         'rw' mode on socket.makefile() is NOT officially
        #         supported and causes buffering deadlocks on Windows.
        # ─────────────────────────────────────────────────────────────
        reader = conn.makefile(mode='r', encoding='utf-8')
        writer = conn.makefile(mode='w', encoding='utf-8')

        # ─────────────────────────────────────────────────────────────
        # FIX 2: Set a read timeout so we never block forever waiting
        #         for Java's response (prevents the "no response" hang).
        # ─────────────────────────────────────────────────────────────
        conn.settimeout(RESPONSE_TIMEOUT)

        try:
            while True:
                # 1. Listen to user voice (blocking call inside VoiceListener)
                text = listener.listen()

                if not text:
                    # Nothing heard — loop back and listen again
                    continue

                # 2. Classify the intent
                intent = classifier.predict(text)

                # 3. Serialize and send with a newline terminator
                payload = json.dumps({'text': text, 'intent': intent})
                writer.write(payload + '\n')
                writer.flush()          # Force the data out immediately
                print(f"Sent to Java: {payload}")

                # 4. Wait for Java's response (one line, with timeout)
                try:
                    response = reader.readline()
                except socket.timeout:
                    print("⚠️  Timed out waiting for Java response. Continuing...")
                    continue

                if not response:
                    print("⚠️  Java closed the connection.")
                    break

                response_text = response.strip()

                # Ignore the SHUTDOWN sentinel — just exit cleanly
                if response_text == "SHUTDOWN":
                    print("Java sent SHUTDOWN. Exiting.")
                    break

                print(f"Received from Java: {response_text}")

                # 5. Speak the response
                tts.speak(response_text)

        finally:
            reader.close()
            writer.close()
            conn.close()

    except Exception as e:
        print(f"Python Bridge Error: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
    finally:
        server.close()
        print("Server socket closed.")

if __name__ == '__main__':
    main()
