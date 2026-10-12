/*
 * Gives a page the colours of the app theme. The app hands them over as Access.theme()
 * (background, surface, text, muted, accent). Colours are mixed here rather than with CSS
 * color-mix(), which older Android WebViews do not know.
 */
function applyTheme(access) {
  function parse(hex) {
    if (!/^#[0-9a-fA-F]{6}$/.test(hex || "")) return null;
    var n = parseInt(hex.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function luminance(c) {
    var l = c.map(function (v) { v /= 255; return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); });
    return .2126 * l[0] + .7152 * l[1] + .0722 * l[2];
  }
  function contrast(a, b) { var x = luminance(a), y = luminance(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05); }
  function mix(a, b, amountOfA) {
    return 'rgb(' + a.map(function (v, i) { return Math.round(v * amountOfA + b[i] * (1 - amountOfA)); }).join(',') + ')';
  }
  function alpha(c, a) { return 'rgba(' + c.join(',') + ',' + a + ')'; }

  var t = {};
  try { t = JSON.parse(access && access.theme ? access.theme() : "{}") || {}; } catch (e) {}
  var root = document.documentElement.style;
  var bg = parse(t.background), ink = parse(t.text), muted = parse(t.muted), accent = parse(t.accent);
  // A theme that would make the page hard to read keeps the colours of the page
  if (bg && ink && contrast(bg, ink) > 4) {
    root.setProperty('--bg', t.background);
    root.setProperty('--ink', t.text);
    root.setProperty('--card', mix(ink, bg, .05));
    root.setProperty('--field', mix(ink, bg, .09));
    root.setProperty('--line', alpha(ink, .10));
    if (muted) root.setProperty('--muted', t.muted);
    if (accent && contrast(accent, bg) > 2.5) {
      root.setProperty('--accent', t.accent);
      root.setProperty('--accent-2', mix(accent, [255, 255, 255], .6));
      root.setProperty('--alert-ink', mix(accent, [255, 255, 255], .55));
      [12, 16, 22, 32, 45].forEach(function (a) { root.setProperty('--accent-a' + a, alpha(accent, a / 100)); });
    }
  }
}
