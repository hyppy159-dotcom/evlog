# 설치할 패키지가 없습니다. 파이썬 표준 라이브러리만 씁니다.
FROM python:3.12-slim

WORKDIR /app
COPY server.py ./
COPY web ./web

ENV EVLOG_DB=/data/evlog.db \
    EVLOG_PORT=8080 \
    PYTHONUNBUFFERED=1 \
    TZ=Asia/Seoul

EXPOSE 8080
VOLUME ["/data"]

HEALTHCHECK --interval=60s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request,sys;sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/healthz',timeout=3).status==200 else 1)"

CMD ["python", "server.py"]
