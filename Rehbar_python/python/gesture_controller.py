"""
gesture_controller.py  --  Rehbar Gesture Controller
                            (MediaPipe 0.10+ Tasks API, IMAGE mode)

Active gestures and their actions:
──────────────────────────────────────────────────────────────────
 #   Gesture             Hand Shape              Action
──────────────────────────────────────────────────────────────────
 1   Open Palm    ✋    All 5 fingers up         Screenshot
 2   Thumbs Up    👍    Thumb out, rest closed   Volume Up
 3   Fist         ✊    All fingers closed       Volume Down
 4   Peace / V    ✌️    Index + middle only      Zoom In  (Ctrl ++)
 5   Three Fingers 🤟   Index+middle+ring only   Zoom Out (Ctrl --)
 6   Four Fingers  🖖   All except thumb         Next Tab (Ctrl+Tab)
 7   Pinky Only   🤙    Only pinky up            Prev Tab (Ctrl+Shift+Tab)
──────────────────────────────────────────────────────────────────

Requires:
    pip install opencv-python mediapipe pyautogui

Model (~7.5 MB) is auto-downloaded to %APPDATA%/RAHBAR/hand_landmarker.task
"""

import datetime
import os
import threading
import time
import urllib.request
from typing import Optional

# ── Model config ────────────────────────────────────────────────────────────────
_APPDATA    = os.getenv('APPDATA', os.path.expanduser('~'))
_MODEL_DIR  = os.path.join(_APPDATA, 'RAHBAR')
_MODEL_PATH = os.path.join(_MODEL_DIR, 'hand_landmarker.task')
_MODEL_URL  = (
    'https://storage.googleapis.com/mediapipe-models/'
    'hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task'
)

# ── Tuning ──────────────────────────────────────────────────────────────────────
CONFIRM_FRAMES   = 8      # consecutive frames before triggering (~0.27s @30fps)
GESTURE_COOLDOWN = 2.0    # seconds before same gesture can re-fire
THUMB_MARGIN     = 0.04   # how much farther tip must be from wrist than IP joint

# Hand landmark connections for manual drawing
_HAND_CONNECTIONS = [
    (0,1),(1,2),(2,3),(3,4),
    (0,5),(5,6),(6,7),(7,8),
    (0,9),(9,10),(10,11),(11,12),
    (0,13),(13,14),(14,15),(15,16),
    (0,17),(17,18),(18,19),(19,20),
    (5,9),(9,13),(13,17),
]

GESTURE_LABELS = {
    'OPEN_PALM':     'Screenshot',
    'THUMBS_UP':     'Volume Up',
    'FIST':          'Volume Down',
    'PEACE':         'Zoom In',
    'THREE_FINGERS': 'Zoom Out',
    'FOUR_FINGERS':  'Next Tab',
    'PINKY_ONLY':    'Prev Tab',
}


def _ensure_model() -> Optional[str]:
    """Download the hand landmark model if not already cached. Returns path or None."""
    if os.path.exists(_MODEL_PATH):
        return _MODEL_PATH
    try:
        os.makedirs(_MODEL_DIR, exist_ok=True)
        print('[Gesture] Downloading hand landmark model (~7.5 MB)...')
        urllib.request.urlretrieve(_MODEL_URL, _MODEL_PATH)
        print(f'[Gesture] Model ready: {_MODEL_PATH}')
        return _MODEL_PATH
    except Exception as e:
        print(f'[Gesture] Model download failed: {e}')
        if os.path.exists(_MODEL_PATH):
            os.remove(_MODEL_PATH)
        return None


