"""
gestures.py  --  Rehbar Gesture Detection Test

Standalone test — shows webcam feed with real-time gesture detection.
Does NOT trigger real system actions. Press Q or ESC to quit.

Usage:
    python gestures.py           # with camera UI (recommended)
    python gestures.py --no-ui   # headless print-only mode

────────────────────────────────────────────────────────
 Gesture         How to do it                  Action
────────────────────────────────────────────────────────
 Open Palm ✋    All 5 fingers spread wide      Screenshot
 Thumbs Up 👍   Thumb out, fist closed         Volume Up
 Fist      ✊   All fingers curled in           Volume Down
 Pinch Out 🤏→  Touch thumb+index, spread out  Zoom In
 Pinch In  🤏←  Spread thumb+index, close in   Zoom Out
 Swipe →        Slap/swipe hand to the RIGHT   Next Tab
 Swipe ←        Slap/swipe hand to the LEFT    Prev Tab
────────────────────────────────────────────────────────
"""

import os
import sys
import time
import urllib.request
from collections import deque, Counter
from typing import Optional

# ── Config (must match gesture_controller.py) ───────────────────────────────────
_APPDATA    = os.getenv('APPDATA', os.path.expanduser('~'))
_MODEL_DIR  = os.path.join(_APPDATA, 'RAHBAR')
_MODEL_PATH = os.path.join(_MODEL_DIR, 'hand_landmarker.task')
_MODEL_URL  = (
    'https://storage.googleapis.com/mediapipe-models/'
    'hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task'
)

STATIC_CONFIRM    = 8
STATIC_COOLDOWN   = 2.0
THUMB_MARGIN      = 0.04
SWIPE_FRAMES      = 10
SWIPE_THRESHOLD   = 0.22
SWIPE_CONSISTENCY = 0.60
PINCH_FRAMES      = 18
PINCH_THRESHOLD   = 0.09
PINCH_MAX_START   = 0.18
PINCH_MIN_END     = 0.02
MOTION_COOLDOWN   = 28

_HAND_CONNECTIONS = [
    (0,1),(1,2),(2,3),(3,4),
    (0,5),(5,6),(6,7),(7,8),
    (0,9),(9,10),(10,11),(11,12),
    (0,13),(13,14),(14,15),(15,16),
    (0,17),(17,18),(18,19),(19,20),
    (5,9),(9,13),(13,17),
]

# (emoji, action, description)
GESTURE_INFO = {
    'OPEN_PALM':   ('✋', 'Screenshot',  'All 5 fingers up'),
    'THUMBS_UP':   ('👍', 'Volume Up',   'Thumb only, rest closed'),
    'FIST':        ('✊', 'Volume Down',  'All fingers curled'),
    'ZOOM_IN':     ('🤏', 'Zoom In',     'Pinch then spread fingers'),
    'ZOOM_OUT':    ('🤏', 'Zoom Out',    'Spread then pinch fingers'),
    'SWIPE_RIGHT': ('→',  'Next Tab',    'Slap/swipe hand to the right'),
    'SWIPE_LEFT':  ('←',  'Prev Tab',    'Slap/swipe hand to the left'),
}


# ── Model download ──────────────────────────────────────────────────────────────

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
        print('\nDone.')
        return _MODEL_PATH
    except Exception as e:
        print(f'\nERROR: {e}')
        if os.path.exists(_MODEL_PATH):
            os.remove(_MODEL_PATH)
        return None


# ── Detection helpers ───────────────────────────────────────────────────────────

def _finger_states(lm) -> list:
    def _d(a, b):
        return ((a.x - b.x)**2 + (a.y - b.y)**2)**0.5
    return [
        _d(lm[4], lm[0]) > _d(lm[3], lm[0]) + THUMB_MARGIN,  # thumb (distance method)
        lm[8].y  < lm[6].y,   # index
        lm[12].y < lm[10].y,  # middle
        lm[16].y < lm[14].y,  # ring
        lm[20].y < lm[18].y,  # pinky
    ]


def _classify_static(fingers) -> Optional[str]:
    t, i, m, r, p = fingers
    if t and i and m and r and p:                 return 'OPEN_PALM'
    if not any([t, i, m, r, p]):                  return 'FIST'
    if t and not i and not m and not r and not p: return 'THUMBS_UP'
    return None


def _pinch_dist(lm) -> float:
    dx = lm[4].x - lm[8].x
    dy = lm[4].y - lm[8].y
    return (dx*dx + dy*dy)**0.5


