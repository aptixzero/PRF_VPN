# Professor VPN — v6.4 (versionCode 45)

نسخه ۶.۴ روی سه مشکلی که گزارش شد تمرکز دارد: **پینگ اشتباه پس از اتصال**،
**قطع/قفل شدن اتصال بعد از حدود ۱ دقیقه**، و **پرچم‌ها** (اندازه + پرچم شیر و خورشید
برای ایران + نمایش IP کشور خروجی).

---

## ۱) پینگ: فقط Cloudflare — دیگر Google نه

**مشکل:** کانفیگ در لیست پینگ ۱۲۰ نشان می‌داد، ولی بعد از وصل شدن یک‌باره ۱۰۰۰ می‌شد.

**ریشه:**
- پینگ لیست (`Pinger`) روی Cloudflare + Telegram + Instagram تست می‌گرفت و
  `max(median, mean)` را گزارش می‌کرد.
- پینگ بعد از اتصال (`XrayManager.measureDelay`) به‌صورت پیش‌فرض روی **Google**
  (`gstatic.com/generate_204`) بود و فقط **یک نمونهٔ سرد** می‌گرفت — آن هم درحالی‌که
  تانل مشغول عبور دادن ترافیک کاربر بود. نتیجه: دو عدد بی‌ربط.

**اصلاح:**
- فایل جدید `config/ProbeEndpoints.kt` — تنها منبع حقیقت برای همهٔ اندازه‌گیری‌های تأخیر:
  - `https://cp.cloudflare.com/generate_204` (پاسخ 204 با بدنهٔ صفر بایت)
  - `https://1.1.1.1/cdn-cgi/trace`
  - `https://www.cloudflare.com/cdn-cgi/trace`
  - `https://speed.cloudflare.com/__down?bytes=0`
- **همهٔ endpointهای Google از `XrayManager` حذف شد.**
- `measureDelayStable()` جدید: اولین نمونهٔ سرد (cold handshake) دور ریخته می‌شود و
  **میانهٔ نمونه‌های گرم** گزارش می‌شود — دقیقاً همان آمارهٔ `Pinger`.
- `Pinger`: `SAMPLE_COUNT` از ۳ به ۴، و گزارش `median(warm)` جای `max(median, mean)`.

نتیجه: عددی که در لیست می‌بینید و عددی که بعد از اتصال می‌بینید **با یک روش و یک
سرور** اندازه‌گیری می‌شوند، پس به هم می‌خورند.

---

## ۲) قفل شدن بعد از ~۱ دقیقه (اینستاگرام / ویدیو) — چهار لایه اصلاح

**مشکل:** وصل می‌شدید، اینستاگرام حدود یک دقیقه کار می‌کرد، بعد ویدیوها خشک/قفل
می‌شدند؛ یک بار قطع و وصل کردن دستی مشکل را حل می‌کرد.

### ۲.۱ نشتی QUIC / HTTP-3 (علت اصلی)
اینستاگرام و یوتیوب HTTP/3 روی **UDP 443** را ترجیح می‌دهند. گره‌های رایگان
VLESS/VMESS معمولاً UDP را قابل‌اعتماد رله نمی‌کنند و DPI هم آن را شکل‌دهی می‌کند.
QUIC نسبت به loss تحمل دارد، پس اپ هیچ‌وقت سریع fail نمی‌کند و retry نمی‌زند —
بافر ویدیو خالی می‌شود و فید قفل می‌ماند، در حالی که تانل از نظر سلامت «سالم» است
(به همین دلیل watchdog هم چیزی نمی‌دید و فقط toggle دستی جواب می‌داد).

**اصلاح:** دو قاعدهٔ routing صریح `block`:
- `network: udp`, `port: 443`
- `protocol: ["quic"]`

اپ‌ها بلافاصله به HTTP/2 روی TCP برمی‌گردند که داخل تانل پایدار است.

### ۲.۲ پر شدن جدول نشست / استخر اتصال
| مقدار | قبل | v6.4 |
|---|---|---|
| `policy.connIdle` | 900s | **120s** |
| `policy.uplinkOnly` / `downlinkOnly` | 20 | **4** |
| `policy.handshake` | 15 | **12** |
| `policy.bufferSize` | 4096 KiB | **512 KiB** |
| hev `read-write-timeout` | 300000 | **60000** |
| hev `udp-read-write-timeout` | — | **20000** |
| hev `max-session-count` | — | **1024** |
| hev `limit-nofile` | — | **65535** |
| `tcpUserTimeout` | 100s | **30s** |
| `tcpKeepAlive` idle/interval/count | 45/15/12 | **30/10/6** |

نکتهٔ مهم: `bufferSize` **به‌ازای هر اتصال** است — ۱۰۰ اتصال × ۴ مگابایت یعنی
درخواست چند گیگابایت رزرو از هسته، که دقیقاً زیر بار «اسکرول ویدیو» باعث
thrash/stall می‌شد.

