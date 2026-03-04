/// SVG topology graph renderer.
/// Column-based layout: ENDPOINT/TOPIC_SUB → SLICE → RESOURCE/TOPIC_PUB
var TopologyGraph = (function() {
    var COLUMN_WIDTH = 260;
    var ROW_HEIGHT = 60;
    var NODE_WIDTH = 220;
    var NODE_HEIGHT = 38;
    var PADDING_X = 40;
    var PADDING_Y = 30;

    var TYPE_COLORS = {
        ENDPOINT:  { fill: '#1a3a5c', stroke: '#58a6ff', text: '#58a6ff' },
        SLICE:     { fill: '#1a3c1a', stroke: '#3fb950', text: '#3fb950' },
        TOPIC:     { fill: '#3c2a1a', stroke: '#d29922', text: '#d29922' },
        TOPIC_PUB: { fill: '#3c2a1a', stroke: '#d29922', text: '#d29922' },
        TOPIC_SUB: { fill: '#3c2a1a', stroke: '#d29922', text: '#d29922' },
        RESOURCE:  { fill: '#2a1a3c', stroke: '#bc8cff', text: '#bc8cff' }
    };

    var TYPE_COLUMN = {
        ENDPOINT:  0,
        TOPIC_SUB: 0,
        SLICE:     1,
        RESOURCE:  2,
        TOPIC_PUB: 2,
        TOPIC:     2
    };

    function assignColumns(nodes) {
        var column = {};
        nodes.forEach(function(n) {
            column[n.id] = TYPE_COLUMN[n.type] !== undefined ? TYPE_COLUMN[n.type] : 1;
        });
        return column;
    }

    function layout(nodes, edges) {
        if (!nodes || nodes.length === 0) {
            return { positions: {}, width: 0, height: 0 };
        }

        var columns = assignColumns(nodes);

        // Group nodes by column
        var groups = {};
        nodes.forEach(function(n) {
            var col = columns[n.id] || 0;
            if (!groups[col]) groups[col] = [];
            groups[col].push(n);
        });

        // Sort within columns by type priority
        var typePriority = { ENDPOINT: 0, TOPIC_SUB: 1, SLICE: 2, RESOURCE: 3, TOPIC_PUB: 4, TOPIC: 5 };
        Object.keys(groups).forEach(function(col) {
            groups[col].sort(function(a, b) {
                return (typePriority[a.type] || 0) - (typePriority[b.type] || 0);
            });
        });

        // Compute positions
        var positions = {};
        var maxCol = 0;
        var maxRow = 0;
        Object.keys(groups).forEach(function(col) {
            var colNum = parseInt(col);
            if (colNum > maxCol) maxCol = colNum;
            groups[col].forEach(function(n, row) {
                if (row > maxRow) maxRow = row;
                positions[n.id] = {
                    x: PADDING_X + colNum * COLUMN_WIDTH,
                    y: PADDING_Y + row * ROW_HEIGHT,
                    node: n
                };
            });
        });

        var width = PADDING_X * 2 + (maxCol + 1) * COLUMN_WIDTH;
        // Extra bottom padding for right-to-left arc edges (pub→sub dotted connectors)
        var height = PADDING_Y * 2 + (maxRow + 1) * ROW_HEIGHT + 60;

        return { positions: positions, columns: columns, width: Math.max(width, 400), height: Math.max(height, 120) };
    }

    function truncateLabel(label, maxLen) {
        if (!label) return '';
        if (label.length <= maxLen) return label;
        return label.substring(0, maxLen - 1) + '\u2026';
    }

    function escapeHtml(str) {
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function render(container, nodes, edges) {
        if (!container) return;
        if (!nodes || nodes.length === 0) {
            container.innerHTML = '<div class="no-data">No topology data. Deploy slices to see the data flow graph.</div>';
            return;
        }

        var result = layout(nodes, edges);
        var positions = result.positions;
        var columns = result.columns;

        var svg = '<svg width="' + result.width + '" height="' + result.height + '" class="topology-svg">';

        // Draw edges first (behind nodes)
        (edges || []).forEach(function(e) {
            var from = positions[e.from];
            var to = positions[e.to];
            if (!from || !to) return;

            var fromCol = columns[e.from] || 0;
            var toCol = columns[e.to] || 0;
            var rightToLeft = fromCol > toCol;

            var x1, y1, x2, y2;
            if (rightToLeft) {
                // Right-to-left: anchor on left side of source, right side of target
                x1 = from.x;
                y1 = from.y + NODE_HEIGHT / 2;
                x2 = to.x + NODE_WIDTH;
                y2 = to.y + NODE_HEIGHT / 2;
            } else {
                // Normal left-to-right
                x1 = from.x + NODE_WIDTH;
                y1 = from.y + NODE_HEIGHT / 2;
                x2 = to.x;
                y2 = to.y + NODE_HEIGHT / 2;
            }

            // Bezier control points
            var dash = e.style === 'DOTTED' ? ' stroke-dasharray="6,4"' : '';
            var color = e.style === 'DOTTED' ? '#d29922' : '#484f58';

            if (rightToLeft) {
                // Arc below the graph for right-to-left edges
                var midY = Math.max(y1, y2) + 50;
                svg += '<path d="M' + x1 + ',' + y1 + ' C' + (x1 - 60) + ',' + midY + ' ' + (x2 + 60) + ',' + midY + ' ' + x2 + ',' + y2 + '"';
            } else {
                var dx = Math.abs(x2 - x1) * 0.4;
                svg += '<path d="M' + x1 + ',' + y1 + ' C' + (x1 + dx) + ',' + y1 + ' ' + (x2 - dx) + ',' + y2 + ' ' + x2 + ',' + y2 + '"';
            }
            svg += ' fill="none" stroke="' + color + '" stroke-width="1.5"' + dash + ' opacity="0.7"/>';

            // Arrow head
            if (rightToLeft) {
                var angle = Math.atan2(y2 - (Math.max(y1, y2) + 50), x2 - (x2 + 60));
                var ax = x2 - 8 * Math.cos(angle - 0.3);
                var ay = y2 - 8 * Math.sin(angle - 0.3);
                var bx = x2 - 8 * Math.cos(angle + 0.3);
                var by = y2 - 8 * Math.sin(angle + 0.3);
                svg += '<polygon points="' + x2 + ',' + y2 + ' ' + ax + ',' + ay + ' ' + bx + ',' + by + '" fill="' + color + '" opacity="0.7"/>';
            } else {
                var dx2 = Math.abs(x2 - x1) * 0.4;
                var angle2 = Math.atan2(y2 - y1, x2 - (x2 - dx2));
                var ax2 = x2 - 8 * Math.cos(angle2 - 0.3);
                var ay2 = y2 - 8 * Math.sin(angle2 - 0.3);
                var bx2 = x2 - 8 * Math.cos(angle2 + 0.3);
                var by2 = y2 - 8 * Math.sin(angle2 + 0.3);
                svg += '<polygon points="' + x2 + ',' + y2 + ' ' + ax2 + ',' + ay2 + ' ' + bx2 + ',' + by2 + '" fill="' + color + '" opacity="0.7"/>';
            }
        });

        // Draw nodes
        Object.keys(positions).forEach(function(id) {
            var pos = positions[id];
            var n = pos.node;
            var colors = TYPE_COLORS[n.type] || TYPE_COLORS.SLICE;
            var label = truncateLabel(n.label, 28);

            svg += '<rect x="' + pos.x + '" y="' + pos.y + '" width="' + NODE_WIDTH + '" height="' + NODE_HEIGHT + '"';
            svg += ' rx="6" ry="6" fill="' + colors.fill + '" stroke="' + colors.stroke + '" stroke-width="1.5"/>';

            // Type badge
            var badgeLabels = { TOPIC_PUB: 'PUB', TOPIC_SUB: 'SUB' };
            var badgeText = badgeLabels[n.type] || n.type.substring(0, 3);
            svg += '<rect x="' + (pos.x + 6) + '" y="' + (pos.y + 6) + '" width="30" height="14" rx="3" fill="' + colors.stroke + '" opacity="0.2"/>';
            svg += '<text x="' + (pos.x + 21) + '" y="' + (pos.y + 16) + '" text-anchor="middle" fill="' + colors.text + '" font-size="9" font-family="monospace">' + badgeText + '</text>';

            // Label
            svg += '<text x="' + (pos.x + 42) + '" y="' + (pos.y + NODE_HEIGHT / 2 + 4) + '" fill="' + colors.text + '" font-size="12" font-family="monospace">' + escapeHtml(label) + '</text>';
        });

        svg += '</svg>';
        container.innerHTML = svg;
    }

    return { render: render };
})();
