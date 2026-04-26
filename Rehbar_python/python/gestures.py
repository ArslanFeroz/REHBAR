"""
gestures.py  --  Rehbar Gesture Detection Test

Standalone test — shows webcam feed with real-time gesture detection.
Does NOT trigger real system actions. Press Q to quit.

Usage:
    python gestures.py           # with UI (recommended)
    python gestures.py --no-ui   # headless print-only

Active gestures:
    ✋  OPEN_PALM      (all 5 up)             → Screenshot
    👍  THUMBS_UP      (thumb only)           → Volume Up
    ✊  FIST           (all closed)           → Volume Down
    ✌️  PEACE          (index + middle)        → Zoom In
    🤟  THREE_FINGERS  (index+middle+ring)     → Zoom Out
    🖖  FOUR_FINGERS   (all except thumb)     → Next Tab
    🤙  PINKY_ONLY     (pinky only)           → Prev Tab
"""

import os
import sys
import time
import urllib.request
from collections import deque, Counter
from typing import Optional

# ── Config ─────────────────────────────────────────────────────────────────────
_APPDATA    = os.getenv('APPDATA', os.path.expanduser('~'))
_MODEL_DIR  = os.path.join(_APPDATA, 'RAHBAR')
_MODEL_PATH = os.path.join(_MODEL_DIR, 'hand_landmarker.task')
_MODEL_URL  = (
    'https://storage.googleapis.com/mediapipe-models/'
    'hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task'
)

CONFIRM_FRAMES   = 8
GESTURE_COOLDOWN = 2.0
THUMB_MARGIN     = 0.04

# (emoji, action label)
GESTURE_INFO = {
    'OPEN_PALM':     ('✋', 'Screenshot',       'All 5 fingers up'),
    'THUMBS_UP':     ('👍', 'Volume Up',         'Thumb only, rest closed'),
    'FIST':          ('✊', 'Volume Down',        'All fingers closed'),
    'PEACE':         ('✌️', 'Zoom In  (Ctrl +)', 'Index + middle only'),
    'THREE_FINGERS': ('🤟', 'Zoom Out (Ctrl -)', 'Index + middle + ring'),
    'FOUR_FINGERS':  ('🖖', 'Next Tab',          'All except thumb'),
    'PINKY_ONLY':    ('🤙', 'Prev Tab',          'Pinky only'),
}

_HAND_CONNECTIONS = [
    (0,1),(1,2),(2,3),(3,4),
    (0,5),(5,6),(6,7),(7,8),
    (0,9),(9,10),(10,11),(11,12),
    (0,13),(13,14),(14,15),(15,16),
    (0,17),(17,18),(18,19),(19,20),
    (5,9),(9,13),(13,17),
]


