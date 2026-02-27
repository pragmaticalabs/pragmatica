window.EventFeed = {
    autoScroll: true,

    scrollToBottom(el) {
        if (!el || !this.autoScroll) return;
        el.scrollTop = 0; // Events are prepended, newest at top
    },

    formatEvent(event) {
        return {
            time: Formatters.time(event.timestamp),
            type: event.type || 'EVENT',
            severity: (event.severity || 'INFO').toLowerCase(),
            summary: event.summary || event.message || ''
        };
    }
};