def _draw_landmarks(cv2, frame, lm) -> None:
    h, w = frame.shape[:2]
    pts  = [(int(p.x * w), int(p.y * h)) for p in lm]
    for a, b in _HAND_CONNECTIONS:
        cv2.line(frame, pts[a], pts[b], (0, 200, 100), 1, cv2.LINE_AA)
    for px, py in pts:
        cv2.circle(frame, (px, py), 4, (0, 255, 140), -1)


# ── Test UI overlay ─────────────────────────────────────────────────────────────

def _draw_ui(cv2, frame, gesture, static_pending, log, fps, fs,
             pinch_d, swipe_dx, wrist_trail, motion_cooling) -> None:
    h, w = frame.shape[:2]

    # ── Wrist trail (swipe visualiser) ─────────────────────────────────────────
    if len(wrist_trail) > 1:
        trail_pts = [(int(x * w), h // 2) for x in wrist_trail]
        for i in range(1, len(trail_pts)):
            alpha = int(180 * i / len(trail_pts))
            cv2.line(frame, trail_pts[i-1], trail_pts[i], (alpha, alpha, 255), 2)
        # Arrow tip
        cv2.arrowedLine(frame, trail_pts[-2], trail_pts[-1],
                        (80, 160, 255), 2, tipLength=0.4)

    # ── Top bar ────────────────────────────────────────────────────────────────
    cv2.rectangle(frame, (0, 0), (w, 36), (15, 15, 30), -1)
    if gesture:
        emoji, action, _ = GESTURE_INFO.get(gesture, ('?', gesture, ''))
        bar_text = f'  {emoji}  {action}  '
        bar_color = (0, 230, 110)
    else:
        bar_text  = '  Waiting for gesture...'
        bar_color = (120, 120, 120)
    cv2.putText(frame, f'REHBAR |{bar_text}',
                (6, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.55, bar_color, 1, cv2.LINE_AA)

    # ── Static confirm bar (fills as you hold a static pose) ───────────────────
    bar_fill = int(w * min(static_pending, STATIC_CONFIRM) / STATIC_CONFIRM)
    if bar_fill > 0:
        cv2.rectangle(frame, (0, 36), (bar_fill, 42), (0, 210, 120), -1)
    cv2.rectangle(frame, (0, 36), (w, 42), (35, 35, 55), 1)

    # ── Finger boxes [T][I][M][R][P] ───────────────────────────────────────────
    lbl  = ['T', 'I', 'M', 'R', 'P']
    bx   = w - 96
    for idx, (name, up) in enumerate(zip(lbl, fs)):
        clr = (0, 210, 100) if up else (55, 55, 75)
        cv2.rectangle(frame, (bx + idx*18, 46), (bx + idx*18 + 14, 62), clr, -1)
        cv2.putText(frame, name, (bx + idx*18 + 3, 59),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.30, (0, 0, 0), 1)

    # ── FPS ────────────────────────────────────────────────────────────────────
    cv2.putText(frame, f'{fps:.0f} fps', (6, 58),
                cv2.FONT_HERSHEY_SIMPLEX, 0.35, (90, 140, 210), 1)

    # ── Cooldown indicator ──────────────────────────────────────────────────────
    if motion_cooling > 0:
        cv2.putText(frame, f'cooldown {motion_cooling}f',
                    (bx - 70, 58), cv2.FONT_HERSHEY_SIMPLEX, 0.30, (180, 100, 60), 1)

    # ── Pinch meter ─────────────────────────────────────────────────────────────
    # Shows current thumb-to-index distance as a horizontal bar
    meter_y  = 70
    meter_w  = w - 12
    meter_d  = min(1.0, pinch_d / 0.30)   # normalise: 0.30 = fully spread
    fill_px  = int(meter_w * meter_d)
    cv2.rectangle(frame, (6, meter_y), (6 + meter_w, meter_y + 8), (30, 30, 50), -1)
    cv2.rectangle(frame, (6, meter_y), (6 + fill_px,  meter_y + 8), (60, 200, 255), -1)
    cv2.putText(frame, f'Pinch {pinch_d:.3f}',
                (6, meter_y + 20), cv2.FONT_HERSHEY_SIMPLEX, 0.30, (60, 180, 230), 1)
    # threshold markers
    lo = int(meter_w * PINCH_MIN_END  / 0.30)
    hi = int(meter_w * PINCH_MAX_START / 0.30)
    cv2.line(frame, (6 + lo, meter_y), (6 + lo, meter_y + 8), (255, 80, 80), 1)
    cv2.line(frame, (6 + hi, meter_y), (6 + hi, meter_y + 8), (255, 200, 80), 1)

    # ── Swipe displacement meter ────────────────────────────────────────────────
    cx       = w // 2
    swipe_y  = meter_y + 30
    cv2.line(frame, (cx, swipe_y - 4), (cx, swipe_y + 4), (80, 80, 120), 1)
    disp_px  = int(swipe_dx * w * 1.2)   # scale for visibility
    end_x    = max(0, min(w, cx + disp_px))
    clr_sw   = (80, 200, 255) if disp_px > 0 else (255, 130, 80)
    cv2.arrowedLine(frame, (cx, swipe_y), (end_x, swipe_y), clr_sw, 2, tipLength=0.3)
    thr_px   = int(SWIPE_THRESHOLD * w * 1.2)
    cv2.line(frame, (cx - thr_px, swipe_y - 3), (cx - thr_px, swipe_y + 3), (200, 80, 80), 1)
    cv2.line(frame, (cx + thr_px, swipe_y - 3), (cx + thr_px, swipe_y + 3), (80, 200, 80), 1)
    cv2.putText(frame, f'Swipe dx {swipe_dx:+.3f}',
                (6, swipe_y + 14), cv2.FONT_HERSHEY_SIMPLEX, 0.30, (80, 170, 220), 1)

    # ── Action log ─────────────────────────────────────────────────────────────
    log_y0 = h - 64
    cv2.rectangle(frame, (0, log_y0 - 10), (w, h), (15, 15, 30), -1)
    cv2.putText(frame, 'Log:', (6, log_y0),
                cv2.FONT_HERSHEY_SIMPLEX, 0.30, (120, 120, 180), 1)
    for k, entry in enumerate(list(log)[:4]):
        grey = max(70, 210 - k * 40)
        cv2.putText(frame, entry, (6, log_y0 + 12 + k * 13),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.30, (grey, grey, grey), 1)

    # ── Cheat sheet ────────────────────────────────────────────────────────────
    cv2.putText(frame,
                'Palm=Scr  Up=Vol+  Fist=Vol-  PinchOut=Zoom+  PinchIn=Zoom-  →=NxtTab  ←=PrvTab',
                (4, h - 3), cv2.FONT_HERSHEY_SIMPLEX, 0.24, (60, 100, 160), 1)


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

    print('\n' + '=' * 64)
    print('  REHBAR Gesture Detection Test')
    print('=' * 64)
    print('  Press Q or ESC to quit\n')
    print(f'  {"Gesture":<14}  {"Emoji"}  {"Action":<20}  How to do it')
    print('  ' + '-' * 60)
    for key, (emoji, action, desc) in GESTURE_INFO.items():
        print(f'  {key:<14}  {emoji}      {action:<20}  {desc}')
    print('=' * 64 + '\n')

    # ── State ──────────────────────────────────────────────────────────────────
    static_pending: dict  = {}
    static_trigger: dict  = {}
    wrist_x_buf:    deque = deque(maxlen=SWIPE_FRAMES)
    pinch_d_buf:    deque = deque(maxlen=PINCH_FRAMES)
    motion_cd       = 0    # frames remaining in motion cooldown
    action_log:     deque = deque(maxlen=4)
    frame_count     = 0
    fps_timer       = time.monotonic()
    fps             = 0.0
    fs              = [False] * 5
    pinch_d         = 0.0
    swipe_dx        = 0.0

    def _log_trigger(gesture: str) -> None:
        emoji, action, _ = GESTURE_INFO.get(gesture, ('?', gesture, ''))
        ts    = time.strftime('%H:%M:%S')
        entry = f'{ts} {emoji} {action}'
        action_log.appendleft(entry)
        print(f'[{ts}] TRIGGERED: {gesture:<14} → {action}')

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

                lm = result.hand_landmarks[0] if result.hand_landmarks else None

                triggered_gesture = None

                # ── Motion detection ──────────────────────────────────────────
                if lm:
                    pinch_d = _pinch_dist(lm)
                    fs      = _finger_states(lm)

                    if motion_cd > 0:
                        motion_cd -= 1
                        wrist_x_buf.append(lm[0].x)
                        pinch_d_buf.append(pinch_d)
                    else:
                        wrist_x_buf.append(lm[0].x)
                        pinch_d_buf.append(pinch_d)

                        # ── Swipe check ───────────────────────────────────────
                        if len(wrist_x_buf) >= SWIPE_FRAMES:
                            xs = list(wrist_x_buf)
                            dx = xs[-1] - xs[0]
                            swipe_dx = dx
                            if abs(dx) >= SWIPE_THRESHOLD:
                                sign = 1 if dx > 0 else -1
                                ok = sum(
                                    1 for i in range(len(xs)-1)
                                    if (xs[i+1]-xs[i])*sign >= 0
                                )
                                if ok >= (len(xs)-1) * SWIPE_CONSISTENCY:
                                    triggered_gesture = 'SWIPE_RIGHT' if dx > 0 else 'SWIPE_LEFT'
                                    motion_cd = MOTION_COOLDOWN
                                    wrist_x_buf.clear()
                                    static_pending.clear()
                        else:
                            swipe_dx = 0.0

                        # ── Pinch check ───────────────────────────────────────
                        if triggered_gesture is None and len(pinch_d_buf) >= PINCH_FRAMES:
                            if lm[8].y < lm[5].y:   # index at least somewhat up
                                ds    = list(pinch_d_buf)
                                q     = max(2, len(ds) // 4)
                                d_old = sum(ds[:q]) / q
                                d_new = sum(ds[-q:]) / q
                                delta = d_new - d_old
                                if delta > PINCH_THRESHOLD and d_old < PINCH_MAX_START:
                                    triggered_gesture = 'ZOOM_IN'
                                    motion_cd = MOTION_COOLDOWN
                                    pinch_d_buf.clear()
                                    static_pending.clear()
                                elif delta < -PINCH_THRESHOLD and d_new > PINCH_MIN_END:
                                    triggered_gesture = 'ZOOM_OUT'
                                    motion_cd = MOTION_COOLDOWN
                                    pinch_d_buf.clear()
                                    static_pending.clear()

                else:
                    # No hand — reset all tracking
                    wrist_x_buf.clear()
                    pinch_d_buf.clear()
                    static_pending.clear()
                    fs      = [False] * 5
                    pinch_d = 0.0
                    swipe_dx = 0.0

                # ── Static pose debounce (only when motion not cooling) ────────
                if triggered_gesture is None and motion_cd == 0 and lm:
                    static = _classify_static(fs)
                    if static:
                        static_pending[static] = static_pending.get(static, 0) + 1
                        for g in list(static_pending):
                            if g != static:
                                static_pending[g] = 0
                        if static_pending[static] >= STATIC_CONFIRM:
                            now  = time.monotonic()
                            last = static_trigger.get(static, 0.0)
                            if now - last >= STATIC_COOLDOWN:
                                static_trigger[static] = now
                                static_pending[static] = 0
                                triggered_gesture = static
                    else:
                        static_pending.clear()

                if triggered_gesture:
                    _log_trigger(triggered_gesture)

                # ── Draw ──────────────────────────────────────────────────────
                if show_ui:
                    if lm:
                        _draw_landmarks(cv2, frame, lm)

                    # Best static pending count for the confirm bar
                    best_pending = max(static_pending.values()) if static_pending else 0

                    _draw_ui(
                        cv2, frame,
                        triggered_gesture,
                        best_pending,
                        action_log,
                        fps,
                        fs,
                        pinch_d,
                        swipe_dx,
                        list(wrist_x_buf),
                        motion_cd,
                    )
                    cv2.imshow('REHBAR Gesture Test  [Q / ESC to quit]', frame)
                    key = cv2.waitKey(1) & 0xFF
                    if key in (ord('q'), 27):
                        break
                else:
                    if lm:
                        f_str = ''.join('1' if x else '0' for x in fs)
                        sys.stdout.write(
                            f'\r  [{f_str}] pd={pinch_d:.3f}  dx={swipe_dx:+.3f}  '
                            f'cd={motion_cd:2d}  ')
                        sys.stdout.flush()

                # ── FPS ───────────────────────────────────────────────────────
                frame_count += 1
                if time.monotonic() - fps_timer >= 1.0:
                    fps         = frame_count / (time.monotonic() - fps_timer)
                    frame_count = 0
                    fps_timer   = time.monotonic()

    except KeyboardInterrupt:
        print('\nInterrupted.')
    except Exception as e:
        print(f'\nERROR: {e}')
        raise
    finally:
        cap.release()
        try:
            cv2.destroyAllWindows()
        except Exception:
            pass

    # ── Summary ────────────────────────────────────────────────────────────────
    print('\n\nSession Summary')
    print('-' * 40)
    if action_log:
        counts = Counter()
        for entry in action_log:
            parts = entry.split(' ')
            if len(parts) >= 3:
                counts[' '.join(parts[2:])] += 1
        for action, n in counts.most_common():
            print(f'  {action:<26} ×{n}')
    else:
        print('  No gestures triggered this session.')
    print('-' * 40)


if __name__ == '__main__':
    run_test(show_ui='--no-ui' not in sys.argv)
