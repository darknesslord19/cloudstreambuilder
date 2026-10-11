/*
 * Runs in the hidden page that hosts Nuvio and SkyStream plugins.
 *
 * Every plugin gets a private iframe, so it has its own global scope and cannot disturb any
 * other plugin. This file only creates those iframes and routes calls to them, the environment
 * a plugin sees is set up by runtime.js inside the iframe.
 *
 * window.__csHost is the bridge to the app (see AddonHostBridge.kt).
 */
(function () {
  'use strict';
  if (window.__cs) return;

  var host = window.__csHost;
  var frames = {};   // frame id -> { key, win, el }
  var byKey = {};    // plugin key -> frame id
  var nextFrame = 1;
  var runtimeSource = null;

  function describe(error) {
    if (error === null || error === undefined) return 'Unknown error';
    try {
      if (error.stack && String(error.stack).indexOf(String(error.message)) >= 0) return String(error.stack);
      if (error.message) return String(error.name || 'Error') + ': ' + error.message;
      return String(error);
    } catch (e) {
      return 'Unknown error';
    }
  }

  function removeFrame(id) {
    var frame = frames[id];
    if (!frame) return;
    delete frames[id];
    if (byKey[frame.key] === id) delete byKey[frame.key];
    try { if (frame.win.__csrt) frame.win.__csrt.dispose(); } catch (e) { /* frame already gone */ }
    try { frame.el.parentNode.removeChild(frame.el); } catch (e) { /* frame already gone */ }
  }

  function createFrame(key) {
    var env = JSON.parse(host.getEnv(key));
    if (runtimeSource === null) runtimeSource = host.getAsset('runtime.js');

    var el = document.createElement('iframe');
    el.style.display = 'none';
    (document.body || document.documentElement).appendChild(el);

    var id = 'f' + (nextFrame++);
    var win = el.contentWindow;
    var frame = { key: key, win: win, el: el };
    frames[id] = frame;
    try {
      win.__csHost = host;
      win.eval(runtimeSource + '\n//# sourceURL=cs-addon-runtime.js');
      win.__csrt.init(id, key, env);
      win.__csrt.load(host.getSource(key));
    } catch (e) {
      removeFrame(id);
      throw e;
    }
    byKey[key] = id;
    return frame;
  }

  window.__cs = {
    /** Calls a function of a plugin, the result is reported through host.done(callId, ...) */
    invoke: function (callId, key, fn, argsJson) {
      var frame;
      try {
        frame = byKey[key] ? frames[byKey[key]] : createFrame(key);
      } catch (e) {
        host.done(callId, '0', 'Failed to load plugin: ' + describe(e));
        return;
      }
      try {
        frame.win.__csrt.invoke(callId, fn, argsJson);
      } catch (e) {
        host.done(callId, '0', describe(e));
      }
    },

    /** The app finished an asynchronous request, its result is waiting in host.take(reqId) */
    onNative: function (reqId) {
      var frame = frames[reqId.substring(0, reqId.indexOf('.'))];
      if (frame) {
        frame.win.__csrt.onNative(reqId);
      } else {
        // The plugin was unloaded in the meantime, drop the result.
        host.take(reqId);
      }
    },

    unload: function (key) {
      if (byKey[key]) removeFrame(byKey[key]);
    },

    unloadAll: function () {
      Object.keys(frames).forEach(removeFrame);
    },

    loaded: function () {
      return Object.keys(byKey);
    }
  };
})();
