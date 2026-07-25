# Professor VPN — v6.3

نسخه ۶.۳ روی سه محور کار می‌کند: **پایداری Auto Test**، **کیفیت واقعی اتصال**، و
**ارتباط بهتر پنل با برنامه** (لینک‌های دانلود + اعلان‌ها). به‌همراه اصلاح تم روشن،
پرچم کشور در صفحه اصلی، و رفع باگ دکمه STOP.

---

## 1) Auto Test دیگر وسط کار نمی‌ایستد

مشکل نسخه ۶.۲: موتور Auto Test روی `ProcessLifecycleOwner.lifecycleScope` اجرا می‌شد.
با خاموش‌شدن صفحه، رفتن برنامه به پس‌زمینه، یا یک استثنای شبکه‌ای در اینترنت ضعیف،
اسکوپ کنسل می‌شد ولی سوییچ UI روشن می‌ماند → «روشن است ولی هیچ کانفیگی اضافه نمی‌کند».

اصلاحات `AutoTestEngine.kt`:

- اسکوپ اختصاصی `CoroutineScope(SupervisorJob() + Dispatchers.Default)` +
  `CoroutineExceptionHandler` — مستقل از lifecycle، مستقل از UI، مستقل از صفحه خاموش.
- هر منبع/چانک `withTimeoutOrNull` دارد؛ یک منبع کند کل دور را قفل نمی‌کند.
- **نردبان بازیابی** `recoverEmptyBatch()`: چرخش منابع، تعویض ترتیب، پاک‌سازی کش
  و تلاش دوباره — به‌جای ایستادن بی‌صدا.
- `MAX_EMPTY_STREAK = 6` → اگر واقعاً امکان افزودن کانفیگ نبود، `autoStop()` اجرا
  می‌شود و **سوییچ خودش خاموش می‌شود** (به‌جای «روشنِ بی‌فایده»)، به‌همراه پیام
  اطلاع‌رسانی به کاربر.
- **Heartbeat + `startStallSupervisor()`**: اگر تیک موتور از یک آستانه بگذرد،
  ناظر دور را ری‌استارت می‌کند.
- `Progress.autoStopped` به UI فرستاده می‌شود و `FreeConfigsFragment` توست
  «Auto Test به‌صورت خودکار متوقف شد» را نشان می‌دهد.
- تکرار «۲۴۰ کانفیگ دو بار» با `Semaphore` + `Mutex` + de-dup پایدار حذف شد؛
  کش هم دیگر بی‌نهایت پر نمی‌شود.

## 2) کیفیت اتصال — پایان «پینگ ۱۵۰ ولی وصل نمی‌شود»

- `Pinger.kt`: قانون قبلی با **یک** رفت‌وبرگشت موفق، سرور را سالم اعلام می‌کرد؛
  نودهای در حال مرگ دقیقاً همین رفتار را دارند. حالا:
  - `MIN_GOOD_SAMPLES = 2` — حداقل دو نمونهٔ موفق لازم است، وگرنه `UNREACHABLE`.
  - `MAX_SAMPLE_FAILS = 3`.
  - عدد گزارش‌شده `max(median, mean)` است، نه بهترین نمونه → پینگ سبزِ دروغین حذف شد.
- `XrayConfigBuilder.kt` برای اینترنت ضعیف/موبایل تنظیم شد:
  - policy level 8: `handshake 8 → 15`, `connIdle 600 → 900`,
    `uplinkOnly/downlinkOnly 12 → 20`, `bufferSize 2048 → 4096`.
  - sockopt: `tcpKeepAliveIdle 60 → 45`, `tcpKeepAliveInterval 30 → 15`,
    `tcpKeepAliveCount 9 → 12`, و افزودن `tcpUserTimeout = 100000`.
  - فرگمنت TLS ClientHello و DoH/DNS و Reality/XTLS-Vision مثل قبل فعال.

نتیجه: اتصال سریع‌تر روی اینترنت ضعیف، قطعی کمتر، و سرعت پایدارتر روی WiFi و دیتای سیم‌کارت.

## 3) پرچم کشور در صفحه اصلی

- مربع خالی پشت نام سرور حالا `flag_box` + `flag_text` است (۳۴dp، `clipChildren`
  → هیچ چیزی بیرون نمی‌زند).
- `util/CountryFlags.kt`: پرچم به‌صورت **ایموجی Unicode regional-indicator** ساخته
  می‌شود → صفر فایل گرافیکی، صفر دانلود، صفر لگ.
- رندر دو فازی: ابتدا `cachedFlagFor()` بدون هیچ I/O، و فقط اگر لازم بود
  `resolveAsync()` در پس‌زمینه اجرا می‌شود (۴ سرویس geo با fallback).
- پیش‌فرض خالی؛ در حالت قطع، خالی می‌شود. هر IP از هر کشوری پشتیبانی می‌شود.

## 4) صفحه «لینک‌های دانلود» (منوی همبرگری)

- اکتیویتی جدید `DownloadsActivity` + `activity_downloads.xml`.
- **بارکد/QR** با `util/QrCode.kt` — انکودر کامل ISO/IEC 18004 model 2 (byte mode،
  Reed–Solomon روی GF(2^8)، ۸ ماسک و ۴ قانون جریمه). بدون هیچ کتابخانه خارجی.
