#!/usr/bin/env python3
"""
SYNE 데모 주행 생성기 → app/src/main/assets/trip_demo.json (코스 A), trip_demo_b.json (코스 B)

10Hz 샘플 주행. 각 사운드 반응이 한 번씩 나오도록 구성.

코스 A "집 → 사무실" (약 2분)
  0~5s    P 정차 (출발 전)
  5~8s    D, 브레이크 밟은 채 대기
  8~22s   부드러운 가속 → 40km/h (도심, 제한 50)
  18~22s  좌측 방향지시등 → 22~29s 좌회전
  29~45s  50km/h 순항 (미세 조향)
  45~53s  일반 감속 → 신호 대기 정차(최대 약 2.8 m/s² → 급제동 아님, '잦아듦')
  58~72s  가속 → 62km/h 순항 (간선, 제한 70 — 고속 음색)
  74~80s  76km/h 제한속도 초과 구간 (편성 축소 테스트)
  80~83s  급제동 76 → 25km/h (브레이크 + 최대 약 6 m/s² → SWEEP)
  84~88s  우측 방향지시등 → 88~95s 우회전
  95~108s 40km/h 재가속 (제한 30 구간 진입 → 감속)
  108~115s 정차, 115s 이후 P (도착)

코스 B "사무실 → 카페" (약 2분, A의 도착지에서 출발, 급제동·과속 없는 차분한 주행)

조향 부호 규약: 양수 = 오른쪽, 음수 = 왼쪽 (deg, 핸들 각도).
origin 좌표는 앱 내부 코스 클러스터링 전용(가상 좌표). 화면에는 이름만 나간다.

사용: python3 tools/gen_trip_demo.py
"""
import json
import math
import os

HZ = 10
WHEELBASE_M = 2.9
STEER_RATIO = 15.0

VARIANTS = {
    "a": dict(
        out="trip_demo.json",
        name="SYNE demo trip A",
        origin=dict(lat=37.39470, lon=127.11120, label="분당구 삼평동"),
        dest_name="판교역 사무실",
        heading_deg=90.0,  # 북쪽
        duration=120.0,
        arrive_t=115.0,
        speed_keys=[
            (0, 0), (8, 0), (22, 40), (26, 25), (29, 30), (34, 50), (45, 50),
            (53, 0), (58, 0), (68, 62), (72, 62), (74, 76), (80, 76), (80.1, 68), (83, 25),
            (88, 20), (95, 20), (102, 40), (106, 40), (108, 30), (115, 0), (120, 0),
        ],
        steer_events=[(22.0, 29.0, -115.0), (88.0, 95.0, 160.0), (60.0, 64.0, 25.0), (64.0, 67.0, -20.0)],
        wiggle=[(29, 45), (95, 106)],
        turn_signals=[(18.0, 25.0, "LEFT"), (58.5, 61.0, "RIGHT"), (84.0, 92.0, "RIGHT")],
        brake_spans=[(5.0, 8.0), (45.0, 58.0), (80.0, 83.0), (106.5, 120.0)],
        speed_limits=[(0, 50), (55, 70), (84, 50), (103, 30)],
    ),
    "b": dict(
        out="trip_demo_b.json",
        name="SYNE demo trip B",
        origin=None,  # 코스 A의 도착 지점에서 출발
        origin_label="분당구 백현동",
        dest_name="정자동 카페",
        heading_deg=180.0,  # 서쪽
        duration=118.0,
        arrive_t=110.0,
        speed_keys=[
            (0, 0), (8, 0), (20, 45), (24, 25), (30, 28), (40, 58), (60, 58),
            (70, 0), (74, 0), (84, 40), (88, 22), (95, 25), (100, 30), (110, 0), (118, 0),
        ],
        steer_events=[(24.0, 31.0, 140.0), (88.0, 95.0, -130.0), (44.0, 50.0, 18.0), (50.0, 56.0, -18.0)],
        wiggle=[(31, 60)],
        turn_signals=[(19.0, 27.0, "RIGHT"), (83.0, 91.0, "LEFT")],
        brake_spans=[(5.0, 8.0), (60.0, 74.0), (102.0, 118.0)],
        speed_limits=[(0, 50), (30, 60), (80, 50), (95, 30)],
    ),
}


