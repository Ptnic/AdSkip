#!/usr/bin/env python3
"""Build app/src/main/assets/blocklist.dat (gzip payload) from a hosts file.

The raw hosts file is not committed. Download it with:

    curl -L https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts \
         -o tools/hosts.txt

Then run:

    python tools/build_blocklist.py

StevenBlack/hosts is MIT licensed. See NOTICE.md.
"""

from __future__ import annotations

import gzip
import ipaddress
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "tools" / "hosts.txt"
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "blocklist.dat"
STATS = ROOT / "tools" / "blocklist-stats.txt"

# A small safety net for domains that are frequently missing from generic lists.
# Deliberately does not contain shared parent domains such as qq.com or bytedance.com.
EXTRA_DOMAINS = {
    "umeng.com",
    "umengcloud.com",
    "cnzz.com",
    "cnzz.net",
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "adservice.google.com",
    "applovin.com",
    "unityads.unity3d.com",
    "vungle.com",
    "chartboost.com",
    "ironsrc.com",
    "inmobi.com",
    "startapp.com",
    "tapjoy.com",
    "supersonicads.com",
    "adcolony.com",
    "mopub.com",
    "pangle.io",
    "pangolin-sdk-toutiao.com",
    "gdt.qq.com",
    "e.qq.com",
    "pingjs.qq.com",
    "ta.qq.com",
    "admaster.com.cn",
    "miaozhen.com",
    "talkingdata.com",
    "talkingdata.net",
    "growingio.com",
    "sensorsdata.cn",
}

SINK_ADDRESSES = {"0.0.0.0", "127.0.0.1", "::", "::1"}
DOMAIN_RE = re.compile(r"^[a-z0-9._-]+$")


def is_ip(value: str) -> bool:
    try:
        ipaddress.ip_address(value)
        return True
    except ValueError:
        return False


def normalize(domain: str) -> str | None:
    domain = domain.strip().lower().strip(".")
    if not domain or len(domain) > 253 or "." not in domain:
        return None
    if domain in {"localhost", "localhost.localdomain", "broadcasthost"}:
        return None
    if domain.startswith("ip6-") or is_ip(domain):
        return None
    if not DOMAIN_RE.match(domain) or ".." in domain:
        return None
    return domain


def parse_line(line: str) -> str | None:
    line = line.strip()
    if not line or line.startswith("#") or line.startswith("!"):
        return None
    line = line.split("#", 1)[0].strip()
    if not line:
        return None
    if line.startswith("||"):
        line = line[2:].split("^", 1)[0]
    else:
        parts = line.split()
        if len(parts) >= 2 and parts[0] in SINK_ADDRESSES:
            line = parts[1]
        else:
            line = parts[0]
    if line.startswith("*."):
        line = line[2:]
    return normalize(line)


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing {SOURCE}; download StevenBlack/hosts first.")
    domains: set[str] = set()
    for line in SOURCE.read_text(encoding="utf-8", errors="ignore").splitlines():
        domain = parse_line(line)
        if domain:
            domains.add(domain)
    domains.update(EXTRA_DOMAINS)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    payload = "\n".join(sorted(domains)).encode("utf-8") + b"\n"
    with OUTPUT.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0,
                           compresslevel=9) as compressed:
            compressed.write(payload)

    STATS.write_text(
        f"domains={len(domains)}\n"
        f"raw_bytes={len(payload)}\n"
        f"gz_bytes={OUTPUT.stat().st_size}\n",
        encoding="utf-8",
    )
    print(f"Wrote {OUTPUT} with {len(domains)} domains "
          f"({OUTPUT.stat().st_size:,} bytes).")


if __name__ == "__main__":
    main()
