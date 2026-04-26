"""
gesture_controller.py  --  Rehbar Gesture Controller

Detects 9 hand gestures via webcam (MediaPipe Hands) and maps each to
a system or Rehbar-specific action.

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
"""

import datetime
import os
import threading
import time
from typing import Optional

# ── Gesture tuning ─────────────────────────────────────────────────────────────
CONFIRM_FRAMES   = 7      # consecutive frames before triggering (≈0.23s @30fps)
GESTURE_COOLDOWN = 1.8    # seconds before same gesture can re-fire
SCROLL_AMOUNT    = 5      # scroll lines per scroll gesture
VOLUME_STEPS     = 3      # volume key presses per gesture

# Maps gesture name → human-readable label
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


def _lazy_imports():
    """Import heavy libs only when the controller actually starts."""
    import cv2
    import mediapipe as mp
    import pyautogui
    pyautogui.FAILSAFE = False
    return cv2, mp, pyautogui


class GestureController:
    """
    Runs in a daemon thread.  Create once, call start() / stop() as needed.

    Parameters
    ----------
    listen_event : threading.Event
        When set, Python will capture voice.  L-Shape gesture sets it.
    tts_queue : queue.Queue
        Drop spoken feedback strings here (screenshot confirmation only).
    command_queue : queue.Queue
        Drop {'text':..., 'intent': 'GESTURE_...'} dicts here so Java
        can handle Rehbar-specific gestures (widget toggle, etc.).
    show_preview : bool
        Show a small camera-overlay window (default True so users can
        see which gesture is recognised during setup).
    """

    def __init__(self, listen_event=None, tts_queue=None,
                 command_queue=None, show_preview: bool = True):
        self._listen_event  = listen_event
        self._tts_queue     = tts_queue
        self._command_queue = command_queue
        self._show_preview  = show_preview

        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None

        # Per-gesture state for debounce / cooldown
        self._pending:      dict = {}   # gesture → consecutive-frame count
        self._last_trigger: dict = {}   # gesture → last trigger timestamp

    # ── Public API ─────────────────────────────────────────────────────────────

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            print('[Gesture] Already running.')
            return
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._run, daemon=True, name='GestureThread')
        self._thread.start()
        print('[Gesture] Controller started. Press Q in preview window to stop.')

    def stop(self) -> None:
        self._stop_event.set()
        print('[Gesture] Controller stopped.')

    def is_running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    # ── Main loop ──────────────────────────────────────────────────────────────

    def _run(self) -> None:
        try:
            cv2, mp, pyautogui = _lazy_imports()
        except ImportError as e:
            print(f'[Gesture] Missing dependency: {e}')
            print('[Gesture] Run: pip install opencv-python mediapipe pyautogui pynput')
            return

        mp_hands   = mp.solutions.hands
        mp_draw    = mp.solutions.drawing_utils
        self._pyag = pyautogui

        cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)   # CAP_DSHOW = faster on Windows
        if not cap.isOpened():
            print('[Gesture] ERROR: Cannot open webcam (index 0). '
                  'Make sure no other app is using it.')
            return

        cap.set(cv2.CAP_PROP_FRAME_WIDTH,  320)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 240)
        cap.set(cv2.CAP_PROP_FPS,          30)

        print('[Gesture] Camera opened. Detecting gestures...')

        with mp_hands.Hands(
                static_image_mode=False,
                max_num_hands=1,
                model_complexity=0,           # fastest model
                min_detection_confidence=0.70,
                min_tracking_confidence=0.60,
        ) as hands:

            while not self._stop_event.is_set():
                ret, frame = cap.read()
                if not ret:
                    time.sleep(0.05)
                    continue

                frame   = cv2.flip(frame, 1)                  # mirror effect
                rgb     = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                results = hands.process(rgb)

                gesture = None
                if results.multi_hand_landmarks:
                    hand_lm = results.multi_hand_landmarks[0]
                    if self._show_preview:
                        mp_draw.draw_landmarks(
                            frame, hand_lm, mp_hands.HAND_CONNECTIONS)
                    fingers = self._finger_states(hand_lm)
                    gesture = self._classify(hand_lm, fingers)

                self._debounce(gesture)

                if self._show_preview:
                    self._draw_overlay(cv2, frame, gesture)
                    cv2.imshow('REHBAR Gesture Control  [Q to close]', frame)
                    if cv2.waitKey(1) & 0xFF == ord('q'):
                        break

        cap.release()
        if self._show_preview:
            cv2.destroyAllWindows()
        print('[Gesture] Camera released.')

    # ── Finger state detection ─────────────────────────────────────────────────

    @staticmethod
    def _finger_states(hand_landmarks) -> list:
        """
        Returns [thumb_up, index_up, middle_up, ring_up, pinky_up].
        Uses y-coordinate comparison (smaller y = higher on screen in MediaPipe).
        """
        lm = hand_landmarks.landmark

        # Thumb: tip (4) above IP joint (3)
        thumb_up = lm[4].y < lm[3].y

        # Fingers: tip above PIP joint
        index_up  = lm[8].y  < lm[6].y
        middle_up = lm[12].y < lm[10].y
        ring_up   = lm[16].y < lm[14].y
        pinky_up  = lm[20].y < lm[18].y

        return [thumb_up, index_up, middle_up, ring_up, pinky_up]

    # ── Gesture classification ─────────────────────────────────────────────────

    @staticmethod
    def _classify(hand_landmarks, fingers) -> Optional[str]:
        t, i, m, r, p = fingers

        # Order matters — most specific checks first

        # Four Fingers (all except thumb) — check BEFORE Open Palm
        if not t and i and m and r and p:
            return 'FOUR_FINGERS'

        # Open Palm — all five up
        if t and i and m and r and p:
            return 'OPEN_PALM'

        # Fist — all closed
        if not t and not i and not m and not r and not p:
            return 'FIST'

        # L-Shape — thumb + index, rest closed (check BEFORE THUMBS_UP)
        if t and i and not m and not r and not p:
            return 'L_SHAPE'

        # Thumbs Up — only thumb
        if t and not i and not m and not r and not p:
            return 'THUMBS_UP'

        # Three Fingers — index + middle + ring
        if not t and i and m and r and not p:
            return 'THREE_FINGERS'

        # Peace / V — index + middle only
        if not t and i and m and not r and not p:
            return 'PEACE'

        # Index Only
        if not t and i and not m and not r and not p:
            return 'INDEX_ONLY'

        # Pinky Only
        if not t and not i and not m and not r and p:
            return 'PINKY_ONLY'

        return None

    # ── Debounce + cooldown ────────────────────────────────────────────────────

    def _debounce(self, gesture: Optional[str]) -> None:
        if gesture:
            self._pending[gesture] = self._pending.get(gesture, 0) + 1
            # Reset counters for all other gestures
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

    # ── Overlay drawing ────────────────────────────────────────────────────────

    @staticmethod
    def _draw_overlay(cv2, frame, gesture: Optional[str]) -> None:
        import cv2 as _cv2
        h, w = frame.shape[:2]

        label  = GESTURE_LABELS.get(gesture, '') if gesture else ''
        status = f'  {label}  ' if label else '  Waiting for gesture...'
        color  = (0, 230, 110) if label else (120, 120, 120)

        _cv2.rectangle(frame, (0, 0), (w, 32), (15, 15, 30), -1)
        _cv2.putText(frame, f'REHBAR  |{status}',
                     (6, 22), _cv2.FONT_HERSHEY_SIMPLEX, 0.55, color, 1,
                     _cv2.LINE_AA)

        hint = '  '.join(f'{k}:{v[:4]}' for k, v in list(GESTURE_LABELS.items())[:5])
        _cv2.putText(frame, hint, (4, h - 6),
                     _cv2.FONT_HERSHEY_SIMPLEX, 0.30, (90, 140, 200), 1,
                     _cv2.LINE_AA)

    # ── Action execution ───────────────────────────────────────────────────────

    def _execute(self, gesture: str) -> None:
        label = GESTURE_LABELS.get(gesture, gesture)
        print(f'[Gesture] ▶ {gesture}  ({label})')

        try:
            if gesture == 'OPEN_PALM':
                self._take_screenshot()

            elif gesture == 'FIST':
                self._toggle_widget()

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

    # ── Individual actions ─────────────────────────────────────────────────────

    def _take_screenshot(self) -> None:
        try:
            desktop = os.path.join(os.path.expanduser('~'), 'Desktop')
            os.makedirs(desktop, exist_ok=True)
            fname = f'REHBAR_{datetime.datetime.now().strftime("%Y%m%d_%H%M%S")}.png'
            path  = os.path.join(desktop, fname)
            img   = self._pyag.screenshot()
            img.save(path)
            print(f'[Gesture] Screenshot saved: {path}')
            self._speak(f'Screenshot saved to Desktop as {fname}')
        except Exception as e:
            print(f'[Gesture] Screenshot error: {e}')

    def _toggle_widget(self) -> None:
        """Puts a GESTURE_TOGGLE_WIDGET intent into the command queue so Java handles it."""
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
