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
        var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        var url = protocol + '//' + location.host + path;
        var self = this;

        try {
            var ws = new WebSocket(url);
            var conn = { ws: ws, name: name, path: path, delay: self.reconnectDelay, connected: false };
            self.connections[name] = conn;

            ws.onopen = function() {
                conn.connected = true;
                conn.delay = self.reconnectDelay;
                console.log('WS connected:', name);
                if (self.onStatusChange) self.onStatusChange(self.isAnyConnected());
            };

            ws.onmessage = function(event) {
                try {
                    var data = JSON.parse(event.data);
                    if (self.onMessage) self.onMessage(name, data);
                } catch (e) {
                    console.warn('WS parse error:', name, e.message);
                }
            };

            ws.onclose = function() {
                conn.connected = false;
                if (self.onStatusChange) self.onStatusChange(self.isAnyConnected());
                setTimeout(function() {
                    conn.delay = Math.min(conn.delay * 1.5, self.maxReconnectDelay);
                    self.connect(path, name);
                }, conn.delay);
            };

            ws.onerror = function() {
                ws.close();
            };
        } catch (e) {
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

    close: function() {
        var self = this;
        Object.keys(self.connections).forEach(function(name) {
            var conn = self.connections[name];
            if (conn.ws) conn.ws.close();
        });
    }
};
