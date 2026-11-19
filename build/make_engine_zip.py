# -*- coding: utf-8 -*-
"""Convert the engine tar produced on the phone into assets/engine.zip."""
import os
import sys
import tarfile
import time
import zipfile

EXCLUDE = ("node_modules/npm", "node_modules/corepack")


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: make_engine_zip.py <engine.tar> <engine.zip>")
        return 2
    tar_path, zip_path = sys.argv[1], sys.argv[2]
    os.makedirs(os.path.dirname(os.path.abspath(zip_path)), exist_ok=True)
    count = 0
    raw = 0
    start = time.time()
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6,
                         allowZip64=True) as zf:
        with tarfile.open(tar_path, "r:") as tf:
            for m in tf:
                name = m.name.lstrip("./")
                if not name or name == ".":
                    continue
                if any(name == ex or name.startswith(ex + "/") for ex in EXCLUDE):
                    continue
                if m.isdir():
                    zi = zipfile.ZipInfo(name.rstrip("/") + "/")
                    zi.external_attr = (0o40755 & 0xFFFF) << 16 | 0x10
                    zf.writestr(zi, b"")
                    continue
                if not m.isfile():
                    continue
                src = tf.extractfile(m)
                if src is None:
                    continue
                data = src.read()
                zi = zipfile.ZipInfo(name, date_time=time.localtime(max(m.mtime, 0))[:6])
                zi.external_attr = (m.mode & 0o777) << 16
                zi.compress_type = zipfile.ZIP_DEFLATED
                zf.writestr(zi, data)
                count += 1
                raw += len(data)
    size = os.path.getsize(zip_path)
    print(f"wrote {zip_path}: {count} files, {raw/1048576:.0f} MB raw, "
          f"{size/1048576:.0f} MB zip, {time.time()-start:.0f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
