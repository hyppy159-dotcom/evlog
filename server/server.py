#!/usr/bin/env python3
"""
EV 차계부 서버 — 시놀로지 NAS의 도커에서 돌아가는 작은 기록 서버.

파이썬 표준 라이브러리만 씁니다. 설치할 패키지가 없어서 이미지가 가볍고,
NAS에서 빌드가 몇 초면 끝납니다. 데이터는 SQLite 파일 하나에 들어갑니다.

로그인은 두 가지로 나뉩니다.
  · 앱(차·휴대폰) → Authorization: Bearer <EVLOG_TOKEN>
  · 웹페이지     → 아이디 / 비밀번호. 처음 열었을 때 계정을 한 번 만듭니다.
                   비밀번호는 PBKDF2-SHA256으로 해시해서 저장하며 원문은 남지 않습니다.

환경 변수
  EVLOG_TOKEN        필수. 앱이 서버에 기록을 올릴 때 쓰는 긴 무작위 문자열.
  EVLOG_DB           기본 /data/evlog.db
  EVLOG_PORT         기본 8080
  EVLOG_RESET_LOGIN  1로 두고 재시작하면 웹 계정을 지웁니다(비밀번호를 잊었을 때).
                     지운 뒤에는 다시 0으로 되돌려 두세요.
"""

import gzip
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import sys
import threading
import time
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB_PATH = os.environ.get("EVLOG_DB", "/data/evlog.db")
PORT = int(os.environ.get("EVLOG_PORT", "8080"))
TOKEN = os.environ.get("EVLOG_TOKEN", "")
RESET_LOGIN = os.environ.get("EVLOG_RESET_LOGIN", "").strip().lower() in ("1", "true", "yes")
WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")
COOKIE_NAME = "evlog_sid"
LEGACY_COOKIE = "evlog_token"  # 예전 토큰 쿠키 — 지우기만 합니다.
MAX_BODY = 32 * 1024 * 1024  # 32MB — 긴 주행의 경로점까지 넉넉히

SESSION_DAYS = 30
PBKDF2_ROUNDS = 200_000
MIN_PASSWORD = 8
USER_RE = re.compile(r"^[A-Za-z0-9._-]{2,32}$")

_db_lock = threading.Lock()
_fail_lock = threading.Lock()
_fails = {}  # ip -> [횟수, 처음 실패 시각]

SCHEMA = """
CREATE TABLE IF NOT EXISTS trips(
  uid TEXT PRIMARY KEY,
  device TEXT NOT NULL DEFAULT '',
  start_ts INTEGER NOT NULL,
  end_ts INTEGER NOT NULL,
  distance_m REAL NOT NULL DEFAULT 0,
  moving_s INTEGER NOT NULL DEFAULT 0,
  total_s INTEGER NOT NULL DEFAULT 0,
  avg_kmh REAL NOT NULL DEFAULT 0,
  max_kmh REAL NOT NULL DEFAULT 0,
  start_soc REAL NOT NULL DEFAULT -1,
  end_soc REAL NOT NULL DEFAULT -1,
  used_wh REAL NOT NULL DEFAULT -1,
  start_lat REAL NOT NULL DEFAULT 0,
  start_lon REAL NOT NULL DEFAULT 0,
  end_lat REAL NOT NULL DEFAULT 0,
  end_lon REAL NOT NULL DEFAULT 0,
  source TEXT NOT NULL DEFAULT 'gps',
  note TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_trips_start ON trips(start_ts DESC);

CREATE TABLE IF NOT EXISTS points(
  trip_uid TEXT NOT NULL,
  seq INTEGER NOT NULL,
  ts INTEGER NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  speed_kmh REAL NOT NULL DEFAULT -1,
  soc REAL NOT NULL DEFAULT -1,
  PRIMARY KEY(trip_uid, seq)
);

CREATE TABLE IF NOT EXISTS charges(
  uid TEXT PRIMARY KEY,
  device TEXT NOT NULL DEFAULT '',
  start_ts INTEGER NOT NULL,
  end_ts INTEGER NOT NULL,
  start_soc REAL NOT NULL DEFAULT -1,
  end_soc REAL NOT NULL DEFAULT -1,
  added_wh REAL NOT NULL DEFAULT 0,
  kind TEXT NOT NULL DEFAULT 'AC',
  max_kw REAL NOT NULL DEFAULT -1,
  cost REAL NOT NULL DEFAULT 0,
  lat REAL NOT NULL DEFAULT 0,
  lon REAL NOT NULL DEFAULT 0,
  manual INTEGER NOT NULL DEFAULT 0,
  note TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_charges_start ON charges(start_ts DESC);

CREATE TABLE IF NOT EXISTS users(
  username TEXT PRIMARY KEY,
  salt TEXT NOT NULL,
  hash TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions(
  sid TEXT PRIMARY KEY,
  username TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
"""


