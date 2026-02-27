window.Formatters = {
    number: function(n) {
        if (n == null) return '0';
        if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
        if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
        return Math.round(n).toString();
    },

    latency: function(ms) {
        if (ms == null) return '0ms';
        if (ms < 1) return (ms * 1000).toFixed(0) + '\u03BCs';
        if (ms < 1000) return ms.toFixed(1) + 'ms';
        return (ms / 1000).toFixed(2) + 's';
    },

    percent: function(rate) {
        if (rate == null) return '0%';
        if (rate > 1) return rate.toFixed(1) + '%';
        return (rate * 100).toFixed(1) + '%';
    },

    uptime: function(seconds) {
        if (!seconds) return '0:00';
        var h = Math.floor(seconds / 3600);
        var m = Math.floor((seconds % 3600) / 60);
        var s = Math.floor(seconds % 60);
        if (h > 0) return h + 'h ' + m.toString().padStart(2, '0') + 'm ' + s.toString().padStart(2, '0') + 's';
        return m + 'm ' + s.toString().padStart(2, '0') + 's';
    },

    time: function(timestamp) {
        if (!timestamp) return '--:--';
        var d = typeof timestamp === 'string' ? new Date(timestamp) : new Date(timestamp);
        return d.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
    },

    duration: function(ms) {
        if (ms < 1000) return ms + 'ms';
        if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
        return Math.floor(ms / 60000) + 'm ' + Math.floor((ms % 60000) / 1000) + 's';
    }
};
