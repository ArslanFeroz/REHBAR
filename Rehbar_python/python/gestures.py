"""
gestures.py  --  Rehbar Gesture Detection Test

Standalone test script. Shows webcam feed with real-time gesture
detection, confidence histogram, and action log. Does NOT trigger
any real system actions (screenshot, volume, etc.) — just detects
and reports.

Run with:
    python gestures.py           # default: show preview window
    python gestures.py --no-ui   # headless print-only mode

Press Q in the preview window to quit.
"""

import os
import sys
import time
import urllib.request
from collections import deque, Counter
from typing import Optional

# ── Model config (same path as gesture_controller.py) ──────────────────────────
_APPDATA    = os.getenv('APPDATA', os.path.expanduser('~'))
_MODEL_DIR  = os.path.join(_APPDATA, 'RAHBAR')
_MODEL_PATH = os.path.join(_MODEL_DIR, 'hand_landmarker.task')
_MODEL_URL  = (
    'https://storage.googleapis.com/mediapipe-models/'
    'hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task'
)

CONFIRM_FRAMES   = 7
GESTURE_COOLDOWN = 1.8

GESTURE_LABELS = {
    'OPEN_PALM':      ('✋', 'Screenshot'),
    'FIST':           ('✊', 'Toggle Widget'),
    'THUMBS_UP':      ('👍', 'Volume Up'),
    'PEACE':          ('✌️', 'Play / Pause'),
    'INDEX_ONLY':     ('☝️', 'Scroll Up'),
    'PINKY_ONLY':     ('🤙', 'Scroll Down'),
    'THREE_FINGERS':  ('🤟', 'Next Track'),
    'FOUR_FINGERS':   ('🖖', 'Previous Track'),
    'L_SHAPE':        ('👉', 'Activate Listening'),
}

_HAND_CONNECTIONS = [
    (0,1),(1,2),(2,3),(3,4),
    (0,5),(5,6),(6,7),(7,8),
    (0,9),(9,10),(10,11),(11,12),
    (0,13),(13,14),(14,15),(15,16),
    (0,17),(17,18),(18,19),(19,20),
    (5,9),(9,13),(13,17),
]


# ── Helpers ────────────────────────────────────────────────────────────────────

