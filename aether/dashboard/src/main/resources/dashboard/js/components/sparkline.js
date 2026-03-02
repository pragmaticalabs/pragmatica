window.Sparkline = {
    instances: {},

    create(el, color) {
        if (!el || !window.uPlot) return;
        var width = el.offsetWidth || 100;
        var opts = {
            width: width,
            height: 30,
            cursor: { show: false },
            legend: { show: false },
            axes: [
                { show: false },
                { show: false }
            ],
            scales: { x: { time: false } },
            series: [
                {},
                { stroke: color || 'rgba(88,166,255,0.8)', fill: color ? color.replace('0.8', '0.1') : 'rgba(88,166,255,0.1)', width: 1 }
            ]
        };
        var data = [[], []];
        var plot = new uPlot(opts, data, el);
        return { plot: plot, data: data, maxPoints: 60 };
    },

    update(instance, value) {
        if (!instance || !instance.plot) return;
        var d = instance.data;
        var idx = d[0].length;
        d[0].push(idx);
        d[1].push(value);
        if (d[0].length > instance.maxPoints) {
            d[0].shift();
            d[1].shift();
            // Re-index
            for (var i = 0; i < d[0].length; i++) d[0][i] = i;
        }
        instance.plot.setData(d);
    },

    destroy(instance) {
        if (instance && instance.plot) {
            instance.plot.destroy();
        }
    }
};