def connect():
    conn = sqlite3.connect(DB_PATH, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    return conn


def init_db():
    d = os.path.dirname(DB_PATH)
    if d and not os.path.isdir(d):
        os.makedirs(d, exist_ok=True)
    with connect() as conn:
        conn.executescript(SCHEMA)


def num(v, default=0.0):
    try:
        if v is None:
            return default
        return float(v)
    except (TypeError, ValueError):
        return default


def integer(v, default=0):
    try:
        if v is None:
            return default
        return int(v)
    except (TypeError, ValueError):
        return default


def text(v, default=""):
    if v is None:
        return default
    return str(v)[:500]


UID_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,64}$")


def valid_uid(v):
    return isinstance(v, str) and bool(UID_RE.match(v))


# --------------------------------------------------------------- 계정

def hash_password(password, salt=None):
    """비밀번호를 소금과 함께 20만 번 늘려 해시합니다. 원문은 저장하지 않습니다."""
    if salt is None:
        salt = secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt.encode("utf-8"), PBKDF2_ROUNDS)
    return salt, digest.hex()


def user_count(conn):
    return conn.execute("SELECT COUNT(*) FROM users").fetchone()[0]


def create_user(conn, username, password):
    salt, digest = hash_password(password)
    conn.execute("INSERT INTO users(username, salt, hash, created_at) VALUES(?,?,?,?)",
                 (username, salt, digest, int(time.time() * 1000)))


def set_password(conn, username, password):
    salt, digest = hash_password(password)
    conn.execute("UPDATE users SET salt=?, hash=? WHERE username=?", (salt, digest, username))
    # 비밀번호를 바꾸면 다른 기기의 로그인은 모두 끊습니다.
    conn.execute("DELETE FROM sessions WHERE username=?", (username,))


def check_password(conn, username, password):
    row = conn.execute("SELECT salt, hash FROM users WHERE username=?", (username,)).fetchone()
    if not row:
        # 없는 아이디도 있는 아이디와 같은 시간이 걸리게 해서 계정 존재 여부를 숨깁니다.
        hash_password(password, "0" * 32)
        return False
    _, digest = hash_password(password, row["salt"])
    return hmac.compare_digest(digest, row["hash"])


def new_session(conn, username):
    sid = secrets.token_urlsafe(32)
    now = int(time.time() * 1000)
    conn.execute("INSERT INTO sessions(sid, username, created_at, expires_at) VALUES(?,?,?,?)",
                 (sid, username, now, now + SESSION_DAYS * 86400 * 1000))
    conn.execute("DELETE FROM sessions WHERE expires_at < ?", (now,))
    return sid


def session_user(conn, sid):
    if not sid:
        return None
    row = conn.execute("SELECT username, expires_at FROM sessions WHERE sid=?", (sid,)).fetchone()
    if not row or row["expires_at"] < int(time.time() * 1000):
        return None
    return row["username"]


def too_many_failures(ip):
    """같은 곳에서 10분 안에 8번 틀리면 잠깐 막습니다."""
    now = time.time()
    with _fail_lock:
        rec = _fails.get(ip)
        if rec and now - rec[1] > 600:
            rec = None
        return bool(rec and rec[0] >= 8)


def note_failure(ip):
    now = time.time()
    with _fail_lock:
        rec = _fails.get(ip)
        if not rec or now - rec[1] > 600:
            _fails[ip] = [1, now]
        else:
            rec[0] += 1
        if len(_fails) > 500:
            for k in [k for k, v in _fails.items() if now - v[1] > 600]:
                _fails.pop(k, None)


def clear_failures(ip):
    with _fail_lock:
        _fails.pop(ip, None)


# --------------------------------------------------------------- 저장

