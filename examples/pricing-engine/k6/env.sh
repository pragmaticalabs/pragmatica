#!/bin/bash
# Forge cluster node configuration — sourced by runner scripts.
# Edit these to match your forge.toml or remote deployment.

# Node URLs matching forge.toml: 7 nodes, app_http_port = 8070
export FORGE_NODES="http://localhost:8070,http://localhost:8071,http://localhost:8072,http://localhost:8073,http://localhost:8074,http://localhost:8075,http://localhost:8076"
