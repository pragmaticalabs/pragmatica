/// SVG topology graph renderer — swim-lane layout with Manhattan routing.
/// Column-based layout: ENDPOINT/TOPIC_SUB → SLICE → RESOURCE/TOPIC_PUB
var TopologyGraph = (function() {
    var COLUMN_WIDTH = 260;
    var ROW_HEIGHT = 50;
    var NODE_WIDTH = 220;
    var NODE_HEIGHT = 38;
    var PADDING_X = 40;
    var PADDING_Y = 30;
    var LANE_GAP = 24;

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

    function truncateLabel(label, maxLen) {
        if (!label) return '';
        if (label.length <= maxLen) return label;
        return label.substring(0, maxLen - 1) + '\u2026';
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function swimLaneLayout(nodes) {
        if (!nodes || nodes.length === 0) {
            return { positions: {}, laneYRanges: [], width: 0, height: 0 };
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

        var positions = {};
        var laneYRanges = [];
        var currentY = PADDING_Y;

        sliceOrder.forEach(function(artifact) {
            var group = sliceGroups[artifact];
            var inputs = [];
            var outputs = [];
            var sliceNode = null;

            group.forEach(function(n) {
                if (n.type === 'ENDPOINT' || n.type === 'TOPIC_SUB') {
                    inputs.push(n);
                } else if (n.type === 'RESOURCE' || n.type === 'TOPIC_PUB') {
                    outputs.push(n);
                } else if (n.type === 'SLICE') {
                    sliceNode = n;
                } else {
                    // TOPIC or unknown — treat as output
                    outputs.push(n);
                }
            });

            var laneHeight = Math.max(inputs.length, outputs.length, 1) * ROW_HEIGHT;
            var laneMidY = currentY + laneHeight / 2;

            // Place slice node at column 1, vertically centered
            if (sliceNode) {
                positions[sliceNode.id] = {
                    x: PADDING_X + COLUMN_WIDTH,
                    y: laneMidY - NODE_HEIGHT / 2,
                    node: sliceNode
                };
            }

            // Center inputs around slice y in column 0
            var inputBlockHeight = inputs.length * ROW_HEIGHT;
            var inputStartY = laneMidY - inputBlockHeight / 2;
            inputs.forEach(function(n, i) {
                positions[n.id] = {
                    x: PADDING_X,
                    y: inputStartY + i * ROW_HEIGHT + (ROW_HEIGHT - NODE_HEIGHT) / 2,
                    node: n
                };
            });

            // Center outputs around slice y in column 2
            var outputBlockHeight = outputs.length * ROW_HEIGHT;
            var outputStartY = laneMidY - outputBlockHeight / 2;
            outputs.forEach(function(n, i) {
                positions[n.id] = {
                    x: PADDING_X + 2 * COLUMN_WIDTH,
                    y: outputStartY + i * ROW_HEIGHT + (ROW_HEIGHT - NODE_HEIGHT) / 2,
                    node: n
                };
            });

            laneYRanges.push({ artifact: artifact, yStart: currentY, yEnd: currentY + laneHeight });
            currentY += laneHeight + LANE_GAP;
        });

        // Orphan nodes in their own lane
        if (orphans.length > 0) {
            var orphanLaneHeight = orphans.length * ROW_HEIGHT;
            orphans.forEach(function(n, i) {
                positions[n.id] = {
                    x: PADDING_X + COLUMN_WIDTH,
                    y: currentY + i * ROW_HEIGHT + (ROW_HEIGHT - NODE_HEIGHT) / 2,
                    node: n
                };
            });
            laneYRanges.push({ artifact: '(unassigned)', yStart: currentY, yEnd: currentY + orphanLaneHeight });
            currentY += orphanLaneHeight + LANE_GAP;
        }

        var width = PADDING_X * 2 + 3 * COLUMN_WIDTH;
        var height = currentY - LANE_GAP + PADDING_Y;

        return { positions: positions, laneYRanges: laneYRanges, width: Math.max(width, 400), height: Math.max(height, 120) };
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

    function renderEdge(edge, edgeIndex, positions, gutterSlots, nodeMap, leftOffset) {
        var from = positions[edge.from];
        var to = positions[edge.to];
        if (!from || !to) return '';

        var type = classifyEdge(edge, nodeMap);
        var topicConfig = edge.topicConfig || '';
        var g = '<g class="topo-edge" data-topic-config="' + escapeHtml(topicConfig) + '" data-from="' + escapeHtml(edge.from) + '" data-to="' + escapeHtml(edge.to) + '">';

        var fromCx = from.x + NODE_WIDTH;
        var fromCy = from.y + NODE_HEIGHT / 2;
        var toCx = to.x;
        var toCy = to.y + NODE_HEIGHT / 2;

        if (type === 'direct') {
            // Short horizontal bezier within the same lane
            var dx = Math.abs(toCx - fromCx) * 0.3;
            g += '<path d="M' + fromCx + ',' + fromCy + ' C' + (fromCx + dx) + ',' + fromCy + ' ' + (toCx - dx) + ',' + toCy + ' ' + toCx + ',' + toCy + '"';
            g += ' fill="none" stroke="#484f58" stroke-width="1.5" opacity="0.5"/>';
            // Arrowhead
            g += renderArrow(toCx, toCy, -1, 0, '#484f58', '0.5');
        } else if (type === 'topic') {
            // Manhattan routing through right gutter
            var slot = gutterSlots.rightSlots[edgeIndex] || 0;
            var gutterX = leftOffset + PADDING_X + 3 * COLUMN_WIDTH + 10 + slot * 12;
            var color = topicColorForConfig(topicConfig);

            var startX = from.x + NODE_WIDTH;
            var startY = from.y + NODE_HEIGHT / 2;
            var endX = to.x + NODE_WIDTH; // right side of TOPIC_SUB — actually we want to connect to right gutter then back to left of sub
            // pub node is in column 2, sub node is in column 0
            // Route: right of pub → gutter → left of sub
            var endX2 = to.x;
            var endY = to.y + NODE_HEIGHT / 2;

            g += '<path d="M' + startX + ',' + startY + ' H' + gutterX + ' V' + endY + ' H' + endX2 + '"';
            g += ' fill="none" stroke="' + color + '" stroke-width="1.5" stroke-dasharray="6,4" opacity="0.7"/>';
            // Arrowhead pointing left into the sub node
            g += renderArrow(endX2, endY, -1, 0, color, '0.7');
        } else if (type === 'dependency') {
            // Manhattan routing through mid-gutter (between col 0 and col 1)
            var slot2 = gutterSlots.leftSlots[edgeIndex] || 0;
            var midGutterX = leftOffset + PADDING_X + NODE_WIDTH + (COLUMN_WIDTH - NODE_WIDTH) / 2 - slot2 * 12;

            var startX2 = from.x;
            var startY2 = from.y + NODE_HEIGHT / 2;
            var endX3 = to.x;
            var endY2 = to.y + NODE_HEIGHT / 2;

            g += '<path d="M' + startX2 + ',' + startY2 + ' H' + midGutterX + ' V' + endY2 + ' H' + endX3 + '"';
            g += ' fill="none" stroke="#8b949e" stroke-width="1.5" opacity="0.5"/>';
            // Arrowhead pointing right into the target slice
            g += renderArrow(endX3, endY2, 1, 0, '#8b949e', '0.5');
        }

        g += '</g>';
        return g;
    }

    function renderArrow(x, y, dirX, dirY, color, opacity) {
        var size = 7;
        // dirX, dirY: direction the arrow points (normalized)
        // For horizontal arrows: dirX=-1 means pointing left, dirX=1 means pointing right
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

    function getTopicConfigColor(nodeId) {
        // Extract config from node ID: everything after the second ':'
        var parts = nodeId.split(':');
        if (parts.length >= 3) {
            var config = parts.slice(2).join(':');
            return topicColorForConfig(config);
        }
        return null;
    }

    function renderNode(id, pos, node) {
        var colors = TYPE_COLORS[node.type] || TYPE_COLORS.SLICE;
        var strokeColor = colors.stroke;

        // For TOPIC_PUB and TOPIC_SUB, derive stroke from topicConfig
        if (node.type === 'TOPIC_PUB' || node.type === 'TOPIC_SUB') {
            var configColor = getTopicConfigColor(id);
            if (configColor) {
                strokeColor = configColor;
            }
        }

        var label = truncateLabel(node.label, 28);
        var badgeLabels = { ENDPOINT: 'END', TOPIC_PUB: 'PUB', TOPIC_SUB: 'SUB', SLICE: 'SLC', RESOURCE: 'RES', TOPIC: 'TOP' };
        var badgeText = badgeLabels[node.type] || node.type.substring(0, 3);

        var g = '<g class="topo-node" data-node-id="' + escapeHtml(id) + '" data-label="' + escapeHtml(node.label || '') + '">';

        // Rounded rect
        g += '<rect x="' + pos.x + '" y="' + pos.y + '" width="' + NODE_WIDTH + '" height="' + NODE_HEIGHT + '"';
        g += ' rx="6" ry="6" fill="' + colors.fill + '" stroke="' + strokeColor + '" stroke-width="1.5"/>';

        // Type badge
        g += '<rect x="' + (pos.x + 6) + '" y="' + (pos.y + 6) + '" width="30" height="14" rx="3" fill="' + strokeColor + '" opacity="0.2"/>';
        g += '<text x="' + (pos.x + 21) + '" y="' + (pos.y + 16) + '" text-anchor="middle" fill="' + colors.text + '" font-size="9" font-family="monospace">' + badgeText + '</text>';

        // Label
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
            // Check if search is active
            var searchInput = container.closest('.panel');
            var searchQuery = '';
            if (searchInput) {
                var input = searchInput.querySelector('input[type="text"]');
                if (input) searchQuery = input.value || '';
            }
            // Also check Alpine store
            if (typeof Alpine !== 'undefined') {
                try {
                    var store = Alpine.store('topology');
                    if (store && store.searchQuery) searchQuery = store.searchQuery;
                } catch(e) { /* ignore */ }
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
                    // Highlight all edges with same topicConfig
                    allEdges.forEach(function(e) {
                        if (e.getAttribute('data-topic-config') === tc) {
                            e.style.opacity = '1';
                            // Boost connected nodes
                            var from = e.getAttribute('data-from');
                            var to = e.getAttribute('data-to');
                            allNodes.forEach(function(n) {
                                var nid = n.getAttribute('data-node-id');
                                if (nid === from || nid === to) n.style.opacity = '1';
                            });
                        }
                    });
                } else {
                    // Just highlight this edge and connected nodes
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

                // For TOPIC_PUB/TOPIC_SUB nodes, highlight all nodes/edges with matching topic config
                var isTopicNode = nodeId.startsWith('topic-pub:') || nodeId.startsWith('topic-sub:');
                if (isTopicNode) {
                    // Extract config: "topic-pub:artifact:config" or "topic-sub:artifact:config"
                    var prefix = nodeId.startsWith('topic-pub:') ? 'topic-pub:' : 'topic-sub:';
                    var topicConfig = nodeId.substring(nodeId.indexOf(':', prefix.length) + 1);

                    // Highlight all edges with matching topicConfig and their connected nodes
                    allEdges.forEach(function(e) {
                        var tc = e.getAttribute('data-topic-config');
                        var from = e.getAttribute('data-from');
                        var to = e.getAttribute('data-to');
                        if (tc === topicConfig || from === nodeId || to === nodeId) {
                            e.style.opacity = '1';
                            allNodes.forEach(function(n) {
                                var nid = n.getAttribute('data-node-id');
                                if (nid === from || nid === to) n.style.opacity = '1';
                            });
                        }
                    });
                } else {
                    // Standard node hover — highlight directly connected edges/nodes
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

        // Build node map
        var nodeMap = {};
        nodes.forEach(function(n) { nodeMap[n.id] = n; });

        // Layout
        var result = swimLaneLayout(nodes);
        var positions = result.positions;

        // Classify and assign gutter slots
        var gutterSlots = assignGutterSlots(edges || [], nodeMap);

        // Compute total width including gutters
        var totalWidth = Math.max(result.width + gutterSlots.rightGutterWidth + gutterSlots.leftGutterWidth + 40, 500);
        var totalHeight = Math.max(result.height + 40, 120);

        // Offset all positions by leftGutterWidth to make room for left gutter
        var leftOffset = gutterSlots.leftGutterWidth;
        for (var id in positions) {
            positions[id].x += leftOffset;
        }

        var svg = '<svg width="' + totalWidth + '" height="' + totalHeight + '" class="topology-svg">';

        // Lane separators
        result.laneYRanges.forEach(function(lane, i) {
            if (i > 0) {
                var sepY = lane.yStart - LANE_GAP / 2;
                svg += '<line x1="0" y1="' + sepY + '" x2="' + totalWidth + '" y2="' + sepY + '" stroke="#30363d" stroke-width="1" stroke-dasharray="4,4" opacity="0.5"/>';
            }
            // Lane label (rotated, short artifact name)
            var labelY = (lane.yStart + lane.yEnd) / 2;
            // Extract artifact name: "org.example:catalog-slice:1.0.0" → "catalog-slice"
            var parts = lane.artifact.split(':');
            var shortName = parts.length >= 2 ? parts[parts.length - 2] : parts[0] || lane.artifact;
            svg += '<text x="' + (leftOffset - 5) + '" y="' + labelY + '" text-anchor="end" fill="#484f58" font-size="10" font-family="monospace" transform="rotate(-90,' + (leftOffset - 5) + ',' + labelY + ')">' + escapeHtml(shortName) + '</text>';
        });

        // Draw edges (behind nodes)
        (edges || []).forEach(function(e, i) {
            svg += renderEdge(e, i, positions, gutterSlots, nodeMap, leftOffset);
        });

        // Draw nodes
        Object.keys(positions).forEach(function(id) {
            var pos = positions[id];
            svg += renderNode(id, pos, pos.node);
        });

        svg += '</svg>';
        container.innerHTML = svg;

        // Attach interactivity
        attachHoverListeners(container);
        if (searchQuery) {
            applySearch(container, searchQuery);
        }
    }

    return { render: render };
})();