def _ensure_model() -> Optional[str]:
    if os.path.exists(_MODEL_PATH):
        return _MODEL_PATH
    try:
        os.makedirs(_MODEL_DIR, exist_ok=True)
        print('Downloading hand landmark model (~7.5 MB)...')
        def _prog(block, bs, total):
            pct = min(100, block * bs * 100 // total) if total > 0 else 0
            sys.stdout.write(f'\r  {pct:3d}%')
            sys.stdout.flush()
        urllib.request.urlretrieve(_MODEL_URL, _MODEL_PATH, _prog)
        print(f'\nDone.')
        return _MODEL_PATH
    except Exception as e:
        print(f'\nERROR: {e}')
        if os.path.exists(_MODEL_PATH):
            os.remove(_MODEL_PATH)
        return None


def _dist(a, b) -> float:
    return ((a.x - b.x) ** 2 + (a.y - b.y) ** 2) ** 0.5


def _finger_states(lm) -> list:
    """
    [thumb_up, index_up, middle_up, ring_up, pinky_up]

    Thumb: distance-from-wrist method (works for any hand orientation).
    Others: tip.y < pip.y (tip above PIP joint in normalised coords).
    """
    wrist    = lm[0]
    thumb_up = _dist(lm[4], wrist) > _dist(lm[3], wrist) + THUMB_MARGIN
    return [
        thumb_up,
        lm[8].y  < lm[6].y,
        lm[12].y < lm[10].y,
        lm[16].y < lm[14].y,
        lm[20].y < lm[18].y,
    ]


def _classify(fingers) -> Optional[str]:
    t, i, m, r, p = fingers
    if t and i and m and r and p:                 return 'OPEN_PALM'
    if not t and i and m and r and p:             return 'FOUR_FINGERS'
    if not any([t, i, m, r, p]):                  return 'FIST'
    if t and not i and not m and not r and not p: return 'THUMBS_UP'
    if not t and i and m and r and not p:         return 'THREE_FINGERS'
    if not t and i and m and not r and not p:     return 'PEACE'
    if not t and not i and not m and not r and p: return 'PINKY_ONLY'
    return None


def _draw_landmarks(cv2, frame, lm) -> None:
    h, w = frame.shape[:2]
    pts  = [(int(p.x * w), int(p.y * h)) for p in lm]
    for a, b in _HAND_CONNECTIONS:
        cv2.line(frame, pts[a], pts[b], (0, 200, 100), 1, cv2.LINE_AA)
    for px, py in pts:
        cv2.circle(frame, (px, py), 4, (0, 255, 140), -1)


def _draw_ui(cv2, frame, gesture, pending_count, log, fps, fs) -> None:
    h, w = frame.shape[:2]

    # ── Top bar ────────────────────────────────────────────────────────────────
    cv2.rectangle(frame, (0, 0), (w, 34), (15, 15, 30), -1)
    if gesture:
        emoji, action, _ = GESTURE_INFO.get(gesture, ('?', gesture, ''))
        label = f'  {action}  '
        color = (0, 230, 110)
    else:
        label, color = '  Waiting for gesture...  ', (120, 120, 120)
    cv2.putText(frame, f'REHBAR |{label}',
                (6, 23), cv2.FONT_HERSHEY_SIMPLEX, 0.54, color, 1, cv2.LINE_AA)

    # ── Confirm progress bar ───────────────────────────────────────────────────
    bar_w = int(w * min(pending_count, CONFIRM_FRAMES) / CONFIRM_FRAMES)
    if bar_w > 0:
        cv2.rectangle(frame, (0, 34), (bar_w, 40), (0, 210, 120), -1)
    cv2.rectangle(frame, (0, 34), (w, 40), (40, 40, 60), 1)

    # ── Finger state boxes  T  I  M  R  P ──────────────────────────────────────
    labels = ['T', 'I', 'M', 'R', 'P']
    bx = w - 94
    for idx, (name, state) in enumerate(zip(labels, fs)):
        clr = (0, 220, 100) if state else (60, 60, 80)
        cv2.rectangle(frame, (bx + idx*17, 44), (bx + idx*17 + 14, 60), clr, -1)
        cv2.putText(frame, name, (bx + idx*17 + 3, 57),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.30, (0, 0, 0), 1)

    # ── FPS ────────────────────────────────────────────────────────────────────
    cv2.putText(frame, f'FPS {fps:.0f}', (6, 56),
                cv2.FONT_HERSHEY_SIMPLEX, 0.36, (100, 150, 220), 1)

    # ── Action log ─────────────────────────────────────────────────────────────
    cv2.rectangle(frame, (0, h - 82), (w, h), (15, 15, 30), -1)
    cv2.putText(frame, 'Log:', (6, h - 68),
                cv2.FONT_HERSHEY_SIMPLEX, 0.33, (130, 130, 190), 1)
    for k, entry in enumerate(list(log)[:5]):
        grey = max(80, 220 - k * 32)
        cv2.putText(frame, entry, (6, h - 56 + k * 13),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.31, (grey, grey, grey), 1)

    # ── Quick cheat sheet ──────────────────────────────────────────────────────
    tips = 'Palm=Scr  Up=Vol+  Fist=Vol-  V=Zoom+  3=Zoom-  4=NxtTab  Pnk=PrvTab'
    cv2.putText(frame, tips, (4, h - 3),
                cv2.FONT_HERSHEY_SIMPLEX, 0.25, (70, 110, 170), 1)


# ── Main ───────────────────────────────────────────────────────────────────────

def run_test(show_ui: bool = True) -> None:
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
        print('ERROR: Model unavailable.')
        sys.exit(1)

    base_options = mp_tasks.BaseOptions(model_asset_path=model_path)
    options = mp_vision.HandLandmarkerOptions(
        base_options=base_options,
        running_mode=mp_vision.RunningMode.IMAGE,
        num_hands=1,
        min_hand_detection_confidence=0.65,
        min_tracking_confidence=0.55,
    )

    cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
    if not cap.isOpened():
        print('ERROR: Cannot open webcam.')
        sys.exit(1)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH,  640)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
    cap.set(cv2.CAP_PROP_FPS,          30)

    # ── Print guide ────────────────────────────────────────────────────────────
    print('\n' + '=' * 62)
    print('  REHBAR Gesture Detection Test')
    print('=' * 62)
    print('  Press Q (or ESC) to quit\n')
    print(f'  {"Gesture":<16}  {"Emoji"}  {"Action":<22}  Shape')
    print('  ' + '-' * 58)
    for key, (emoji, action, shape) in GESTURE_INFO.items():
        print(f'  {key:<16}  {emoji}      {action:<22}  {shape}')
    print('=' * 62 + '\n')

    # ── State ──────────────────────────────────────────────────────────────────
    pending:      dict  = {}
    last_trigger: dict  = {}
    action_log:   deque = deque(maxlen=5)
    frame_count   = 0
    fps_timer     = time.monotonic()
    fps           = 0.0
    fs            = [False] * 5

    try:
        with mp_vision.HandLandmarker.create_from_options(options) as landmarker:
            while True:
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
                    fs      = _finger_states(lm)
                    gesture = _classify(fs)
                    if show_ui:
                        _draw_landmarks(cv2, frame, lm)
                else:
                    fs = [False] * 5

                # ── Debounce ──────────────────────────────────────────────────
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
                            emoji, action, _      = GESTURE_INFO[gesture]
                            ts    = time.strftime('%H:%M:%S')
                            entry = f'{ts}  {emoji}  {action}'
                            action_log.appendleft(entry)
                            print(f'[{ts}] TRIGGERED: {gesture:<16} → {action}')
                else:
                    pending.clear()

                # ── FPS ───────────────────────────────────────────────────────
                frame_count += 1
                if time.monotonic() - fps_timer >= 1.0:
                    fps         = frame_count / (time.monotonic() - fps_timer)
                    frame_count = 0
                    fps_timer   = time.monotonic()

                # ── Display ───────────────────────────────────────────────────
                if show_ui:
                    _draw_ui(cv2, frame, gesture,
                             pending.get(gesture, 0) if gesture else 0,
                             action_log, fps, fs)
                    cv2.imshow('REHBAR Gesture Test  [Q / ESC to quit]', frame)
                    key = cv2.waitKey(1) & 0xFF
                    if key in (ord('q'), 27):
                        break
                else:
                    if gesture:
                        f_str = ''.join('1' if x else '0' for x in fs)
                        sys.stdout.write(
                            f'\r  [{f_str}] {gesture:<16}  '
                            f'{pending.get(gesture,0)}/{CONFIRM_FRAMES} frames  ')
                        sys.stdout.flush()

    except KeyboardInterrupt:
        print('\nInterrupted.')
    except Exception as e:
        print(f'\nERROR during detection: {e}')
        raise
    finally:
        cap.release()
        try:
            cv2.destroyAllWindows()
        except Exception:
            pass

    # ── Session summary ────────────────────────────────────────────────────────
    print('\n\nSession Summary')
    print('-' * 40)
    if action_log:
        counts = Counter()
        for entry in action_log:
            parts = entry.split('  ')
            if len(parts) >= 3:
                counts[parts[2]] += 1
        for action, n in counts.most_common():
            print(f'  {action:<26} ×{n}')
    else:
        print('  No gestures triggered.')
    print('-' * 40)


if __name__ == '__main__':
    run_test(show_ui='--no-ui' not in sys.argv)