def save_trips(conn, device, trips):
    saved = 0
    points = 0
    for t in trips:
        uid = t.get("uid")
        if not valid_uid(uid):
            continue
        conn.execute(
            """INSERT INTO trips(uid, device, start_ts, end_ts, distance_m, moving_s, total_s,
                                 avg_kmh, max_kmh, start_soc, end_soc, used_wh,
                                 start_lat, start_lon, end_lat, end_lon, source, note, created_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(uid) DO UPDATE SET
                 distance_m=excluded.distance_m, moving_s=excluded.moving_s,
                 total_s=excluded.total_s, avg_kmh=excluded.avg_kmh, max_kmh=excluded.max_kmh,
                 start_soc=excluded.start_soc, end_soc=excluded.end_soc, used_wh=excluded.used_wh,
                 end_lat=excluded.end_lat, end_lon=excluded.end_lon, note=excluded.note""",
            (uid, device, integer(t.get("start_ts")), integer(t.get("end_ts")),
             num(t.get("distance_m")), integer(t.get("moving_s")), integer(t.get("total_s")),
             num(t.get("avg_kmh")), num(t.get("max_kmh")),
             num(t.get("start_soc"), -1), num(t.get("end_soc"), -1), num(t.get("used_wh"), -1),
             num(t.get("start_lat")), num(t.get("start_lon")),
             num(t.get("end_lat")), num(t.get("end_lon")),
             text(t.get("source"), "gps"), text(t.get("note")), int(time.time() * 1000)))
        saved += 1

        raw = t.get("points") or []
        if raw:
            # 같은 주행을 다시 올리면 경로를 통째로 갈아끼웁니다.
            conn.execute("DELETE FROM points WHERE trip_uid=?", (uid,))
            rows = []
            for i, p in enumerate(raw):
                if isinstance(p, (list, tuple)) and len(p) >= 3:
                    ts, lat, lon = p[0], p[1], p[2]
                    spd = p[3] if len(p) > 3 else -1
                    soc = p[4] if len(p) > 4 else -1
                elif isinstance(p, dict):
                    ts, lat, lon = p.get("ts"), p.get("lat"), p.get("lon")
                    spd, soc = p.get("speed_kmh", -1), p.get("soc", -1)
                else:
                    continue
                lat, lon = num(lat), num(lon)
                if not (-90 <= lat <= 90) or not (-180 <= lon <= 180) or (lat == 0 and lon == 0):
                    continue
                rows.append((uid, i, integer(ts), lat, lon, num(spd, -1), num(soc, -1)))
            if rows:
                conn.executemany(
                    "INSERT OR REPLACE INTO points(trip_uid, seq, ts, lat, lon, speed_kmh, soc)"
                    " VALUES(?,?,?,?,?,?,?)", rows)
                points += len(rows)
    return saved, points


def save_charges(conn, device, charges):
    saved = 0
    for c in charges:
        uid = c.get("uid")
        if not valid_uid(uid):
            continue
        conn.execute(
            """INSERT INTO charges(uid, device, start_ts, end_ts, start_soc, end_soc, added_wh,
                                   kind, max_kw, cost, lat, lon, manual, note, created_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(uid) DO UPDATE SET
                 end_ts=excluded.end_ts, end_soc=excluded.end_soc, added_wh=excluded.added_wh,
                 kind=excluded.kind, max_kw=excluded.max_kw, cost=excluded.cost,
                 note=excluded.note""",
            (uid, device, integer(c.get("start_ts")), integer(c.get("end_ts")),
             num(c.get("start_soc"), -1), num(c.get("end_soc"), -1), num(c.get("added_wh")),
             text(c.get("kind"), "AC"), num(c.get("max_kw"), -1), num(c.get("cost")),
             num(c.get("lat")), num(c.get("lon")),
             1 if c.get("manual") else 0, text(c.get("note")), int(time.time() * 1000)))
        saved += 1
    return saved


# --------------------------------------------------------------- 조회

def list_trips(conn, limit, offset):
    rows = conn.execute(
        "SELECT * FROM trips ORDER BY start_ts DESC LIMIT ? OFFSET ?", (limit, offset)).fetchall()
    out = []
    for r in rows:
        d = dict(r)
        # 점이 하나뿐이면 선이 안 그려지므로 경로로 치지 않습니다.
        d["has_route"] = conn.execute(
            "SELECT COUNT(*) FROM (SELECT 1 FROM points WHERE trip_uid=? LIMIT 2)",
            (r["uid"],)).fetchone()[0] >= 2
        out.append(d)
    return out


