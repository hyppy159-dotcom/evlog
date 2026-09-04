#!/usr/bin/env python3
"""서버가 제대로 도는지 스스로 확인합니다. NAS에 올리기 전 로컬에서 돌려 보세요.

    EVLOG_TOKEN=test python3 server.py &
    python3 selftest.py http://127.0.0.1:8080 test
"""
import json
import math
import random
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
TOKEN = sys.argv[2] if len(sys.argv) > 2 else "test"

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
failures = []


def call(method, path, body=None, token=TOKEN, expect=200):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with opener.open(req, timeout=20) as r:
            got, payload = r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        got, payload = e.code, {}
    ok = got == expect
    print(("  OK   " if ok else "  FAIL ") + "%-6s %-34s → %s (기대 %s)" % (method, path, got, expect))
    if not ok:
        failures.append(path)
    return payload


def make_trip(uid, start, minutes, km, used_wh, n_points=120):
    """서울 근처를 도는 가짜 주행 한 건."""
    pts = []
    lat0, lon0 = 37.5665, 126.9780
    for i in range(n_points):
        f = i / max(1, n_points - 1)
        lat = lat0 + 0.06 * math.sin(f * math.pi * 1.4) + random.uniform(-0.0004, 0.0004)
        lon = lon0 + 0.09 * f + random.uniform(-0.0004, 0.0004)
        speed = 20 + 90 * abs(math.sin(f * math.pi))
        soc = 80 - 20 * f
        pts.append([start + int(f * minutes * 60000), round(lat, 6), round(lon, 6),
                    round(speed, 1), round(soc, 1)])
    return {
        "uid": uid, "start_ts": start, "end_ts": start + minutes * 60000,
        "distance_m": km * 1000, "moving_s": minutes * 60, "total_s": minutes * 60,
        "avg_kmh": km / (minutes / 60.0), "max_kmh": 112.0,
        "start_soc": 80, "end_soc": 60, "used_wh": used_wh,
        "start_lat": pts[0][1], "start_lon": pts[0][2],
        "end_lat": pts[-1][1], "end_lon": pts[-1][2],
        "source": "car", "note": "", "points": pts,
    }


def main():
    print("서버:", BASE)
    print("\n[1] 인증")
    call("GET", "/healthz", token=None, expect=200)
    call("GET", "/api/v1/stats", token=None, expect=401)
    call("GET", "/api/v1/stats", token="wrong-token", expect=401)
    call("GET", "/api/v1/stats", expect=200)

    print("\n[2] 업로드")
    now = int(time.time() * 1000)
    trips = [make_trip("trip-%d" % i, now - (14 - i) * 86400000,
                       20 + i * 5, 18.0 + i * 4, 3200 + i * 700)
             for i in range(8)]
    charges = [{
        "uid": "chg-%d" % i,
        "start_ts": now - (12 - i) * 86400000,
        "end_ts": now - (12 - i) * 86400000 + 40 * 60000,
        "start_soc": 30, "end_soc": 80, "added_wh": 48000 + i * 1000,
        "kind": "DC" if i % 2 else "AC", "max_kw": 88.0 if i % 2 else 6.6,
        "cost": 16000 + i * 400, "lat": 37.55, "lon": 127.0,
        "manual": False, "note": "테스트 충전 %d" % i,
    } for i in range(5)]

    res = call("POST", "/api/v1/sync", {"device": "polestar4", "trips": trips, "charges": charges})
    print("       올린 결과:", res)
    if res.get("trips") != 8 or res.get("charges") != 5 or res.get("points") != 8 * 120:
        failures.append("sync counts")
        print("  FAIL 저장 건수가 다릅니다")

    print("\n[3] 같은 것을 다시 올려도 중복이 안 생기는지")
    call("POST", "/api/v1/sync", {"device": "polestar4", "trips": trips, "charges": charges})
    s = call("GET", "/api/v1/stats")
    print("       통계:", s)
    if s.get("trip_count") != 8 or s.get("charge_count") != 5 or s.get("point_count") != 960:
        failures.append("duplicate rows")
        print("  FAIL 중복이 생겼습니다")

    print("\n[4] 조회")
    lst = call("GET", "/api/v1/trips?limit=5")
    if len(lst.get("trips", [])) != 5:
        failures.append("trip list limit")
    if lst.get("trips") and not lst["trips"][0].get("has_route"):
        failures.append("has_route")
        print("  FAIL has_route 가 표시되지 않습니다")
    one = call("GET", "/api/v1/trips/trip-3")
    if len(one.get("points", [])) != 120:
        failures.append("points")
        print("  FAIL 경로점 수가 다릅니다:", len(one.get("points", [])))
    call("GET", "/api/v1/trips/does-not-exist", expect=404)
    call("GET", "/api/v1/charges")

    print("\n[5] 잘못된 입력을 견디는지")
    call("POST", "/api/v1/sync", {"trips": [{"uid": "no good uid!"}]}, expect=200)
    call("POST", "/api/v1/sync", {"trips": "not-a-list"}, expect=400)
    call("POST", "/api/v1/sync", {"trips": [{
        "uid": "bad-points", "start_ts": now, "end_ts": now,
        "points": [[now, 999, 999], [now, 0, 0], "쓰레기", [now, 37.5, 127.0]]}]}, expect=200)
    bad = call("GET", "/api/v1/trips/bad-points")
    if len(bad.get("points", [])) != 1:
        failures.append("point filtering")
        print("  FAIL 이상한 좌표가 걸러지지 않았습니다:", bad.get("points"))

    print("\n[6] 웹 페이지")
    req = urllib.request.Request(BASE + "/")
    with opener.open(req, timeout=10) as r:
        html = r.read().decode()
    for needle in ["EV 차계부", "leaflet", "api/v1/stats"]:
        ok = needle in html
        print(("  OK   " if ok else "  FAIL ") + "index.html 에 '%s' 있음" % needle)
        if not ok:
            failures.append("html:" + needle)

    print("\n" + ("모두 통과" if not failures else "실패 %d건: %s" % (len(failures), failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
