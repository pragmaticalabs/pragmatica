/// SVG topology graph renderer — swim-lane layout with Manhattan routing.
/// Column-based layout: ENDPOINT/TOPIC_SUB → SLICE → RESOURCE/TOPIC_PUB
/// Node widths are computed per-column to fit the widest label.
var TopologyGraph = (function() {
    var ROW_HEIGHT = 50;
    var NODE_HEIGHT = 38;
    var PADDING_X = 40;
    var PADDING_Y = 30;
    var LANE_GAP = 24;
    var COL_GAP = 40;
    var MIN_NODE_WIDTH = 120;
    var CHAR_WIDTH = 7.2;
    var BADGE_WIDTH = 42;
    var NODE_PAD_RIGHT = 10;

    var TYPE_COLORS = {
        ENDPOINT:  { fill: '#1a3a5c', stroke: '#58a6ff', text: '#58a6ff' },
        SLICE:     { fill: '#1a3c1a', stroke: '#3fb950', text: '#3fb950' },
        TOPIC:     { fill: '#3c2a1a', stroke: '#d29922', text: '#d29922' },
        TOPIC_PUB: { fill: '#3c2a1a', stroke: '#d29922', text: '#d29922' },
        TOPIC_SUB: { fill: '#3c2a1a', stroke: '#d29922', text: '#d29922' },
        RESOURCE:  { fill: '#2a1a3c', stroke: '#bc8cff', text: '#bc8cff' }
    };

    function topicColorForConfig(configName) {
        var hash = 0;
        for (var i = 0; i < configName.length; i++) {
            hash = configName.charCodeAt(i) + ((hash << 5) - hash);
        }
        var hue = ((hash % 360) + 360) % 360;
        return 'hsl(' + hue + ', 70%, 60%)';
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function nodeColumn(type) {
        if (type === 'ENDPOINT' || type === 'TOPIC_SUB') return 0;
        if (type === 'SLICE') return 1;
        return 2;
    }

    function swimLaneLayout(nodes) {
        var defaultColWidth = [MIN_NODE_WIDTH, MIN_NODE_WIDTH, MIN_NODE_WIDTH];
        var defaultColX = [PADDING_X, PADDING_X + MIN_NODE_WIDTH + COL_GAP, PADDING_X + 2 * (MIN_NODE_WIDTH + COL_GAP)];

        if (!nodes || nodes.length === 0) {
            return { positions: {}, laneYRanges: [], width: 0, height: 0, colNodeWidth: defaultColWidth, colX: defaultColX };
        }

        // Group by sliceArtifact preserving first-occurrence order
        var sliceOrder = [];
        var sliceGroups = {};
        var orphans = [];

        nodes.forEach(function(n) {
            var artifact = n.sliceArtifact;
            if (!artifact) {
                orphans.push(n);
                return;
            }
            if (!sliceGroups[artifact]) {
                sliceGroups[artifact] = [];
                sliceOrder.push(artifact);
            }
            sliceGroups[artifact].push(n);
        });

        // Measure max label length per column across all nodes
        var maxLen = [0, 0, 0];
        nodes.forEach(function(n) {
            var col = nodeColumn(n.type);
            var labelLen = (n.label || '').length;
            if (labelLen > maxLen[col]) maxLen[col] = labelLen;
        });

        var colNodeWidth = [
            Math.max(MIN_NODE_WIDTH, Math.ceil(BADGE_WIDTH + maxLen[0] * CHAR_WIDTH + NODE_PAD_RIGHT)),
            Math.max(MIN_NODE_WIDTH, Math.ceil(BADGE_WIDTH + maxLen[1] * CHAR_WIDTH + NODE_PAD_RIGHT)),
            Math.max(MIN_NODE_WIDTH, Math.ceil(BADGE_WIDTH + maxLen[2] * CHAR_WIDTH + NODE_PAD_RIGHT))
        ];

        var colX = [
            PADDING_X,
            PADDING_X + colNodeWidth[0] + COL_GAP,
            PADDING_X + colNodeWidth[0] + COL_GAP + colNodeWidth[1] + COL_GAP
        ];

        var positions = {};
        var laneYRanges = [];
        var currentY = PADDING_Y;

        sliceOrder.forEach(function(artifact) {
            var group = sliceGroups[artifact];
            var inputs = [];
            var outputs = [];
            var sliceNode = null;

            group.forEach(function(n) {
                var col = nodeColumn(n.type);
                if (col === 0) inputs.push(n);
                else if (col === 1) sliceNode = n;
                else outputs.push(n);
            });

            var laneHeight = Math.max(inputs.length, outputs.length, 1) * ROW_HEIGHT;
            var laneMidY = currentY + laneHeight / 2;

            if (sliceNode) {
                positions[sliceNode.id] = {
                    x: colX[1], y: laneMidY - NODE_HEIGHT / 2,
                    node: sliceNode, nodeWidth: colNodeWidth[1]
                };
            }

            var inputBlockHeight = inputs.length * ROW_HEIGHT;
            var inputStartY = laneMidY - inputBlockHeight / 2;
            inputs.forEach(function(n, i) {
                positions[n.id] = {
                    x: colX[0],
                    y: inputStartY + i * ROW_HEIGHT + (ROW_HEIGHT - NODE_HEIGHT) / 2,
                    node: n, nodeWidth: colNodeWidth[0]
                };
            });

            var outputBlockHeight = outputs.length * ROW_HEIGHT;
            var outputStartY = laneMidY - outputBlockHeight / 2;
            outputs.forEach(function(n, i) {
                positions[n.id] = {
                    x: colX[2],
                    y: outputStartY + i * ROW_HEIGHT + (ROW_HEIGHT - NODE_HEIGHT) / 2,
                    node: n, nodeWidth: colNodeWidth[2]
                };
            });

            laneYRanges.push({ artifact: artifact, yStart: currentY, yEnd: currentY + laneHeight });
            currentY += laneHeight + LANE_GAP;
        });

        if (orphans.length > 0) {
            var orphanLaneHeight = orphans.length * ROW_HEIGHT;
            orphans.forEach(function(n, i) {
                positions[n.id] = {
                    x: colX[1],
                    y: currentY + i * ROW_HEIGHT + (ROW_HEIGHT - NODE_HEIGHT) / 2,
                    node: n, nodeWidth: colNodeWidth[1]
                };
            });
            laneYRanges.push({ artifact: '(unassigned)', yStart: currentY, yEnd: currentY + orphanLaneHeight });
            currentY += orphanLaneHeight + LANE_GAP;
        }

        var width = colX[2] + colNodeWidth[2] + PADDING_X;
        var height = currentY - LANE_GAP + PADDING_Y;

        return {
            positions: positions, laneYRanges: laneYRanges,
            width: Math.max(width, 400), height: Math.max(height, 120),
            colNodeWidth: colNodeWidth, colX: colX
        };
    }

    function classifyEdge(edge, nodeMap) {
        if (edge.style === 'DOTTED') return 'topic';
        var src = nodeMap[edge.from];
        var tgt = nodeMap[edge.to];
        if (src && tgt && src.type === 'SLICE' && tgt.type === 'SLICE') return 'dependency';
        return 'direct';
    }

    function assignGutterSlots(edges, nodeMap) {
        var leftSlots = {};
        var rightSlots = {};
        var rightConfigIndex = {};
        var rightSlotCount = 0;
        var leftSlotCount = 0;

        edges.forEach(function(e, i) {
            var type = classifyEdge(e, nodeMap);
            if (type === 'topic') {
                var config = e.topicConfig || '';
                if (rightConfigIndex[config] === undefined) {
                    rightConfigIndex[config] = rightSlotCount++;
                }
                rightSlots[i] = rightConfigIndex[config];
            } else if (type === 'dependency') {
                leftSlots[i] = leftSlotCount++;
            }
        });

        return {
            leftSlots: leftSlots,
            rightSlots: rightSlots,
            leftGutterWidth: Math.max(30, leftSlotCount * 12 + 10),
            rightGutterWidth: Math.max(30, rightSlotCount * 12 + 10)
        };
    }

    function renderEdge(edge, edgeIndex, positions, gutterSlots, nodeMap, leftOffset, layout) {
        var from = positions[edge.from];
        var to = positions[edge.to];
        if (!from || !to) return '';

        var type = classifyEdge(edge, nodeMap);
        var topicConfig = edge.topicConfig || '';
        var g = '<g class="topo-edge" data-topic-config="' + escapeHtml(topicConfig) + '" data-from="' + escapeHtml(edge.from) + '" data-to="' + escapeHtml(edge.to) + '">';

        var fromCx = from.x + from.nodeWidth;
        var fromCy = from.y + NODE_HEIGHT / 2;
        var toCx = to.x;
        var toCy = to.y + NODE_HEIGHT / 2;

        if (type === 'direct') {
            var dx = Math.abs(toCx - fromCx) * 0.3;
            g += '<path d="M' + fromCx + ',' + fromCy + ' C' + (fromCx + dx) + ',' + fromCy + ' ' + (toCx - dx) + ',' + toCy + ' ' + toCx + ',' + toCy + '"';
            g += ' fill="none" stroke="#484f58" stroke-width="1.5" opacity="0.5"/>';
            g += renderArrow(toCx, toCy, -1, 0, '#484f58', '0.5');
        } else if (type === 'topic') {
            var slot = gutterSlots.rightSlots[edgeIndex] || 0;
            var rightGutterX = leftOffset + layout.colX[2] + layout.colNodeWidth[2] + 10 + slot * 12;
            var leftGutterX = 10 + slot * 12;
            var color = topicColorForConfig(topicConfig);
            var midY = (fromCy + toCy) / 2;

            g += '<path d="M' + fromCx + ',' + fromCy + ' H' + rightGutterX + ' V' + midY + ' H' + leftGutterX + ' V' + toCy + ' H' + toCx + '"';
            g += ' fill="none" stroke="' + color + '" stroke-width="1.5" stroke-dasharray="6,4" opacity="0.7"/>';
            g += renderArrow(toCx, toCy, -1, 0, color, '0.7');
        } else if (type === 'dependency') {
            var slot2 = gutterSlots.leftSlots[edgeIndex] || 0;
            var rightChannelX = leftOffset + layout.colX[1] + layout.colNodeWidth[1] + 10 + slot2 * 12;
            var leftChannelX = leftOffset + layout.colX[0] + layout.colNodeWidth[0] + 10 + slot2 * 12;
            var midY2 = (fromCy + toCy) / 2;

            g += '<path d="M' + fromCx + ',' + fromCy + ' H' + rightChannelX + ' V' + midY2 + ' H' + leftChannelX + ' V' + toCy + ' H' + toCx + '"';
            g += ' fill="none" stroke="#8b949e" stroke-width="1.5" opacity="0.5"/>';
            g += renderArrow(toCx, toCy, -1, 0, '#8b949e', '0.5');
        }

        g += '</g>';
        return g;
    }

    function renderArrow(x, y, dirX, dirY, color, opacity) {
        var size = 7;
        var ax, ay, bx, by;
        if (dirX !== 0) {
            ax = x - dirX * size;
            ay = y - size * 0.5;
            bx = x - dirX * size;
            by = y + size * 0.5;
        } else {
            ax = x - size * 0.5;
            ay = y - dirY * size;
            bx = x + size * 0.5;
            by = y - dirY * size;
        }
        return '<polygon points="' + x + ',' + y + ' ' + ax + ',' + ay + ' ' + bx + ',' + by + '" fill="' + color + '" opacity="' + opacity + '"/>';
    }

    function getTopicConfigColor(label) {
        if (label) {
            return topicColorForConfig(label);
        }
        return null;
    }

    function renderNode(id, pos, node) {
        var colors = TYPE_COLORS[node.type] || TYPE_COLORS.SLICE;
        var strokeColor = colors.stroke;
        var nodeWidth = pos.nodeWidth;

        if (node.type === 'TOPIC_PUB' || node.type === 'TOPIC_SUB') {
            var configColor = getTopicConfigColor(node.label);
            if (configColor) {
                strokeColor = configColor;
            }
        }

        var label = node.label || '';
        var badgeLabels = { ENDPOINT: 'END', TOPIC_PUB: 'PUB', TOPIC_SUB: 'SUB', SLICE: 'SLC', RESOURCE: 'RES', TOPIC: 'TOP' };
        var badgeText = badgeLabels[node.type] || node.type.substring(0, 3);

        var g = '<g class="topo-node" data-node-id="' + escapeHtml(id) + '" data-label="' + escapeHtml(label) + '">';

        g += '<rect x="' + pos.x + '" y="' + pos.y + '" width="' + nodeWidth + '" height="' + NODE_HEIGHT + '"';
        g += ' rx="6" ry="6" fill="' + colors.fill + '" stroke="' + strokeColor + '" stroke-width="1.5"/>';

        g += '<rect x="' + (pos.x + 6) + '" y="' + (pos.y + 6) + '" width="30" height="14" rx="3" fill="' + strokeColor + '" opacity="0.2"/>';
        g += '<text x="' + (pos.x + 21) + '" y="' + (pos.y + 16) + '" text-anchor="middle" fill="' + colors.text + '" font-size="9" font-family="monospace">' + badgeText + '</text>';

        g += '<text x="' + (pos.x + 42) + '" y="' + (pos.y + NODE_HEIGHT / 2 + 4) + '" fill="' + colors.text + '" font-size="12" font-family="monospace">' + escapeHtml(label) + '</text>';

        g += '</g>';
        return g;
    }

    function applySearch(container, searchQuery) {
        var nodes = container.querySelectorAll('.topo-node');
        var edges = container.querySelectorAll('.topo-edge');

        if (!searchQuery || searchQuery.trim() === '') {
            nodes.forEach(function(n) { n.style.opacity = '1'; });
            edges.forEach(function(e) {
                var tc = e.getAttribute('data-topic-config');
                e.style.opacity = (tc && tc !== '') ? '0.7' : '0.5';
            });
            return;
        }

        var q = searchQuery.toLowerCase();
        var matchingIds = {};
        nodes.forEach(function(n) {
            var label = (n.getAttribute('data-label') || '').toLowerCase();
            if (label.indexOf(q) >= 0) {
                n.style.opacity = '1';
                matchingIds[n.getAttribute('data-node-id')] = true;
            } else {
                n.style.opacity = '0.15';
            }
        });

        edges.forEach(function(e) {
            var from = e.getAttribute('data-from');
            var to = e.getAttribute('data-to');
            if (matchingIds[from] || matchingIds[to]) {
                e.style.opacity = '0.7';
            } else {
                e.style.opacity = '0.08';
            }
        });
    }

    function attachHoverListeners(container) {
        var allNodes = container.querySelectorAll('.topo-node');
        var allEdges = container.querySelectorAll('.topo-edge');

        function dimAll() {
            allNodes.forEach(function(n) { n.style.opacity = '0.1'; });
            allEdges.forEach(function(e) { e.style.opacity = '0.1'; });
        }

        function restoreDefaults() {
            var searchQuery = '';
            if (typeof Alpine !== 'undefined') {
                try {
                    var store = Alpine.store('topology');
                    if (store && store.searchQuery) searchQuery = store.searchQuery;
                } catch(e) { /* ignore */ }
            }
            if (!searchQuery) {
                var panel = container.closest('.panel');
                if (panel) {
                    var input = panel.querySelector('input[type="text"]');
                    if (input) searchQuery = input.value || '';
                }
            }

            if (searchQuery && searchQuery.trim() !== '') {
                applySearch(container, searchQuery);
            } else {
                allNodes.forEach(function(n) { n.style.opacity = '1'; });
                allEdges.forEach(function(e) {
                    var tc = e.getAttribute('data-topic-config');
                    e.style.opacity = (tc && tc !== '') ? '0.7' : '0.5';
                });
            }
        }

        allEdges.forEach(function(edge) {
            edge.addEventListener('mouseenter', function() {
                var tc = edge.getAttribute('data-topic-config');
                dimAll();

                if (tc && tc !== '') {
                    allEdges.forEach(function(e) {
                        if (e.getAttribute('data-topic-config') === tc) {
                            e.style.opacity = '1';
                            var from = e.getAttribute('data-from');
                            var to = e.getAttribute('data-to');
                            allNodes.forEach(function(n) {
                                var nid = n.getAttribute('data-node-id');
                                if (nid === from || nid === to) n.style.opacity = '1';
                            });
                        }
                    });
                } else {
                    edge.style.opacity = '1';
                    var from = edge.getAttribute('data-from');
                    var to = edge.getAttribute('data-to');
                    allNodes.forEach(function(n) {
                        var nid = n.getAttribute('data-node-id');
                        if (nid === from || nid === to) n.style.opacity = '1';
                    });
                }
            });
            edge.addEventListener('mouseleave', restoreDefaults);
        });

        allNodes.forEach(function(node) {
            node.addEventListener('mouseenter', function() {
                var nodeId = node.getAttribute('data-node-id');
                dimAll();
                node.style.opacity = '1';

                var isTopicNode = nodeId.startsWith('topic-pub:') || nodeId.startsWith('topic-sub:');
                if (isTopicNode) {
                    var topicConfig = node.getAttribute('data-label');

                    // Collect all topic nodes with matching config
                    var topicNodeIds = {};
                    allNodes.forEach(function(n) {
                        var nid = n.getAttribute('data-node-id');
                        if ((nid.startsWith('topic-pub:') || nid.startsWith('topic-sub:')) &&
                            n.getAttribute('data-label') === topicConfig) {
                            topicNodeIds[nid] = true;
                            n.style.opacity = '1';
                        }
                    });

                    // Highlight edges connected to any matching topic node or with matching topicConfig
                    allEdges.forEach(function(e) {
                        var tc = e.getAttribute('data-topic-config');
                        var from = e.getAttribute('data-from');
                        var to = e.getAttribute('data-to');
                        if (tc === topicConfig || topicNodeIds[from] || topicNodeIds[to]) {
                            e.style.opacity = '1';
                            allNodes.forEach(function(n) {
                                var nid = n.getAttribute('data-node-id');
                                if (nid === from || nid === to) n.style.opacity = '1';
                            });
                        }
                    });
                } else {
                    allEdges.forEach(function(e) {
                        var from = e.getAttribute('data-from');
                        var to = e.getAttribute('data-to');
                        if (from === nodeId || to === nodeId) {
                            e.style.opacity = '1';
                            var otherId = (from === nodeId) ? to : from;
                            allNodes.forEach(function(n) {
                                if (n.getAttribute('data-node-id') === otherId) n.style.opacity = '1';
                            });
                        }
                    });
                }
            });
            node.addEventListener('mouseleave', restoreDefaults);
        });
    }

    function render(container, nodes, edges, searchQuery) {
        if (!container) return;
        if (!nodes || nodes.length === 0) {
            container.innerHTML = '<div class="no-data">No topology data. Deploy slices to see the data flow graph.</div>';
            return;
        }

        var nodeMap = {};
        nodes.forEach(function(n) { nodeMap[n.id] = n; });

        var result = swimLaneLayout(nodes);
        var positions = result.positions;

        var gutterSlots = assignGutterSlots(edges || [], nodeMap);

        var totalWidth = Math.max(result.width + gutterSlots.rightGutterWidth + gutterSlots.leftGutterWidth + 40, 500);
        var totalHeight = Math.max(result.height + 40, 120);

        var leftOffset = gutterSlots.leftGutterWidth;
        for (var id in positions) {
            positions[id].x += leftOffset;
        }

        var svg = '<svg width="' + totalWidth + '" height="' + totalHeight + '" class="topology-svg">';

        result.laneYRanges.forEach(function(lane, i) {
            if (i > 0) {
                var sepY = lane.yStart - LANE_GAP / 2;
                svg += '<line x1="0" y1="' + sepY + '" x2="' + totalWidth + '" y2="' + sepY + '" stroke="#30363d" stroke-width="1" stroke-dasharray="4,4" opacity="0.5"/>';
            }
        });

        (edges || []).forEach(function(e, i) {
            svg += renderEdge(e, i, positions, gutterSlots, nodeMap, leftOffset, result);
        });

        Object.keys(positions).forEach(function(id) {
            var pos = positions[id];
            svg += renderNode(id, pos, pos.node);
        });

        svg += '</svg>';
        container.innerHTML = svg;

        attachHoverListeners(container);
        if (searchQuery) {
            applySearch(container, searchQuery);
        }
    }

    return { render: render };
})();