### ۲.۳ توقف‌های IPv6 (Happy Eyeballs)
- آدرس IPv6 و مسیر `::/0` از TUN حذف شد؛ به‌جایش `allowFamily(AF_INET)`.
- DNS فقط IPv4: `queryStrategy = UseIPv4` و DNS فقط `1.1.1.1` / `1.0.0.1`
  (حذف `dns.google`).
- outbound `direct` هم `domainStrategy = UseIPv4`.

اپ‌های dual-stack دیگر برای هر اتصال جدید تایم‌اوت Happy Eyeballs نمی‌پردازند.

### ۲.۴ خود‌ترمیمی برای «قفل خاموش» (silent stall)
همهٔ health checkهای قبلی فقط ثابت می‌کردند **هسته** می‌تواند dial کند (که همیشه
می‌توانست). حالا:
- `probeDevicePath()` — یک درخواست واقعی از طریق **SOCKS5 محلی** (همان سوکتی که
  tun2socks به آن feed می‌کند، یعنی مسیر واقعی اپ‌های گوشی).
- در صورت **۲ شکست پشت‌سرهم**، `restartTun2Socks()` فقط پل tun2socks را بازسازی
  می‌کند: TUN، هستهٔ Xray و نشست کاربر باقی می‌مانند، هیچ دیالوگ مجوزی نمی‌آید و
  UI از حالت Connected بیرون نمی‌رود.

این همان «قطع و وصل دستی» شماست که خودکار و در حدود ۱ ثانیه انجام می‌شود.

---

## ۳) پرچم‌ها

### ۳.۱ پرچم دقیقاً به اندازهٔ مربع
`TextView` همیشه glyph را با padding ناشی از ascent/descent فونت می‌چیند، پس با هیچ
`textSize` پر نمی‌شود. ویجت جدید `ui/widget/FlagView.kt` روی Canvas نقاشی می‌کند،
**ink bounds** واقعی glyph را با `Paint.getTextBounds` می‌سنجد و X و Y را **مستقل**
مقیاس می‌دهد تا لبه‌تا‌لبهٔ کاشی پر شود. کاشی از 34dp به **38dp** با پس‌زمینهٔ
`flag_tile_bg` (گوشهٔ ۹dp، theme-aware) رفت.

### ۳.۲ ایران = شیر و خورشید، در هیچ حالتی پرچم ج.ا.
چهار لایهٔ دفاعی:
1. `CountryFlags.emojiOf("IR")` رشتهٔ خالی برمی‌گرداند — اپ اصلاً نمی‌تواند آن emoji
   را تولید کند.
2. `FlagView.setCountry("IR")` به vector محلی `drawable/flag_ir_lion_sun.xml`
   می‌رود (سه‌رنگ + خورشید ۱۸ پره + شیر با شمشیر).
3. `FlagView.setFlagEmoji()` هر emoji ورودی IR را هم به همان vector مسیریابی می‌کند.
4. `sanitizeForDisplay()` آن glyph را از remark های آمده از فید حذف و با `[IR]`
   جایگزین می‌کند (کارت خانه + لیست کانفیگ‌ها).

چون vector یک asset محلی است، **آفلاین و روی اینترنت ضعیف هم** درست نمایش داده
می‌شود.

### ۳.۳ نمایش IP و کشور خروجی بعد از اتصال
حدس کشور از hostname سرور اغلب غلط است (آدرس ورودی ≠ خروجی، CDN fronting). حالا:
- `fetchTraceThroughTunnel()` مقدار `/cdn-cgi/trace` را **از داخل تانل** می‌خواند و
  فیلدهای `ip=` و `loc=` را استخراج می‌کند.
- `resolveExitIdentityAsync()` آن را روی `VpnStateBus.ExitIdentity` منتشر می‌کند؛
  `renderExit()` هم IP را در آمار و پرچم کشور خروجی را در کاشی می‌گذارد.
- کشور با `rememberCode()` per-host کش می‌شود، پس اتصال بعدی بدون هیچ I/O فوراً
  پرچم درست را می‌کشد.

---

## فایل‌های تغییر‌یافته

**جدید**
- `app/src/main/java/com/neonvpn/app/config/ProbeEndpoints.kt`
- `app/src/main/java/com/neonvpn/app/ui/widget/FlagView.kt`
- `app/src/main/res/drawable/flag_ir_lion_sun.xml`
- `app/src/main/res/drawable/flag_tile_bg.xml`

**تغییر‌یافته**
- `config/Pinger.kt`, `config/XrayConfigBuilder.kt`
- `service/XrayManager.kt`, `service/NeonVpnService.kt`
- `util/CountryFlags.kt`
- `ui/ConnectFragment.kt`, `ui/ConfigsFragment.kt`, `ui/VpnStateBus.kt`
- `res/layout/fragment_connect.xml`
- `app/build.gradle.kts` (versionCode 45 / versionName 6.4)

---

## دانلود

APK یونیورسال امضا‌شده (arm64-v8a, armeabi-v7a, x86, x86_64):
`build/ProfessorVPN-v6.4-universal.apk` — و همچنین از صفحهٔ Releases با تگ `v6.4`.
