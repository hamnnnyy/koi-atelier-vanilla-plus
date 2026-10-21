"""
.mrpack 모드팩을 폴더에 설치 (Modrinth 앱/Prism Launcher가 하는 것과 같은 방식).
파일을 받아서 sha512로 검증하고, overrides/ 를 덮어씀.

사용법: python tools/install_mrpack.py <pack.mrpack> <설치할 폴더>
"""
import hashlib
import json
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

UA = {"User-Agent": "hamnnnyy/koi-atelier-vanilla-plus (install script)"}


def main(pack: Path, target: Path):
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(pack) as z:
        index = json.loads(z.read("modrinth.index.json"))
        print(f"{index['name']} {index['versionId']} -> {target}")
        for f in index["files"]:
            if f.get("env", {}).get("client") == "unsupported":
                continue
            dest = target / f["path"]
            dest.parent.mkdir(parents=True, exist_ok=True)
            if not (dest.exists() and hashlib.sha512(dest.read_bytes()).hexdigest() == f["hashes"]["sha512"]):
                with urllib.request.urlopen(urllib.request.Request(f["downloads"][0], headers=UA)) as r:
                    data = r.read()
                if hashlib.sha512(data).hexdigest() != f["hashes"]["sha512"]:
                    raise SystemExit(f"hash mismatch: {f['path']}")
                dest.write_bytes(data)
            print("  ", f["path"])
        for name in z.namelist():
            for prefix in ("overrides/", "client-overrides/"):
                if name.startswith(prefix) and not name.endswith("/"):
                    dest = target / name[len(prefix):]
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    with z.open(name) as src, open(dest, "wb") as out:
                        shutil.copyfileobj(src, out)
                    print("   (override)", name[len(prefix):])
        print("dependencies:", index["dependencies"])


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
