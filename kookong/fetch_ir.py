"""向酷控云端取 rid=8259（TCL 吸顶灯）的红外码，并解密为明文 JSON。"""

import json
import os
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decryptor import decode  # noqa: E402

BASE = "https://sdk2.kookong.com/m/irs"
SECRET = "E8A87CE67252443109C61029771A7143"
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "out")


def fetch(bin_flag: int, rids: str = "8259", device_type: str = "10"):
    params = {
        "rids": rids,
        "deviceType": device_type,
        "mcode": "0",
        "bin": str(bin_flag),
        "ackey": "false",
        "alg": "0",
        "keyGroup": "false",
        "inclDis": "1",
        "s": "android-kksdk",
        "sdk": "1",
        "version": "1",
        "secret": SECRET,
        "pkg": "com.oplus.consumerIRApp",
        "platform": "android",
        "cv": "2",
        "sdhiad": "0",
        "model": "PJZ110_15",
        "sdk_vcode": "260",
        "lanCode": "zh",
    }
    url = BASE + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": "Dalv1k/2.1.0"})
    with urllib.request.urlopen(req, timeout=25) as resp:
        header = "e2" if resp.headers.get("e2") is not None else (
            "e" if resp.headers.get("e") is not None else "")
        body = resp.read()
        ctype = resp.headers.get("Content-Type", "")
    plain = decode(body, header)
    return header, ctype, body, plain


def main():
    os.makedirs(OUT, exist_ok=True)
    for bin_flag in (0, 1):
        header, ctype, body, plain = fetch(bin_flag)
        prefix = os.path.join(OUT, "rid8259_bin%d" % bin_flag)
        open(prefix + ".enc", "wb").write(body)
        open(prefix + ".raw", "wb").write(plain)
        print("=== bin=%d header=%s ctype=%s enc=%dB plain=%dB" % (
            bin_flag, header, ctype, len(body), len(plain)))
        print(plain[:1200].decode("utf-8", "replace"))
        try:
            data = json.loads(plain.decode("utf-8"))
            open(prefix + ".json", "w", encoding="utf-8").write(
                json.dumps(data, ensure_ascii=False, indent=2))
            print("--- json ok, type=%s" % type(data).__name__)
        except Exception as exc:  # noqa: BLE001
            print("--- json fail: %r" % (exc,))
        print()


if __name__ == "__main__":
    main()
