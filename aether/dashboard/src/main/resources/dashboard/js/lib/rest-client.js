// Issue 17: Notification system for error feedback
window.Notifications = {
    container: null,

    ensureContainer: function() {
        if (this.container) return this.container;
        this.container = document.getElementById('notification-container');
        if (!this.container) {
            this.container = document.createElement('div');
            this.container.id = 'notification-container';
            this.container.className = 'notification-container';
            document.body.appendChild(this.container);
        }
        return this.container;
    },

    show: function(message, type) {
        var container = this.ensureContainer();
        var el = document.createElement('div');
        el.className = 'notification notification-' + (type || 'error');
        el.textContent = message;
        container.appendChild(el);
        setTimeout(function() {
            el.classList.add('notification-fade-out');
            setTimeout(function() { el.remove(); }, 300);
        }, 5000);
    }
};

window.RestClient = {
    // #294: an endpoint the server has no route for (Forge stand-ins, or a dashboard path not yet
    // migrated to /api/v1 — #300) 404s on every poll tick. Toasting that every 2-3s is just noise;
    // log it once per method+path instead. Every OTHER failure status still toasts every time — this
    // is a narrow carve-out for one status code, not general failure suppression.
    _warned404: {},

    // #294 (three-state health gate, corrected after #846 review): while the cluster's OWN health
    // probe cannot reach the server at all (Alpine.store('cluster').healthUnknown), every OTHER
    // poller failing with the SAME network error is not new information — toasting each one
    // individually is the exact toast storm the health gate exists to prevent. Log once per
    // method+path instead, the same carve-out shape as the 404 case above. A reachable server that
    // later returns an application error still toasts normally: that path never sets healthUnknown.
    _warnedUnreachable: {},

    _reportFailure: function(method, path, status) {
        if (status === 404) {
            var key = method + ' ' + path;
            if (!this._warned404[key]) {
                this._warned404[key] = true;
                console.warn('[RestClient] ' + key + ' -> 404 (endpoint not implemented by this server; further 404s on this endpoint are suppressed)');
            }
            return;
        }
        Notifications.show(method + ' ' + path + ' failed: ' + status, 'error');
    },

    _reportNetworkFailure: function(method, path, message) {
        var cluster = window.Alpine && Alpine.store('cluster');
        if (cluster && cluster.healthUnknown) {
            var key = method + ' ' + path;
            if (!this._warnedUnreachable[key]) {
                this._warnedUnreachable[key] = true;
                console.warn('[RestClient] ' + key + ' -> network error while cluster health is unknown (' + message + '); further network-error toasts on this endpoint are suppressed until health is known again');
            }
            return;
        }
        Notifications.show(method + ' ' + path + ': ' + message, 'error');
    },

    // #294/#300 (three-state health gate): a dedicated probe used ONLY by checkHealth(). It never
    // toasts — health probing runs on every poll tick by design — and it reports HOW a probe failed,
    // which the shared get()/post()/put()/del() cannot: any HTTP response at all (a 404 included)
    // means the server is reachable, just without this route; only a fetch-level exception (refused,
    // timeout, DNS failure) means the server could not be reached. Those are different claims and the
    // three-state gate needs both.
    probeHealth: function(path) {
        var self = this;
        return fetch(path, { headers: self.getHeaders() }).then(function(response) {
            if (!response.ok) return { reachable: true, json: null };
            return response.json().then(function(json) {
                return { reachable: true, json: json };
            }).catch(function() {
                return { reachable: true, json: null };
            });
        }).catch(function() {
            return { reachable: false, json: null };
        });
    },

    // #293/G7: API key is read from sessionStorage (set by the auth overlay) or a cookie — never the
    // URL, since keys in URLs leak into access logs, proxies and browser history. Always sent as the
    // X-API-Key header.
    getHeaders: function(extra) {
        var headers = extra || {};
        var apiKey = sessionStorage.getItem('aether-api-key');
        if (!apiKey) {
            var match = document.cookie.match(/(?:^|;\s*)aether-api-key=([^;]*)/);
            if (match) apiKey = decodeURIComponent(match[1]);
        }
        if (apiKey) headers['x-api-key'] = apiKey;
        return headers;
    },

    get: function(path) {
        var self = this;
        return fetch(path, { headers: self.getHeaders() }).then(function(response) {
            if (response.status === 401) { self.handleUnauthorized(); return null; }
            if (!response.ok) {
                self._reportFailure('GET', path, response.status);
                return null;
            }
            return response.json();
        }).catch(function(e) {
            self._reportNetworkFailure('GET', path, e.message);
            return null;
        });
    },

    post: function(path, body) {
        var self = this;
        var headers = {};
        var opts = { method: 'POST' };
        if (body != null) {
            if (typeof body === 'string') {
                headers['Content-Type'] = 'text/plain';
                opts.body = body;
            } else {
                headers['Content-Type'] = 'application/json';
                opts.body = JSON.stringify(body);
            }
        }
        opts.headers = self.getHeaders(headers);
        return fetch(path, opts).then(function(response) {
            if (response.status === 401) { self.handleUnauthorized(); return null; }
            if (!response.ok) {
                self._reportFailure('POST', path, response.status);
                return null;
            }
            return response.text().then(function(text) {
                try { return JSON.parse(text); } catch(e) { return text; }
            });
        }).catch(function(e) {
            self._reportNetworkFailure('POST', path, e.message);
            return null;
        });
    },

    put: function(path, body) {
        var self = this;
        var opts = {
            method: 'PUT',
            headers: self.getHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(body)
        };
        return fetch(path, opts).then(function(response) {
            if (!response.ok) {
                self._reportFailure('PUT', path, response.status);
            }
            return response.ok;
        }).catch(function(e) {
            self._reportNetworkFailure('PUT', path, e.message);
            return false;
        });
    },

    handleUnauthorized: function() {
        if (window.AetherAuth) window.AetherAuth.onUnauthorized();
    },

    del: function(path) {
        var self = this;
        return fetch(path, { method: 'DELETE', headers: self.getHeaders() }).then(function(response) {
            if (!response.ok) {
                self._reportFailure('DELETE', path, response.status);
            }
            return response.ok;
        }).catch(function(e) {
            self._reportNetworkFailure('DELETE', path, e.message);
            return false;
        });
    }
};