def _ensure_model() -> Optional[str]:
    if os.path.exists(_MODEL_PATH):
        return _MODEL_PATH
    try:
        os.makedirs(_MODEL_DIR, exist_ok=True)
        print(f'Downloading hand landmark model (~7.5 MB)...')
        downloaded = [0]
        def _prog(block, block_size, total):
            downloaded[0] = block * block_size
            pct = min(100, downloaded[0] * 100 // total) if total > 0 else 0
            sys.stdout.write(f'\r  {pct:3d}%  ({downloaded[0]//1024} KB)')
            sys.stdout.flush()
        urllib.request.urlretrieve(_MODEL_URL, _MODEL_PATH, _prog)
        print(f'\nDone: {os.path.getsize(_MODEL_PATH)/1024/1024:.1f} MB')
        return _MODEL_PATH
    except Exception as e:
        print(f'\nERROR: Could not download model: {e}')
        if os.path.exists(_MODEL_PATH):
            os.remove(_MODEL_PATH)
        return None


def _finger_states(landmarks) -> list:
    lm = landmarks
    return [
        lm[4].y  < lm[3].y,   # thumb
        lm[8].y  < lm[6].y,   # index
        lm[12].y < lm[10].y,  # middle
        lm[16].y < lm[14].y,  # ring
        lm[20].y < lm[18].y,  # pinky
    ]


def _classify(fingers) -> Optional[str]:
    t, i, m, r, p = fingers
    if not t and i and m and r and p:               return 'FOUR_FINGERS'
    if t and i and m and r and p:                   return 'OPEN_PALM'
    if not any([t, i, m, r, p]):                    return 'FIST'
    if t and i and not m and not r and not p:        return 'L_SHAPE'
    if t and not i and not m and not r and not p:    return 'THUMBS_UP'
    if not t and i and m and r and not p:            return 'THREE_FINGERS'
    if not t and i and m and not r and not p:        return 'PEACE'
    if not t and i and not m and not r and not p:    return 'INDEX_ONLY'
    if not t and not i and not m and not r and p:    return 'PINKY_ONLY'
    return None


def _draw_landmarks(cv2, frame, landmarks):
    h, w = frame.shape[:2]
    pts = [(int(lm.x * w), int(lm.y * h)) for lm in landmarks]
    for a, b in _HAND_CONNECTIONS:
        cv2.line(frame, pts[a], pts[b], (0, 200, 100), 1, cv2.LINE_AA)
    for px, py in pts:
        cv2.circle(frame, (px, py), 4, (0, 255, 140), -1)


def _draw_ui(cv2, frame, gesture, pending, log, fps, finger_states):
    h, w = frame.shape[:2]
    # ── Top bar ────────────────────────────────────────────────────────────────
    cv2.rectangle(frame, (0, 0), (w, 36), (15, 15, 30), -1)
    emoji, action = GESTURE_LABELS.get(gesture, ('', '')) if gesture else ('', '')
    label  = f'  {action}  ' if action else '  Waiting...'
    color  = (0, 230, 110) if action else (120, 120, 120)
    cv2.putText(frame, f'REHBAR Gesture Test  |{label}',
                (6, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.55, color, 1, cv2.LINE_AA)

    # ── Confirm bar (progress to trigger) ──────────────────────────────────────
    if gesture and pending > 0:
        bar_w = int(w * min(pending, CONFIRM_FRAMES) / CONFIRM_FRAMES)
        cv2.rectangle(frame, (0, 36), (bar_w, 42), (0, 200, 120), -1)
    cv2.rectangle(frame, (0, 36), (w, 42), (40, 40, 60), 1)

    # ── Finger state indicators ─────────────────────────────────────────────────
    names  = ['T', 'I', 'M', 'R', 'P']
    box_x  = w - 90
    for idx, (name, state) in enumerate(zip(names, finger_states)):
        clr = (0, 230, 110) if state else (80, 80, 100)
        cv2.rectangle(frame, (box_x + idx*16, 48), (box_x + idx*16 + 12, 64), clr, -1)
        cv2.putText(frame, name, (box_x + idx*16 + 1, 62),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.32, (0, 0, 0), 1)

    # ── FPS ────────────────────────────────────────────────────────────────────
    cv2.putText(frame, f'FPS {fps:.0f}', (6, 56),
                cv2.FONT_HERSHEY_SIMPLEX, 0.38, (100, 160, 220), 1)

    # ── Action log (last 5 triggers) ───────────────────────────────────────────
    cv2.rectangle(frame, (0, h - 90), (w, h), (15, 15, 30), -1)
    cv2.putText(frame, 'Triggered:', (6, h - 76),
                cv2.FONT_HERSHEY_SIMPLEX, 0.36, (140, 140, 200), 1)
    for k, entry in enumerate(reversed(list(log)[-5:])):
        alpha = 1.0 - k * 0.18
        grey  = int(220 * alpha)
        cv2.putText(frame, entry, (6, h - 60 + k * 14),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.33, (grey, grey, grey), 1)

    # ── Gesture cheatsheet ──────────────────────────────────────────────────────
    cv2.putText(frame, 'Palm=Scr  Fist=Wdg  Up=Vol  V=Play  Idx=Up  Pnk=Dn  3=Nxt  4=Prv  L=Voice',
                (4, h - 4), cv2.FONT_HERSHEY_SIMPLEX, 0.26, (70, 120, 180), 1)


# ── Main ───────────────────────────────────────────────────────────────────────

def run_test(show_ui: bool = True):
    # ── Import check ────────────────────────────────────────────────────────────
    try:
        import cv2
        import mediapipe as mp
        from mediapipe.tasks import python as mp_tasks
        from mediapipe.tasks.python import vision as mp_vision
    except ImportError as e:
        print(f'ERROR: Missing package — {e}')
        print('Run: pip install opencv-python mediapipe')
        sys.exit(1)

    model_path = _ensure_model()
    if model_path is None:
        print('ERROR: Model file unavailable. Check internet connection.')
        sys.exit(1)

    # ── Build landmarker ────────────────────────────────────────────────────────
    base_options = mp_tasks.BaseOptions(model_asset_path=model_path)
    options = mp_vision.HandLandmarkerOptions(
        base_options=base_options,
        running_mode=mp_vision.RunningMode.VIDEO,
        num_hands=1,
        min_hand_detection_confidence=0.70,
        min_tracking_confidence=0.60,
    )

    # ── Open camera ─────────────────────────────────────────────────────────────
    cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
    if not cap.isOpened():
        print('ERROR: Could not open webcam.')
        sys.exit(1)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH,  640)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
    cap.set(cv2.CAP_PROP_FPS,          30)

    print('\n' + '=' * 60)
    print('  REHBAR Gesture Detection Test')
    print('=' * 60)
    print('  Press Q to quit')
    print()
    print('  Gesture Guide:')
    for key, (emoji, action) in GESTURE_LABELS.items():
        print(f'    {emoji}  {key:<16} → {action}')
    print('=' * 60 + '\n')

    # ── State ───────────────────────────────────────────────────────────────────
    pending:       dict  = {}
    last_trigger:  dict  = {}
    action_log:    deque = deque(maxlen=20)
    frame_count    = 0
    fps_timer      = time.monotonic()
    fps            = 0.0
    start_ns       = time.monotonic_ns()
    finger_states  = [False] * 5

    with mp_vision.HandLandmarker.create_from_options(options) as landmarker:
        while True:
            ret, frame = cap.read()
            if not ret:
                time.sleep(0.05)
                continue

            frame       = cv2.flip(frame, 1)
            rgb         = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            mp_image    = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
            ts_ms       = (time.monotonic_ns() - start_ns) // 1_000_000
            result      = landmarker.detect_for_video(mp_image, ts_ms)

            gesture = None
            if result.hand_landmarks:
                lm            = result.hand_landmarks[0]
                finger_states = _finger_states(lm)
                gesture       = _classify(finger_states)
                if show_ui:
                    _draw_landmarks(cv2, frame, lm)
            else:
                finger_states = [False] * 5

            # ── Debounce ──────────────────────────────────────────────────────
            if gesture:
                pending[gesture] = pending.get(gesture, 0) + 1
                for g in list(pending):
                    if g != gesture:
                        pending[g] = 0

                if pending[gesture] >= CONFIRM_FRAMES:
                    now  = time.monotonic()
                    last = last_trigger.get(gesture, 0.0)
                    if now - last >= GESTURE_COOLDOWN:
                        last_trigger[gesture] = now
                        pending[gesture]      = 0
                        emoji, action         = GESTURE_LABELS.get(gesture, ('?', gesture))
                        ts_str = time.strftime('%H:%M:%S')
                        entry  = f'{ts_str}  {emoji}  {action}'
                        action_log.appendleft(entry)
                        print(f'[{ts_str}] TRIGGERED: {gesture} → {action}')
            else:
                pending.clear()

            # ── FPS counter ───────────────────────────────────────────────────
            frame_count += 1
            elapsed = time.monotonic() - fps_timer
            if elapsed >= 1.0:
                fps       = frame_count / elapsed
                frame_count = 0
                fps_timer = time.monotonic()

            # ── Draw UI ───────────────────────────────────────────────────────
            if show_ui:
                _draw_ui(cv2, frame, gesture,
                         pending.get(gesture, 0) if gesture else 0,
                         action_log, fps, finger_states)
                cv2.imshow('REHBAR Gesture Test  [Q to quit]', frame)
                key = cv2.waitKey(1) & 0xFF
                if key == ord('q') or key == 27:
                    break
            else:
                # Headless — just print when something is detected
                if gesture:
                    sys.stdout.write(f'\r  Detecting: {gesture:<16}  frames: {pending.get(gesture,0)}/{CONFIRM_FRAMES}  ')
                    sys.stdout.flush()

    cap.release()
    if show_ui:
        cv2.destroyAllWindows()

    # ── Summary ──────────────────────────────────────────────────────────────
    print('\n\nSession Summary')
    print('-' * 40)
    if action_log:
        counts = Counter(entry.split('  ')[2] for entry in action_log if len(entry.split('  ')) > 2)
        for action, count in counts.most_common():
            print(f'  {action:<22} × {count}')
    else:
        print('  No gestures triggered this session.')
    print('-' * 40)


if __name__ == '__main__':
    show_ui = '--no-ui' not in sys.argv
    run_test(show_ui=show_ui)
