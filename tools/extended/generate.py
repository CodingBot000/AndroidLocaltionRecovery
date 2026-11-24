"""Build the v2 maps, public replay catalog and separate held-out evaluation files."""

import argparse
import json
from pathlib import Path

from driving import add_neighborhood, receiver, trajectory
from maps import MAPS

# Map, duration, planned route, title, Korean title, and fault category.
CASES = [
    (1, 100, "ABCD", "Across the grid", "격자 지구 정상 주행", "Normal"),
    (2, 100, "ABC", "Ramp GPS jumps", "진입로 위치 점프", "Jump"),
    (7, 100, "ABC", "Slow residential drive", "주택가 저속 주행", "Low speed"),
    (1, 110, "AEFDM", "Noisy side streets", "도로 편향과 품질 저하", "Quality"),
    (9, 100, "ABCD", "Delayed and repeated fixes", "지연·중복·역순", "Time"),
    (3, 100, "ATC", "Short tunnel loss", "터널의 짧은 신호 손실", "Loss"),
    (3, 120, "ATC", "Long tunnel loss", "터널의 긴 신호 손실", "Loss"),
    (6, 110, "ABC", "Braking on the bridge", "교량에서 감속·정차", "Loss"),
    (8, 110, "ABT", "Unstable recovery on bends", "굽잇길의 불안정한 복구", "Recovery"),
    (4, 100, "ABC", "Parallel-road ambiguity", "평행도로 매칭 모호성", "Ambiguity"),
    (2, 120, "ABCD", "Exit ramp departure", "램프로 실제 경로 이탈", "Departure"),
    (4, 100, "ABC", "One fix on the next road", "평행도로의 단발 관측", "Ambiguity"),
    (5, 120, "ABC", "Flyover departure and return", "입체 교차로 이탈·복귀", "Departure"),
    (7, 90, "ABFG", "Waiting for the first fix", "첫 유효 위치 대기", "Acquisition"),
    (9, 110, "AFGHD", "Replay across the corridor", "복합 회랑 재생 제어", "Replay"),
    (10, 120, "ABCTEFG", "A turn during signal loss", "신호 손실 중 실제 이탈", "Departure"),
    (6, 100, "HEFG", "Invalid fixes by the river", "수변에서 비정상 관측", "Time"),
    (10, 180, "ABCTEFG", "Across the recovery lab", "복합 회랑 종합 주행", "Mixed"),
]
CATEGORY_KO = dict(zip(("Normal", "Jump", "Low speed", "Quality", "Time", "Loss", "Recovery",
                        "Ambiguity", "Departure", "Acquisition", "Replay", "Mixed"),
                       ("정상", "위치 점프", "저속", "품질", "시각", "신호 손실", "복구", "모호성", "이탈", "최초 획득", "재생", "종합")))
HELD_OUT = (4, 11, 7, 10, 13, 17, 14, 9, 5, 16)


