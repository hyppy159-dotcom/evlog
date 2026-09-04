# EV 차계부 서버 (시놀로지 NAS)

차에서 기록한 주행·충전·경로를 NAS에 모아 두고, 휴대폰 브라우저로 보는 작은 서버입니다.

- **설치할 패키지가 없습니다.** 파이썬 표준 라이브러리만 씁니다. NAS에서 빌드가 몇 초면 끝납니다.
- 데이터는 **SQLite 파일 하나**(`/data/evlog.db`)에 들어갑니다. 그 폴더만 백업하면 전부 백업됩니다.
- 지도는 **OpenStreetMap**을 씁니다. API 키도 결제수단도 필요 없습니다.

---

## 1. NAS에 올리기

1. **File Station**에서 폴더를 만듭니다. 예: `/docker/evlog`
2. 이 `server` 폴더 안의 파일을 전부 그 안에 넣습니다.
   `server.py`, `Dockerfile`, `docker-compose.yml`, `web/index.html`
3. `docker-compose.yml`을 열어 **두 곳**을 고칩니다.
   - `EVLOG_TOKEN` — 아무도 못 맞출 긴 무작위 문자열. 앱과 웹이 함께 쓰는 접속 암호입니다.
     DSM에서 만들려면 SSH로 들어가 `openssl rand -base64 32` 를 치면 됩니다.
   - `volumes` 경로 — 위에서 만든 폴더에 맞게. 예: `/volume1/docker/evlog/data:/data`
4. **Container Manager → 프로젝트 → 생성**
   - 프로젝트 이름: `evlog`
   - 경로: 위에서 만든 폴더
   - 소스: **기존 docker-compose.yml 업로드** (또는 폴더에 있는 것을 그대로 사용)
   - 빌드가 끝나면 컨테이너가 뜹니다.
5. 같은 NAS 안에서 확인: 브라우저로 `http://NAS내부IP:8123` → 토큰 입력 → 화면이 뜨면 성공.

Guacamole 올리실 때와 같은 방식입니다.

---

## 2. 밖에서 접속되게 하기 (DDNS + 리버스 프록시)

차가 밖에서 기록을 올려야 하니 인터넷에서 닿아야 합니다.
**8123 포트를 공유기에서 직접 열지 마세요.** HTTPS 리버스 프록시를 거치는 편이 안전합니다.

1. **제어판 → 외부 액세스 → DDNS**
   이미 쓰시는 도메인이 있으면 그대로 씁니다. (`xxx.synology.me` 같은 것)
2. **제어판 → 보안 → 인증서**
   해당 도메인으로 Let's Encrypt 인증서를 발급받습니다.
   서브도메인을 쓰려면(`evlog.xxx.synology.me`) 인증서 발급 시 **주체 대체 이름**에 넣어 주세요.
3. **제어판 → 로그인 포털 → 고급 → 역방향 프록시 → 생성**

   | 항목 | 값 |
   |---|---|
   | 설명 | evlog |
   | 소스 프로토콜 | HTTPS |
   | 소스 호스트 이름 | `evlog.내도메인` (또는 기존 도메인) |
   | 소스 포트 | 443 |
   | HSTS 사용 | 켬 |
   | 대상 프로토콜 | HTTP |
   | 대상 호스트 이름 | `localhost` |
   | 대상 포트 | `8123` |

   **사용자 지정 헤더** 탭에서 `X-Forwarded-Proto` 를 추가하면(값 `https`)
   로그인 쿠키에 `Secure` 표시가 붙습니다. 넣어 두시길 권합니다.
4. 공유기에서 **443 포트만** NAS로 포워딩합니다. 8123은 열지 않습니다.
5. 브라우저로 `https://evlog.내도메인` 접속 → 토큰 입력.

앱 설정의 **서버 주소**에도 이 주소(`https://evlog.내도메인`)를 그대로 넣습니다.

---

## 3. 안전하게 쓰기

- **토큰을 길게.** 32자 이상 무작위. 이게 유일한 자물쇠입니다.
- **HTTPS로만.** `http://`로 열면 토큰이 그대로 지나갑니다.
- **DSM 방화벽**에서 한국 IP만 허용하거나, 자동 차단(로그인 실패 시)을 켜 두면 더 낫습니다.
- 이 서버는 파이썬 기본 웹서버를 씁니다. 집에서 혼자 쓰기엔 충분하지만
  **인터넷에 그대로 노출하지 말고** 반드시 리버스 프록시 뒤에 두세요.
- 토큰을 바꾸려면 `docker-compose.yml`을 고치고 프로젝트를 다시 빌드하면 됩니다.
  앱 설정에서도 같이 바꿔 주세요.

---

## 4. 백업

`/volume1/docker/evlog/data` 폴더만 챙기면 됩니다.
Hyper Backup에 이 폴더를 넣어 두시면 끝입니다.

---

## 5. 문제가 생기면

| 증상 | 확인할 것 |
|---|---|
| 웹이 안 열림 | Container Manager에서 컨테이너가 실행 중인지, 로그에 오류가 없는지 |
| 토큰이 안 맞다고 함 | `docker-compose.yml`의 `EVLOG_TOKEN`과 입력한 값이 같은지 (앞뒤 공백 주의) |
| 앱이 업로드를 못 함 | 앱 설정의 서버 주소가 `https://`로 시작하는지, 밖에서 그 주소가 열리는지 |
| 지도가 회색 | 인터넷이 안 되는 환경. 지도 타일은 OpenStreetMap에서 받아옵니다 |

컨테이너 로그: Container Manager → 컨테이너 → evlog → 로그

---

## 개발용

로컬에서 그냥 돌려 볼 수 있습니다.

```bash
EVLOG_TOKEN=test EVLOG_DB=/tmp/evlog.db EVLOG_PORT=8123 python3 server.py
python3 selftest.py http://127.0.0.1:8123 test    # API가 제대로 도는지 자체 점검
```

`selftest.py`는 가짜 주행·충전을 올려 보고, 중복 업로드·잘못된 좌표·인증까지 확인합니다.

## API 요약

인증은 `Authorization: Bearer <토큰>` 헤더 또는 로그인 쿠키.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/v1/sync` | 앱이 기록을 올림. `uid` 기준이라 여러 번 올려도 중복되지 않음 |
| GET | `/api/v1/trips?limit=&offset=` | 주행 목록 |
| GET | `/api/v1/trips/{uid}` | 주행 1건 + 경로점 |
| GET | `/api/v1/charges` | 충전 목록 |
| GET | `/api/v1/stats` | 요약 통계 |
| GET | `/healthz` | 상태 확인 (인증 불필요) |
