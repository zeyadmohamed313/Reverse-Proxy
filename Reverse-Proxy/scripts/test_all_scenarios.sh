#!/usr/bin/env bash

# Reverse Proxy - All Scenarios Test Script
set -u

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${PROJECT_ROOT}/build/classes"
PROXY_PORT="8443"
BACKEND_PORTS=("8081" "8082" "8083")
SERVER_PID=""
BACKEND_PIDS=()

# 0. Hard Restart (Kill any old instances)
echo "Ensuring a clean start (Killing old processes)..."
pkill -f com.reverseproxy 2>/dev/null
sleep 1 

cleanup() {
    echo "Cleaning up..."
    [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
    for pid in "${BACKEND_PIDS[@]}"; do
        kill "$pid" 2>/dev/null
    done
}

trap cleanup EXIT

# 1. Compile everything
echo "Compiling project..."
mkdir -p "$BUILD_DIR"
find "$PROJECT_ROOT/src/main/java" -name "*.java" > sources.txt
javac -d "$BUILD_DIR" @sources.txt
rm sources.txt

# 2. Start 3 Backends
echo "Starting 3 Backend instances..."
for port in "${BACKEND_PORTS[@]}"; do
    java -cp "$BUILD_DIR" com.reverseproxy.test.BackendApp "$port" > "/tmp/backend_$port.log" 2>&1 &
    BACKEND_PIDS+=($!)
done

sleep 2

# 3. Start Proxy
echo "Starting Reverse Proxy..."
java -cp "$BUILD_DIR" com.reverseproxy.core.ProxyServer "$PROXY_PORT" > "/tmp/proxy.log" 2>&1 &
SERVER_PID=$!

sleep 3

echo "------------------------------------------------"
echo "SCENARIO 1: Basic Forwarding & Load Balancing"
echo "------------------------------------------------"
for i in {1..6}; do
    curl -k -s "https://localhost:$PROXY_PORT/hello" | grep "Served by" || echo "Request failed"
done

sleep 3 # Wait for rate limiter refill

echo "------------------------------------------------"
echo "SCENARIO 2: Sticky Sessions (using Cookies)"
echo "------------------------------------------------"
echo "Request 1 (Getting Cookie):"
curl -k -s -i -c cookies.txt "https://localhost:$PROXY_PORT/hello" | grep "Served by"
echo "Subsequent Requests (Using Cookie):"
for i in {1..3}; do
    curl -k -s -b cookies.txt "https://localhost:$PROXY_PORT/hello" | grep "Served by"
done
rm cookies.txt

sleep 3

echo "------------------------------------------------"
echo "SCENARIO 3: Rate Limiting (Token Bucket)"
echo "------------------------------------------------"
echo "Flooding proxy with 15 requests (Limit is 10)..."
for i in {1..15}; do
    resp=$(curl -k -s -o /dev/null -w "%{http_code}" "https://localhost:$PROXY_PORT/hello")
    if [ "$resp" == "429" ]; then
        echo "Request $i: [BLOCKED] 429 Too Many Requests"
    else
        echo "Request $i: [OK] 200"
    fi
done

echo "------------------------------------------------"
echo "SCENARIO 4: Circuit Breaker"
echo "------------------------------------------------"
echo "Killing Backend on port 8081..."
kill "${BACKEND_PIDS[0]}"
sleep 1
echo "Sending requests to trigger Circuit Breaker (Threshold: 5 failures)..."
for i in {1..7}; do
    resp=$(curl -k -s -o /dev/null -w "%{http_code}" "https://localhost:$PROXY_PORT/hello")
    echo "Request $i: Status $resp"
done

echo "------------------------------------------------"
echo "SCENARIO 6: Active Health Check & Self-Healing"
echo "------------------------------------------------"
echo "Killing Backend on port 8082..."
kill "${BACKEND_PIDS[1]}"
echo "Waiting 5 seconds for Heartbeat to detect failure..."
sleep 5
echo "Proxy Stats (Should show 8082 as OPEN/FAILED):"
curl -k -s "https://localhost:$PROXY_PORT/__stats" | grep "8082"

echo "Restarting Backend on port 8082..."
java -cp "$BUILD_DIR" com.reverseproxy.test.BackendApp 8082 > /dev/null 2>&1 &
NEW_BACKEND_PID=$!
echo "Waiting 3 seconds for Heartbeat to detect recovery..."
sleep 3
echo "Proxy Stats (Should show 8082 as CLOSED/HEALTHY again):"
curl -k -s "https://localhost:$PROXY_PORT/__stats" | grep "8082"
kill $NEW_BACKEND_PID

echo "------------------------------------------------"
echo "Tests completed. Checking system logs..."
tail -n 20 logs/system.log
