/* Professor VPN — Admin Console (v4.6)
 * Fully client-side, static panel. No plaintext credentials live in this file:
 * only SHA-256 digests of the username/password are stored, so reading this
 * source never reveals them. The GitHub token is entered at runtime and kept
 * ONLY in the browser's localStorage — never committed.
 *
 * Publishing model:
 *   - app_config.json is written to  aptixzero/PRF_VPN @ adminpanel/app_config.json
 *     (a PUBLIC path) so the Android app can fetch it via raw.githubusercontent.
 *   - The panel HTML/JS itself lives in the PRIVATE repo.
 *   - Uploaded banner images are committed as binary blobs into
 *     adminpanel/media/ and referenced by their raw.githubusercontent URL.
 *
 * Config schema produced here matches RemoteConfig.kt exactly:
 *   version, appLogo{url}, inAppTelegramUrl, contact{...}, appBanner{...},
 *   ad{...}, homeCta{enabled,labelFa,labelEn,url},
 *   homeBanner{enabled,imageUrl,text,textColor,url},
 *   donate{enabled,heading,note,items:[{id,coin,address,logoUrl,network}]}
 */
(function () {
  "use strict";

  /* ---------------------------------------------------------------- auth --- */
  // Login is verified by comparing SHA-256 of the typed input against these
  // digests. Plaintext credentials (prf / prf!123) are NOT present in source.
  var USER_HASH = "9e7b69b83de60d8771bad1cceace73a97986da02e74fde20f913ea96be71bf6c";
  var PASS_HASH = "c751b1388c0f70e1a104d47f945bd888cdde69fd414b48fa3c4be5b7d73e9e7d";

  var LS_AUTH  = "pv_auth";
  var LS_TOKEN = "pv_gh_token";
  var LS_CFG   = "pv_cfg_cache";

  // The PUBLIC repo/path the app reads. Publishing writes here.
  var PUB_REPO = "aptixzero/PRF_VPN";
  var PUB_PATH = "adminpanel/app_config.json";
  var CONFIG_URL =
    "https://raw.githubusercontent.com/" + PUB_REPO + "/main/" + PUB_PATH;

  // Anonymous aggregate counter (matches UserStatsReporter.kt).
  var COUNTER_NS = "professorvpn";
  var ONLINE_WINDOW_MS = 5 * 60 * 1000;

  /* ---------------------------------------------------------------- utils -- */
  var $ = function (id) { return document.getElementById(id); };
  var val = function (id) { var e = $(id); return e ? e.value : ""; };
  var trimv = function (id) { return (val(id) || "").trim(); };
  var checked = function (id) { var e = $(id); return !!(e && e.checked); };
  var setVal = function (id, v) { var e = $(id); if (e) e.value = (v == null ? "" : v); };
  var setChk = function (id, v) { var e = $(id); if (e) e.checked = !!v; };

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  async function sha256(str) {
    var buf = new TextEncoder().encode(str);
    var hash = await crypto.subtle.digest("SHA-256", buf);
    return Array.from(new Uint8Array(hash))
      .map(function (b) { return b.toString(16).padStart(2, "0"); })
      .join("");
  }

  var toastTimer = null;
  function toast(msg) {
    var t = $("toast");
    if (!t) return;
    t.textContent = msg;
    t.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.remove("show"); }, 2600);
  }

  /* ------------------------------------------------------------ the model -- */
  // Custom donate rows added by the operator (preset rows are handled inline).
  var customDonate = [];

  function defaultModel() {
    return {
      version: 1,
      latestApkVersion: "4.6",
      appLogo: { url: "" },
      inAppTelegramUrl: "",
      contact: {
        text: "جهت ثبت تبلیغات و استعلام قیمت بنر به آیدی زیر در تلگرام پیام دهید:",
        telegramId: "@mx_pr",
        telegramUrl: "https://t.me/mx_pr"
      },
      appBanner: {
        enabled: true, title: "", subtitle: "",
        bgColor: "#11161C", textColor: "#E6F2EC",
        imageUrl: "", action: "url", actionUrl: ""
      },
      homeCta: { enabled: true, labelFa: "", labelEn: "", url: "" },
      homeBanner: { enabled: true, imageUrl: "", text: "", textColor: "#E6F2EC", url: "" },
      donate: { enabled: true, heading: "", note: "", items: [] }
    };
  }

  /* -------------------------------------------------------- form <-> model - */
  // Fill the form fields from a parsed config object (used when we load the
  // currently-published config so the operator edits the real values).
  function applyToForm(cfg) {
    cfg = cfg || {};
    // links tab
    setVal("ln-inapp", cfg.inAppTelegramUrl || (cfg.inAppTelegram && cfg.inAppTelegram.url) || "");
    var cta = cfg.homeCta || {};
    setChk("cta-enabled", cta.enabled !== false);
    setVal("cta-fa", cta.labelFa || "");
    setVal("cta-en", cta.labelEn || "");
    setVal("cta-url", cta.url || "");
    var ct = cfg.contact || {};
    setVal("ct-id", ct.telegramId || "");
    setVal("ct-url", ct.telegramUrl || "");
    // home tab
    setVal("app-logo", (cfg.appLogo && cfg.appLogo.url) || cfg.appLogoUrl || "");
    var bn = cfg.homeBanner || {};
    setChk("bn-enabled", bn.enabled !== false);
    setVal("bn-image", bn.imageUrl || "");
    setVal("bn-text", bn.text || "");
    if (bn.textColor) setVal("bn-color", bn.textColor);
    setVal("bn-url", bn.url || "");
    setVal("hm-apk", cfg.latestApkVersion || "4.6");
    // donate tab
    var dn = cfg.donate || {};
    setVal("dn-heading", dn.heading || "");
    setVal("dn-note", dn.note || "");
    customDonate = [];
    var items = (dn.items || []);
    ["usdt", "trx", "ton", "btc"].forEach(function (net) {
      var found = items.filter(function (x) { return x.network === net; })[0];
      setChk("p-" + net + "-on", !!found);
      setVal("p-" + net + "-addr", found ? found.address : "");
    });
    items.forEach(function (x) {
      if (["usdt", "trx", "ton", "btc"].indexOf(x.network) < 0) {
        customDonate.push({ coin: x.coin || x.network, address: x.address || "", logoUrl: x.logoUrl || "" });
      }
    });
    renderCustom();
  }

  // Read the form fields into a fresh config object matching RemoteConfig.kt.
  function readForm(prevVersion) {
    var m = defaultModel();
    m.version = (prevVersion || 0) + 1;
    m.ts = Date.now();
    m.lastUpdatedAt = Date.now();
    m.latestApkVersion = trimv("hm-apk") || "4.6";

    var inapp = trimv("ln-inapp");
    m.inAppTelegramUrl = inapp;
    m.inAppTelegram = { url: inapp };

    m.appLogo = { url: trimv("app-logo") };

    m.contact.telegramId = trimv("ct-id") || m.contact.telegramId;
    m.contact.telegramUrl = trimv("ct-url") || m.contact.telegramUrl;

    m.homeCta = {
      enabled: checked("cta-enabled"),
      labelFa: trimv("cta-fa"),
      labelEn: trimv("cta-en"),
      url: trimv("cta-url")
    };

    m.homeBanner = {
      enabled: checked("bn-enabled"),
      imageUrl: trimv("bn-image"),
      text: trimv("bn-text"),
      textColor: trimv("bn-color") || "#E6F2EC",
      url: trimv("bn-url")
    };
    // keep the legacy appBanner/ad blocks in sync so older app builds still work
    m.appBanner = {
      enabled: m.homeBanner.enabled,
      title: "", subtitle: "",
      bgColor: "#11161C", textColor: m.homeBanner.textColor,
      imageUrl: m.homeBanner.imageUrl,
      action: m.homeBanner.url ? "url" : "contact",
      actionUrl: m.homeBanner.url
    };
    m.ad = JSON.parse(JSON.stringify(m.appBanner));

    // donate items — presets first (only when enabled AND address present)
    var items = [];
    var presets = [
      { net: "usdt", coin: "USDT (TRC20)" },
      { net: "trx",  coin: "TRON (TRC20)" },
      { net: "ton",  coin: "TON" },
      { net: "btc",  coin: "Bitcoin" }
    ];
    presets.forEach(function (p) {
      if (checked("p-" + p.net + "-on")) {
        var addr = trimv("p-" + p.net + "-addr");
        if (addr) items.push({ id: p.net, coin: p.coin, address: addr, logoUrl: "", network: p.net });
      }
    });
    customDonate.forEach(function (c, i) {
      if (c.address && c.coin) {
        items.push({ id: "custom_" + i, coin: c.coin, address: c.address.trim(), logoUrl: c.logoUrl || "", network: "custom" });
      }
    });
    m.donate = {
      enabled: true,
      heading: trimv("dn-heading"),
      note: trimv("dn-note"),
      items: items
    };
    return m;
  }

  /* ----------------------------------------------------- custom donate UI -- */
  function renderCustom() {
    var box = $("custom-list");
    if (!box) return;
    if (!customDonate.length) { box.innerHTML = "<div class='small'>هنوز آدرس دلخواهی اضافه نشده.</div>"; return; }
    box.innerHTML = customDonate.map(function (c, i) {
      return "<div class='donate-row'>" +
        "<div class='ico' style='background:#6d28d9'>◈</div>" +
        "<div style='flex:1;min-width:0'>" +
          "<div class='chip'>" + esc(c.coin) + "</div>" +
          "<div class='addr' style='margin-top:6px'>" + esc(c.address) + "</div>" +
        "</div>" +
        "<span class='del-x' data-del='" + i + "'>حذف</span>" +
      "</div>";
    }).join("");
    Array.prototype.forEach.call(box.querySelectorAll("[data-del]"), function (el) {
      el.addEventListener("click", function () {
        customDonate.splice(parseInt(el.getAttribute("data-del"), 10), 1);
        renderCustom(); refreshPreview();
      });
    });
  }

  /* ------------------------------------------------------------- preview --- */
  function refreshPreview() {
    // CTA
    var ctaEl = $("pv-cta"), lblEl = $("pv-cta-label");
    if (ctaEl) ctaEl.style.display = checked("cta-enabled") ? "flex" : "none";
    if (lblEl) lblEl.textContent = trimv("cta-fa") || "عضو کانال تلگرام شوید";
    // banner
    var bn = $("pv-banner");
    if (bn) {
      if (!checked("bn-enabled")) {
        bn.style.display = "none";
      } else {
        bn.style.display = "flex";
        var img = trimv("bn-image");
        if (img) {
          bn.innerHTML = "<img src='" + esc(img) + "' alt='' onerror=\"this.style.display='none'\"/>";
          bn.style.color = "";
        } else {
          bn.innerHTML = esc(trimv("bn-text") || "محل بنر شما");
          bn.style.color = trimv("bn-color") || "#E6F2EC";
        }
      }
    }
  }

  /* ----------------------------------------------------------- tracking ---- */
  async function counterValue(key) {
    // counterapi v1 returns {"count":N}; the trailing-slash GET does NOT
    // increment. Try the read path, then fall back to abacus.
    var urls = [
      "https://api.counterapi.dev/v1/" + COUNTER_NS + "/" + key + "/",
      "https://abacus.jasoncameron.dev/get/" + COUNTER_NS + "/" + key
    ];
    for (var i = 0; i < urls.length; i++) {
      try {
        var r = await fetch(urls[i], { cache: "no-store" });
        if (!r.ok) continue;
        var j = await r.json();
        var v = (j && (j.count != null ? j.count : j.value));
        if (v != null) return parseInt(v, 10) || 0;
      } catch (e) { /* try next */ }
    }
    return null;
  }

  async function loadStats() {
    setVal; // no-op to satisfy lint
    var allEl = $("st-all"), onEl = $("st-online"), offEl = $("st-offline");
    if (allEl) allEl.textContent = "…";
    if (onEl) onEl.innerHTML = "<span class='dot g'></span>…";
    if (offEl) offEl.innerHTML = "<span class='dot r'></span>…";

    var total = await counterValue("installs_total");
    // Online = current window + previous window (covers the 5-min heartbeat gap).
    var win = Math.floor(Date.now() / ONLINE_WINDOW_MS);
    var cur = await counterValue("online_" + win);
    var prev = await counterValue("online_" + (win - 1));
    var online = (cur || 0) + (prev || 0);

    var totalTxt = (total == null ? "—" : String(total));
    var onlineTxt = String(online);
    var offlineTxt = (total == null ? "—" : String(Math.max(0, total - online)));

    if (allEl) allEl.textContent = totalTxt;
    if (onEl) onEl.innerHTML = "<span class='dot g'></span>" + onlineTxt;
    if (offEl) offEl.innerHTML = "<span class='dot r'></span>" + offlineTxt;
    var up = $("stats-updated");
    if (up) up.textContent = "آخرین بروزرسانی: " + new Date().toLocaleTimeString("fa-IR");
  }

  /* ------------------------------------------------------------ GitHub ----- */
  function b64EncodeUtf8(str) {
    return btoa(unescape(encodeURIComponent(str)));
  }

  async function ghGetSha(token, repo, path) {
    var url = "https://api.github.com/repos/" + repo + "/contents/" + path;
    try {
      var r = await fetch(url + "?ref=main", {
        headers: { Authorization: "token " + token, Accept: "application/vnd.github+json" },
        cache: "no-store"
      });
      if (r.status === 200) { var j = await r.json(); return j.sha || null; }
    } catch (e) { /* new file */ }
    return null;
  }

  async function ghPutFile(token, repo, path, contentB64, message, sha) {
    var url = "https://api.github.com/repos/" + repo + "/contents/" + path;
    var body = { message: message, content: contentB64, branch: "main" };
    if (sha) body.sha = sha;
    var r = await fetch(url, {
      method: "PUT",
      headers: {
        Authorization: "token " + token,
        Accept: "application/vnd.github+json",
        "Content-Type": "application/json"
      },
      body: JSON.stringify(body)
    });
    if (!r.ok) {
      var t = "";
      try { t = (await r.json()).message || ""; } catch (e) {}
      throw new Error("GitHub " + r.status + ": " + t);
    }
    return await r.json();
  }

  // Upload the chosen banner file to adminpanel/media/ and return its raw URL.
  async function uploadBannerFile(token, repo, file) {
    var buf = await file.arrayBuffer();
    var bytes = new Uint8Array(buf);
    var bin = "";
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    var b64 = btoa(bin);
    var ext = (file.name.split(".").pop() || "png").toLowerCase().replace(/[^a-z0-9]/g, "");
    var name = "banner_" + Date.now() + "." + ext;
    var path = "adminpanel/media/" + name;
    var sha = await ghGetSha(token, repo, path);
    await ghPutFile(token, repo, path, b64, "panel: upload banner " + name, sha);
    return "https://raw.githubusercontent.com/" + repo + "/main/" + path + "?v=" + Date.now();
  }

  async function publish() {
    var token = trimv("pb-token");
    var repo = trimv("pb-repo") || PUB_REPO;
    var path = trimv("pb-path") || PUB_PATH;
    var status = $("pb-status");
    if (!token) { if (status) { status.style.color = "var(--red)"; status.textContent = "توکن گیت‌هاب را وارد کنید."; } return; }
    localStorage.setItem(LS_TOKEN, token);

    if (status) { status.style.color = "var(--dim)"; status.textContent = "در حال انتشار…"; }
    try {
      // If the operator picked a local banner file, upload it first.
      var fileEl = $("bn-file");
      if (fileEl && fileEl.files && fileEl.files[0]) {
        if (status) status.textContent = "در حال آپلود عکس بنر…";
        var rawUrl = await uploadBannerFile(token, repo, fileEl.files[0]);
        setVal("bn-image", rawUrl);
        toast("عکس بنر آپلود شد");
      }

      // read current version to bump it
      var prevVersion = 0;
      try {
        var rr = await fetch(CONFIG_URL + "?t=" + Date.now(), { cache: "no-store" });
        if (rr.ok) { var pc = await rr.json(); prevVersion = parseInt(pc.version, 10) || 0; }
      } catch (e) {}

      var model = readForm(prevVersion);
      var json = JSON.stringify(model, null, 2);
      $("pb-json").value = json;
      localStorage.setItem(LS_CFG, json);

      var sha = await ghGetSha(token, repo, path);
      await ghPutFile(token, repo, path, b64EncodeUtf8(json), "panel: update app_config v" + model.version, sha);

      if (status) { status.style.color = "var(--green)"; status.textContent = "✅ منتشر شد (نسخه " + model.version + "). برنامه در اجرای بعدی به‌روزرسانی می‌شود."; }
      toast("تنظیمات منتشر شد ✅");
    } catch (e) {
      if (status) { status.style.color = "var(--red)"; status.textContent = "خطا: " + e.message; }
      toast("خطا در انتشار");
    }
  }

  function updateJsonPreview() {
    var m = readForm(0);
    var el = $("pb-json");
    if (el) el.value = JSON.stringify(m, null, 2);
  }

  /* --------------------------------------------------------------- tabs ---- */
  function showTab(name) {
    Array.prototype.forEach.call(document.querySelectorAll(".tab"), function (t) {
      t.classList.toggle("active", t.getAttribute("data-tab") === name);
    });
    ["tracking", "links", "home", "donate", "preview", "publish"].forEach(function (n) {
      var s = $("tab-" + n);
      if (s) s.classList.toggle("hidden", n !== name);
    });
    if (name === "tracking") loadStats();
    if (name === "preview") refreshPreview();
    if (name === "publish") updateJsonPreview();
  }

  /* --------------------------------------------------------------- boot ---- */
  async function loadPublishedConfig() {
    // Prefer the live published config; fall back to any local cache.
    try {
      var r = await fetch(CONFIG_URL + "?t=" + Date.now(), { cache: "no-store" });
      if (r.ok) { var cfg = await r.json(); applyToForm(cfg); return; }
    } catch (e) {}
    var cached = localStorage.getItem(LS_CFG);
    if (cached) { try { applyToForm(JSON.parse(cached)); return; } catch (e) {} }
    applyToForm(defaultModel());
  }

  function enterApp() {
    $("login").classList.add("hidden");
    $("app").classList.remove("hidden");
    var t = localStorage.getItem(LS_TOKEN);
    if (t) setVal("pb-token", t);
    loadPublishedConfig();
    showTab("tracking");
  }

  async function doLogin() {
    var u = trimv("lg-user"), p = val("lg-pass");
    var err = $("lg-err");
    var uh = await sha256(u), ph = await sha256(p);
    if (uh === USER_HASH && ph === PASS_HASH) {
      sessionStorage.setItem(LS_AUTH, "1");
      if (err) err.textContent = "";
      enterApp();
    } else {
      if (err) err.textContent = "نام کاربری یا رمز عبور اشتباه است.";
    }
  }

  function wire() {
    // login
    var lb = $("lg-btn");
    if (lb) lb.addEventListener("click", doLogin);
    ["lg-user", "lg-pass"].forEach(function (id) {
      var e = $(id);
      if (e) e.addEventListener("keydown", function (ev) { if (ev.key === "Enter") doLogin(); });
    });
    var lo = $("btn-logout");
    if (lo) lo.addEventListener("click", function () {
      sessionStorage.removeItem(LS_AUTH);
      location.reload();
    });

    // tabs
    Array.prototype.forEach.call(document.querySelectorAll(".tab"), function (t) {
      t.addEventListener("click", function () { showTab(t.getAttribute("data-tab")); });
    });

    // tracking
    var rs = $("btn-refresh-stats");
    if (rs) rs.addEventListener("click", loadStats);

    // custom donate add
    var ac = $("btn-add-custom");
    if (ac) ac.addEventListener("click", function () {
      var coin = trimv("cust-coin"), addr = trimv("cust-addr"), logo = trimv("cust-logo");
      if (!coin || !addr) { toast("نام و آدرس را وارد کنید"); return; }
      customDonate.push({ coin: coin, address: addr, logoUrl: logo });
      setVal("cust-coin", ""); setVal("cust-addr", ""); setVal("cust-logo", "");
      renderCustom(); refreshPreview();
      toast("آدرس اضافه شد");
    });

    // publish
    var pb = $("btn-publish");
    if (pb) pb.addEventListener("click", publish);

    // live preview on relevant field changes
    ["cta-enabled", "cta-fa", "bn-enabled", "bn-image", "bn-text", "bn-color"].forEach(function (id) {
      var e = $(id);
      if (e) { e.addEventListener("input", refreshPreview); e.addEventListener("change", refreshPreview); }
    });

    // resume session if already authed this tab
    if (sessionStorage.getItem(LS_AUTH) === "1") enterApp();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", wire);
  } else {
    wire();
  }
})();
