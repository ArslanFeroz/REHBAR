"""
gesture_controller.py  --  Rehbar Gesture Controller
                            (MediaPipe 0.10+ Tasks API, IMAGE mode)

Active gestures:
──────────────────────────────────────────────────────────────────
 Type    Gesture           How to do it                Action
──────────────────────────────────────────────────────────────────
 Static  Open Palm   ✋   All 5 fingers spread wide   Screenshot
 Static  Thumbs Up   👍   Thumb out, fist closed      Volume Up
 Static  Fist        ✊   All fingers curled in        Volume Down
 Motion  Pinch Open  🤏→  Start with thumb+index       Zoom In
                          touching, then spread apart
 Motion  Pinch Close 🤏←  Start with thumb+index       Zoom Out
                          spread, then pinch together
 Motion  Swipe Right →   Slap / swipe hand to right   Next Tab
 Motion  Swipe Left  ←   Slap / swipe hand to left    Prev Tab
──────────────────────────────────────────────────────────────────

Requires:
    pip install opencv-python mediapipe pyautogui

Model (~7.5 MB) auto-downloaded to %APPDATA%/RAHBAR/hand_landmarker.task
"""

import datetime
import os
import threading
import time
import urllib.request
from collections import deque
from typing import Optional

# ── Model ───────────────────────────────────────────────────────────────────────
_APPDATA    = os.getenv('APPDATA', os.path.expanduser('~'))
_MODEL_DIR  = os.path.join(_APPDATA, 'RAHBAR')
_MODEL_PATH = os.path.join(_MODEL_DIR, 'hand_landmarker.task')
_MODEL_URL  = (
    'https://storage.googleapis.com/mediapipe-models/'
    'hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task'
)

# ── Static gesture tuning ────────────────────────────────────────────────────────
STATIC_CONFIRM  = 8      # consecutive frames to confirm a static pose
STATIC_COOLDOWN = 2.0    # seconds before same static gesture re-fires
THUMB_MARGIN    = 0.04   # distance(tip,wrist) must exceed distance(IP,wrist) by this

# ── Motion gesture tuning ────────────────────────────────────────────────────────
SWIPE_FRAMES      = 10   # frames over which swipe displacement is measured
SWIPE_THRESHOLD   = 0.22 # minimum normalized x displacement (22% of frame width)
SWIPE_CONSISTENCY = 0.60 # fraction of steps that must go in swipe direction

PINCH_FRAMES     = 18    # frames over which pinch change is measured
PINCH_THRESHOLD  = 0.09  # minimum distance change (normalized) to fire
PINCH_MAX_START  = 0.18  # pinch start distance must be below this (fingers not too far apart)
PINCH_MIN_END    = 0.02  # pinch end distance must be above this (not fully closed)

MOTION_COOLDOWN  = 28    # frames (~0.9s) to ignore gestures after motion trigger

_HAND_CONNECTIONS = [
    (0,1),(1,2),(2,3),(3,4),
    (0,5),(5,6),(6,7),(7,8),
    (0,9),(9,10),(10,11),(11,12),
    (0,13),(13,14),(14,15),(15,16),
    (0,17),(17,18),(18,19),(19,20),
    (5,9),(9,13),(13,17),
]

GESTURE_LABELS = {
    'OPEN_PALM':   'Screenshot',
    'THUMBS_UP':   'Volume Up',
    'FIST':        'Volume Down',
    'ZOOM_IN':     'Zoom In',
    'ZOOM_OUT':    'Zoom Out',
    'SWIPE_RIGHT': 'Next Tab',
    'SWIPE_LEFT':  'Prev Tab',
}


