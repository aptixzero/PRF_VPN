#!/usr/bin/env python3
"""
Config bridge aggregator — runs on GitHub Actions, never on a user device.

Reads the same public feeds the app reads, de-duplicates them, and writes a
single vless.txt / vmess.txt pair plus a status.json into bridge/, which is
served from this repository's GitHub Pages site.

The point is the HOSTNAME, not the content: the app already knows these feeds,
but they all live on raw.githubusercontent.com, which is heavily DNS-poisoned
on Iranian ISPs. Publishing the same data under the account's own Pages domain
gives the app an independent second origin to fall back to.

Nothing here is a proxy. It runs on a schedule and produces static files; no
user request is ever forwarded anywhere.
"""

import base64
import binascii
import concurrent.futures
import json
import re
import time
import urllib.request

UA = "ProfessorVPN-bridge/1.0 (+https://github.com/aptixzero/my_prFF_vP_N)"
TIMEOUT = 25
MAX_PER_KIND = 4000

FEEDS = [
    "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt",
    "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vmess.txt",
    "https://raw.githubusercontent.com/nyeinkokoaung404/V2ray-Configs/main/Splitted-By-Protocol/vless.txt",
    "https://raw.githubusercontent.com/nyeinkokoaung404/V2ray-Configs/main/Splitted-By-Protocol/vmess.txt",
    "https://raw.githubusercontent.com/coldwater-10/V2ray-Config-Lite/main/Splitted-By-Protocol/vless.txt",
    "https://raw.githubusercontent.com/coldwater-10/V2ray-Config-Lite/main/Splitted-By-Protocol/vmess.txt",
    "https://raw.githubusercontent.com/barry-far/V2ray-config/main/Splitted-By-Protocol/vless.txt",
    "https://raw.githubusercontent.com/barry-far/V2ray-config/main/Splitted-By-Protocol/vmess.txt",
    "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/filtered/subs/vless.txt",
    "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/filtered/subs/vmess.txt",
    "https://raw.githubusercontent.com/SoliSpirit/v2ray-configs/refs/heads/main/Protocols/vless.txt",
    "https://raw.githubusercontent.com/SoliSpirit/v2ray-configs/refs/heads/main/Protocols/vmess.txt",
    "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/refs/heads/main/vless_configs.txt",
    "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/refs/heads/main/vmess_configs.txt",
    "https://raw.githubusercontent.com/V2RayRoot/V2RayConfig/main/Config/vless.txt",
    "https://raw.githubusercontent.com/V2RayRoot/V2RayConfig/main/Config/vmess.txt",
    "https://raw.githubusercontent.com/Kwinshadow/TelegramV2rayCollector/main/sublinks/vless.txt",
    "https://raw.githubusercontent.com/Kwinshadow/TelegramV2rayCollector/main/sublinks/vmess.txt",
    "https://raw.githubusercontent.com/MahanKenway/Freedom-V2Ray/main/configs/vless.txt",
    "https://raw.githubusercontent.com/MahanKenway/Freedom-V2Ray/main/configs/vmess.txt",
    "https://raw.githubusercontent.com/iboxz/free-v2ray-collector/main/main/vless.txt",
    "https://raw.githubusercontent.com/iboxz/free-v2ray-collector/main/main/vmess.txt",
    "https://raw.githubusercontent.com/Kolandone/v2raycollector/main/vless.txt",
    "https://raw.githubusercontent.com/Kolandone/v2raycollector/main/vmess.txt",
    "https://raw.githubusercontent.com/hamedcode/port-based-v2ray-configs/main/sub/vless.txt",
    "https://raw.githubusercontent.com/hamedcode/port-based-v2ray-configs/main/sub/vmess.txt",
    "https://raw.githubusercontent.com/10ium/V2rayCollector/main/vless_iran.txt",
    "https://raw.githubusercontent.com/10ium/V2rayCollector/main/vmess_iran.txt",
    "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
    "https://raw.githubusercontent.com/ALIILAPRO/v2rayNG-Config/main/server.txt",
]

LINK_RE = re.compile(r"(?:vless|vmess)://[^\s\"'<>]+", re.IGNORECASE)


def fetch(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return url, r.read().decode("utf-8", "ignore")
    except Exception as e:  # a dead feed must never fail the whole run
        print(f"skip {url}: {e}")
        return url, ""


def maybe_b64(body):
    """Some feeds ship one big base64 blob rather than plain lines."""
    stripped = "".join(body.split())
    if not stripped or len(stripped) < 64:
        return body
    if LINK_RE.search(body):
        return body
    if not re.fullmatch(r"[A-Za-z0-9+/=_-]+", stripped):
        return body
    try:
        pad = "=" * (-len(stripped) % 4)
        return base64.b64decode(stripped + pad).decode("utf-8", "ignore")
    except (binascii.Error, ValueError):
        return body


def endpoint_of(link):
    """
    Dedup key. Two links to the same host:port are the same node even when the
    remark differs, and public feeds are full of the same node under a dozen
    names — deduping on the raw string would keep all of them.
    """
    low = link.lower()
    if low.startswith("vmess://"):
        try:
            raw = link[8:].split("#", 1)[0].strip()
            pad = "=" * (-len(raw) % 4)
            o = json.loads(base64.b64decode(raw + pad).decode("utf-8", "ignore"))
            return f"vmess|{o.get('add', '')}|{o.get('port', '')}|{o.get('id', '')}"
        except Exception:
            return link.split("#", 1)[0]
    body = link.split("#", 1)[0]
    m = re.search(r"@([^/?]+)", body)
    return f"vless|{m.group(1).lower()}" if m else body


def main():
    vless, vmess = {}, {}
    ok = 0

    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as ex:
        for url, body in ex.map(fetch, FEEDS):
            if not body.strip():
                continue
            ok += 1
            for link in LINK_RE.findall(maybe_b64(body)):
                link = link.strip()
                bucket = vless if link.lower().startswith("vless://") else vmess
                bucket.setdefault(endpoint_of(link), link)

    v_list = list(vless.values())[:MAX_PER_KIND]
    m_list = list(vmess.values())[:MAX_PER_KIND]

    # A run where almost every feed failed would otherwise publish an empty
    # bridge and take the app's fallback origin down with it. Better to leave
    # the previous snapshot in place.
    if ok < 3 or (not v_list and not m_list):
        raise SystemExit(f"refusing to publish: only {ok} feeds responded, "
                         f"{len(v_list)} vless / {len(m_list)} vmess")

    with open("bridge/vless.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(v_list) + "\n")
    with open("bridge/vmess.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(m_list) + "\n")
    with open("bridge/status.json", "w", encoding="utf-8") as f:
        json.dump({
            "updated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "feeds_total": len(FEEDS),
            "feeds_ok": ok,
            "vless": len(v_list),
            "vmess": len(m_list),
        }, f, indent=2)

    print(f"feeds ok {ok}/{len(FEEDS)} — vless {len(v_list)}, vmess {len(m_list)}")


if __name__ == "__main__":
    main()