def get_trip(conn, uid):
    r = conn.execute("SELECT * FROM trips WHERE uid=?", (uid,)).fetchone()
    if not r:
        return None
    d = dict(r)
    d["points"] = [
        [p["ts"], p["lat"], p["lon"], p["speed_kmh"], p["soc"]]
        for p in conn.execute(
            "SELECT ts, lat, lon, speed_kmh, soc FROM points WHERE trip_uid=? ORDER BY seq", (uid,))
    ]
    return d


def stats(conn):
    def one(sql, args=()):
        v = conn.execute(sql, args).fetchone()[0]
        return v if v is not None else 0

    month_start = int((time.time() - time.localtime().tm_mday * 86400 + 86400) * 1000)
    # 이번 달 1일 0시를 정확히 계산
    lt = time.localtime()
    month_start = int(time.mktime((lt.tm_year, lt.tm_mon, 1, 0, 0, 0, 0, 0, -1)) * 1000)

    total_m = one("SELECT SUM(distance_m) FROM trips")
    month_m = one("SELECT SUM(distance_m) FROM trips WHERE start_ts>=?", (month_start,))
    eff_m = one("SELECT SUM(distance_m) FROM trips WHERE used_wh>0")
    eff_wh = one("SELECT SUM(used_wh) FROM trips WHERE used_wh>0")
    return {
        "trip_count": one("SELECT COUNT(*) FROM trips"),
        "charge_count": one("SELECT COUNT(*) FROM charges"),
        "total_km": round(total_m / 1000.0, 1),
        "month_km": round(month_m / 1000.0, 1),
        "month_cost": round(one("SELECT SUM(cost) FROM charges WHERE start_ts>=?", (month_start,))),
        "month_kwh": round(one("SELECT SUM(added_wh) FROM charges WHERE start_ts>=?", (month_start,)) / 1000.0, 1),
        "total_cost": round(one("SELECT SUM(cost) FROM charges")),
        "avg_efficiency": round((eff_m / 1000.0) / (eff_wh / 1000.0), 2) if eff_m > 0 and eff_wh > 0 else None,
        "point_count": one("SELECT COUNT(*) FROM points"),
    }


