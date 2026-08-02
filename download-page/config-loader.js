/* ============================================================
   Professor VPN — Download Page loader
   - Reads the download manifest (professor_vpn_config.json) for
     the current version + the APK link.
   - Reads the admin-panel app_config.json (same file the Android
     app uses) so the WEBSITE banner, website logo and contact info
     stay in sync — LIVE, with cache-busting.

   The website banner + website logo are read from `websiteBanner`
   and `websiteLogo`, which are completely INDEPENDENT from the
   in-app banner (`appBanner`) and the in-app logo (`appLogo`).
   No ad-network scripts anywhere — the banner is a plain,
   panel-controlled placeholder.
   ============================================================ */

var DL_URL = "professor_vpn_config.json";

/* v6.9 — this used to point at prfgame/prf-VPN, which returns 404, so the
   website banner / logo / contact block silently never loaded. It now reads
   the SAME live file the Android app reads, straight from the origin with no
   CDN, mirror or CORS relay in between. */
var APP_CONFIG_URL = "https://raw.githubusercontent.com/aptixzero/my_prFF_vP_N/main/adminpanel/app_config.json";

document.getElementById("year").textContent = new Date().getFullYear();

function esc(s){return (s||"").replace(/[&<>"]/g,function(c){
  return {"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;"}[c];});}

function scrollToSupport(){
  var el = document.getElementById("support");
  if (el) el.scrollIntoView({behavior:"smooth", block:"center"});
}

/* ---------- download manifest ---------- */
function loadDownloads(){
  fetch(DL_URL + "?t=" + Date.now(), {cache:"no-store"})
    .then(function(r){return r.json();})
    .then(renderDownloads)
    .catch(function(){
      var meta = document.getElementById("dl-meta");
      if (meta) meta.textContent = "فعلاً نسخه‌ای ثبت نشده است.";
    });
}

function renderDownloads(cfg){
  var app = cfg.app || {};
  var downloads = cfg.downloads || [];
  var latest = downloads[0] || null;

  document.getElementById("latest-ver").textContent = "v" + (app.latest_version || "1.0.0");

  var mainBtn = document.getElementById("main-dl");
  if (latest){
    mainBtn.onclick = function(){ location.href = latest.url; };
    document.getElementById("dl-meta").textContent =
      (latest.size ? latest.size + " · " : "") + "امضا شده · Android 7+";
  } else {
    mainBtn.onclick = scrollToSupport;
    document.getElementById("dl-meta").textContent = "به‌زودی…";
  }

  /* what's-new / changelog block (manifest-driven) */
  renderChangelog(latest, app);
}

function renderChangelog(latest, app){
  var box  = document.getElementById("changelog");
  var list = document.getElementById("cl-list");
  if (!box || !list || !latest) return;
  var items = Array.isArray(latest.changelog) ? latest.changelog : [];
  if (!items.length) { box.classList.add("hidden"); return; }

  var verEl = document.getElementById("cl-ver");
  if (verEl) verEl.textContent = "نسخه " + (latest.version || app.latest_version || "");

  list.innerHTML = "";
  items.forEach(function(line){
    var li = document.createElement("li");
    li.textContent = line;          // textContent => no HTML injection
    list.appendChild(li);
  });
  box.classList.remove("hidden");
}

/* ---------- panel-controlled website banner + contact (LIVE) ---------- */
function loadAppConfig(){
  fetch(APP_CONFIG_URL + "?t=" + Date.now(), {cache:"no-store"})
    .then(function(r){return r.json();})
    .then(renderAppConfig)
    .catch(function(){ /* keep built-in defaults already in the HTML */ });
}

function renderAppConfig(m){
  m = m || {};
  /* website banner is independent from app banner; fall back to legacy ad */
  var wb = m.websiteBanner || m.ad || {};
  var contact = m.contact || {};
  var webLogo = (m.websiteLogo && m.websiteLogo.url) || "";

  /* website logo (separate from the app logo) */
  if (webLogo){
    var logoEl = document.getElementById("site-logo");
    if (logoEl) logoEl.src = webLogo;
  }

  /* website banner */
  var banner = document.getElementById("web-banner");
  if (banner){
    if (wb.enabled === false){
      banner.classList.add("hidden");
    } else {
      banner.classList.remove("hidden");
      var t = document.getElementById("wb-title");
      var s = document.getElementById("wb-subtitle");
      var img = document.getElementById("wb-img");
      var showMedia = wb.mediaVisible !== false && !!wb.imageUrl;
      if (showMedia){
        img.src = wb.imageUrl; img.classList.remove("hidden");
        if (t) t.classList.add("hidden");
        if (s) s.classList.add("hidden");
      } else {
        img.classList.add("hidden");
        if (t){ t.classList.remove("hidden"); t.textContent = wb.title || "محل تبلیغ شما"; }
        if (s){ s.classList.remove("hidden"); s.textContent = wb.subtitle || "جهت ثبت تبلیغ با ما در ارتباط باشید"; }
      }
      var action = wb.action || "contact";
      banner.onclick = function(){
        if (action === "url" && wb.actionUrl){ window.open(wb.actionUrl, "_blank"); }
        else if (action === "none"){ /* nothing */ }
        else { scrollToSupport(); }
      };
    }
  }

  /* contact / support */
  if (contact.text){
    var ct = document.getElementById("contact-text");
    if (ct) ct.textContent = contact.text;
  }
  var id = contact.telegramId || "@mx_pr";
  var url = contact.telegramUrl || ("https://t.me/" + id.replace(/^@/, ""));
  var chip = document.getElementById("contact-chip");
  if (chip) chip.textContent = id;

  var copyBtn = document.getElementById("copy-id");
  if (copyBtn){
    if (contact.btnCopy) copyBtn.textContent = contact.btnCopy;
    var copyLabel = copyBtn.textContent;
    copyBtn.onclick = function(){
      navigator.clipboard.writeText(id).then(function(){
        copyBtn.textContent = "کپی شد ✓";
        setTimeout(function(){ copyBtn.textContent = copyLabel; }, 1500);
      });
    };
  }
  var sendBtn = document.getElementById("send-msg");
  if (sendBtn){
    if (contact.btnSend) sendBtn.textContent = contact.btnSend;
    sendBtn.onclick = function(){ window.open(url, "_blank"); };
  }
}

/* support button in the hero */
var supportBtn = document.getElementById("support-btn");
if (supportBtn) supportBtn.onclick = scrollToSupport;

loadDownloads();
loadAppConfig();
