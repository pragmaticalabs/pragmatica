window.TimeSeries = {
    instances: {},

    colors: [
        'rgba(88,166,255,1)',    // blue
        'rgba(63,185,80,1)',     // green
        'rgba(210,153,34,1)',    // orange
        'rgba(188,140,255,1)',   // purple
        'rgba(57,210,192,1)',    // cyan
        'rgba(248,81,73,1)',     // red
    ],

    create(el, opts) {
        if (!el || !window.uPlot) return null;
        var width = el.offsetWidth || 600;
        var height = opts.height || 200;
        var series = [{ label: 'Time' }];
        var self = this;

        (opts.series || ['Value']).forEach(function(name, i) {
            series.push({
                label: name,
                stroke: self.colors[i % self.colors.length],
                width: 2,
                fill: opts.fill ? self.colors[i % self.colors.length].replace('1)', '0.1)') : undefined
            });
        });

        var uOpts = {
            width: width,
            height: height,
            cursor: { show: true, drag: { x: false, y: false } },
            legend: { show: series.length > 2 },
            scales: { x: { time: true } },
            axes: [
                { stroke: '#8b949e', grid: { stroke: 'rgba(48,54,61,0.5)' }, ticks: { stroke: 'rgba(48,54,61,0.5)' }, font: '11px -apple-system,sans-serif', labelFont: '11px -apple-system,sans-serif' },
                { stroke: '#8b949e', grid: { stroke: 'rgba(48,54,61,0.5)' }, ticks: { stroke: 'rgba(48,54,61,0.5)' }, font: '11px -apple-system,sans-serif', labelFont: '11px -apple-system,sans-serif', label: opts.yLabel || '' }
            ],
            series: series
        };

        var data = [];
        for (var i = 0; i < series.length; i++) data.push([]);

        var plot = new uPlot(uOpts, data, el);
        return { plot: plot, seriesCount: series.length };
    },

    updateData(instance, timestamps, seriesData) {
        if (!instance || !instance.plot) return;
        var data = [timestamps];
        seriesData.forEach(function(s) { data.push(s); });
        instance.plot.setData(data);
    },

    resize(instance, el) {
        if (!instance || !instance.plot || !el) return;
        instance.plot.setSize({ width: el.offsetWidth, height: instance.plot.height });
    },

    destroy(instance) {
        if (instance && instance.plot) instance.plot.destroy();
    }
};
