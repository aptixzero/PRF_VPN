/*
 * v6.5 — PanelQr: a dependency-free QR Code (model 2) encoder for the admin panel.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The new «لینک پشت بارکد» tab lets the operator paste a link, press «ساخت بارکد»,
 * and immediately SEE the barcode that the app will draw on the Download-Links
 * page. That preview is only trustworthy if the panel and the app agree module
 * for module, so this file is a line-by-line port of
 *
 *     app/src/main/java/com/neonvpn/app/util/QrCode.kt
 *
 * including the v6.5 interleave fix (`shortBlockLen = rawCount / numBlocks`).
 * Byte-mode only, automatic version selection, all 8 masks evaluated with the
 * spec's four penalty rules — identical arithmetic, identical output.
 *
 * NO EXTERNAL LIBRARY is loaded: the panel is served from GitHub Pages and must
 * keep working with zero third-party requests (a CDN outage must never break the
 * operator's ability to publish a config).
 *
 * Public API:
 *   PanelQr.encode(text, eccName)  -> boolean[][]  (true == dark module)
 *   PanelQr.draw(canvas, text, opts)               (renders, returns module count)
 */
(function (root) {
  "use strict";

  /* ecc name -> { ord, fmt } — order matches Kotlin's Ecc enum ordinal. */
  var ECC = {
    LOW:      { ord: 0, fmt: 1 },
    MEDIUM:   { ord: 1, fmt: 0 },
    QUARTILE: { ord: 2, fmt: 3 },
    HIGH:     { ord: 3, fmt: 2 }
  };

  /* Index [ecc][version]; index 0 unused so `version` maps directly. */
  var ECC_PER_BLOCK = [
    [0,7,10,15,20,26,18,20,24,30,18,20,24,26,30,22,24,28,30,28,28,28,28,30,30,26,28,30,30,30,30,30,30,30,30,30,30,30,30,30,30],
    [0,10,16,26,18,24,16,18,22,22,26,30,22,22,24,24,28,28,26,26,26,26,28,28,28,28,28,28,28,28,28,28,28,28,28,28,28,28,28,28,28],
    [0,13,22,18,26,18,24,18,22,20,24,28,26,24,20,30,24,28,28,26,30,28,30,30,30,30,28,30,30,30,30,30,30,30,30,30,30,30,30,30,30],
    [0,17,28,22,16,22,28,26,26,24,28,24,28,22,24,24,30,28,28,26,28,30,24,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30]
  ];
  var NUM_BLOCKS = [
    [0,1,1,1,1,1,2,2,2,2,4,4,4,4,4,6,6,6,6,7,8,8,9,9,10,12,12,12,13,14,15,16,17,18,19,19,20,21,22,24,25],
    [0,1,1,1,2,2,4,4,4,5,5,5,8,9,9,10,10,11,13,14,16,17,17,18,20,21,23,25,26,28,29,31,33,35,37,38,40,43,45,47,49],
    [0,1,1,2,2,4,4,6,6,8,8,8,10,12,16,12,17,16,18,21,20,23,23,25,27,29,34,34,35,38,40,43,45,48,51,53,56,59,62,65,68],
    [0,1,1,2,4,4,4,5,6,8,8,11,11,16,16,18,16,19,21,25,25,25,34,30,32,35,37,40,42,45,48,51,54,57,60,63,66,70,74,77,81]
  ];

  /* ------------------------------------------------------------ helpers -- */

  function utf8Bytes(str) {
    // encodeURIComponent yields the exact UTF-8 byte sequence, which matters
    // because Persian captions and non-ASCII URLs must encode identically to
    // Kotlin's String.toByteArray(Charsets.UTF_8).
    var esc = encodeURIComponent(String(str));
    var out = [];
    for (var i = 0; i < esc.length; i++) {
      if (esc.charAt(i) === "%") {
        out.push(parseInt(esc.substr(i + 1, 2), 16));
        i += 2;
      } else {
        out.push(esc.charCodeAt(i));
      }
    }
    return out;
  }

  function charCountBits(version) { return version <= 9 ? 8 : 16; }

  function totalCodewords(version) {
    var size = version * 4 + 17;
    var modules = size * size;
    modules -= 8 * 8 * 3;               // finders + separators
    modules -= 15 * 2 + 1;              // format info
    modules -= (size - 16) * 2;         // timing
    if (version >= 2) {
      var n = Math.floor(version / 7) + 2;
      modules -= (n - 1) * (n - 1) * 25;       // alignment patterns
      modules -= (n - 2) * 2 * 20;             // minus timing overlap
      if (version >= 7) modules -= 6 * 3 * 2;  // version info
    }
    return Math.floor(modules / 8);
  }

  function eccPerBlock(version, ecc) { return ECC_PER_BLOCK[ecc.ord][version]; }
  function numBlocksFor(version, ecc) { return NUM_BLOCKS[ecc.ord][version]; }

  function dataCodewords(version, ecc) {
    return totalCodewords(version) - eccPerBlock(version, ecc) * numBlocksFor(version, ecc);
  }

  function smallestVersionFor(byteLen, ecc) {
    for (var v = 1; v <= 40; v++) {
      var cap = dataCodewords(v, ecc);
      var header = 4 + charCountBits(v);
      if (cap * 8 >= header + byteLen * 8) return v;
    }
    return 0;
  }

  function getBit(v, i) { return ((v >>> i) & 1) !== 0; }

  /* Multiply in GF(2^8) modulo the QR primitive polynomial 0x11D. */
  function gfMul(x, y) {
    var z = 0;
    for (var i = 7; i >= 0; i--) {
      z = ((z << 1) ^ ((z >>> 7) * 0x11D)) & 0xFFFF;
      z = z ^ (((y >>> i) & 1) * x);
    }
    return z & 0xFF;
  }

  function rsGenerator(degree) {
    var result = new Array(degree);
    for (var i = 0; i < degree; i++) result[i] = 0;
    result[degree - 1] = 1;
    var root = 1;
    for (var k = 0; k < degree; k++) {
      for (var j = 0; j < degree; j++) {
        result[j] = gfMul(result[j] & 0xFF, root);
        if (j + 1 < degree) result[j] = (result[j] ^ (result[j + 1] & 0xFF)) & 0xFF;
      }
      root = gfMul(root, 0x02);
    }
    return result;
  }

  function rsRemainder(data, generator) {
    var result = new Array(generator.length);
    for (var i = 0; i < result.length; i++) result[i] = 0;
    for (var b = 0; b < data.length; b++) {
      var factor = (data[b] ^ result[0]) & 0xFF;
      for (var s = 1; s < result.length; s++) result[s - 1] = result[s];
      result[result.length - 1] = 0;
      for (var i2 = 0; i2 < result.length; i2++) {
        result[i2] = (result[i2] ^ gfMul(generator[i2] & 0xFF, factor)) & 0xFF;
      }
    }
    return result;
  }

  /* Minimal MSB-first bit accumulator (mirrors Kotlin's BitBuffer). */
  function BitBuffer(capacityBits) {
    this.data = new Array(Math.floor((capacityBits + 7) / 8) + 4);
    for (var i = 0; i < this.data.length; i++) this.data[i] = 0;
    this.length = 0;
  }
  BitBuffer.prototype.append = function (value, bits) {
    for (var i = bits - 1; i >= 0; i--) {
      var bit = (value >>> i) & 1;
      if (bit !== 0) {
        var idx = this.length >>> 3;
        if (idx < this.data.length) {
          this.data[idx] = (this.data[idx] | (0x80 >>> (this.length & 7))) & 0xFF;
        }
      }
      this.length++;
    }
  };
  BitBuffer.prototype.toBytes = function () {
    return this.data.slice(0, Math.floor((this.length + 7) / 8));
  };

  /* -------------------------------------------------- data bit assembly -- */

  function buildDataBits(data, version, ecc) {
    var capacityBits = dataCodewords(version, ecc) * 8;
    var bb = new BitBuffer(capacityBits);

    bb.append(0x4, 4);                              // byte mode
    bb.append(data.length, charCountBits(version)); // length
    for (var i = 0; i < data.length; i++) bb.append(data[i] & 0xFF, 8);

    var remaining = capacityBits - bb.length;
    bb.append(0, Math.min(4, remaining));           // terminator
    bb.append(0, (8 - (bb.length % 8)) % 8);        // byte align

    var pad = 0xEC;
    while (bb.length < capacityBits) {
      bb.append(pad, 8);
      pad = pad === 0xEC ? 0x11 : 0xEC;
    }
    return bb.toBytes();
  }

  /* ---------------------------------------------- EC + block interleave -- */

  function interleave(data, version, ecc) {
    var numBlocks = numBlocksFor(version, ecc);
    var rawCount = totalCodewords(version);
    var eccLen = eccPerBlock(version, ecc);

    // v6.5 — THE BARCODE FIX (identical to QrCode.kt). `shortBlockLen` is the
    // TOTAL length of a short block, data PLUS parity, so the EC codewords must
    // NOT be subtracted here — they are subtracted once, below, when each block's
    // data length is derived. v6.4 subtracted them twice, leaving every block
    // `eccLen` codewords short and roughly two thirds of the matrix blank: the
    // code looked perfect but always failed Reed-Solomon, which is exactly the
    // reported "the barcode does nothing when scanned".
    var shortBlockLen = Math.floor(rawCount / numBlocks);
    var numShortBlocks = numBlocks - (rawCount % numBlocks);

    var blocks = [];
    var eccBlocks = [];
    var generator = rsGenerator(eccLen);

    var k = 0;
    for (var i = 0; i < numBlocks; i++) {
      var len = shortBlockLen - eccLen + (i < numShortBlocks ? 0 : 1);
      var block = data.slice(k, k + len);
      k += len;
      blocks.push(block);
      eccBlocks.push(rsRemainder(block, generator));
    }

    var result = new Array(rawCount);
    for (var z = 0; z < rawCount; z++) result[z] = 0;
    var idx = 0;

    var maxData = 0;
    for (var b1 = 0; b1 < blocks.length; b1++) {
      if (blocks[b1].length > maxData) maxData = blocks[b1].length;
    }
    for (var d = 0; d < maxData; d++) {
      for (var b2 = 0; b2 < blocks.length; b2++) {
        if (d < blocks[b2].length) result[idx++] = blocks[b2][d];
      }
    }
    for (var e = 0; e < eccLen; e++) {
      for (var b3 = 0; b3 < eccBlocks.length; b3++) result[idx++] = eccBlocks[b3][e];
    }

    // Regression guard — a correct interleave fills the array EXACTLY.
    if (idx !== rawCount) {
      throw new Error("QR interleave produced " + idx + " of " + rawCount +
        " codewords (v" + version + ") - the code would not be scannable");
    }
    return result;
  }

  /* ------------------------------------------------- matrix rendering ---- */

  function newGrid(size) {
    var g = new Array(size);
    for (var y = 0; y < size; y++) {
      g[y] = new Array(size);
      for (var x = 0; x < size; x++) g[y][x] = false;
    }
    return g;
  }

  function setFn(m, r, x, y, dark) {
    if (x < 0 || y < 0 || x >= m.length || y >= m.length) return;
    m[y][x] = dark;
    r[y][x] = true;
  }
  function reserve(r, x, y) {
    if (x >= 0 && y >= 0 && x < r.length && y < r.length) r[y][x] = true;
  }

  function alignmentPositions(version) {
    if (version === 1) return [];
    var n = Math.floor(version / 7) + 2;
    var step = version === 32
      ? 26
      : Math.floor((version * 4 + n * 2 + 1) / (n * 2 - 2)) * 2;
    var result = new Array(n);
    result[0] = 6;
    var pos = version * 4 + 10;
    for (var i = n - 1; i >= 1; i--) { result[i] = pos; pos -= step; }
    return result;
  }

  function drawFinder(m, r, cx, cy) {
    for (var dy = -4; dy <= 4; dy++) {
      for (var dx = -4; dx <= 4; dx++) {
        var x = cx + dx, y = cy + dy;
        if (x < 0 || y < 0 || x >= m.length || y >= m.length) continue;
        var d = Math.max(Math.abs(dx), Math.abs(dy));
        setFn(m, r, x, y, d !== 2 && d !== 4);
      }
    }
  }

  function drawAlignment(m, r, cx, cy) {
    for (var dy = -2; dy <= 2; dy++) {
      for (var dx = -2; dx <= 2; dx++) {
        var x = cx + dx, y = cy + dy;
        if (x < 0 || y < 0 || x >= m.length || y >= m.length) continue;
        setFn(m, r, x, y, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
      }
    }
  }

  function drawVersionBits(m, r, version, size) {
    var rem = version;
    for (var i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
    var bits = (version << 12) | rem;
    for (var j = 0; j < 18; j++) {
      var bit = ((bits >>> j) & 1) !== 0;
      var a = size - 11 + (j % 3);
      var b = Math.floor(j / 3);
      setFn(m, r, a, b, bit);
      setFn(m, r, b, a, bit);
    }
  }

  function drawFunctionPatterns(m, r, version) {
    var size = m.length;
    for (var i = 0; i < size; i++) {
      setFn(m, r, 6, i, i % 2 === 0);
      setFn(m, r, i, 6, i % 2 === 0);
    }
    drawFinder(m, r, 3, 3);
    drawFinder(m, r, size - 4, 3);
    drawFinder(m, r, 3, size - 4);

    var align = alignmentPositions(version);
    for (var a = 0; a < align.length; a++) {
      for (var b = 0; b < align.length; b++) {
        if ((a === 0 && b === 0) ||
            (a === 0 && b === align.length - 1) ||
            (a === align.length - 1 && b === 0)) continue;
        drawAlignment(m, r, align[a], align[b]);
      }
    }
    if (version >= 7) drawVersionBits(m, r, version, size);

    for (var q = 0; q <= 8; q++) {
      if (q !== 6) { reserve(r, q, 8); reserve(r, 8, q); }
    }
    reserve(r, 8, 8);
    for (var s = 0; s <= 7; s++) reserve(r, size - 1 - s, 8);
    for (var t = 0; t <= 7; t++) reserve(r, 8, size - 1 - t);
    setFn(m, r, 8, size - 8, true);   // permanent dark module
  }

  function drawFormatBits(m, r, ecc, mask, size) {
    var data = (ecc.fmt << 3) | mask;
    var rem = data;
    for (var i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    var bits = ((data << 10) | rem) ^ 0x5412;

    for (var a = 0; a <= 5; a++) setFn(m, r, 8, a, getBit(bits, a));
    setFn(m, r, 8, 7, getBit(bits, 6));
    setFn(m, r, 8, 8, getBit(bits, 7));
    setFn(m, r, 7, 8, getBit(bits, 8));
    for (var b = 9; b <= 14; b++) setFn(m, r, 14 - b, 8, getBit(bits, b));

    for (var c = 0; c <= 7; c++) setFn(m, r, size - 1 - c, 8, getBit(bits, c));
    for (var d = 8; d <= 14; d++) setFn(m, r, 8, size - 15 + d, getBit(bits, d));
    setFn(m, r, 8, size - 8, true);
  }

  function drawCodewords(m, r, data, size) {
    var i = 0;
    var right = size - 1;
    while (right >= 1) {
      if (right === 6) right = 5;
      for (var vert = 0; vert < size; vert++) {
        for (var j = 0; j <= 1; j++) {
          var x = right - j;
          var upward = ((right + 1) & 2) === 0;
          var y = upward ? size - 1 - vert : vert;
          if (r[y][x]) continue;
          if (i < data.length * 8) {
            m[y][x] = getBit(data[i >>> 3], 7 - (i & 7));
            i++;
          }
        }
      }
      right -= 2;
    }
  }

  function applyMask(m, r, mask, size) {
    for (var y = 0; y < size; y++) {
      for (var x = 0; x < size; x++) {
        if (r[y][x]) continue;
        var invert;
        switch (mask) {
          case 0: invert = (x + y) % 2 === 0; break;
          case 1: invert = y % 2 === 0; break;
          case 2: invert = x % 3 === 0; break;
          case 3: invert = (x + y) % 3 === 0; break;
          case 4: invert = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break;
          case 5: invert = ((x * y) % 2 + (x * y) % 3) === 0; break;
          case 6: invert = (((x * y) % 2 + (x * y) % 3) % 2) === 0; break;
          default: invert = (((x + y) % 2 + (x * y) % 3) % 2) === 0; break;
        }
        if (invert) m[y][x] = !m[y][x];
      }
    }
  }

  var FINDER_RUN = [true, false, true, true, true, false, true];

  function matchesFinderRun(m, y, x, horizontal, size) {
    for (var i = 0; i <= 6; i++) {
      var v = horizontal ? m[y][x + i] : m[y + i][x];
      if (v !== FINDER_RUN[i]) return false;
    }
    var light = 0, a, v2;
    for (var j = 1; j <= 4; j++) {
      a = horizontal ? x - j : y - j;
      if (a < 0) { light++; continue; }
      v2 = horizontal ? m[y][a] : m[a][x];
      if (!v2) light++; else break;
    }
    if (light >= 4) return true;
    light = 0;
    for (var k = 7; k <= 10; k++) {
      a = horizontal ? x + k : y + k;
      if (a >= size) { light++; continue; }
      v2 = horizontal ? m[y][a] : m[a][x];
      if (!v2) light++; else break;
    }
    return light >= 4;
  }

  function penalty(m, size) {
    var result = 0, x, y, runColor, runLen;

    for (y = 0; y < size; y++) {
      runColor = m[y][0]; runLen = 1;
      for (x = 1; x < size; x++) {
        if (m[y][x] === runColor) {
          runLen++;
          if (runLen === 5) result += 3; else if (runLen > 5) result++;
        } else { runColor = m[y][x]; runLen = 1; }
      }
    }
    for (x = 0; x < size; x++) {
      runColor = m[0][x]; runLen = 1;
      for (y = 1; y < size; y++) {
        if (m[y][x] === runColor) {
          runLen++;
          if (runLen === 5) result += 3; else if (runLen > 5) result++;
        } else { runColor = m[y][x]; runLen = 1; }
      }
    }
    for (y = 0; y < size - 1; y++) {
      for (x = 0; x < size - 1; x++) {
        var c = m[y][x];
        if (c === m[y][x + 1] && c === m[y + 1][x] && c === m[y + 1][x + 1]) result += 3;
      }
    }
    for (y = 0; y < size; y++) {
      for (x = 0; x < size - 6; x++) if (matchesFinderRun(m, y, x, true, size)) result += 40;
    }
    for (x = 0; x < size; x++) {
      for (y = 0; y < size - 6; y++) if (matchesFinderRun(m, y, x, false, size)) result += 40;
    }
    var dark = 0;
    for (y = 0; y < size; y++) for (x = 0; x < size; x++) if (m[y][x]) dark++;
    var total = size * size;
    var kk = Math.floor((Math.abs(dark * 20 - total * 10) + total - 1) / total);
    return result + kk * 10;
  }

  function renderMatrix(codewords, version, ecc) {
    var size = version * 4 + 17;
    var modules = newGrid(size);
    var reserved = newGrid(size);

    drawFunctionPatterns(modules, reserved, version);
    drawCodewords(modules, reserved, codewords, size);

    var bestMask = 0, bestPenalty = Infinity;
    for (var mask = 0; mask <= 7; mask++) {
      applyMask(modules, reserved, mask, size);
      drawFormatBits(modules, reserved, ecc, mask, size);
      var p = penalty(modules, size);
      if (p < bestPenalty) { bestPenalty = p; bestMask = mask; }
      applyMask(modules, reserved, mask, size);   // XOR again to undo
    }
    applyMask(modules, reserved, bestMask, size);
    drawFormatBits(modules, reserved, ecc, bestMask, size);
    return modules;
  }

  /* ------------------------------------------------------------- public -- */

  function encode(text, eccName) {
    var ecc = ECC[String(eccName || "QUARTILE").toUpperCase()] || ECC.QUARTILE;
    var data = utf8Bytes(text);
    var version = smallestVersionFor(data.length, ecc);
    if (!version) throw new Error("payload too large for a QR code");
    var bits = buildDataBits(data, version, ecc);
    return renderMatrix(interleave(bits, version, ecc), version, ecc);
  }

  /**
   * Render `text` into `canvas`. Modules are drawn at an INTEGER pixel scale so
   * the code is always crisp — a fractionally-scaled QR blurs the module edges
   * and is the classic cause of "my phone won't scan it off the screen".
   */
  function draw(canvas, text, opts) {
    opts = opts || {};
    var quiet = opts.quietZone == null ? 4 : opts.quietZone;
    var dark = opts.dark || "#000000";
    var light = opts.light || "#ffffff";

    var m;
    try {
      m = encode(text, opts.ecc || "QUARTILE");
    } catch (e) {
      // A payload too long for QUARTILE still fits at MEDIUM; degrading is far
      // better than showing the operator nothing. Mirrors DownloadsActivity.
      m = encode(text, "MEDIUM");
    }

    var n = m.length;
    var total = n + quiet * 2;
    var target = opts.sizePx || canvas.width || 320;
    var scale = Math.max(1, Math.floor(target / total));
    var dim = total * scale;

    canvas.width = dim;
    canvas.height = dim;
    var ctx = canvas.getContext("2d");
    ctx.fillStyle = light;
    ctx.fillRect(0, 0, dim, dim);
    ctx.fillStyle = dark;
    for (var y = 0; y < n; y++) {
      for (var x = 0; x < n; x++) {
        if (!m[y][x]) continue;
        ctx.fillRect((x + quiet) * scale, (y + quiet) * scale, scale, scale);
      }
    }
    return n;
  }

  root.PanelQr = { encode: encode, draw: draw };
})(typeof window !== "undefined" ? window : this);
