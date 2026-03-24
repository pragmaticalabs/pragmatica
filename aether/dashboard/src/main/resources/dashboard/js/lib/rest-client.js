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
    // Issue 19: Read API key from same source as WS auth
    getHeaders: function(extra) {
        var headers = extra || {};
        var apiKey = localStorage.getItem('aether-api-key');
        if (!apiKey) {
            var params = new URLSearchParams(window.location.search);
            apiKey = params.get('apiKey');
            if (apiKey) localStorage.setItem('aether-api-key', apiKey);
        }
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
            if (!response.ok) {
                Notifications.show('GET ' + path + ' failed: ' + response.status, 'error');
                return null;
            }
            return response.json();
        }).catch(function(e) {
            Notifications.show('GET ' + path + ': ' + e.message, 'error');
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
            if (!response.ok) {
                Notifications.show('POST ' + path + ' failed: ' + response.status, 'error');
                return null;
            }
            return response.text().then(function(text) {
                try { return JSON.parse(text); } catch(e) { return text; }
            });
        }).catch(function(e) {
            Notifications.show('POST ' + path + ': ' + e.message, 'error');
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
                Notifications.show('PUT ' + path + ' failed: ' + response.status, 'error');
            }
            return response.ok;
        }).catch(function(e) {
            Notifications.show('PUT ' + path + ': ' + e.message, 'error');
            return false;
        });
    },

    del: function(path) {
        var self = this;
        return fetch(path, { method: 'DELETE', headers: self.getHeaders() }).then(function(response) {
            if (!response.ok) {
                Notifications.show('DELETE ' + path + ' failed: ' + response.status, 'error');
            }
            return response.ok;
        }).catch(function(e) {
            Notifications.show('DELETE ' + path + ': ' + e.message, 'error');
            return false;
        });
    }
};