def smoothstep(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def make(v):
    def speed_at(t):
        keys = v["speed_keys"]
        for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
            if t0 <= t <= t1:
                if t1 - t0 < 0.2:
                    return v1
                return v0 + (v1 - v0) * smoothstep((t - t0) / (t1 - t0))
        return keys[-1][1]

    def steer_at(t):
        s = 0.0
        for t0, t1, peak in v["steer_events"]:
            if t0 <= t <= t1:
                s += peak * math.sin(math.pi * (t - t0) / (t1 - t0))
        if any(a <= t <= b for a, b in v["wiggle"]):
            s += 3.0 * math.sin(t * 0.9) + 1.5 * math.sin(t * 2.3)
        return s

    def in_spans(t, spans):
        return any(t0 <= t < t1 for t0, t1 in spans)

    def turn_at(t):
        for t0, t1, state in v["turn_signals"]:
            if t0 <= t < t1:
                return state
        return "OFF"

    def limit_at(t):
        lim = v["speed_limits"][0][1]
        for t0, val in v["speed_limits"]:
            if t >= t0:
                lim = val
        return lim

    frames = []
    x = y = 0.0
    dist = 0.0
    heading = math.radians(v["heading_deg"])
    dt = 1.0 / HZ
    arrive = v["arrive_t"]
    for i in range(int(v["duration"] * HZ) + 1):
        t = i * dt
        kmh = speed_at(t)
        steer = steer_at(t)
        ms = kmh / 3.6
        # 자전거 모델: 양수 조향 = 오른쪽 = 시계 방향
        heading -= ms / WHEELBASE_M * math.tan(math.radians(steer / STEER_RATIO)) * dt
        x += ms * math.cos(heading) * dt
        y += ms * math.sin(heading) * dt
        dist += ms * dt
        frames.append([
            round(t, 1), round(kmh, 1), round(steer, 1), 1 if in_spans(t, v["brake_spans"]) else 0,
            turn_at(t), "P" if (t < 5.0 or t >= arrive) else "D",
            max(0, int(math.ceil(arrive - t))), limit_at(t), round(x, 1), round(y, 1),
        ])
    return frames, (x, y), dist


def offset(origin, x, y):
    lat = origin["lat"] + y / 111_320.0
    lon = origin["lon"] + x / (111_320.0 * math.cos(math.radians(origin["lat"])))
    return dict(lat=round(lat, 6), lon=round(lon, 6))


def write(path, doc):
    with open(path, "w") as f:
        f.write("{\n")
        for k, val in doc.items():
            if k == "frames":
                continue
            f.write(f"  {json.dumps(k)}: {json.dumps(val, ensure_ascii=False)},\n")
        f.write('  "frames": [\n')
        f.write(",\n".join("    " + json.dumps(fr, separators=(",", ":")) for fr in doc["frames"]))
        f.write("\n  ]\n}\n")


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    assets = os.path.join(root, "app", "src", "main", "assets")
    end_of = {}
    for key in ["a", "b"]:
        v = VARIANTS[key]
        origin = v["origin"]
        if origin is None:  # B는 A의 도착지에서 출발
            origin = dict(end_of["a"], label=v["origin_label"])
        frames, (ex, ey), dist = make(v)
        end = offset(origin, ex, ey)
        end_of[key] = end
        doc = {
            "name": v["name"],
            "version": 2,
            "hz": HZ,
            "durationSec": v["duration"],
            "steeringSign": "positive=right",
            "origin": origin,
            "destination": dict(name=v["dest_name"], lat=end["lat"], lon=end["lon"], distanceM=int(dist)),
            "fields": ["t", "speedKmh", "steeringDeg", "brake", "turn", "gear",
                       "remainSec", "speedLimitKmh", "xM", "yM"],
            "frames": frames,
        }
        write(os.path.join(assets, v["out"]), doc)
        print(f"wrote {v['out']}: {len(frames)} frames, {v['duration']}s, {int(dist)}m, end offset ({ex:.0f},{ey:.0f})m")


if __name__ == "__main__":
    main()