def _ensure_model() -> Optional[str]:
    if os.path.exists(_MODEL_PATH):
        return _MODEL_PATH
    try:
        os.makedirs(_MODEL_DIR, exist_ok=True)
        print('[Gesture] Downloading hand landmark model (~7.5 MB)...')
        urllib.request.urlretrieve(_MODEL_URL, _MODEL_PATH)
        print(f'[Gesture] Model ready.')
        return _MODEL_PATH
    except Exception as e:
        print(f'[Gesture] Model download failed: {e}')
        if os.path.exists(_MODEL_PATH):
            os.remove(_MODEL_PATH)
        return None


# ── Motion detector ──────────────────────────────────────────────────────────────

class MotionDetector:
    """
    Tracks positional history across frames to detect swipe and pinch motions.
    Completely separate from static pose detection so they don't interfere.
    """

    def __init__(self):
        self._wrist_x  = deque(maxlen=SWIPE_FRAMES)
        self._pinch_d  = deque(maxlen=PINCH_FRAMES)
        self._cooldown = 0     # frames remaining in post-trigger cooldown

    def reset(self) -> None:
        self._wrist_x.clear()
        self._pinch_d.clear()

    @property
    def cooling_down(self) -> bool:
        return self._cooldown > 0

    def tick(self, lm) -> Optional[str]:
        """
        Call every frame with current hand landmarks (or None).
        Returns the motion gesture name if triggered, else None.
        """
        if self._cooldown > 0:
            self._cooldown -= 1
            # Keep updating history so motion resumes naturally after cooldown
            if lm is not None:
                self._wrist_x.append(lm[0].x)
                self._pinch_d.append(self._pinch_dist(lm))
            else:
                self._wrist_x.clear()
                self._pinch_d.clear()
            return None

        if lm is None:
            self._wrist_x.clear()
            self._pinch_d.clear()
            return None

        # Update rolling history
        self._wrist_x.append(lm[0].x)
        self._pinch_d.append(self._pinch_dist(lm))

        # Swipe takes priority (it's faster/clearer)
        result = self._check_swipe()
        if result:
            self._cooldown = MOTION_COOLDOWN
            self._wrist_x.clear()
            return result

        # Pinch
        result = self._check_pinch(lm)
        if result:
            self._cooldown = MOTION_COOLDOWN
            self._pinch_d.clear()
            return result

        return None

    @staticmethod
    def _pinch_dist(lm) -> float:
        """Euclidean distance between thumb tip (4) and index tip (8)."""
        dx = lm[4].x - lm[8].x
        dy = lm[4].y - lm[8].y
        return (dx * dx + dy * dy) ** 0.5

    def _check_swipe(self) -> Optional[str]:
        """
        Detects a fast horizontal slap/swipe.
        Requires: enough frames, large displacement, consistent direction.
        """
        if len(self._wrist_x) < SWIPE_FRAMES:
            return None

        xs = list(self._wrist_x)
        dx = xs[-1] - xs[0]
        if abs(dx) < SWIPE_THRESHOLD:
            return None

        # Consistency: most steps must go in the swipe direction
        sign = 1 if dx > 0 else -1
        consistent = sum(
            1 for i in range(len(xs) - 1)
            if (xs[i + 1] - xs[i]) * sign >= 0
        )
        if consistent < (len(xs) - 1) * SWIPE_CONSISTENCY:
            return None

        # dx > 0 = hand moved right in (flipped) frame = rightward swipe = next tab
        # dx < 0 = hand moved left  in (flipped) frame = leftward swipe  = prev tab
        return 'SWIPE_RIGHT' if dx > 0 else 'SWIPE_LEFT'

    def _check_pinch(self, lm) -> Optional[str]:
        """
        Detects pinch-open (zoom in) or pinch-close (zoom out).
        Requires index finger to be at least partially extended (not fisted).
        """
        if len(self._pinch_d) < PINCH_FRAMES:
            return None

        # Index tip must be above index MCP — ensures hand isn't fully fisted
        if lm[8].y >= lm[5].y:
            return None

        ds    = list(self._pinch_d)
        q     = max(2, len(ds) // 4)
        d_old = sum(ds[:q]) / q      # average over first quarter
        d_new = sum(ds[-q:]) / q     # average over last quarter
        delta = d_new - d_old

        # Zoom in: fingers SPREAD (distance increases)
        # Require start was close (user started in a pinch)
        if delta > PINCH_THRESHOLD and d_old < PINCH_MAX_START:
            return 'ZOOM_IN'

        # Zoom out: fingers CLOSE (distance decreases)
        # Require end is still somewhat open (not a full fist)
        if delta < -PINCH_THRESHOLD and d_new > PINCH_MIN_END:
            return 'ZOOM_OUT'

        return None

    # ── Debug properties for test UI ─────────────────────────────────────────────

    @property
    def current_pinch_dist(self) -> float:
        return self._pinch_d[-1] if self._pinch_d else 0.0

    @property
    def swipe_displacement(self) -> float:
        if len(self._wrist_x) < 2:
            return 0.0
        xs = list(self._wrist_x)
        return xs[-1] - xs[0]

    @property
    def wrist_trail(self) -> list:
        return list(self._wrist_x)


# ── Main controller ──────────────────────────────────────────────────────────────

class GestureController:
    """
    Runs in a daemon thread. Create once, call start() / stop() as needed.

    Parameters
    ----------
    listen_event : threading.Event  (API compat — unused by current gesture set)
    tts_queue    : queue.Queue      Spoken feedback strings
    command_queue: queue.Queue      {'text':..., 'intent': 'GESTURE_...'} for Java
    show_preview : bool             Show camera overlay window
    """

    def __init__(self, listen_event=None, tts_queue=None,
                 command_queue=None, show_preview: bool = True):
        self._listen_event  = listen_event
        self._tts_queue     = tts_queue
        self._command_queue = command_queue
        self._show_preview  = show_preview
        self._stop_event    = threading.Event()
        self._thread: Optional[threading.Thread] = None

        # Static pose state
        self._pending:      dict = {}
        self._last_trigger: dict = {}

        # Motion state
        self._motion = MotionDetector()

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
        print('[Gesture] Camera open. Detecting gestures...')

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

                    lm      = result.hand_landmarks[0] if result.hand_landmarks else None
                    fingers = self._finger_states(lm) if lm else None

                    # ── Motion gestures (pinch / swipe) ──────────────────────
                    motion_gesture = self._motion.tick(lm)
                    if motion_gesture:
                        self._execute(motion_gesture)
                        # Reset static debounce so motion doesn't bleed into static
                        self._pending.clear()

                    # ── Static poses (open palm / thumbs up / fist) ──────────
                    # Skip while motion detector is cooling down to avoid conflicts
                    elif not self._motion.cooling_down and fingers is not None:
                        static_gesture = self._classify(fingers)
                        self._debounce(static_gesture)
                    else:
                        self._pending.clear()

                    if lm and self._show_preview:
                        self._draw_landmarks(cv2, frame, lm)
                    if self._show_preview:
                        self._draw_overlay(cv2, frame,
                                           motion_gesture or
                                           self._current_static_label())
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

    def _current_static_label(self) -> Optional[str]:
        """Returns the static gesture being held (if past threshold), else None."""
        for g, count in self._pending.items():
            if count >= STATIC_CONFIRM:
                return g
        return None

    # ── Finger state detection ───────────────────────────────────────────────────

    @staticmethod
    def _finger_states(lm) -> list:
        """
        [thumb_up, index_up, middle_up, ring_up, pinky_up]

        Thumb: distance-from-wrist method — robust across any hand angle.
        Others: tip.y < pip.y in normalized coordinates.
        """
        def _d(a, b):
            return ((a.x - b.x) ** 2 + (a.y - b.y) ** 2) ** 0.5

        thumb_up  = _d(lm[4], lm[0]) > _d(lm[3], lm[0]) + THUMB_MARGIN
        return [
            thumb_up,
            lm[8].y  < lm[6].y,
            lm[12].y < lm[10].y,
            lm[16].y < lm[14].y,
            lm[20].y < lm[18].y,
        ]

    # ── Static pose classification ───────────────────────────────────────────────

    @staticmethod
    def _classify(fingers) -> Optional[str]:
        t, i, m, r, p = fingers
        if t and i and m and r and p:                  return 'OPEN_PALM'
        if not any([t, i, m, r, p]):                   return 'FIST'
        if t and not i and not m and not r and not p:  return 'THUMBS_UP'
        return None

    # ── Debounce (static only) ───────────────────────────────────────────────────

    def _debounce(self, gesture: Optional[str]) -> None:
        if gesture:
            self._pending[gesture] = self._pending.get(gesture, 0) + 1
            for g in list(self._pending):
                if g != gesture:
                    self._pending[g] = 0
            if self._pending[gesture] >= STATIC_CONFIRM:
                now  = time.time()
                last = self._last_trigger.get(gesture, 0.0)
                if now - last >= STATIC_COOLDOWN:
                    self._last_trigger[gesture] = now
                    self._pending[gesture]      = 0
                    self._execute(gesture)
        else:
            self._pending.clear()

    # ── Overlay ──────────────────────────────────────────────────────────────────

    @staticmethod
    def _draw_landmarks(cv2_mod, frame, lm) -> None:
        h, w = frame.shape[:2]
        pts  = [(int(p.x * w), int(p.y * h)) for p in lm]
        for a, b in _HAND_CONNECTIONS:
            cv2_mod.line(frame, pts[a], pts[b], (0, 200, 100), 1, cv2_mod.LINE_AA)
        for px, py in pts:
            cv2_mod.circle(frame, (px, py), 3, (0, 255, 140), -1)

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

    # ── Action execution ─────────────────────────────────────────────────────────

    def _execute(self, gesture: str) -> None:
        label = GESTURE_LABELS.get(gesture, gesture)
        print(f'[Gesture] ▶ {gesture}  ({label})')
        try:
            if   gesture == 'OPEN_PALM':    self._screenshot()
            elif gesture == 'THUMBS_UP':    self._volume_up()
            elif gesture == 'FIST':         self._volume_down()
            elif gesture == 'ZOOM_IN':      self._zoom_in()
            elif gesture == 'ZOOM_OUT':     self._zoom_out()
            elif gesture == 'SWIPE_RIGHT':  self._next_tab()
            elif gesture == 'SWIPE_LEFT':   self._prev_tab()
        except Exception as e:
            print(f'[Gesture] Action error ({gesture}): {e}')

    def _screenshot(self) -> None:
        desktop = os.path.join(os.path.expanduser('~'), 'Desktop')
        os.makedirs(desktop, exist_ok=True)
        fname = f'REHBAR_{datetime.datetime.now().strftime("%Y%m%d_%H%M%S")}.png'
        path  = os.path.join(desktop, fname)
        self._pyag.screenshot().save(path)
        print(f'[Gesture] Screenshot → {path}')
        self._speak('Screenshot saved.')

    def _volume_up(self) -> None:
        for _ in range(3):
            self._pyag.press('volumeup')

    def _volume_down(self) -> None:
        for _ in range(3):
            self._pyag.press('volumedown')

    def _zoom_in(self) -> None:
        self._pyag.hotkey('ctrl', '+')

    def _zoom_out(self) -> None:
        self._pyag.hotkey('ctrl', '-')

    def _next_tab(self) -> None:
        self._pyag.hotkey('ctrl', 'tab')

    def _prev_tab(self) -> None:
        self._pyag.hotkey('ctrl', 'shift', 'tab')

    def _speak(self, text: str) -> None:
        if self._tts_queue is not None:
            try:
                self._tts_queue.put_nowait(text)
            except Exception:
                pass