- اسکن QR توسط فرد مقابل → دیپ‌لینک `professorvpn://get?...&bt=1` →
  **بلوتوث دستگاه او خودکار روشن می‌شود** و برنامه اعلام می‌کند که بلوتوث دستگاه
  مقابل هم باید روشن باشد → APK از طریق **Bluetooth OPP** با سرعت بالا منتقل می‌شود.
- انتقال APK با `FileProvider` (`res/xml/file_paths.xml`) و
  `ACTION_SEND` با mime `application/vnd.android.package-archive`.
- پایین صفحه: **لیست اسکرول‌شونده لینک‌های دانلود** با دکمه **کپی** برای هر ردیف.

## 5) پنل مدیریت — تب «لینک‌های دانلود»

- تب جدید `⬇️ لینک‌های دانلود` با فرم عنوان/لینک/توضیح و دکمه **افزودن** (بدون محدودیت تعداد).
- امکان حذف و جابه‌جایی ترتیب ردیف‌ها.
- خروجی در `app_config.json` زیر کلید `downloadLinks` (+ alias `downloads`).

## 6) پنل مدیریت — تب «اعلان‌ها»

- تب جدید `🔔 اعلان‌ها` با سوییچ فعال‌سازی، تکست‌باکس متن، رنگ نوار و پیش‌نمایش زنده.
- در برنامه به‌صورت کارت اعلان بالای صفحه اصلی نمایش داده می‌شود با تیتر ثابت
  **«اعلان Professor Vpn :»** و سپس متن — **هرگز** عبارت «ارسال‌شده از پنل مدیریت»
  نمایش داده نمی‌شود.
- هر اعلان `id` پایدار دارد؛ `AppPrefs.isNoticeDismissed/dismissNotice` باعث می‌شود
  یک اعلان فقط یک‌بار اجباری شود، ولی اعلان جدید حتماً دوباره ظاهر شود.

## 7) رفع باگ دکمه STOP

ریشه: `stopVpn()` در `NeonVpnService` عملیات بلاکینگ نیتیو
(`TProxyService.TProxyStopService()`، `xray.stop()`، بستن TUN، interrupt نخ‌ها) را
روی **ترد اصلی** و از داخل `onStartCommand` اجرا می‌کرد → فریز UI و خطر ANR-kill
پیش از ارسال برادکست قطع.

اصلاح:

- ابتدا وضعیت به‌صورت **همزمان** به DISCONNECTED تغییر می‌کند و برادکست می‌شود.
- سپس `cleanup()` روی نخ دیمن `vpn-stop` اجرا می‌شود، با `finally` برای
  `stopForegroundCompat()` + `stopSelf()`.
- `stopThread` نگهبان **idempotency** است → دو بار زدن STOP مسابقه ایجاد نمی‌کند.
- `onDestroy()` دیگر cleanup تکراری اجرا نمی‌کند.
- `ConnectFragment.stopVpn()` هم مقاوم شد (fallback به `startForegroundService`).

## 8) اکشن‌های سریع فقط نمایشی

`auto connect` / `kill switch` / `protocol` حالا `clickable=false` و بدون ripple
هستند (صرفاً نمایش وضعیت). فقط `settings` قابل کلیک باقی مانده است.

## 9) انیمیشن‌های بهتر

`GlobeConnectView`:

- ۶۰ فریم بر ثانیه (`frameIntervalMs 24 → 16`).
- سرعت چرخش با easing مستقل از نرخ فریم: `exp(-k·dt)`.
- **کراس‌فید رنگ** بین حالت‌ها با `smoothstep` (`COLOR_FADE_SEC = 0.55`).
- حلقه‌های CONNECTING/ERROR در خلاف جهت هم می‌چرخند با sweep تنفسی.
- در حالت CONNECTED یک **ripple** منبسط‌شونده و محوشونده اضافه شد.

`ConnectFragment`: کراس‌فید نرم برای پس‌زمینه‌ی pill و متن وضعیت
(`setPillBackground` / `setStatus`).

## 10) اصلاح کامل تم روشن

- ۱۲ اتریبیوت معنایی جدید در `values/attrs.xml`
  (`appAccentViolet`, `appAccentBlue`, `appAccentPurple`, `appChipTopColor`,
  `appChipBottomColor`, `appSurfaceColor`, `appTabBarColor`, `appHairline`,
  `appCardBorder`, `appCardBorderLite`, `appTagFill`, `appTagStroke`).
- مقادیر تیره و روشن هر دو در `values/themes.xml`؛ ۹ رنگ روشن جدید در `colors.xml`.
- ۹ drawable و ۹ layout از رنگ ثابت به `?attr/…` منتقل شدند.
- دیالوگ‌های تم روشن هم `windowBackground` درست گرفتند.

## 11) دکمه Cancel برای Ping All

- در «کانفیگ‌های من»، هنگام پینگ همه، دکمه **لغو** بالای نوار پیشرفت ظاهر می‌شود.
- `PingService.cancel()` ردیف‌های `Testing` معلق را پاک می‌کند ولی نتایج
  اندازه‌گیری‌شده را نگه می‌دارد → لغو بدون به‌هم‌ریختگی.

---

## نسخه

- `versionCode = 44`
- `versionName = "6.3"`
- APK یونیورسال: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` — Android 7.0+
- خروجی: `build/ProfessorVPN-v6.3-universal.apk`
- `adminpanel/app_config.json` → `version: 19`, `latestApkVersion: "6.3"`