class GestureController:
    """
    Runs in a daemon thread.  Create once, call start() / stop() as needed.

    Parameters
    ----------
    listen_event : threading.Event  (unused by current gesture set but kept for API compat)
    tts_queue    : queue.Queue      Spoken feedback strings
    command_queue: queue.Queue      {'text':..., 'intent': 'GESTURE_...'} for Java
    show_preview : bool             Show camera overlay window (default True)
    """

    def __init__(self, listen_event=None, tts_queue=None,
                 command_queue=None, show_preview: bool = True):
        self._listen_event  = listen_event
        self._tts_queue     = tts_queue
        self._command_queue = command_queue
        self._show_preview  = show_preview
        self._stop_event    = threading.Event()
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
            return

        model_path = _ensure_model()
        if model_path is None:
            print('[Gesture] No model — gesture detection disabled.')
            return

        try:
            base_options = mp_tasks.BaseOptions(model_asset_path=model_path)
            options = mp_vision.HandLandmarkerOptions(
                base_options=base_options,
                running_mode=mp_vision.RunningMode.IMAGE,
                num_hands=1,
                min_hand_detection_confidence=0.65,
                min_tracking_confidence=0.55,
            )
        except Exception as e:
            print(f'[Gesture] HandLandmarker config error: {e}')
            return

        cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
        if not cap.isOpened():
            print('[Gesture] ERROR: Cannot open webcam.')
            return
        cap.set(cv2.CAP_PROP_FRAME_WIDTH,  320)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 240)
        cap.set(cv2.CAP_PROP_FPS,          30)
        print('[Gesture] Camera open. Gesture detection active.')

        try:
            with mp_vision.HandLandmarker.create_from_options(options) as landmarker:
                while not self._stop_event.is_set():
                    ret, frame = cap.read()
                    if not ret:
                        time.sleep(0.05)
                        continue

                    frame    = cv2.flip(frame, 1)
                    rgb      = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                    mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
                    result   = landmarker.detect(mp_image)

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
                        cv2.imshow('REHBAR Gestures  [Q to close]', frame)
                        if cv2.waitKey(1) & 0xFF == ord('q'):
                            break

        except Exception as e:
            print(f'[Gesture] Runtime error: {e}')
        finally:
            cap.release()
            try:
                cv2.destroyAllWindows()
            except Exception:
                pass
            print('[Gesture] Camera released.')

    # ── Finger state detection ───────────────────────────────────────────────────

    @staticmethod
    def _finger_states(lm) -> list:
        """
        Returns [thumb_up, index_up, middle_up, ring_up, pinky_up].

        Thumb uses distance-from-wrist: the tip (4) must be noticeably
        farther from the wrist (0) than the IP joint (3) — works regardless
        of hand orientation or whether it's left/right hand.

        All other fingers use y-coordinate: tip above PIP joint.
        """
        def _dist(a, b):
            return ((a.x - b.x) ** 2 + (a.y - b.y) ** 2) ** 0.5

        wrist      = lm[0]
        tip_dist   = _dist(lm[4], wrist)
        ip_dist    = _dist(lm[3], wrist)
        thumb_up   = tip_dist > ip_dist + THUMB_MARGIN

        index_up   = lm[8].y  < lm[6].y
        middle_up  = lm[12].y < lm[10].y
        ring_up    = lm[16].y < lm[14].y
        pinky_up   = lm[20].y < lm[18].y

        return [thumb_up, index_up, middle_up, ring_up, pinky_up]

    # ── Gesture classification ───────────────────────────────────────────────────

    @staticmethod
    def _classify(fingers) -> Optional[str]:
        t, i, m, r, p = fingers

        # Check most-specific patterns first to avoid ambiguity
        if t and i and m and r and p:               return 'OPEN_PALM'
        if not t and i and m and r and p:           return 'FOUR_FINGERS'
        if not any([t, i, m, r, p]):                return 'FIST'
        if t and not i and not m and not r and not p: return 'THUMBS_UP'
        if not t and i and m and r and not p:       return 'THREE_FINGERS'
        if not t and i and m and not r and not p:   return 'PEACE'
        if not t and not i and not m and not r and p: return 'PINKY_ONLY'

        return None

    # ── Landmark drawing ─────────────────────────────────────────────────────────

    @staticmethod
    def _draw_landmarks(cv2_mod, frame, landmarks) -> None:
        h, w = frame.shape[:2]
        pts  = [(int(lm.x * w), int(lm.y * h)) for lm in landmarks]
        for a, b in _HAND_CONNECTIONS:
            cv2_mod.line(frame, pts[a], pts[b], (0, 200, 100), 1, cv2_mod.LINE_AA)
        for px, py in pts:
            cv2_mod.circle(frame, (px, py), 3, (0, 255, 140), -1)

    # ── Overlay ──────────────────────────────────────────────────────────────────

    @staticmethod
    def _draw_overlay(cv2_mod, frame, gesture: Optional[str]) -> None:
        h, w  = frame.shape[:2]
        label = GESTURE_LABELS.get(gesture, '') if gesture else ''
        text  = f'  {label}  ' if label else '  Waiting...'
        color = (0, 230, 110) if label else (120, 120, 120)
        cv2_mod.rectangle(frame, (0, 0), (w, 30), (15, 15, 30), -1)
        cv2_mod.putText(frame, f'REHBAR | {text}',
                        (6, 21), cv2_mod.FONT_HERSHEY_SIMPLEX, 0.52, color, 1,
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
                    self._pending[gesture]      = 0
                    self._execute(gesture)
        else:
            self._pending.clear()

    # ── Action execution ─────────────────────────────────────────────────────────

    def _execute(self, gesture: str) -> None:
        label = GESTURE_LABELS.get(gesture, gesture)
        print(f'[Gesture] ▶ {gesture}  ({label})')
        try:
            if   gesture == 'OPEN_PALM':      self._screenshot()
            elif gesture == 'THUMBS_UP':      self._volume_up()
            elif gesture == 'FIST':           self._volume_down()
            elif gesture == 'PEACE':          self._zoom_in()
            elif gesture == 'THREE_FINGERS':  self._zoom_out()
            elif gesture == 'FOUR_FINGERS':   self._next_tab()
            elif gesture == 'PINKY_ONLY':     self._prev_tab()
        except Exception as e:
            print(f'[Gesture] Action error ({gesture}): {e}')

    # ── Individual actions ───────────────────────────────────────────────────────

    def _screenshot(self) -> None:
        desktop = os.path.join(os.path.expanduser('~'), 'Desktop')
        os.makedirs(desktop, exist_ok=True)
        fname = f'REHBAR_{datetime.datetime.now().strftime("%Y%m%d_%H%M%S")}.png'
        path  = os.path.join(desktop, fname)
        img   = self._pyag.screenshot()
        img.save(path)
        print(f'[Gesture] Screenshot → {path}')
        self._speak('Screenshot saved.')

    def _volume_up(self) -> None:
        for _ in range(3):
            self._pyag.press('volumeup')
        print('[Gesture] Volume up.')

    def _volume_down(self) -> None:
        for _ in range(3):
            self._pyag.press('volumedown')
        print('[Gesture] Volume down.')

    def _zoom_in(self) -> None:
        self._pyag.hotkey('ctrl', '+')
        print('[Gesture] Zoom in.')

    def _zoom_out(self) -> None:
        self._pyag.hotkey('ctrl', '-')
        print('[Gesture] Zoom out.')

    def _next_tab(self) -> None:
        self._pyag.hotkey('ctrl', 'tab')
        print('[Gesture] Next tab.')

    def _prev_tab(self) -> None:
        self._pyag.hotkey('ctrl', 'shift', 'tab')
        print('[Gesture] Previous tab.')

    def _speak(self, text: str) -> None:
        if self._tts_queue is not None:
            try:
                self._tts_queue.put_nowait(text)
            except Exception:
                pass
