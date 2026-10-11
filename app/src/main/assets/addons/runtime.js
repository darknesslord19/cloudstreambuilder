/*
 * The environment a Nuvio or SkyStream plugin runs in. bootstrap.js evaluates this file once
 * inside the private iframe of every plugin, then calls __csrt.init() and __csrt.load().
 *
 * Network access, timers and a few helpers go through window.__csHost, the bridge to the app
 * (AddonHostBridge.kt). Requests made that way are not limited by CORS and may set any header,
 * which is what plugins written for Nuvio (React Native) and SkyStream (QuickJS) expect.
 *
 * Written in conservative JavaScript on purpose, so it also runs on older WebView versions.
 */
(function (global) {
  'use strict';

  var host = global.__csHost;
  var frameId = '';
  var pluginKey = '';
  var env = {};
  var api = {};          // what the loaded plugin exports
  var pending = {};      // request id -> function(result text)
  var requestSeq = 0;

  // ---------------------------------------------------------------- small helpers

  function describe(error) {
    if (error === null || error === undefined) return 'Unknown error';
    try {
      if (typeof error === 'string') return error;
      if (error.message) return String(error.name || 'Error') + ': ' + error.message;
      return String(error);
    } catch (e) {
      return 'Unknown error';
    }
  }

  function nextId() {
    requestSeq += 1;
    return frameId + '.' + requestSeq;
  }

  function parseEnvelope(text) {
    try {
      return JSON.parse(text);
    } catch (e) {
      return { ok: false, error: 'Invalid answer from the app' };
    }
  }

  function bytesToBase64(bytes) {
    var binary = '';
    var chunk = 0x8000;
    for (var i = 0; i < bytes.length; i += chunk) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    return btoa(binary);
  }

  function base64ToBytes(text) {
    var binary = atob(text || '');
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  function isThenable(value) {
    return value !== null && value !== undefined && typeof value.then === 'function';
  }

  // ---------------------------------------------------------------- console

  function formatLogArgument(value) {
    if (typeof value === 'string') return value;
    if (value instanceof Error) return describe(value) + (value.stack ? '\n' + value.stack : '');
    try {
      var text = JSON.stringify(value);
      return text === undefined ? String(value) : text;
    } catch (e) {
      return String(value);
    }
  }

  function logger(level) {
    return function () {
      try {
        var parts = [];
        for (var i = 0; i < arguments.length; i++) parts.push(formatLogArgument(arguments[i]));
        var text = parts.join(' ');
        if (text.length > 4000) text = text.substring(0, 4000) + '... [truncated]';
        host.log(level, pluginKey, text);
      } catch (e) { /* logging must never break a plugin */ }
    };
  }

  var consoleShim = {
    log: logger('d'), debug: logger('d'), info: logger('i'), warn: logger('w'),
    error: logger('e'), trace: logger('d'), dir: logger('d'), table: logger('d'),
    group: function () {}, groupEnd: function () {}, groupCollapsed: function () {},
    time: function () {}, timeEnd: function () {}, assert: function () {}
  };

  // ---------------------------------------------------------------- timers
  // Timers of a page that is not on screen are slowed down by the browser engine, so they are
  // run by the app instead, which keeps them exact.

  var timers = {};
  var timerSeq = 0;

  function startTimer(callback, delay, args, repeat) {
    if (typeof callback !== 'function') return 0;
    timerSeq += 1;
    var timerId = timerSeq;
    var ms = Math.max(0, Number(delay) || 0);
    var timer = { requestId: '' };
    timers[timerId] = timer;

    function arm() {
      var requestId = nextId();
      timer.requestId = requestId;
      pending[requestId] = function () {
        if (timers[timerId] !== timer) return;
        if (repeat) arm(); else delete timers[timerId];
        try {
          callback.apply(global, args);
        } catch (e) {
          consoleShim.error('Uncaught error in timer:', e);
        }
      };
      host.sleep(requestId, String(ms));
    }

    arm();
    return timerId;
  }

  function stopTimer(timerId) {
    var timer = timers[timerId];
    if (!timer) return;
    delete timers[timerId];
    delete pending[timer.requestId];
    try { host.cancel(timer.requestId); } catch (e) { /* nothing to cancel */ }
  }

  function csSetTimeout(callback, delay) {
    return startTimer(callback, delay, Array.prototype.slice.call(arguments, 2), false);
  }

  function csSetInterval(callback, delay) {
    return startTimer(callback, delay, Array.prototype.slice.call(arguments, 2), true);
  }

  // ---------------------------------------------------------------- http

  /** Asks the app to do a request, never rejects: failures come back as { ok: false, error }. */
  function nativeHttp(request) {
    return new Promise(function (resolve) {
      var requestId = nextId();
      pending[requestId] = function (text) { resolve(parseEnvelope(text)); };
      try {
        host.http(requestId, JSON.stringify(request));
      } catch (e) {
        delete pending[requestId];
        resolve({ ok: false, error: describe(e) });
      }
    });
  }

  function decodeBody(answer) {
    var bytes = base64ToBytes(answer.b64);
    try {
      return new TextDecoder(answer.charset || 'utf-8').decode(bytes);
    } catch (e) {
      return new TextDecoder('utf-8').decode(bytes);
    }
  }

  /** Copies any kind of header collection into a plain object. */
  function plainHeaders(headers) {
    var result = {};
    if (!headers) return result;
    if (typeof headers.forEach === 'function' && !Array.isArray(headers)) {
      headers.forEach(function (value, name) { result[name] = String(value); });
    } else if (Array.isArray(headers)) {
      headers.forEach(function (pair) {
        if (pair && pair.length >= 2) result[pair[0]] = String(pair[1]);
      });
    } else if (typeof headers === 'object') {
      Object.keys(headers).forEach(function (name) {
        var value = headers[name];
        if (value !== undefined && value !== null) result[name] = String(value);
      });
    }
    return result;
  }

  function hasHeader(headers, name) {
    var lower = name.toLowerCase();
    return Object.keys(headers).some(function (key) { return key.toLowerCase() === lower; });
  }

  function getHeader(headers, name) {
    var lower = name.toLowerCase();
    var keys = Object.keys(headers);
    for (var i = 0; i < keys.length; i++) {
      if (keys[i].toLowerCase() === lower) return headers[keys[i]];
    }
    return undefined;
  }

  /** The headers of an answer, with the methods of the standard Headers class. */
  function ResponseHeaders(map, setCookie) {
    this.map = map || {};   // React Native exposes this, some plugins read it directly
    this._setCookie = setCookie || [];
  }
  ResponseHeaders.prototype.get = function (name) {
    var value = this.map[String(name).toLowerCase()];
    return value === undefined ? null : value;
  };
  ResponseHeaders.prototype.has = function (name) {
    return this.map[String(name).toLowerCase()] !== undefined;
  };
  ResponseHeaders.prototype.forEach = function (callback, thisArg) {
    var self = this;
    Object.keys(this.map).forEach(function (name) { callback.call(thisArg, self.map[name], name, self); });
  };
  ResponseHeaders.prototype.keys = function () { return Object.keys(this.map)[Symbol.iterator](); };
  ResponseHeaders.prototype.values = function () {
    var self = this;
    return Object.keys(this.map).map(function (name) { return self.map[name]; })[Symbol.iterator]();
  };
  ResponseHeaders.prototype.entries = function () {
    var self = this;
    return Object.keys(this.map).map(function (name) { return [name, self.map[name]]; })[Symbol.iterator]();
  };
  ResponseHeaders.prototype[Symbol.iterator] = ResponseHeaders.prototype.entries;
  ResponseHeaders.prototype.getSetCookie = function () { return this._setCookie.slice(); };

  function abortError() {
    try {
      return new DOMException('The operation was aborted.', 'AbortError');
    } catch (e) {
      var error = new Error('The operation was aborted.');
      error.name = 'AbortError';
      return error;
    }
  }

  function makeResponse(answer, requestUrl) {
    var bytes = null;
    var text = null;
    function getBytes() {
      if (bytes === null) bytes = base64ToBytes(answer.b64);
      return bytes;
    }
    function getText() {
      if (text === null) text = decodeBody(answer);
      return text;
    }
    var finalUrl = answer.url || requestUrl;
    var headers = new ResponseHeaders(answer.headers, answer.setCookie);
    return {
      ok: answer.status >= 200 && answer.status < 300,
      status: answer.status,
      statusText: answer.statusText || '',
      url: finalUrl,
      redirected: finalUrl !== requestUrl,
      type: 'basic',
      headers: headers,
      body: null,
      bodyUsed: false,
      text: function () { return Promise.resolve().then(getText); },
      json: function () { return Promise.resolve().then(function () { return JSON.parse(getText()); }); },
      arrayBuffer: function () {
        return Promise.resolve().then(function () {
          var data = getBytes();
          return data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
        });
      },
      blob: function () {
        return Promise.resolve().then(function () {
          return new Blob([getBytes()], { type: headers.get('content-type') || '' });
        });
      },
      clone: function () { return makeResponse(answer, requestUrl); }
    };
  }

  /** Turns the body of a request into text, or into base64 for binary data. */
  function encodeBody(body, headers) {
    if (body === undefined || body === null) return Promise.resolve({ body: null, binary: false });
    if (typeof body === 'string') return Promise.resolve({ body: body, binary: false });

    if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
      if (!hasHeader(headers, 'content-type')) {
        headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
      }
      return Promise.resolve({ body: body.toString(), binary: false });
    }

    if (typeof FormData !== 'undefined' && body instanceof FormData) {
      var boundary = '----csFormBoundary' + Math.random().toString(36).substring(2) + Date.now().toString(36);
      var multipart = '';
      body.forEach(function (value, name) {
        multipart += '--' + boundary + '\r\nContent-Disposition: form-data; name="' +
          String(name).replace(/"/g, '%22') + '"\r\n\r\n' + String(value) + '\r\n';
      });
      multipart += '--' + boundary + '--\r\n';
      headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary;
      return Promise.resolve({ body: multipart, binary: false });
    }

    if (body instanceof ArrayBuffer) {
      return Promise.resolve({ body: bytesToBase64(new Uint8Array(body)), binary: true });
    }
    if (ArrayBuffer.isView(body)) {
      return Promise.resolve({
        body: bytesToBase64(new Uint8Array(body.buffer, body.byteOffset, body.byteLength)),
        binary: true
      });
    }
    if (typeof Blob !== 'undefined' && body instanceof Blob && typeof body.arrayBuffer === 'function') {
      return body.arrayBuffer().then(function (buffer) {
        if (body.type && !hasHeader(headers, 'content-type')) headers['Content-Type'] = body.type;
        return { body: bytesToBase64(new Uint8Array(buffer)), binary: true };
      });
    }
    return Promise.resolve({ body: String(body), binary: false });
  }

  function csFetch(input, init) {
    return new Promise(function (resolve, reject) {
      var options = init || {};
      var url;
      if (typeof input === 'string') {
        url = input;
      } else if (input && typeof input.url === 'string') {
        // A Request object, its own settings are used unless init overrides them
        url = input.url;
        options = {
          method: options.method || input.method,
          headers: options.headers || input.headers,
          body: options.body,
          redirect: options.redirect || input.redirect,
          signal: options.signal || input.signal
        };
      } else {
        url = String(input);
      }

      var signal = options.signal || null;
      if (signal && signal.aborted) {
        reject(abortError());
        return;
      }

      var headers = plainHeaders(options.headers);
      var method = String(options.method || 'GET').toUpperCase();
      var settled = false;

      if (signal && typeof signal.addEventListener === 'function') {
        signal.addEventListener('abort', function () {
          if (settled) return;
          settled = true;
          reject(abortError());
        });
      }

      encodeBody(options.body, headers).then(function (encoded) {
        return nativeHttp({
          url: url,
          method: method,
          headers: headers,
          body: encoded.body,
          binary: encoded.binary,
          redirect: options.redirect === 'manual' || options.redirect === 'error' ? 'manual' : 'follow'
        });
      }).then(function (answer) {
        if (settled) return;
        settled = true;
        if (!answer.ok) {
          reject(new TypeError('Network request failed: ' + (answer.error || 'unknown error')));
        } else {
          resolve(makeResponse(answer, url));
        }
      }, function (error) {
        if (settled) return;
        settled = true;
        reject(error);
      });
    });
  }

  // A small XMLHttpRequest on top of csFetch, for bundled libraries that only know that one.
  function CsXMLHttpRequest() {
    this.readyState = 0;
    this.status = 0;
    this.statusText = '';
    this.response = null;
    this.responseText = '';
    this.responseURL = '';
    this.responseType = '';
    this.timeout = 0;
    this.withCredentials = false;
    this.upload = { addEventListener: function () {}, removeEventListener: function () {} };
    this._listeners = {};
    this._headers = {};
    this._responseHeaders = null;
    this._aborted = false;
    this._sent = 0;
  }
  CsXMLHttpRequest.UNSENT = 0;
  CsXMLHttpRequest.OPENED = 1;
  CsXMLHttpRequest.HEADERS_RECEIVED = 2;
  CsXMLHttpRequest.LOADING = 3;
  CsXMLHttpRequest.DONE = 4;
  CsXMLHttpRequest.prototype.addEventListener = function (type, listener) {
    (this._listeners[type] = this._listeners[type] || []).push(listener);
  };
  CsXMLHttpRequest.prototype.removeEventListener = function (type, listener) {
    var list = this._listeners[type] || [];
    var index = list.indexOf(listener);
    if (index >= 0) list.splice(index, 1);
  };
  CsXMLHttpRequest.prototype._emit = function (type) {
    var event = { type: type, target: this, currentTarget: this, loaded: 0, total: 0, lengthComputable: false };
    var handler = this['on' + type];
    if (typeof handler === 'function') {
      try { handler.call(this, event); } catch (e) { consoleShim.error(e); }
    }
    var self = this;
    (this._listeners[type] || []).slice().forEach(function (listener) {
      try { listener.call(self, event); } catch (e) { consoleShim.error(e); }
    });
  };
  CsXMLHttpRequest.prototype.open = function (method, url) {
    this._method = method;
    this._url = String(url);
    this._headers = {};
    this._aborted = false;
    this.readyState = 1;
    this._emit('readystatechange');
  };
  CsXMLHttpRequest.prototype.setRequestHeader = function (name, value) {
    this._headers[name] = hasHeader(this._headers, name) && this._headers[name] !== undefined
      ? this._headers[name] + ', ' + value : String(value);
  };
  CsXMLHttpRequest.prototype.overrideMimeType = function () {};
  CsXMLHttpRequest.prototype.getResponseHeader = function (name) {
    return this._responseHeaders ? this._responseHeaders.get(name) : null;
  };
  CsXMLHttpRequest.prototype.getAllResponseHeaders = function () {
    if (!this._responseHeaders) return '';
    var lines = '';
    this._responseHeaders.forEach(function (value, name) { lines += name + ': ' + value + '\r\n'; });
    return lines;
  };
  CsXMLHttpRequest.prototype.abort = function () {
    this._aborted = true;
    this._sent += 1;
    if (this.readyState !== 0 && this.readyState !== 4) {
      this.readyState = 4;
      this._emit('readystatechange');
      this._emit('abort');
      this._emit('loadend');
    }
    this.readyState = 0;
  };
  CsXMLHttpRequest.prototype.send = function (body) {
    var self = this;
    var ticket = ++this._sent;
    var timeoutTimer = 0;
    function finish(type) {
      if (timeoutTimer) stopTimer(timeoutTimer);
      self.readyState = 4;
      self._emit('readystatechange');
      self._emit(type);
      self._emit('loadend');
    }
    if (this.timeout > 0) {
      timeoutTimer = csSetTimeout(function () {
        if (ticket !== self._sent || self.readyState === 4) return;
        self._sent += 1;
        finish('timeout');
      }, this.timeout);
    }
    csFetch(this._url, { method: this._method, headers: this._headers, body: body }).then(function (response) {
      if (ticket !== self._sent) return null;
      self.status = response.status;
      self.statusText = response.statusText;
      self.responseURL = response.url;
      self._responseHeaders = response.headers;
      var type = self.responseType;
      if (type === 'arraybuffer') return response.arrayBuffer();
      if (type === 'blob') return response.blob();
      return response.text();
    }).then(function (data) {
      if (ticket !== self._sent) return;
      var type = self.responseType;
      if (type === 'arraybuffer' || type === 'blob') {
        self.response = data;
      } else {
        self.responseText = data;
        if (type === 'json') {
          try { self.response = JSON.parse(data); } catch (e) { self.response = null; }
        } else {
          self.response = data;
        }
      }
      finish('load');
    }).catch(function () {
      if (ticket !== self._sent) return;
      self.status = 0;
      finish('error');
    });
  };

  // ---------------------------------------------------------------- crypto helpers

  function nativeCrypto(operation, a, b, c, d) {
    var answer = parseEnvelope(host.crypto(operation, String(a === undefined || a === null ? '' : a),
      String(b === undefined || b === null ? '' : b), String(c === undefined || c === null ? '' : c),
      String(d === undefined || d === null ? '' : d)));
    if (!answer.ok) throw new Error(answer.error || 'Crypto operation failed');
    return answer.value;
  }

  // ---------------------------------------------------------------- bundled libraries

  var vendorCache = {};

  /** Loads a library shipped with the app. The file must define the variable [globalName]. */
  function vendor(file, globalName) {
    if (!vendorCache[file]) {
      var source = host.getAsset(file);
      // Run inside a function so the library neither sees nor touches `module` and `exports`,
      // which belong to the plugin.
      vendorCache[file] = new Function(
        'module', 'exports', 'define', 'require',
        source + '\n;return ' + globalName + ';\n//# sourceURL=cs-addon-' + file.replace(/[^a-z0-9.]/gi, '-')
      )(undefined, undefined, undefined, undefined);
    }
    return vendorCache[file];
  }

  function cheerio() { return vendor('vendor/cheerio.js', '__csCheerio'); }
  function cryptoJs() { return vendor('vendor/crypto-js.js', '__csCryptoJS'); }

  // The parts of axios that scrapers use, built on csFetch.
  function createAxios(defaults) {
    function buildUrl(config) {
      var url = config.url || '';
      if (config.baseURL && !/^[a-z][a-z0-9+.-]*:\/\//i.test(url)) {
        url = String(config.baseURL).replace(/\/+$/, '') + '/' + String(url).replace(/^\/+/, '');
      }
      if (config.params) {
        var query = typeof URLSearchParams !== 'undefined' && config.params instanceof URLSearchParams
          ? config.params.toString()
          : Object.keys(config.params).filter(function (key) {
            return config.params[key] !== undefined && config.params[key] !== null;
          }).map(function (key) {
            return encodeURIComponent(key) + '=' + encodeURIComponent(config.params[key]);
          }).join('&');
        if (query) url += (url.indexOf('?') >= 0 ? '&' : '?') + query;
      }
      return url;
    }

    function request(input) {
      var config = {};
      var source = [defaults || {}, input || {}];
      source.forEach(function (part) {
        Object.keys(part).forEach(function (key) {
          if (key === 'headers') return;
          config[key] = part[key];
        });
      });
      var headers = plainHeaders((defaults || {}).headers);
      // Old style defaults keep shared headers under "common"
      if (headers.common !== undefined) delete headers.common;
      var given = plainHeaders((input || {}).headers);
      Object.keys(given).forEach(function (name) { headers[name] = given[name]; });
      config.headers = headers;

      var method = String(config.method || 'get').toUpperCase();
      var body = config.data;
      if (body !== undefined && body !== null && typeof body === 'object' &&
        !(body instanceof ArrayBuffer) && !ArrayBuffer.isView(body) &&
        !(typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) &&
        !(typeof FormData !== 'undefined' && body instanceof FormData) &&
        !(typeof Blob !== 'undefined' && body instanceof Blob)) {
        body = JSON.stringify(body);
        if (!hasHeader(headers, 'content-type')) headers['Content-Type'] = 'application/json';
      } else if (typeof body === 'string' && !hasHeader(headers, 'content-type')) {
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
      }
      if (!hasHeader(headers, 'accept')) headers.Accept = 'application/json, text/plain, */*';

      var fetchOptions = { method: method, headers: headers, signal: config.signal };
      if (method !== 'GET' && method !== 'HEAD') fetchOptions.body = body;
      if (config.maxRedirects === 0) fetchOptions.redirect = 'manual';

      var call = csFetch(buildUrl(config), fetchOptions).then(function (response) {
        var type = config.responseType || 'json';
        var reader = type === 'arraybuffer' ? response.arrayBuffer()
          : type === 'blob' ? response.blob() : response.text();
        return reader.then(function (data) {
          if (type === 'json' && typeof data === 'string') {
            try { data = JSON.parse(data); } catch (e) { /* keep the text, like axios does */ }
          }
          var result = {
            data: data,
            status: response.status,
            statusText: response.statusText,
            headers: response.headers.map,
            config: config,
            request: { responseURL: response.url, res: { responseUrl: response.url } }
          };
          var validate = config.validateStatus === undefined
            ? function (status) { return status >= 200 && status < 300; }
            : config.validateStatus;
          if (validate && !validate(response.status)) {
            var error = new Error('Request failed with status code ' + response.status);
            error.name = 'AxiosError';
            error.isAxiosError = true;
            error.response = result;
            error.config = config;
            error.code = response.status >= 500 ? 'ERR_BAD_RESPONSE' : 'ERR_BAD_REQUEST';
            throw error;
          }
          return result;
        });
      }, function (cause) {
        var error = new Error(cause && cause.message ? cause.message : 'Network Error');
        error.name = 'AxiosError';
        error.isAxiosError = true;
        error.config = config;
        error.code = 'ERR_NETWORK';
        throw error;
      });

      if (config.timeout > 0) {
        return new Promise(function (resolve, reject) {
          var timer = csSetTimeout(function () {
            var error = new Error('timeout of ' + config.timeout + 'ms exceeded');
            error.name = 'AxiosError';
            error.isAxiosError = true;
            error.config = config;
            error.code = 'ECONNABORTED';
            reject(error);
          }, config.timeout);
          call.then(function (value) { stopTimer(timer); resolve(value); },
            function (error) { stopTimer(timer); reject(error); });
        });
      }
      return call;
    }

    function axios(first, second) {
      if (typeof first === 'string') {
        var merged = {};
        Object.keys(second || {}).forEach(function (key) { merged[key] = second[key]; });
        merged.url = first;
        return request(merged);
      }
      return request(first);
    }
    axios.request = request;
    axios.defaults = defaults || {};
    if (!axios.defaults.headers) axios.defaults.headers = {};
    ['get', 'delete', 'head', 'options'].forEach(function (method) {
      axios[method] = function (url, config) {
        var merged = {};
        Object.keys(config || {}).forEach(function (key) { merged[key] = config[key]; });
        merged.url = url;
        merged.method = method;
        return request(merged);
      };
    });
    ['post', 'put', 'patch'].forEach(function (method) {
      axios[method] = function (url, data, config) {
        var merged = {};
        Object.keys(config || {}).forEach(function (key) { merged[key] = config[key]; });
        merged.url = url;
        merged.method = method;
        merged.data = data;
        return request(merged);
      };
    });
    axios.create = function (config) {
      var merged = {};
      Object.keys(defaults || {}).forEach(function (key) { merged[key] = defaults[key]; });
      Object.keys(config || {}).forEach(function (key) { merged[key] = config[key]; });
      merged.headers = plainHeaders((defaults || {}).headers);
      var extra = plainHeaders((config || {}).headers);
      Object.keys(extra).forEach(function (name) { merged.headers[name] = extra[name]; });
      return createAxios(merged);
    };
    axios.isAxiosError = function (error) { return !!(error && error.isAxiosError); };
    axios.all = function (promises) { return Promise.all(promises); };
    axios.spread = function (callback) { return function (values) { return callback.apply(null, values); }; };
    axios.interceptors = {
      request: { use: function () { return 0; }, eject: function () {} },
      response: { use: function () { return 0; }, eject: function () {} }
    };
    axios.default = axios;
    return axios;
  }

  var axiosInstance = null;

  var CRYPTO_JS_PARTS = {
    'core': null, 'md5': 'MD5', 'sha1': 'SHA1', 'sha256': 'SHA256', 'sha224': 'SHA224',
    'sha512': 'SHA512', 'sha384': 'SHA384', 'sha3': 'SHA3', 'ripemd160': 'RIPEMD160',
    'hmac-md5': 'HmacMD5', 'hmac-sha1': 'HmacSHA1', 'hmac-sha256': 'HmacSHA256',
    'hmac-sha512': 'HmacSHA512', 'pbkdf2': 'PBKDF2', 'evpkdf': 'EvpKDF', 'aes': 'AES',
    'tripledes': 'TripleDES', 'rc4': 'RC4', 'rabbit': 'Rabbit',
    'enc-base64': 'enc.Base64', 'enc-utf8': 'enc.Utf8', 'enc-hex': 'enc.Hex',
    'enc-latin1': 'enc.Latin1', 'enc-utf16': 'enc.Utf16', 'enc-base64url': 'enc.Base64url',
    'mode-ecb': 'mode.ECB', 'mode-cbc': 'mode.CBC', 'mode-cfb': 'mode.CFB', 'mode-ctr': 'mode.CTR',
    'mode-ofb': 'mode.OFB', 'pad-pkcs7': 'pad.Pkcs7', 'pad-nopadding': 'pad.NoPadding',
    'pad-zeropadding': 'pad.ZeroPadding', 'format-hex': 'format.Hex', 'format-openssl': 'format.OpenSSL'
  };

  function csRequire(name) {
    var id = String(name);
    if (id === 'cheerio' || id === 'cheerio-without-node-native' || id === 'react-native-cheerio' ||
      id === 'cheerio/slim') {
      return cheerio();
    }
    if (id === 'crypto-js') return cryptoJs();
    if (id.indexOf('crypto-js/') === 0) {
      var part = id.substring('crypto-js/'.length).replace(/\.js$/, '');
      if (Object.prototype.hasOwnProperty.call(CRYPTO_JS_PARTS, part)) {
        var path = CRYPTO_JS_PARTS[part];
        var value = cryptoJs();
        if (path) path.split('.').forEach(function (key) { value = value ? value[key] : undefined; });
        if (value !== undefined) return value;
      }
    }
    if (id === 'axios') {
      if (!axiosInstance) axiosInstance = createAxios({});
      return axiosInstance;
    }
    if (id === 'node-fetch' || id === 'cross-fetch' || id === 'isomorphic-fetch' || id === 'whatwg-fetch') {
      var fetchModule = function (input, init) { return csFetch(input, init); };
      fetchModule.default = fetchModule;
      fetchModule.fetch = fetchModule;
      fetchModule.Headers = global.Headers;
      return fetchModule;
    }
    if (id === 'url') return { URL: global.URL, URLSearchParams: global.URLSearchParams };
    if (id === 'querystring' || id === 'qs') {
      return {
        stringify: function (object) { return new URLSearchParams(object || {}).toString(); },
        parse: function (text) {
          var result = {};
          new URLSearchParams(String(text || '').replace(/^\?/, '')).forEach(function (value, key) {
            result[key] = value;
          });
          return result;
        }
      };
    }
    throw new Error("Module '" + id + "' is not available");
  }

  // A small part of Node's Buffer, enough for the base64/hex/text conversions scrapers do.
  var BufferPrototype = Object.create(Uint8Array.prototype);
  function wrapBuffer(bytes) {
    Object.setPrototypeOf(bytes, BufferPrototype);
    return bytes;
  }
  function binaryToBytes(binary) {
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i) & 0xff;
    return bytes;
  }
  function bytesToBinary(bytes) {
    var binary = '';
    var chunk = 0x8000;
    for (var i = 0; i < bytes.length; i += chunk) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    return binary;
  }
  var BufferShim = {
    from: function (input, encoding) {
      if (typeof input === 'string') {
        var kind = String(encoding || 'utf8').toLowerCase();
        if (kind === 'base64' || kind === 'base64url') {
          var normal = input.replace(/-/g, '+').replace(/_/g, '/').replace(/[^A-Za-z0-9+/]/g, '');
          return wrapBuffer(binaryToBytes(atob(normal)));
        }
        if (kind === 'hex') {
          var hex = input.length % 2 ? input.substring(0, input.length - 1) : input;
          var bytes = new Uint8Array(hex.length / 2);
          for (var i = 0; i < bytes.length; i++) bytes[i] = parseInt(hex.substr(i * 2, 2), 16) || 0;
          return wrapBuffer(bytes);
        }
        if (kind === 'binary' || kind === 'latin1' || kind === 'ascii') return wrapBuffer(binaryToBytes(input));
        return wrapBuffer(new TextEncoder().encode(input));
      }
      if (input instanceof ArrayBuffer) return wrapBuffer(new Uint8Array(input.slice(0)));
      if (ArrayBuffer.isView(input)) {
        return wrapBuffer(new Uint8Array(input.buffer.slice(input.byteOffset, input.byteOffset + input.byteLength)));
      }
      return wrapBuffer(new Uint8Array(input || []));
    },
    alloc: function (size) { return wrapBuffer(new Uint8Array(size)); },
    isBuffer: function (value) { return !!value && Object.getPrototypeOf(value) === BufferPrototype; },
    byteLength: function (text, encoding) { return BufferShim.from(text, encoding).length; },
    concat: function (list) {
      var total = 0;
      list.forEach(function (item) { total += item.length; });
      var result = new Uint8Array(total);
      var offset = 0;
      list.forEach(function (item) { result.set(item, offset); offset += item.length; });
      return wrapBuffer(result);
    }
  };
  BufferPrototype.toString = function (encoding) {
    var kind = String(encoding || 'utf8').toLowerCase();
    if (kind === 'base64') return btoa(bytesToBinary(this));
    if (kind === 'base64url') {
      return btoa(bytesToBinary(this)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }
    if (kind === 'hex') {
      var hex = '';
      for (var i = 0; i < this.length; i++) hex += (this[i] < 16 ? '0' : '') + this[i].toString(16);
      return hex;
    }
    if (kind === 'binary' || kind === 'latin1' || kind === 'ascii') return bytesToBinary(this);
    return new TextDecoder('utf-8').decode(this);
  };
  BufferPrototype.slice = function (start, end) {
    return wrapBuffer(Uint8Array.prototype.slice.call(this, start, end));
  };

  /** Defines a global that is only created when a script first touches it. */
  function lazyGlobal(name, factory) {
    if (typeof global[name] !== 'undefined') return;
    Object.defineProperty(global, name, {
      configurable: true,
      enumerable: false,
      get: function () {
        var value = factory();
        Object.defineProperty(global, name, { value: value, writable: true, configurable: true, enumerable: false });
        return value;
      },
      set: function (value) {
        Object.defineProperty(global, name, { value: value, writable: true, configurable: true, enumerable: true });
      }
    });
  }

  // ---------------------------------------------------------------- Nuvio

  /** Rewrites the few forms of ES module syntax scrapers use into CommonJS. */
  function esmToCommonJs(source) {
    return source
      .replace(/^\s*export\s+default\s+/gm, 'module.exports.default = ')
      .replace(/^\s*export\s+\{([^}]*)\}\s*;?/gm, function (match, names) {
        var assignments = names.split(',').map(function (name) {
          var parts = name.trim().split(/\s+as\s+/);
          if (!parts[0]) return '';
          return 'module.exports[' + JSON.stringify((parts[1] || parts[0]).trim()) + '] = ' + parts[0].trim() + ';';
        });
        return assignments.join(' ');
      })
      .replace(/^(\s*)export\s+(async\s+function|function|const|let|var|class)\s+([A-Za-z_$][\w$]*)/gm,
        function (match, indent, keyword, name) {
          exportedNames.push(name);
          return indent + keyword + ' ' + name;
        });
  }
  var exportedNames = [];

  function runNuvioSource(source) {
    global.module = { exports: {} };
    global.exports = global.module.exports;
    // Indirect eval runs the code as a normal (non strict) global script of this iframe.
    // The function wrapper is what Nuvio itself uses, it allows a top level `return`.
    (0, eval)('(function(){' + source +
      '\n;if (typeof getStreams === "function" && !module.exports.getStreams) module.exports.getStreams = getStreams;' +
      exportedNames.map(function (name) {
        return 'if (typeof ' + name + ' !== "undefined" && module.exports[' + JSON.stringify(name) +
          '] === undefined) module.exports[' + JSON.stringify(name) + '] = ' + name + ';';
      }).join('') +
      '\n})();\n//# sourceURL=nuvio-' + String(env.id || 'scraper').replace(/[^\w.-]/g, '_') + '.js');
  }

  function loadNuvio(source) {
    global.require = csRequire;
    global.SCRAPER_ID = env.id || '';
    global.SCRAPER_SETTINGS = env.settings || {};
    if (typeof global.TMDB_API_KEY === 'undefined') global.TMDB_API_KEY = env.tmdbApiKey || '';
    if (!global.process) global.process = { env: {}, platform: 'android', version: '', versions: {}, nextTick: function (callback) { Promise.resolve().then(callback); } };
    // Nuvio itself offers these two libraries as globals, next to require()
    lazyGlobal('cheerio', cheerio);
    lazyGlobal('CryptoJS', cryptoJs);
    if (typeof global.Buffer === 'undefined') global.Buffer = BufferShim;

    exportedNames = [];
    try {
      runNuvioSource(source);
    } catch (e) {
      if (!(e instanceof SyntaxError) || !/^\s*(export|import)\s/m.test(source)) throw e;
      runNuvioSource(esmToCommonJs(source));
    }

    var exported = global.module.exports || {};
    var getStreams = exported.getStreams ||
      (exported.default && exported.default.getStreams) ||
      (typeof exported.default === 'function' ? exported.default : undefined) ||
      (typeof exported === 'function' ? exported : undefined) ||
      global.getStreams;
    if (typeof getStreams !== 'function') {
      throw new Error('The scraper does not export a getStreams function');
    }
    api = { getStreams: getStreams };
  }

  function invokeNuvio(fn, args, finish) {
    if (typeof api[fn] !== 'function') {
      finish(false, 'Function ' + fn + ' not found');
      return;
    }
    Promise.resolve().then(function () {
      return api[fn].apply(null, args);
    }).then(function (result) {
      finish(true, result === undefined || result === null ? [] : result);
    }, function (error) {
      finish(false, describe(error));
    });
  }

  // ---------------------------------------------------------------- SkyStream

  var domNodes = {};       // node id -> element, for the node ids SkyStream plugins pass around
  var domNodeOrder = [];
  var domNodeSeq = 0;

  function registerNode(node) {
    domNodeSeq += 1;
    var id = 'node_' + domNodeSeq;
    domNodes[id] = node;
    domNodeOrder.push(id);
    if (domNodeOrder.length > 20000) {
      domNodeOrder.splice(0, 10000).forEach(function (old) { delete domNodes[old]; });
    }
    return id;
  }

  function parseDocument(html) {
    return new DOMParser().parseFromString(String(html === undefined || html === null ? '' : html), 'text/html');
  }

  /** querySelectorAll with support for the :contains(text) extension SkyStream offers. */
  function queryAll(root, query) {
    var selector = String(query);
    try {
      var match = /^(.*?):contains\((.*?)\)(.*)$/.exec(selector);
      if (!match) return Array.prototype.slice.call(root.querySelectorAll(selector));
      var base = match[1].trim();
      var needle = match[2].trim().replace(/^(['"])(.*)\1$/, '$2');
      var rest = match[3].trim();
      var found = Array.prototype.slice.call(root.querySelectorAll(base || '*')).filter(function (element) {
        return (element.textContent || '').indexOf(needle) >= 0;
      });
      if (!rest) return found;
      var result = [];
      found.forEach(function (element) {
        queryAll(element, rest.replace(/^>\s*/, ':scope > ')).forEach(function (child) {
          if (result.indexOf(child) < 0) result.push(child);
        });
      });
      return result;
    } catch (e) {
      return [];
    }
  }

  function extractAttribute(element, attribute) {
    switch (attribute) {
      case 'textContent': return element.textContent;
      case 'innerHTML': return element.innerHTML;
      case 'outerHTML': return element.outerHTML;
      case 'tagName': return element.localName;
      case 'className': return element.className;
      default: return element.getAttribute(attribute);
    }
  }

  function JSNode(node) {
    this._node = node;
    this.nodeId = registerNode(node);
    var isElement = node.nodeType === 1;
    var attributes = {};
    if (isElement) {
      for (var i = 0; i < node.attributes.length; i++) {
        attributes[node.attributes[i].name] = node.attributes[i].value;
      }
    }
    this.data = { nodeId: this.nodeId, attributes: attributes };
    this.tagName = isElement ? node.localName : '';
    this.textContent = (isElement ? node.textContent : (node.body ? node.body.textContent : '')) || '';
    this.innerHTML = (isElement ? node.innerHTML : (node.documentElement ? node.documentElement.innerHTML : '')) || '';
    this.outerHTML = (isElement ? node.outerHTML : (node.documentElement ? node.documentElement.outerHTML : '')) || '';
  }
  Object.defineProperty(JSNode.prototype, 'className', {
    get: function () { return this.getAttribute('class') || ''; }
  });
  Object.defineProperty(JSNode.prototype, 'id', {
    get: function () { return this.getAttribute('id') || ''; }
  });
  JSNode.prototype.getAttribute = function (name) {
    var value = this.data.attributes[name];
    return value === undefined ? null : value;
  };
  JSNode.prototype.hasAttribute = function (name) {
    return this.data.attributes[name] !== undefined;
  };
  JSNode.prototype.querySelector = function (query) {
    var found = queryAll(this._node, query);
    return found.length ? new JSNode(found[0]) : null;
  };
  JSNode.prototype.querySelectorAll = function (query) {
    return queryAll(this._node, query).map(function (element) { return new JSNode(element); });
  };

  function JSDocument(document) {
    JSNode.call(this, document);
  }
  JSDocument.prototype = Object.create(JSNode.prototype);
  JSDocument.prototype.constructor = JSDocument;
  Object.defineProperty(JSDocument.prototype, 'body', {
    get: function () { return this._node.body ? new JSNode(this._node.body) : null; }
  });
  Object.defineProperty(JSDocument.prototype, 'head', {
    get: function () { return this._node.head ? new JSNode(this._node.head) : null; }
  });
  Object.defineProperty(JSDocument.prototype, 'title', {
    get: function () { return this._node.title || ''; }
  });

  function JSDOM(html) {
    var self = this;
    this.window = { document: new JSDocument(parseDocument(html)) };
    this._initPromise = Promise.resolve(self);
  }
  JSDOM.prototype.waitForInit = function () { return this._initPromise; };

  function extractJsonPath(object, path) {
    var parts = path.split('.');
    var current = object;
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      if (current === null || current === undefined) return null;
      if (/\[\*\]$/.test(part)) {
        var listKey = part.substring(0, part.length - 3);
        if (listKey && typeof current === 'object' && !Array.isArray(current)) current = current[listKey];
        if (!Array.isArray(current)) return null;
        var remaining = parts.slice(i + 1).join('.');
        if (!remaining) return current;
        return current.map(function (item) { return extractJsonPath(item, remaining); });
      }
      var indexed = /^(.+)\[(\d+)\]$/.exec(part);
      if (indexed) {
        if (typeof current === 'object' && !Array.isArray(current)) current = current[indexed[1]];
        var index = parseInt(indexed[2], 10);
        if (!Array.isArray(current) || index >= current.length) return null;
        current = current[index];
        continue;
      }
      if (typeof current !== 'object' || Array.isArray(current)) return null;
      current = current[part];
    }
    return current === undefined ? null : current;
  }

  function formEncode(object) {
    return Object.keys(object).filter(function (key) {
      return object[key] !== undefined && object[key] !== null;
    }).map(function (key) {
      return encodeURIComponent(key) + '=' + encodeURIComponent(object[key]);
    }).join('&');
  }

  /** One request the way SkyStream does it: never throws, any status is a normal answer. */
  function skyHttp(method, url, headers, body) {
    if (method === 'POST' && typeof headers === 'object' && headers !== null && !body &&
      (headers.body || headers.headers)) {
      body = headers.body;
      headers = headers.headers;
    }
    var requestHeaders = plainHeaders(headers);
    var upperMethod = String(method || 'GET').toUpperCase();
    if ((upperMethod === 'POST' || upperMethod === 'PUT') && !hasHeader(requestHeaders, 'content-type')) {
      requestHeaders['Content-Type'] = 'application/x-www-form-urlencoded';
    }
    var text = null;
    if (body !== undefined && body !== null) {
      if (typeof body === 'string') {
        text = body;
      } else if (typeof body === 'object') {
        var contentType = String(getHeader(requestHeaders, 'content-type') || '').toLowerCase();
        text = contentType.indexOf('json') >= 0 ? JSON.stringify(body) : formEncode(body);
      } else {
        text = String(body);
      }
    }
    return nativeHttp({
      url: String(url),
      method: upperMethod,
      headers: requestHeaders,
      body: upperMethod === 'GET' || upperMethod === 'HEAD' ? null : text,
      binary: false,
      redirect: 'follow'
    }).then(function (answer) {
      if (!answer.ok) {
        return { code: 0, statusCode: 0, status: 0, body: '', headers: {}, error: answer.error || 'Request failed' };
      }
      var responseHeaders = {};
      Object.keys(answer.headers || {}).forEach(function (name) { responseHeaders[name] = answer.headers[name]; });
      if (answer.setCookie && answer.setCookie.length) responseHeaders['set-cookie'] = answer.setCookie;
      return {
        code: answer.status,
        statusCode: answer.status,
        status: answer.status,
        body: decodeBody(answer),
        headers: responseHeaders,
        finalUrl: answer.url
      };
    });
  }

  function loadSkyStream(source) {
    var manifest = env.manifest || {};
    global.manifest = manifest;

    function httpGet(url, headers, callback) {
      return skyHttp('GET', url, headers, null).then(function (response) {
        if (typeof callback === 'function') callback(response);
        return response;
      });
    }
    function httpPost(url, headers, body, callback) {
      return skyHttp('POST', url, headers, body).then(function (response) {
        if (typeof callback === 'function') callback(response);
        return response;
      });
    }
    global.http_get = httpGet;
    global.http_post = httpPost;
    global.http_parallel = function (requests) {
      return Promise.all((requests || []).map(function (request) {
        return skyHttp(request.method || 'GET', request.url, request.headers, request.body);
      }));
    };
    global._fetch = function (url) { return httpGet(url, {}); };
    global.log = function (message) { consoleShim.log(message); };

    global.getAndUnpack = function (code) {
      try { return host.unpack(String(code)); } catch (e) { return String(code); }
    };
    global.loadExtractor = function (url, referer) {
      return new Promise(function (resolve) {
        var requestId = nextId();
        pending[requestId] = function (text) {
          var answer = parseEnvelope(text);
          resolve(answer.ok && Array.isArray(answer.value) ? answer.value : []);
        };
        try {
          host.extract(requestId, String(url), referer ? String(referer) : '');
        } catch (e) {
          delete pending[requestId];
          resolve([]);
        }
      });
    };

    global.parse_html = function (html, selector, attribute) {
      return Promise.resolve().then(function () {
        return queryAll(parseDocument(html), selector || '*').map(function (element) {
          var value = attribute ? extractAttribute(element, attribute) : '';
          return {
            text: element.textContent || '',
            html: element.innerHTML,
            attr: value === null || value === undefined ? '' : value
          };
        });
      });
    };
    global.JSDOM = JSDOM;
    global.parseHtml = function (html) {
      return Promise.resolve().then(function () { return new JSDocument(parseDocument(html)); });
    };
    global.nativeDomBatch = function (nodeId, queries) {
      var node = domNodes[nodeId];
      if (!node) return [];
      return (queries || []).map(function (query) {
        var elements = queryAll(node, query.query || '*');
        var attribute = query.attr || 'textContent';
        if (query.first) return elements.length ? extractAttribute(elements[0], attribute) : null;
        return elements.map(function (element) { return extractAttribute(element, attribute); });
      });
    };
    global.nativeExtract = function (html, extractionMap) {
      return Promise.resolve().then(function () {
        var document = parseDocument(html);
        var result = {};
        Object.keys(extractionMap || {}).forEach(function (key) {
          var spec = extractionMap[key] || {};
          var elements = queryAll(document, spec.query || '*');
          var attribute = spec.attr || 'textContent';
          result[key] = spec.first
            ? (elements.length ? extractAttribute(elements[0], attribute) : null)
            : elements.map(function (element) { return extractAttribute(element, attribute); });
        });
        return result;
      });
    };
    global.nativeRegex = function (text, pattern, group, caseSensitive) {
      try {
        var regex = new RegExp(pattern, caseSensitive === false ? 'gi' : 'g');
        var index = group || 0;
        var matches = [];
        var match;
        var source = String(text);
        while ((match = regex.exec(source)) !== null) {
          if (match[index] !== undefined && match[index] !== null) matches.push(match[index]);
          if (match[0] === '') regex.lastIndex += 1;
        }
        return matches;
      } catch (e) {
        return [];
      }
    };
    global.nativeJsonExtract = function (jsonText, paths) {
      try {
        var parsed = JSON.parse(jsonText);
        var result = {};
        (paths || []).forEach(function (path) { result[String(path)] = extractJsonPath(parsed, String(path)); });
        return result;
      } catch (e) {
        return {};
      }
    };
    global.nativeMd5 = function (input) { return nativeCrypto('md5', String(input)); };
    global.nativeSha256 = function (input) { return nativeCrypto('sha256', String(input)); };

    // SkyStream adds its helpers to the global `crypto`, the browser's own stay available.
    var skyCrypto = global.crypto || {};
    skyCrypto.decryptAES = function (data, key, iv, options) {
      return Promise.resolve().then(function () {
        return nativeCrypto('aes-decrypt', data, key, iv, (options && options.mode) || 'cbc');
      });
    };
    skyCrypto.pbkdf2 = function (password, salt, iterations, keyLength) {
      return Promise.resolve().then(function () {
        return nativeCrypto('pbkdf2', password, salt, iterations || 10000, keyLength || 32);
      });
    };
    if (!global.crypto) global.crypto = skyCrypto;

    global.solveCaptcha = function () {
      return Promise.reject(new Error('Captcha solving is not supported here'));
    };
    global.registerSettings = function () {};
    global.setPreference = function (key, value) {
      var text = value === undefined || value === null ? ''
        : (typeof value === 'object' ? JSON.stringify(value) : String(value));
      host.prefSet(String(env.storageNamespace || pluginKey), String(key), value === undefined || value === null ? '0' : '1', text);
    };
    global.getPreference = function (key) {
      return Promise.resolve().then(function () {
        var answer = parseEnvelope(host.prefGet(String(env.storageNamespace || pluginKey), String(key)));
        return answer.ok ? answer.value : null;
      });
    };
    global.CloudStream = {
      getLanguage: function () { return env.language || 'en'; },
      getRegion: function () { return env.region || 'US'; }
    };

    // SkyStream's own base64 helpers return an empty text instead of throwing.
    var nativeAtob = global.atob.bind(global);
    var nativeBtoa = global.btoa.bind(global);
    global.atob = function (text) {
      if (!text) return '';
      try { return nativeAtob(String(text)); } catch (e) { return ''; }
    };
    global.btoa = function (text) {
      if (!text) return '';
      try { return nativeBtoa(String(text)); } catch (e) { return ''; }
    };

    // The data classes plugins build their results with, same defaults as SkyStream.
    function assign(target, source) {
      Object.keys(source || {}).forEach(function (key) { target[key] = source[key]; });
      return target;
    }
    global.Actor = function Actor(params) { assign(this, params); };
    global.Trailer = function Trailer(params) { assign(this, params); };
    global.NextAiring = function NextAiring(params) { assign(this, params); };
    global.MultimediaItem = function MultimediaItem(params) {
      assign(this, { type: 'movie', status: 'ongoing', playbackPolicy: 'none', isAdult: false, streams: [], syncData: {} });
      assign(this, params);
    };
    global.Episode = function Episode(params) {
      assign(this, { season: 0, episode: 0, dubStatus: 'none', playbackPolicy: 'none', streams: [] });
      assign(this, params);
    };
    global.StreamResult = function StreamResult(params) {
      assign(this, params);
      this.source = (params && params.source) || 'Auto';
    };

    var exported = (0, eval)('(function(){' + source + '\n;return {' +
      ['getHome', 'search', 'load', 'loadStreams', 'getProviders', 'getSettings'].map(function (name) {
        return name + ': (typeof ' + name + ' !== "undefined") ? ' + name +
          ' : (typeof globalThis.' + name + ' !== "undefined" ? globalThis.' + name + ' : undefined)';
      }).join(',') +
      '};})();\n//# sourceURL=skystream-' + String(manifest.packageName || 'plugin').replace(/[^\w.-]/g, '_') + '.js');
    api = exported || {};
  }

  /** Calls a SkyStream function, which answers through a callback, a promise, or both. */
  function invokeSkyStream(fn, args, finish) {
    var target = api[fn];
    if (typeof target !== 'function') {
      finish(false, 'Function ' + fn + ' not found');
      return;
    }
    // Some plugins declare search(query, page, callback), SkyStream itself passes no page
    if (fn === 'search' && target.length >= 3 && args.length === 1) args = [args[0], 1];
    var answered = false;
    function callback(result) {
      if (answered) return;
      answered = true;
      if (result !== null && typeof result === 'object' && !Array.isArray(result) &&
        typeof result.success === 'boolean') {
        if (result.success) {
          finish(true, result.data === undefined ? null : result.data);
        } else {
          finish(false, String(result.errorCode || 'UNKNOWN_ERROR') + ': ' +
            String(result.message || 'An unexpected plugin error occurred'));
        }
      } else {
        finish(true, result === undefined ? null : result);
      }
    }
    try {
      var returned = target.apply(null, args.concat([callback]));
      if (isThenable(returned)) {
        returned.then(function (value) {
          if (value !== undefined) {
            callback(value);
          } else if (!answered) {
            // The function finished without a value. Normally it has answered through the
            // callback by now, if not give a late callback a moment before reporting nothing.
            csSetTimeout(function () { callback(null); }, 15000);
          }
        }, function (error) {
          if (answered) return;
          answered = true;
          finish(false, describe(error));
        });
      } else if (returned !== undefined) {
        callback(returned);
      }
    } catch (e) {
      if (!answered) {
        answered = true;
        finish(false, describe(e));
      }
    }
  }

  // ---------------------------------------------------------------- entry points

  global.__csrt = {
    init: function (id, key, environment) {
      frameId = id;
      pluginKey = key;
      env = environment || {};

      global.global = global;
      if (typeof global.globalThis === 'undefined') global.globalThis = global;
      global.console = consoleShim;
      global.fetch = csFetch;
      global.XMLHttpRequest = CsXMLHttpRequest;
      global.setTimeout = csSetTimeout;
      global.setInterval = csSetInterval;
      global.clearTimeout = stopTimer;
      global.clearInterval = stopTimer;
      global.setImmediate = function (callback) {
        return startTimer(callback, 0, Array.prototype.slice.call(arguments, 1), false);
      };
      global.clearImmediate = stopTimer;
      global.requestAnimationFrame = function (callback) {
        return startTimer(callback, 16, [Date.now()], false);
      };
      global.cancelAnimationFrame = stopTimer;
      global.addEventListener('unhandledrejection', function (event) {
        // A plugin that forgets to handle a rejection must not spam the app log with page errors
        try { event.preventDefault(); } catch (e) { /* not cancelable */ }
        consoleShim.warn('Unhandled promise rejection:', event.reason);
      });
    },

    load: function (source) {
      if (env.flavor === 'nuvio') {
        loadNuvio(source);
      } else if (env.flavor === 'skystream') {
        loadSkyStream(source);
      } else {
        throw new Error('Unknown plugin type ' + env.flavor);
      }
    },

    invoke: function (callId, fn, argsJson) {
      var finished = false;
      function finish(ok, value) {
        if (finished) return;
        finished = true;
        var payload;
        if (ok) {
          try {
            payload = JSON.stringify(value);
            if (payload === undefined) payload = 'null';
          } catch (e) {
            ok = false;
            payload = 'The result could not be serialized: ' + describe(e);
          }
        } else {
          payload = String(value);
        }
        host.done(callId, ok ? '1' : '0', payload);
      }
      var args;
      try {
        args = JSON.parse(argsJson || '[]');
      } catch (e) {
        finish(false, 'Invalid arguments');
        return;
      }
      if (env.flavor === 'nuvio') invokeNuvio(fn, args, finish); else invokeSkyStream(fn, args, finish);
    },

    onNative: function (requestId) {
      var waiter = pending[requestId];
      delete pending[requestId];
      var text = host.take(requestId);
      if (waiter) waiter(text);
    },

    dispose: function () {
      Object.keys(timers).forEach(function (timerId) { stopTimer(timerId); });
      pending = {};
      api = {};
    }
  };
})(typeof globalThis !== 'undefined' ? globalThis : window);