def build_case(number, seed, held_out=False):
    map_number, duration, route, title, ko, kind = CASES[number - 1]
    map_id = f"M{map_number:02}"
    if held_out and number in (4, 14):
        route = {4: "HGCDM", 14: "ABC"}[number]
    actual = route
    times = None
    stops = [(19, 24)] if number in (1, 3, 14, 15) else []
    corrections = {}
    expected = []
    recoveries = []
    shift = 2 if held_out else 0

    def add(second, action):
        corrections.setdefault(second, []).append(action)

    def interval(first, last, action):
        for second in range(first, last):
            add(second, action)

    def check(reason, count=1, second=None):
        expected.append(dict(reason=reason, count=count, time=None if second is None else second * 1000))

    def outage(first, last, *, recovery=True):
        interval(first, last, "drop")
        check("SIGNAL_LOST", second=first + 2)
        if recovery:
            check("SIGNAL_RECOVERED", second=last + 2)
            recoveries.append(last * 1000)

    if number == 2:
        for second in (32 + shift, 68 + shift):
            add(second, "jump")
            check("IMPLAUSIBLE_MOVE", second=second)
    elif number == 3:
        interval(14, 32, "heading")
    elif number == 4:
        interval(21, 29, "shake")
        interval(37 + shift, 48 + shift, "bias")
        interval(54 + shift, 56 + shift, "quality")
        check("LOW_QUALITY", 2)
    elif number == 5:
        for second, fault, reason in [(25, "duplicate", "DUPLICATE_ID"), (41, "order", "MEASUREMENT_ORDER"),
                                      (56, "collision", "MEASUREMENT_ORDER")]:
            add(second + shift, fault)
            check(reason, second=second + shift)
        add(65 + shift, "delay")
        add(66 + shift, "drop")
        # A delayed observation newer than the watermark arrives after an explicit outage.
        interval(61 + shift, 65 + shift, "drop")
        check("DELIVERY_DELAY", second=66 + shift)
        check("SIGNAL_LOST", second=63 + shift)
        check("SIGNAL_RECOVERED", second=69 + shift)
        recoveries.append((67 + shift) * 1000)
    elif number in (6, 7):
        times = [0, 29, duration - 29, duration]
        first, last = (41, 48) if number == 6 else (41 + shift, 88 + shift)
        outage(first, last)
        check("DR_STARTED", 1)
        check("DR_LIMIT", int(number == 7))
    elif number == 8:
        stops = [(46, 55)]
        outage(42, 57)
        check("DR_STARTED")
        check("DR_LIMIT")
    elif number == 9:
        first, last = 44 + shift, 58 + shift
        outage(first, last, recovery=False)
        add(last, "quality")
        interval(last + 2, last + 4, "drop")
        add(last + 4, "poor")
        check("LOW_QUALITY", second=last)
        check("RECOVERY_TIMEOUT", second=last + 3)
        check("SIGNAL_RECOVERED", second=last + 7)
        recoveries.append((last + 1) * 1000)
    elif number == 10:
        outage(42 + shift, 53 + shift, recovery=False)
        interval(53 + shift, 56 + shift, "parallel")
        check("AMBIGUOUS", second=55 + shift)
        check("SIGNAL_RECOVERED", second=55 + shift)
        recoveries.append((53 + shift) * 1000)
    elif number == 11:
        actual = "ABXYZ"
        times = [0, 21, 48, 72, 94, 120] if not held_out else [0, 22, 51, 74, 96, 120]
        check("REROUTE_RECOMMENDED")
        if held_out:
            add(32, "jump")
            check("IMPLAUSIBLE_MOVE", second=32)
    elif number == 12:
        add(51, "side_jump")
    elif number == 13:
        actual = "AXYZC"
        times = [0, 35, 63, 78, 92, 120] if not held_out else [0, 37, 65, 81, 94, 120]
        outage(70 + shift, 77 + shift)
        check("REROUTE_RECOMMENDED")
        check("ROUTE_RETURNED")
    elif number == 14:
        interval(0, 6 + shift, "drop")
        interval(6 + shift, 9 + shift, "quality")
        check("LOW_QUALITY", 3)
        check("ACCEPTED", second=9 + shift)
    elif number == 16:
        actual = "ABCTEXY"
        times = [0, 13, 26, 46, 65, 82, 103, 120]
        outage(72 + shift, 92 + shift)
        check("DR_STARTED")
        check("REROUTE_RECOMMENDED")
    elif number == 17:
        for second, action, reason in [(22, "negative_speed", "INVALID_SPEED"),
                                       (41, "future", "FUTURE_MEASUREMENT"), (75, "poor", "ACCURACY_EXCEEDED")]:
            add(second + shift, action)
            check(reason, second=second + shift)
    elif number == 18:
        actual = "ABCTEXYG"
        times = [0, 27, 55, 80, 105, 131, 144, 157, 180]
        stops = [(13, 16)]
        interval(32, 35, "shake")
        add(39, "jump")
        check("IMPLAUSIBLE_MOVE", second=39)
        interval(47, 49, "quality")
        check("LOW_QUALITY", 2)
        outage(82, 106, recovery=False)
        add(106, "poor")
        check("ACCURACY_EXCEEDED", second=106)
        check("DR_STARTED")
        check("DR_LIMIT")
        check("SIGNAL_RECOVERED", second=109)
        recoveries.append(107_000)
        check("REROUTE_RECOMMENDED")
        check("ROUTE_RETURNED")
    check("RUN_COMPLETED", second=duration)
    if number not in (11, 13, 16, 18):
        check("REROUTE_RECOMMENDED", 0)
    if number not in (6, 7, 8, 9, 10, 13, 16, 18, 5):
        check("SIGNAL_LOST", 0)

    truth, speed = trajectory(MAPS[map_id], actual, duration, edge_times=times, stops=stops, seed=seed)
    observations = receiver(truth, speed, corrections, seed)
    scenario_id = ("E" + map_id[1:]) if held_out else ("DEMO_LONG" if number == 18 else f"L{number:02}")
    scenario = dict(schemaVersion=2, scenarioId=scenario_id, title=title, mapId=map_id, seed=seed,
                    duration=duration * 1000, routeVersion=1, plannedRoute=list(route),
                    actualRoute=list(actual), observations=observations, groundTruth=truth,
                    expected=expected, expectedRecoveryAtMs=recoveries, expectedFinalSignal="TRACKING")
    description = f"{MAPS[map_id]['name']} · {kind} · {duration} s. Watch the map and compare the receiver decision with the synthetic drive."
    description_ko = f"{MAPS[map_id]['nameKo']} · {CATEGORY_KO[kind]} · {duration}초. 지도와 관측 판정을 가상 주행 위치와 비교합니다."
    entry = dict(id=scenario_id, mapId=map_id, duration=duration * 1000, title=title, titleKo=ko,
                 kind=kind, kindKo=CATEGORY_KO[kind], description=description, descriptionKo=description_ko,
                 evaluation=held_out, representative=number == 18)
    return entry, scenario


def generate(output):
    for index, (map_id, data) in enumerate(MAPS.items(), 1):
        # Copy the lists so two invocations in a single process remain identical.
        document = dict(data, mapId=map_id)
        add_neighborhood(document, index)
        write(output / "maps" / f"{map_id}.json", document)
    entries = []
    for number, seed, held_out in [(i, 9100 + i, False) for i in range(1, 19)] + [
            (number, 24000 + index, True) for index, number in enumerate(HELD_OUT, 1)]:
        entry, scenario = build_case(number, seed, held_out)
        entries.append(entry)
        folder = "evaluation" if held_out else "scenarios"
        write(output / folder / f"{entry['id']}.json", scenario)
    write(output / "catalog.json", dict(schemaVersion=2, entries=entries,
                                       maps=[dict(id=map_id, name=data["name"], nameKo=data["nameKo"])
                                             for map_id, data in MAPS.items()]))


def write(file, data):
    file.parent.mkdir(parents=True, exist_ok=True)
    # Keep fixtures inspectable while giving each time sample one line.
    rows = []
    for key, value in data.items():
        if isinstance(value, list) and value and isinstance(value[0], dict):
            encoded = "[\n    " + ",\n    ".join(json.dumps(row, ensure_ascii=False, separators=(",", ":"))
                                                   for row in value) + "\n  ]"
        else:
            encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        rows.append(f'  "{key}":{encoded}')
    file.write_text("{\n" + ",\n".join(rows) + "\n}\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="fixtures/extended", type=Path)
    generate(parser.parse_args().output)
