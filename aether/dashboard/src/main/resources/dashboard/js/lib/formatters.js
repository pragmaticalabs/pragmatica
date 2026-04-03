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
        if (rate == null || isNaN(rate)) return '\u2014';
        return (rate * 100).toFixed(1) + '%';
    },

    // Normalize server success rate: values > 1 are already percentage (0-100), convert to fraction (0-1)
    normalizeRate: function(rate) {
        if (rate == null || isNaN(rate)) return 1;
        return rate > 1 ? rate / 100 : rate;
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
    },

    bytes: function(b) {
        if (b == null || b === 0) return '0 B';
        if (b >= 1073741824) return (b / 1073741824).toFixed(1) + ' GB';
        if (b >= 1048576) return (b / 1048576).toFixed(1) + ' MB';
        if (b >= 1024) return (b / 1024).toFixed(1) + ' KB';
        return b + ' B';
    }
};
