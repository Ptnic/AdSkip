# Third-party notices

## StevenBlack/hosts

`app/src/main/assets/blocklist.dat` is a gzip-compressed, normalized derivative
of the hosts file from:

- https://github.com/StevenBlack/hosts
- Snapshot date in the source header: 2026-11-08
- License: MIT

The generated file contains 78,225 unique domains. To regenerate it:

```bash
curl -L https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts \
     -o tools/hosts.txt
python tools/build_blocklist.py
```

## Chinese ad rule packs

`rules/cn-*.txt` and `app/src/main/assets/rules_cn_*.txt` are curated domain
lists compiled from public Chinese ad-filtering projects and DNS observations.
Domain names are factual data. The following projects were used as references:

- privacy-protection-tools/anti-AD (MIT)
- Mircc/sgmodule AD-Rules
- StevenBlack/hosts (MIT)
- AdguardTeam/AdguardFilters (GPL-3.0; used only to verify domain facts, no
  filter text was copied)

## Inspiration / prior art

The following open-source projects were reviewed while designing AdSkip. No
source code was copied from them:

- pass-with-high-score/blockads-android (GPL-3.0)
- nineaiyu/dns-override (MIT)
- georgechou0517/DNSSwitch (MIT-0)
- cccoddad/SplashGuard (MIT)
- an-stu/CiviAdShield
- AdAway/AdAway (GPL-3.0)
- julian-klode/dns66 (GPL-3.0)

Android is a trademark of Google LLC. This project is not affiliated with
Google or any of the projects above.
