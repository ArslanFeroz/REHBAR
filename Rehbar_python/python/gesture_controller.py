"""
gesture_controller.py  --  Rehbar Gesture Controller
                            (MediaPipe 0.10+ Tasks API)

Detects 9 hand gestures via webcam and maps each to an action.

──────────────────────────────────────────────────────────────────────
 #   Gesture             Hand Shape            Action
──────────────────────────────────────────────────────────────────────
 1   Open Palm    ✋    All 5 fingers up       Take Screenshot
 2   Closed Fist  ✊    All fingers closed     Toggle Widget Minimize
 3   Thumbs Up    👍    Only thumb up          Volume Up  (+3 steps)
 4   Peace / V    ✌️    Index + middle up      Play / Pause Media
 5   Index Only   ☝️    Only index up          Scroll Up
 6   Pinky Only   🤙    Only pinky up          Scroll Down
 7   Three Fingers 🤟   Index+middle+ring up   Next Track
 8   Four Fingers  🖖   All except thumb up    Previous Track
 9   L-Shape      👉    Thumb + index up       Activate Voice Listening
──────────────────────────────────────────────────────────────────────

Requires:
    pip install opencv-python mediapipe pyautogui pynput

Model file (~7.5 MB) is auto-downloaded from Google on first run to
%APPDATA%/RAHBAR/hand_landmarker.task
"""

import datetime
import os
import threading
import time
import urllib.request
from typing import Optional

# ── Model config ────────────────────────────────────────────────────────────────
_APPDATA     = os.getenv('APPDATA', os.path.expanduser('~'))
_MODEL_DIR   = os.path.join(_APPDATA, 'RAHBAR')
_MODEL_PATH  = os.path.join(_MODEL_DIR, 'hand_landmarker.task')
_MODEL_URL   = (
    'https://storage.googleapis.com/mediapipe-models/'
    'hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task'
)

# ── Gesture tuning ──────────────────────────────────────────────────────────────
CONFIRM_FRAMES   = 7      # consecutive frames before triggering (~0.23s @30fps)
GESTURE_COOLDOWN = 1.8    # seconds before same gesture can re-fire
SCROLL_AMOUNT    = 5      # scroll lines per scroll gesture
VOLUME_STEPS     = 3      # volume key presses per gesture

# Hand landmark connections for manual drawing (MediaPipe 0.10 removed mp_draw)
_HAND_CONNECTIONS = [
    (0,1),(1,2),(2,3),(3,4),          # thumb
    (0,5),(5,6),(6,7),(7,8),          # index
    (0,9),(9,10),(10,11),(11,12),     # middle
    (0,13),(13,14),(14,15),(15,16),   # ring
    (0,17),(17,18),(18,19),(19,20),   # pinky
    (5,9),(9,13),(13,17),             # palm
]

GESTURE_LABELS = {
    'OPEN_PALM':      'Screenshot',
    'FIST':           'Toggle Widget',
    'THUMBS_UP':      'Volume Up',
    'PEACE':          'Play / Pause',
    'INDEX_ONLY':     'Scroll Up',
    'PINKY_ONLY':     'Scroll Down',
    'THREE_FINGERS':  'Next Track',
    'FOUR_FINGERS':   'Previous Track',
    'L_SHAPE':        'Activate Listening',
}


def _ensure_model() -> Optional[str]:
    """Download the hand landmark model if not already cached. Returns path or None."""
    if os.path.exists(_MODEL_PATH):
        return _MODEL_PATH
    try:
        os.makedirs(_MODEL_DIR, exist_ok=True)
        print(f'[Gesture] Downloading hand landmark model (~7.5 MB)...')
        urllib.request.urlretrieve(_MODEL_URL, _MODEL_PATH)
        size_mb = os.path.getsize(_MODEL_PATH) / 1024 / 1024
        print(f'[Gesture] Model downloaded ({size_mb:.1f} MB) -> {_MODEL_PATH}')
        return _MODEL_PATH
    except Exception as e:
        print(f'[Gesture] Could not download model: {e}')
        if os.path.exists(_MODEL_PATH):
            os.remove(_MODEL_PATH)  # remove partial download
        return None


