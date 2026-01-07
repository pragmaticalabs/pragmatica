#!/bin/bash
#
# Aether Real Cluster Demo
#
# Starts a 5-node cluster with order-demo slices deployed.
#
# Usage:
#   ./demo-cluster.sh start   - Start 5-node cluster
#   ./demo-cluster.sh stop    - Stop all nodes
#   ./demo-cluster.sh status  - Check cluster status
#   ./demo-cluster.sh deploy  - Deploy order-demo blueprint
#   ./demo-cluster.sh logs    - Tail node logs
#   ./demo-cluster.sh clean   - Stop and clean up
#
# Requirements:
#   - Maven build completed (mvn package -DskipTests)
#   - order-demo installed (cd examples/order-demo && mvn install)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOG_DIR="$PROJECT_DIR/logs"
PID_DIR="$PROJECT_DIR/.pids"

NODE_JAR="$PROJECT_DIR/node/target/aether-node.jar"
CLI_JAR="$PROJECT_DIR/cli/target/aether.jar"
BLUEPRINT="$PROJECT_DIR/examples/order-demo/demo-order.blueprint"

# Cluster configuration (5 nodes)
PEERS="node-1:localhost:8091,node-2:localhost:8092,node-3:localhost:8093,node-4:localhost:8094,node-5:localhost:8095"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

ensure_built() {
    if [ ! -f "$NODE_JAR" ]; then
        log_info "Building node JAR..."
        mvn -f "$PROJECT_DIR/pom.xml" package -pl node -am -DskipTests -q
    fi
    if [ ! -f "$CLI_JAR" ]; then
        log_info "Building CLI JAR..."
        mvn -f "$PROJECT_DIR/pom.xml" package -pl cli -am -DskipTests -q
    fi
}

ensure_order_demo() {
    local demo_jar="$HOME/.m2/repository/org/pragmatica-lite/aether/demo/inventory-service/0.1.0/inventory-service-0.1.0.jar"
    if [ ! -f "$demo_jar" ]; then
        log_info "Installing order-demo to local repository..."
        mvn -f "$PROJECT_DIR/examples/order-demo/pom.xml" install -DskipTests -q
    fi
}

start_node() {
    local node_id=$1
    local cluster_port=$2
    local mgmt_port=$3

    mkdir -p "$LOG_DIR" "$PID_DIR"

    log_info "Starting $node_id on ports $cluster_port (cluster) / $mgmt_port (mgmt)..."

    java -jar "$NODE_JAR" \
        --node-id="$node_id" \
        --port="$cluster_port" \
        --management-port="$mgmt_port" \
        --peers="$PEERS" \
        > "$LOG_DIR/$node_id.log" 2>&1 &

    echo $! > "$PID_DIR/$node_id.pid"
    log_info "$node_id started (PID: $!)"
}

start_cluster() {
    ensure_built
    ensure_order_demo

    log_info "Starting 5-node Aether cluster..."

    start_node "node-1" 8091 8081
    start_node "node-2" 8092 8082
    start_node "node-3" 8093 8083
    start_node "node-4" 8094 8084
    start_node "node-5" 8095 8085

    log_info "Waiting for cluster to form..."
    sleep 7

    # Check if nodes are up
    if curl -s http://localhost:8081/health > /dev/null 2>&1; then
        log_info "Cluster is UP!"
        echo ""
        log_info "Management endpoints:"
        echo "  - Node 1: http://localhost:8081 (Dashboard: http://localhost:8081/dashboard)"
        echo "  - Node 2: http://localhost:8082"
        echo "  - Node 3: http://localhost:8083"
        echo "  - Node 4: http://localhost:8084"
        echo "  - Node 5: http://localhost:8085"
        echo ""
        log_info "To deploy order-demo: ./demo-cluster.sh deploy"
        log_info "To check status: ./demo-cluster.sh status"
    else
        log_warn "Cluster may still be forming. Check logs with: ./demo-cluster.sh logs"
    fi
}

stop_cluster() {
    log_info "Stopping cluster..."

    for pid_file in "$PID_DIR"/*.pid; do
        if [ -f "$pid_file" ]; then
            local pid=$(cat "$pid_file")
            local node_id=$(basename "$pid_file" .pid)
            if kill -0 "$pid" 2>/dev/null; then
                log_info "Stopping $node_id (PID: $pid)..."
                kill "$pid" 2>/dev/null || true
            fi
            rm -f "$pid_file"
        fi
    done

    log_info "Cluster stopped"
}

cluster_status() {
    ensure_built

    echo ""
    log_info "Cluster Status"
    echo "=============="

    for port in 8081 8082 8083 8084 8085; do
        local node_id="node-$((port - 8080))"
        echo -n "$node_id (localhost:$port): "
        if curl -s "http://localhost:$port/health" > /dev/null 2>&1; then
            echo -e "${GREEN}UP${NC}"
        else
            echo -e "${RED}DOWN${NC}"
        fi
    done

    echo ""
    log_info "Detailed status from node-1:"
    java -jar "$CLI_JAR" --connect localhost:8081 status 2>/dev/null || log_warn "Could not connect to node-1"
}

deploy_demo() {
    ensure_built

    if [ ! -f "$BLUEPRINT" ]; then
        log_error "Blueprint not found: $BLUEPRINT"
        exit 1
    fi

    log_info "Deploying order-demo blueprint..."
    java -jar "$CLI_JAR" --connect localhost:8081 blueprint apply "$BLUEPRINT"

    log_info "Waiting for deployment..."
    sleep 10

    log_info "Deployed slices:"
    java -jar "$CLI_JAR" --connect localhost:8081 slices
}

show_logs() {
    if [ -d "$LOG_DIR" ]; then
        tail -f "$LOG_DIR"/*.log
    else
        log_error "No log directory found"
    fi
}

clean_up() {
    stop_cluster

    log_info "Cleaning up..."
    rm -rf "$LOG_DIR" "$PID_DIR"
    log_info "Done"
}

case "${1:-help}" in
    start)
        start_cluster
        ;;
    stop)
        stop_cluster
        ;;
    status)
        cluster_status
        ;;
    deploy)
        deploy_demo
        ;;
    logs)
        show_logs
        ;;
    clean)
        clean_up
        ;;
    *)
        echo "Aether Real Cluster Demo"
        echo ""
        echo "Usage: $0 <command>"
        echo ""
        echo "Commands:"
        echo "  start   - Start 5-node cluster"
        echo "  stop    - Stop all nodes"
        echo "  status  - Check cluster status"
        echo "  deploy  - Deploy order-demo blueprint"
        echo "  logs    - Tail node logs"
        echo "  clean   - Stop and clean up"
        ;;
esac
