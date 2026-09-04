#!/usr/bin/env python3
"""프로젝트 정합성 점검: XML 문법, 리소스 참조 누락, 레이아웃-코드 id 대응."""
import io
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
JAVA = os.path.join(ROOT, "app/src/main/java")
MANIFEST = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")

problems = []
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def all_files(base, ext):
    out = []
    for d, _, fs in os.walk(base):
        for f in fs:
            if f.endswith(ext):
                out.append(os.path.join(d, f))
    return sorted(out)


# ---------- 1. XML 문법 ----------
xml_files = all_files(RES, ".xml") + [MANIFEST] + all_files(os.path.join(ROOT, ".github"), ".yml")
for p in all_files(RES, ".xml") + [MANIFEST]:
    try:
        ET.parse(p)
    except Exception as e:
        problems.append("XML 문법 오류 %s: %s" % (os.path.relpath(p, ROOT), e))

# ---------- 2. 정의된 리소스 수집 ----------
defined = {k: set() for k in
           ["string", "color", "style", "dimen", "layout", "drawable", "mipmap",
            "menu", "xml", "id", "array", "bool", "integer"]}

for p in all_files(os.path.join(RES, "values"), ".xml"):
    try:
        root = ET.parse(p).getroot()
    except Exception:
        continue
    for child in root:
        tag = child.tag
        name = child.get("name")
        if not name:
            continue
        if tag in defined:
            defined[tag].add(name)
        elif tag == "item":
            t = child.get("type")
            if t in defined:
                defined[t].add(name)

for kind, folder_prefix in [("layout", "layout"), ("drawable", "drawable"),
                            ("mipmap", "mipmap"), ("menu", "menu"), ("xml", "xml")]:
    for d in os.listdir(RES):
        if d == folder_prefix or d.startswith(folder_prefix + "-"):
            for f in os.listdir(os.path.join(RES, d)):
                defined[kind].add(os.path.splitext(f)[0])

# @+id 수집
id_by_layout = {}
for p in all_files(RES, ".xml"):
    text = io.open(p, encoding="utf-8").read()
    ids = set(re.findall(r'@\+id/([A-Za-z0-9_]+)', text))
    defined["id"] |= ids
    id_by_layout[os.path.splitext(os.path.basename(p))[0]] = ids

# ---------- 3. XML 안의 참조 확인 ----------
ref_re = re.compile(r'@(?:android:)?(string|color|style|dimen|layout|drawable|mipmap|menu|xml|id|array)/([A-Za-z0-9_.]+)')
for p in all_files(RES, ".xml") + [MANIFEST]:
    text = io.open(p, encoding="utf-8").read()
    rel = os.path.relpath(p, ROOT)
    for kind, name in ref_re.findall(text):
        if "@android:" in text and name in ("white", "black"):
            continue
        if "+id/" + name in text and kind == "id":
            continue
        if "." in name:      # 프레임워크/머티리얼 스타일
            continue
        if name not in defined[kind]:
            problems.append("리소스 참조 없음 %s: @%s/%s" % (rel, kind, name))

# ---------- 4. Java 안의 R 참조 확인 ----------
java_files = all_files(JAVA, ".java")
r_re = re.compile(r'\bR\.(string|color|style|dimen|layout|drawable|mipmap|menu|xml|id|array)\.([A-Za-z0-9_]+)')
for p in java_files:
    text = io.open(p, encoding="utf-8").read()
    rel = os.path.relpath(p, ROOT)
    for kind, name in r_re.findall(text):
        if name not in defined[kind]:
            problems.append("R 참조 없음 %s: R.%s.%s" % (rel, kind, name))

# ---------- 5. 레이아웃-코드 id 대응 ----------
# 각 자바 파일이 인플레이트/설정하는 레이아웃을 찾아, findViewById 대상이 그 레이아웃에 있는지 본다.
for p in java_files:
    text = io.open(p, encoding="utf-8").read()
    rel = os.path.relpath(p, ROOT)
    layouts = set(re.findall(r'R\.layout\.([A-Za-z0-9_]+)', text))
    if not layouts:
        continue
    available = set()
    for l in layouts:
        available |= id_by_layout.get(l, set())
    for name in set(re.findall(r'findViewById\(R\.id\.([A-Za-z0-9_]+)\)', text)):
        if name not in available:
            problems.append("레이아웃에 없는 id %s: R.id.%s (레이아웃 %s)"
                            % (rel, name, ",".join(sorted(layouts))))

# ---------- 6. 매니페스트에 선언된 클래스 존재 확인 ----------
mtext = io.open(MANIFEST, encoding="utf-8").read()
pkg = "com.jhkim.evlog"
for cls in set(re.findall(r'android:name="(\.[A-Za-z0-9_.]+)"', mtext)):
    path = os.path.join(JAVA, pkg.replace(".", "/") + cls.replace(".", "/") + ".java")
    if not os.path.exists(path):
        problems.append("매니페스트가 가리키는 클래스 없음: %s" % cls)

# ---------- 7. 커스텀 뷰 클래스 존재 확인 ----------
for p in all_files(RES, ".xml"):
    text = io.open(p, encoding="utf-8").read()
    for cls in set(re.findall(r'<(com\.jhkim\.evlog\.[A-Za-z0-9_.]+)', text)):
        path = os.path.join(JAVA, cls.replace(".", "/") + ".java")
        if not os.path.exists(path):
            problems.append("레이아웃이 가리키는 커스텀 뷰 없음 %s: %s"
                            % (os.path.relpath(p, ROOT), cls))

# ---------- 결과 ----------
if problems:
    print("문제 %d건" % len(problems))
    for x in problems:
        print(" -", x)
    sys.exit(1)
print("이상 없음 — 자바 %d개, XML %d개 점검" % (len(java_files), len(all_files(RES, '.xml')) + 1))
