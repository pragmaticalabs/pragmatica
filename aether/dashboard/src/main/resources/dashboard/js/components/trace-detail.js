window.TraceDetail = {
    visible: false,
    trace: null,

    show(traceData) {
        this.trace = traceData;
        this.visible = true;
    },

    hide() {
        this.visible = false;
        this.trace = null;
    },

    // Calculate waterfall bar positions
    calculateBars(spans) {
        if (!spans || spans.length === 0) return [];
        var minStart = spans[0].startTime || 0;
        var maxEnd = 0;
        spans.forEach(function(s) {
            var end = (s.startTime || 0) + (s.durationMs || 0);
            if (end > maxEnd) maxEnd = end;
        });
        var totalDuration = maxEnd - minStart || 1;

        return spans.map(function(span) {
            var start = ((span.startTime || 0) - minStart) / totalDuration * 100;
            var width = (span.durationMs || 0) / totalDuration * 100;
            return {
                name: span.name || span.method || 'unknown',
                depth: span.depth || 0,
                startPct: Math.max(0, start),
                widthPct: Math.max(1, width),
                durationMs: span.durationMs || 0,
                status: span.status || 'OK',
                statusClass: (span.status || 'OK') === 'OK' ? 'success' : 'failure'
            };
        });
    },

    renderModal() {
        if (!this.visible || !this.trace) return '';
        return '<div class="modal-overlay" onclick="TraceDetail.hide()">' +
               '<div class="modal-content trace-modal" onclick="event.stopPropagation()">' +
               '<div class="modal-header"><h3>Trace: ' + (this.trace.traceId || 'unknown') + '</h3>' +
               '<button class="btn btn-small btn-secondary" onclick="TraceDetail.hide()">&#x2715;</button></div>' +
               '<div class="trace-waterfall" id="trace-waterfall"></div></div></div>';
    }
};
