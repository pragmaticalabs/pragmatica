window.WsManager = {
    connections: {},
    reconnectDelay: 1000,
    maxReconnectDelay: 30000,
    onMessage: null,
    onStatusChange: null,

    init: function(onMessage, onStatusChange) {
        this.onMessage = onMessage;
        this.onStatusChange = onStatusChange;
        this.connect('/ws/status', 'status');
        this.connect('/ws/dashboard', 'dashboard');
        this.connect('/ws/events', 'events');
    },

    connect: function(path, name) {
        var self = this;

        // Issue 12: Guard against duplicate reconnects
        var existing = self.connections[name];
        if (existing && existing.connecting) return;

        var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        var url = protocol + '//' + location.host + path;

        try {
            var conn = { ws: null, name: name, path: path, delay: (existing && existing.delay) || self.reconnectDelay, connected: false, connecting: true, authenticated: false };
            self.connections[name] = conn;

            var ws = new WebSocket(url);
            conn.ws = ws;

            ws.onopen = function() {
                conn.connecting = false;
                conn.connected = true;
                conn.delay = self.reconnectDelay;
                console.log('WS connected:', name);
                if (self.onStatusChange) self.onStatusChange(self.isAnyConnected());
            };

            ws.onmessage = function(event) {
                try {
                    var data = JSON.parse(event.data);
                    // Issue 18: Handle auth messages
                    if (data.type === 'AUTH_REQUIRED') {
                        var apiKey = self.getApiKey();
                        if (apiKey) {
                            ws.send(JSON.stringify({ type: 'AUTH', apiKey: apiKey }));
                        } else {
                            self.showAuthRequired();
                        }
                        return;
                    }
                    if (data.type === 'AUTH_SUCCESS') {
                        conn.authenticated = true;
                        return;
                    }
                    if (data.type === 'AUTH_FAILED') {
                        conn.authenticated = false;
                        Notifications.show('WebSocket authentication failed for channel: ' + name, 'error');
                        conn.connecting = false;
                        // Do not reconnect on auth failure
                        return;
                    }
                    if (self.onMessage) self.onMessage(name, data);
                } catch (e) {
                    console.warn('WS parse error:', name, e.message);
                }
            };

            ws.onclose = function() {
                conn.connecting = false;
                conn.connected = false;
                if (self.onStatusChange) self.onStatusChange(self.isAnyConnected());
                setTimeout(function() {
                    conn.delay = Math.min(conn.delay * 1.5, self.maxReconnectDelay);
                    self.connect(path, name);
                }, conn.delay);
            };

            ws.onerror = function() {
                conn.connecting = false;
                ws.close();
            };
        } catch (e) {
            if (self.connections[name]) self.connections[name].connecting = false;
            console.warn('WS connect failed:', name, e.message);
            setTimeout(function() { self.connect(path, name); }, 5000);
        }
    },

    isAnyConnected: function() {
        return Object.values(this.connections).some(function(c) { return c.connected; });
    },

    send: function(name, data) {
        var conn = this.connections[name];
        if (conn && conn.connected && conn.ws.readyState === WebSocket.OPEN) {
            conn.ws.send(typeof data === 'string' ? data : JSON.stringify(data));
        }
    },

    // Issue 18: Read API key from localStorage, cookie, or URL param
    getApiKey: function() {
        // 1. localStorage
        var key = localStorage.getItem('aether-api-key');
        if (key) return key;
        // 2. URL param
        var params = new URLSearchParams(window.location.search);
        key = params.get('apiKey');
        if (key) {
            localStorage.setItem('aether-api-key', key);
            return key;
        }
        // 3. Cookie
        var match = document.cookie.match(/(?:^|;\s*)aether-api-key=([^;]*)/);
        if (match) return decodeURIComponent(match[1]);
        return null;
    },

    showAuthRequired: function() {
        Notifications.show('API key required. Set via URL param ?apiKey=... or localStorage.', 'warning');
    },

    // Issue 20: Per-channel status
    channelStatus: function() {
        var result = {};
        var self = this;
        Object.keys(self.connections).forEach(function(name) {
            result[name] = self.connections[name].connected;
        });
        return result;
    },

    close: function() {
        var self = this;
        Object.keys(self.connections).forEach(function(name) {
            var conn = self.connections[name];
            if (conn.ws) conn.ws.close();
        });
    }
};