class GestureController:
    """
    Runs in a daemon thread. Create once, call start() / stop() as needed.

    Parameters
    ----------
    listen_event : threading.Event
        When set, Python captures voice. L-Shape gesture sets it.
    tts_queue : queue.Queue
        Drop spoken feedback strings here.
    command_queue : queue.Queue
        Drop {'text':..., 'intent': 'GESTURE_...'} dicts here for Java.
    show_preview : bool
        Show camera overlay window (default True).
    """

    def __init__(self, listen_event=None, tts_queue=None,
                 command_queue=None, show_preview: bool = True):
        self._listen_event  = listen_event
        self._tts_queue     = tts_queue
        self._command_queue = command_queue
        self._show_preview  = show_preview

        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None

        self._pending:      dict = {}
        self._last_trigger: dict = {}

    # ── Public API ──────────────────────────────────────────────────────────────

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            print('[Gesture] Already running.')
            return
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._run, daemon=True, name='GestureThread')
        self._thread.start()
        print('[Gesture] Controller started.')

    def stop(self) -> None:
        self._stop_event.set()
        print('[Gesture] Controller stopped.')

    def is_running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    # ── Main loop ────────────────────────────────────────────────────────────────

    def _run(self) -> None:
        # ── Import heavy libs ────────────────────────────────────────────────────
        try:
            import cv2
            import mediapipe as mp
            from mediapipe.tasks import python as mp_tasks
            from mediapipe.tasks.python import vision as mp_vision
            import pyautogui
            pyautogui.FAILSAFE = False
            self._pyag = pyautogui
        except ImportError as e:
            print(f'[Gesture] Missing dependency: {e}')
            print('[Gesture] Run: pip install opencv-python mediapipe pyautogui')
            return

        # ── Ensure model file ────────────────────────────────────────────────────
        model_path = _ensure_model()
        if model_path is None:
            print('[Gesture] No model file available — gesture detection disabled.')
            return

        # ── Configure HandLandmarker ─────────────────────────────────────────────
        # IMAGE mode is fully synchronous (no timestamp bookkeeping) and avoids
        # the Windows threading executor hang that occurs with VIDEO mode.
        try:
            base_options = mp_tasks.BaseOptions(model_asset_path=model_path)
            options = mp_vision.HandLandmarkerOptions(
                base_options=base_options,
                running_mode=mp_vision.RunningMode.IMAGE,
                num_hands=1,
                min_hand_detection_confidence=0.70,
                min_tracking_confidence=0.60,
            )
        except Exception as e:
            print(f'[Gesture] HandLandmarker config error: {e}')
            return

        # ── Open camera ──────────────────────────────────────────────────────────
        cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
        if not cap.isOpened():
            print('[Gesture] ERROR: Cannot open webcam (index 0). '
                  'Make sure no other app is using it.')
            return

        cap.set(cv2.CAP_PROP_FRAME_WIDTH,  320)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 240)
        cap.set(cv2.CAP_PROP_FPS,          30)
        print('[Gesture] Camera opened. Detecting gestures...')

        try:
            with mp_vision.HandLandmarker.create_from_options(options) as landmarker:

                while not self._stop_event.is_set():
                    ret, frame = cap.read()
                    if not ret:
                        time.sleep(0.05)
                        continue

                    frame    = cv2.flip(frame, 1)   # mirror effect
                    rgb      = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                    mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)

                    # IMAGE mode: simple synchronous detect(), no timestamp needed
                    result  = landmarker.detect(mp_image)
                    gesture = None

                    if result.hand_landmarks:
                        lm      = result.hand_landmarks[0]
                        fingers = self._finger_states(lm)
                        gesture = self._classify(fingers)

                        if self._show_preview:
                            self._draw_landmarks(cv2, frame, lm)

                    self._debounce(gesture)

                    if self._show_preview:
                        self._draw_overlay(cv2, frame, gesture)
                        cv2.imshow('REHBAR Gesture Control  [Q to close]', frame)
                        if cv2.waitKey(1) & 0xFF == ord('q'):
                            break

        except Exception as e:
            print(f'[Gesture] Runtime error: {e}')
        finally:
            cap.release()
            if self._show_preview:
                try:
                    cv2.destroyAllWindows()
                except Exception:
                    pass
            print('[Gesture] Camera released.')

    # ── Finger state detection ───────────────────────────────────────────────────

    @staticmethod
    def _finger_states(landmarks) -> list:
        """
        Returns [thumb_up, index_up, middle_up, ring_up, pinky_up].
        landmarks is a list of NormalizedLandmark (mediapipe.tasks API).
        Smaller .y = higher on screen in normalized coordinates.
        """
        lm = landmarks
        thumb_up  = lm[4].y  < lm[3].y
        index_up  = lm[8].y  < lm[6].y
        middle_up = lm[12].y < lm[10].y
        ring_up   = lm[16].y < lm[14].y
        pinky_up  = lm[20].y < lm[18].y
        return [thumb_up, index_up, middle_up, ring_up, pinky_up]

    # ── Gesture classification ───────────────────────────────────────────────────

    @staticmethod
    def _classify(fingers) -> Optional[str]:
        t, i, m, r, p = fingers

        # Order matters — most specific checks first
        if not t and i and m and r and p:   return 'FOUR_FINGERS'  # before OPEN_PALM
        if t and i and m and r and p:        return 'OPEN_PALM'
        if not t and not i and not m and not r and not p: return 'FIST'
        if t and i and not m and not r and not p:         return 'L_SHAPE'      # before THUMBS_UP
        if t and not i and not m and not r and not p:     return 'THUMBS_UP'
        if not t and i and m and r and not p:             return 'THREE_FINGERS'
        if not t and i and m and not r and not p:         return 'PEACE'
        if not t and i and not m and not r and not p:     return 'INDEX_ONLY'
        if not t and not i and not m and not r and p:     return 'PINKY_ONLY'
        return None

    # ── Landmark drawing (no mp_draw in 0.10+) ───────────────────────────────────

    @staticmethod
    def _draw_landmarks(cv2_mod, frame, landmarks) -> None:
        h, w = frame.shape[:2]
        pts = [(int(lm.x * w), int(lm.y * h)) for lm in landmarks]
        for a, b in _HAND_CONNECTIONS:
            cv2_mod.line(frame, pts[a], pts[b], (0, 200, 100), 1, cv2_mod.LINE_AA)
        for px, py in pts:
            cv2_mod.circle(frame, (px, py), 3, (0, 255, 140), -1)

    # ── Overlay drawing ──────────────────────────────────────────────────────────

    @staticmethod
    def _draw_overlay(cv2_mod, frame, gesture: Optional[str]) -> None:
        h, w = frame.shape[:2]
        label  = GESTURE_LABELS.get(gesture, '') if gesture else ''
        status = f'  {label}  ' if label else '  Waiting for gesture...'
        color  = (0, 230, 110) if label else (120, 120, 120)

        cv2_mod.rectangle(frame, (0, 0), (w, 32), (15, 15, 30), -1)
        cv2_mod.putText(frame, f'REHBAR  |{status}',
                        (6, 22), cv2_mod.FONT_HERSHEY_SIMPLEX, 0.55, color, 1,
                        cv2_mod.LINE_AA)

        hint = '  '.join(f'{k[:3]}:{v[:4]}' for k, v in list(GESTURE_LABELS.items())[:5])
        cv2_mod.putText(frame, hint, (4, h - 6),
                        cv2_mod.FONT_HERSHEY_SIMPLEX, 0.30, (90, 140, 200), 1,
                        cv2_mod.LINE_AA)

    # ── Debounce + cooldown ──────────────────────────────────────────────────────

    def _debounce(self, gesture: Optional[str]) -> None:
        if gesture:
            self._pending[gesture] = self._pending.get(gesture, 0) + 1
            for g in list(self._pending):
                if g != gesture:
                    self._pending[g] = 0
            if self._pending[gesture] >= CONFIRM_FRAMES:
                now  = time.time()
                last = self._last_trigger.get(gesture, 0.0)
                if now - last >= GESTURE_COOLDOWN:
                    self._last_trigger[gesture] = now
                    self._pending[gesture] = 0
                    self._execute(gesture)
        else:
            self._pending.clear()

    # ── Action execution ─────────────────────────────────────────────────────────

    def _execute(self, gesture: str) -> None:
        label = GESTURE_LABELS.get(gesture, gesture)
        print(f'[Gesture] ▶ {gesture}  ({label})')
        try:
            if   gesture == 'OPEN_PALM':      self._take_screenshot()
            elif gesture == 'FIST':           self._toggle_widget()
            elif gesture == 'THUMBS_UP':
                for _ in range(VOLUME_STEPS):
                    self._pyag.press('volumeup')
                print('[Gesture] Volume up.')
            elif gesture == 'PEACE':
                self._pyag.press('playpause')
                print('[Gesture] Play/Pause toggled.')
            elif gesture == 'INDEX_ONLY':
                self._pyag.scroll(SCROLL_AMOUNT)
                print('[Gesture] Scrolled up.')
            elif gesture == 'PINKY_ONLY':
                self._pyag.scroll(-SCROLL_AMOUNT)
                print('[Gesture] Scrolled down.')
            elif gesture == 'THREE_FINGERS':
                self._pyag.press('nexttrack')
                print('[Gesture] Next track.')
            elif gesture == 'FOUR_FINGERS':
                self._pyag.press('prevtrack')
                print('[Gesture] Previous track.')
            elif gesture == 'L_SHAPE':
                self._activate_listening()
        except Exception as e:
            print(f'[Gesture] Action error ({gesture}): {e}')

    # ── Individual actions ───────────────────────────────────────────────────────

    def _take_screenshot(self) -> None:
        try:
            desktop = os.path.join(os.path.expanduser('~'), 'Desktop')
            os.makedirs(desktop, exist_ok=True)
            fname = f'REHBAR_{datetime.datetime.now().strftime("%Y%m%d_%H%M%S")}.png'
            path  = os.path.join(desktop, fname)
            img   = self._pyag.screenshot()
            img.save(path)
            print(f'[Gesture] Screenshot saved: {path}')
            self._speak(f'Screenshot saved to Desktop.')
        except Exception as e:
            print(f'[Gesture] Screenshot error: {e}')

    def _toggle_widget(self) -> None:
        if self._command_queue is not None:
            try:
                self._command_queue.put_nowait(
                    {'text': 'gesture fist', 'intent': 'GESTURE_TOGGLE_WIDGET'})
                print('[Gesture] Widget toggle signal sent.')
            except Exception:
                print('[Gesture] command_queue full — widget toggle dropped.')
        else:
            print('[Gesture] No command_queue configured for widget toggle.')

    def _activate_listening(self) -> None:
        if self._listen_event is not None:
            self._listen_event.set()
            print('[Gesture] Voice listening activated.')
            self._speak('Listening.')
        else:
            print('[Gesture] No listen_event configured.')

    def _speak(self, text: str) -> None:
        if self._tts_queue is not None:
            try:
                self._tts_queue.put_nowait(text)
            except Exception:
                pass
