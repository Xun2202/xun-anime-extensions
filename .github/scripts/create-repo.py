"""
Collect built extension APKs and generate the extension repo layout expected by
Anikku / Aniyomi:

    repo/
      index.min.json
      index.json
      index.html
      apk/<name>.apk
      icon/<package>.png

Adapted from yuzono/anime-extensions (.github/scripts/create-repo.py + merge-repo.py).
Requires: ANDROID_HOME (aapt in build-tools), output.json produced by the Inspector jar.
"""

import html
import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from zipfile import ZipFile

PACKAGE_NAME_REGEX = re.compile(r"package: name='([^']+)'")
VERSION_CODE_REGEX = re.compile(r"versionCode='([^']+)'")
VERSION_NAME_REGEX = re.compile(r"versionName='([^']+)'")
IS_NSFW_REGEX = re.compile(r"'tachiyomi.animeextension.nsfw' value='([^']+)'")
APPLICATION_LABEL_REGEX = re.compile(r"^application-label:'([^']+)'", re.MULTILINE)
APPLICATION_ICON_320_REGEX = re.compile(r"^application-icon-320:'([^']+)'", re.MULTILINE)
LANGUAGE_REGEX = re.compile(r"aniyomi-([^.]+)")

REPO_DIR = Path("repo")
REPO_APK_DIR = REPO_DIR / "apk"
REPO_ICON_DIR = REPO_DIR / "icon"


def find_aapt() -> Path:
    build_tools = sorted((Path(os.environ["ANDROID_HOME"]) / "build-tools").iterdir())
    for candidate in reversed(build_tools):
        aapt = candidate / "aapt"
        if aapt.exists():
            return aapt
    raise FileNotFoundError("aapt not found under ANDROID_HOME/build-tools")


def collect_apks() -> None:
    shutil.rmtree(REPO_APK_DIR, ignore_errors=True)
    REPO_APK_DIR.mkdir(parents=True, exist_ok=True)
    for apk in Path("src").glob("**/build/outputs/apk/release/*.apk"):
        target = REPO_APK_DIR / apk.name.replace("-release.apk", ".apk")
        shutil.copy(apk, target)
        print(f"collected {target}")


def build_index() -> None:
    aapt = find_aapt()
    REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)

    with open("output.json", encoding="utf-8") as f:
        inspector_data = json.load(f)

    index = []

    for apk in sorted(REPO_APK_DIR.iterdir()):
        badging = subprocess.check_output(
            [aapt, "dump", "--include-meta-data", "badging", apk]
        ).decode()

        package_info = next(x for x in badging.splitlines() if x.startswith("package: "))
        package_name = PACKAGE_NAME_REGEX.search(package_info)[1]
        application_icon = APPLICATION_ICON_320_REGEX.search(badging)[1]

        with ZipFile(apk) as z, z.open(application_icon) as i, (
            REPO_ICON_DIR / f"{package_name}.png"
        ).open("wb") as f:
            f.write(i.read())

        language = LANGUAGE_REGEX.search(apk.name)[1]
        sources = inspector_data[package_name]

        if len(sources) == 1:
            source_language = sources[0]["lang"]
            if (
                source_language != language
                and source_language not in {"all", "other"}
                and language not in {"all", "other"}
            ):
                language = source_language

        index.append(
            {
                "name": APPLICATION_LABEL_REGEX.search(badging)[1],
                "pkg": package_name,
                "apk": apk.name,
                "lang": language,
                "code": int(VERSION_CODE_REGEX.search(package_info)[1]),
                "version": VERSION_NAME_REGEX.search(package_info)[1],
                "nsfw": int(IS_NSFW_REGEX.search(badging)[1]),
                "sources": [
                    {
                        "name": s["name"],
                        "lang": s["lang"],
                        "id": s["id"],
                        "baseUrl": s["baseUrl"],
                        "versionId": s["versionId"],
                    }
                    for s in sources
                ],
            }
        )

    index.sort(key=lambda x: x["pkg"])

    # Repo metadata (name + signing key fingerprint); Mihon/Anikku fetch this when adding the repo.
    shutil.copy(Path("repo.json"), REPO_DIR / "repo.json")

    with (REPO_DIR / "index.json").open("w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)

    for item in index:
        for source in item["sources"]:
            source.pop("versionId", None)

    with (REPO_DIR / "index.min.json").open("w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, separators=(",", ":"))

    with (REPO_DIR / "index.html").open("w", encoding="utf-8") as f:
        f.write("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n<title>apks</title>\n</head>\n<body>\n<pre>\n")
        for entry in index:
            f.write(f'<a href="apk/{html.escape(entry["apk"])}">{html.escape(entry["name"])}</a>\n')
        f.write("</pre>\n</body>\n</html>\n")

    print(json.dumps(index, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    import sys

    step = sys.argv[1] if len(sys.argv) > 1 else "all"
    if step in ("collect", "all"):
        collect_apks()
    if step in ("index", "all"):
        build_index()