# --------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "EvLog"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    # ---- 공통 ----

    def send_json(self, obj, code=200, extra_headers=None):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra_headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def send_bytes(self, body, ctype, code=200, extra_headers=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        for k, v in (extra_headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def read_body(self):
        length = integer(self.headers.get("Content-Length"), 0)
        if length <= 0 or length > MAX_BODY:
            return b""
        data = self.rfile.read(length)
        if (self.headers.get("Content-Encoding") or "").lower() == "gzip":
            try:
                data = gzip.decompress(data)
            except OSError:
                return b""
        return data

    def cookie(self, name):
        raw = self.headers.get("Cookie")
        if not raw:
            return None
        try:
            c = SimpleCookie()
            c.load(raw)
            if name in c:
                return c[name].value
        except Exception:
            pass
        return None

    def bearer_ok(self):
        """앱(차·휴대폰)이 기록을 올릴 때 쓰는 토큰."""
        auth = self.headers.get("Authorization") or ""
        if not auth.lower().startswith("bearer "):
            return False
        return hmac.compare_digest(auth[7:].strip(), TOKEN)

    def web_user(self):
        """웹페이지 쿠키가 살아 있으면 아이디를, 아니면 None."""
        sid = self.cookie(COOKIE_NAME)
        if not sid:
            return None
        with connect() as conn:
            return session_user(conn, sid)

    def authorized(self):
        return self.bearer_ok() or self.web_user() is not None

    def client_ip(self):
        fwd = self.headers.get("X-Forwarded-For")
        if fwd:
            return fwd.split(",")[0].strip()[:60]
        return self.client_address[0]

    def is_https(self):
        return (self.headers.get("X-Forwarded-Proto") or "").lower() == "https"

    def session_cookie(self, sid, max_age=None):
        secure = "; Secure" if self.is_https() else ""
        age = SESSION_DAYS * 86400 if max_age is None else max_age
        return "%s=%s; Path=/; HttpOnly; SameSite=Lax; Max-Age=%d%s" % (
            COOKIE_NAME, sid, age, secure)

    def json_body(self):
        try:
            obj = json.loads(self.read_body().decode("utf-8"))
            return obj if isinstance(obj, dict) else {}
        except Exception:
            return {}

    # ---- 라우팅 ----

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        query = {}
        if "?" in self.path:
            for part in self.path.split("?", 1)[1].split("&"):
                if "=" in part:
                    k, v = part.split("=", 1)
                    query[k] = v

        if path == "/healthz":
            return self.send_json({"ok": True})

        if path == "/auth/state":
            with connect() as conn:
                need_setup = user_count(conn) == 0
            who = None if need_setup else self.web_user()
            return self.send_json({
                "setup_needed": need_setup,
                "logged_in": who is not None,
                "user": who,
            })

        if path in ("/", "/index.html"):
            return self.serve_web("index.html")

        if path.startswith("/api/"):
            if not self.authorized():
                return self.send_json({"error": "unauthorized"}, 401)
            return self.api_get(path, query)

        # 그 밖의 정적 파일
        name = path.lstrip("/")
        if re.fullmatch(r"[A-Za-z0-9_.-]+", name or ""):
            return self.serve_web(name)
        return self.send_json({"error": "not found"}, 404)

    def api_get(self, path, query):
        limit = max(1, min(integer(query.get("limit"), 200), 2000))
        offset = max(0, integer(query.get("offset"), 0))
        with connect() as conn:
            if path == "/api/v1/trips":
                return self.send_json({"trips": list_trips(conn, limit, offset)})
            m = re.fullmatch(r"/api/v1/trips/([A-Za-z0-9_.:-]{1,64})", path)
            if m:
                t = get_trip(conn, m.group(1))
                return self.send_json(t or {"error": "not found"}, 200 if t else 404)
            if path == "/api/v1/charges":
                rows = conn.execute(
                    "SELECT * FROM charges ORDER BY start_ts DESC LIMIT ? OFFSET ?",
                    (limit, offset)).fetchall()
                return self.send_json({"charges": [dict(r) for r in rows]})
            if path == "/api/v1/stats":
                return self.send_json(stats(conn))
            if path == "/api/v1/known":
                rows = conn.execute("SELECT uid FROM trips").fetchall()
                crow = conn.execute("SELECT uid FROM charges").fetchall()
                return self.send_json({
                    "trips": [r["uid"] for r in rows],
                    "charges": [r["uid"] for r in crow],
                })
        return self.send_json({"error": "not found"}, 404)

    def do_POST(self):
        path = self.path.split("?", 1)[0]

        if path == "/setup":
            return self.do_setup()

        if path == "/login":
            return self.do_login()

        if path == "/auth/password":
            return self.do_change_password()

        if path == "/logout":
            sid = self.cookie(COOKIE_NAME)
            if sid:
                with _db_lock, connect() as conn:
                    conn.execute("DELETE FROM sessions WHERE sid=?", (sid,))
                    conn.commit()
            return self.send_json({"ok": True}, 200, {
                "Set-Cookie": self.session_cookie("", 0)})

        if not self.authorized():
            return self.send_json({"error": "unauthorized"}, 401)

        if path == "/api/v1/sync":
            body = self.read_body()
            if not body:
                return self.send_json({"error": "empty body"}, 400)
            try:
                payload = json.loads(body.decode("utf-8"))
            except Exception:
                return self.send_json({"error": "bad json"}, 400)
            device = text(payload.get("device"), "unknown")
            trips = payload.get("trips") or []
            charges = payload.get("charges") or []
            if not isinstance(trips, list) or not isinstance(charges, list):
                return self.send_json({"error": "bad payload"}, 400)
            with _db_lock, connect() as conn:
                nt, npoints = save_trips(conn, device, trips)
                nc = save_charges(conn, device, charges)
                conn.commit()
            return self.send_json({"ok": True, "trips": nt, "points": npoints, "charges": nc})

        return self.send_json({"error": "not found"}, 404)

    # ---- 로그인 ----

    def do_setup(self):
        """계정이 하나도 없을 때 딱 한 번, 아이디와 비밀번호를 정합니다."""
        body = self.json_body()
        username = str(body.get("user", "")).strip().lower()
        password = str(body.get("pass", ""))
        if not USER_RE.match(username):
            return self.send_json(
                {"error": "아이디는 영문·숫자·. _ - 만 써서 2~32자로 지어 주세요."}, 400)
        if len(password) < MIN_PASSWORD:
            return self.send_json(
                {"error": "비밀번호는 %d자 이상이어야 합니다." % MIN_PASSWORD}, 400)
        with _db_lock, connect() as conn:
            if user_count(conn) > 0:
                return self.send_json({"error": "이미 계정이 있습니다."}, 409)
            create_user(conn, username, password)
            sid = new_session(conn, username)
            conn.commit()
        return self.send_json({"ok": True, "user": username}, 200,
                              {"Set-Cookie": self.session_cookie(sid)})

    def do_login(self):
        ip = self.client_ip()
        if too_many_failures(ip):
            time.sleep(1.0)
            return self.send_json(
                {"error": "여러 번 틀렸습니다. 10분 뒤에 다시 해 주세요."}, 429)
        body = self.json_body()
        username = str(body.get("user", "")).strip().lower()
        password = str(body.get("pass", ""))
        with connect() as conn:
            if user_count(conn) == 0:
                return self.send_json({"error": "setup required", "setup_needed": True}, 409)
            ok = bool(username) and bool(password) and check_password(conn, username, password)
        if not ok:
            note_failure(ip)
            time.sleep(0.5)
            return self.send_json({"error": "아이디나 비밀번호가 맞지 않습니다."}, 401)
        clear_failures(ip)
        with _db_lock, connect() as conn:
            sid = new_session(conn, username)
            conn.commit()
        return self.send_json({"ok": True, "user": username}, 200,
                              {"Set-Cookie": self.session_cookie(sid)})

    def do_change_password(self):
        who = self.web_user()
        if not who:
            return self.send_json({"error": "unauthorized"}, 401)
        body = self.json_body()
        current = str(body.get("current", ""))
        fresh = str(body.get("new", ""))
        if len(fresh) < MIN_PASSWORD:
            return self.send_json(
                {"error": "새 비밀번호는 %d자 이상이어야 합니다." % MIN_PASSWORD}, 400)
        with connect() as conn:
            if not check_password(conn, who, current):
                note_failure(self.client_ip())
                time.sleep(0.5)
                return self.send_json({"error": "지금 비밀번호가 맞지 않습니다."}, 401)
        with _db_lock, connect() as conn:
            set_password(conn, who, fresh)
            sid = new_session(conn, who)
            conn.commit()
        return self.send_json({"ok": True}, 200, {"Set-Cookie": self.session_cookie(sid)})

    # ---- 정적 파일 ----

    def serve_web(self, name):
        full = os.path.join(WEB_DIR, name)
        if not os.path.isfile(full):
            return self.send_json({"error": "not found"}, 404)
        ctype = {
            ".html": "text/html; charset=utf-8",
            ".js": "application/javascript; charset=utf-8",
            ".css": "text/css; charset=utf-8",
            ".svg": "image/svg+xml",
        }.get(os.path.splitext(name)[1], "application/octet-stream")
        with open(full, "rb") as f:
            return self.send_bytes(f.read(), ctype)


def main():
    global TOKEN
    if not TOKEN:
        TOKEN = secrets.token_urlsafe(24)
        sys.stderr.write(
            "\n[경고] EVLOG_TOKEN 이 설정되지 않아 임시 토큰을 만들었습니다.\n"
            "        재시작하면 바뀌니 docker-compose.yml 에 넣어 주세요.\n"
            "        임시 토큰: %s\n\n" % TOKEN)
    init_db()
    if RESET_LOGIN:
        with connect() as conn:
            conn.execute("DELETE FROM sessions")
            conn.execute("DELETE FROM users")
            conn.commit()
        sys.stderr.write(
            "[알림] EVLOG_RESET_LOGIN 때문에 웹 계정을 지웠습니다.\n"
            "        웹페이지를 열어 아이디·비밀번호를 새로 정하고,\n"
            "        그 뒤 이 환경 변수는 다시 0으로 되돌려 주세요.\n")
    with connect() as conn:
        if user_count(conn) == 0:
            sys.stderr.write("[알림] 웹 계정이 없습니다. 웹페이지를 열면 처음에 만들게 됩니다.\n")
    sys.stderr.write("EV 차계부 서버 시작 — 포트 %d, DB %s\n" % (PORT, DB_PATH))
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
