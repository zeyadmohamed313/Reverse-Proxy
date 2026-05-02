#!/usr/bin/env bash

set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAVA_SRC_DIR="${PROJECT_ROOT}/src/main/java"
BUILD_DIR="${PROJECT_ROOT}/build/classes"
PORT="18080"
SERVER_LOG="/tmp/reverse_proxy_server.log"
SERVER_PID=""

PASS_COUNT=0
FAIL_COUNT=0

ALL_TESTS=(
  basic_get
  post_body
  keep_alive
  timeout
  header_limit
  malformed
)

REQUESTED_TESTS=()

usage() {
  printf 'Usage:\n'
  printf '  %s [--port <port>] [--log <path>] <test-name> [test-name ...]\n' "$0"
  printf '  %s [--port <port>] [--log <path>] --all\n' "$0"
  printf '  %s --list\n\n' "$0"
  printf 'Examples:\n'
  printf '  %s basic_get\n' "$0"
  printf '  %s keep_alive header_limit\n' "$0"
  printf '  %s --all\n\n' "$0"
  printf 'Available tests:\n'
  list_tests
}

list_tests() {
  printf '  basic_get    - GET /hello returns 200 + expected body\n'
  printf '  post_body    - POST /users body is parsed (Content-Length)\n'
  printf '  keep_alive   - Two requests on same socket (keep-alive then close)\n'
  printf '  timeout      - Slow client is closed by read timeout\n'
  printf '  header_limit - Oversized headers return 431\n'
  printf '  malformed    - Malformed request line returns 400\n'
}

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  printf '[PASS] %s\n' "$1"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  printf '[FAIL] %s\n' "$1"
}

require_tool() {
  local tool_name="$1"
  if ! command -v "$tool_name" >/dev/null 2>&1; then
    printf 'Missing required tool: %s\n' "$tool_name"
    exit 1
  fi
}

cleanup() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" >/dev/null 2>&1; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" >/dev/null 2>&1 || true
  fi
}

wait_for_server() {
  local max_attempts=50
  local attempt=1

  while (( attempt <= max_attempts )); do
    if curl -s --max-time 1 -o /dev/null "http://127.0.0.1:${PORT}/__health" >/dev/null 2>&1; then
      return 0
    fi

    if ! kill -0 "$SERVER_PID" >/dev/null 2>&1; then
      return 1
    fi

    sleep 0.2
    attempt=$((attempt + 1))
  done

  return 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  local message="$3"

  if [[ "$haystack" == *"$needle"* ]]; then
    pass "$message"
  else
    fail "$message (missing: $needle)"
  fi
}

is_valid_test() {
  local candidate="$1"
  local test_name
  for test_name in "${ALL_TESTS[@]}"; do
    if [[ "$candidate" == "$test_name" ]]; then
      return 0
    fi
  done
  return 1
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --port)
        if [[ $# -lt 2 ]]; then
          printf 'Missing value for --port\n'
          exit 1
        fi
        PORT="$2"
        shift 2
        ;;
      --log)
        if [[ $# -lt 2 ]]; then
          printf 'Missing value for --log\n'
          exit 1
        fi
        SERVER_LOG="$2"
        shift 2
        ;;
      --all)
        REQUESTED_TESTS=("${ALL_TESTS[@]}")
        shift
        ;;
      --list)
        list_tests
        exit 0
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        REQUESTED_TESTS+=("$1")
        shift
        ;;
    esac
  done

  if [[ "${#REQUESTED_TESTS[@]}" -eq 0 ]]; then
    printf 'No tests selected.\n\n'
    usage
    exit 1
  fi

  local test_name
  for test_name in "${REQUESTED_TESTS[@]}"; do
    if ! is_valid_test "$test_name"; then
      printf 'Unknown test: %s\n\n' "$test_name"
      usage
      exit 1
    fi
  done
}

run_basic_get_test() {
  local response

  printf '\n=== basic_get ===\n'
  printf 'Request: curl -i --http1.1 http://127.0.0.1:%s/hello\n\n' "$PORT"

  if ! response=$(curl -sS -i --http1.1 "http://127.0.0.1:${PORT}/hello"); then
    fail 'basic_get request failed to execute'
    return
  fi

  printf '%s\n' "$response"
  assert_contains "$response" 'HTTP/1.1 200 OK' 'basic_get returns 200'
  assert_contains "$response" 'Hello from Proxy Core' 'basic_get returns expected body'
}

run_post_body_test() {
  local response

  printf '\n=== post_body ===\n'
  printf 'Request: curl -i --http1.1 -X POST http://127.0.0.1:%s/users -d "name=zeyad"\n\n' "$PORT"

  if ! response=$(curl -sS -i --http1.1 -X POST "http://127.0.0.1:${PORT}/users" -d 'name=zeyad'); then
    fail 'post_body request failed to execute'
    return
  fi

  printf '%s\n' "$response"
  assert_contains "$response" 'HTTP/1.1 200 OK' 'post_body returns 200'

  sleep 0.3
  printf '\nServer log excerpt for /users:\n'

  if python3 - "$SERVER_LOG" <<'PY'
import sys

log_path = sys.argv[1]

with open(log_path, "r", encoding="utf-8", errors="replace") as f:
    text = f.read()

parts = text.split("-----------------------------------------------")
matches = [p for p in parts if "Path: /users" in p]

if not matches:
    print("(No /users request block found)")
    sys.exit(1)

block = matches[-1].strip()
print(block)

if "Body Length: 10 bytes" not in block:
    sys.exit(2)
PY
  then
    pass 'post_body parsed Content-Length/body correctly'
  else
    rc=$?
    if [[ "$rc" -eq 1 ]]; then
      fail 'post_body /users block not found in server log'
    else
      fail 'post_body /users block found, but expected body length 10 bytes'
    fi
  fi
}

run_keep_alive_test() {
  local output

  printf '\n=== keep_alive ===\n'
  printf 'Request: two HTTP/1.1 requests on the same socket\n\n'

  if output=$(python3 - "$PORT" <<'PY'
import socket
import sys

port = int(sys.argv[1])

def read_response(sock):
    raw = b""
    while b"\r\n\r\n" not in raw:
        chunk = sock.recv(4096)
        if not chunk:
            raise RuntimeError("socket closed before headers")
        raw += chunk

    header_bytes, body = raw.split(b"\r\n\r\n", 1)
    header_text = header_bytes.decode("iso-8859-1")
    lines = header_text.split("\r\n")
    status_line = lines[0]
    headers = {}

    for line in lines[1:]:
        if ":" in line:
            key, value = line.split(":", 1)
            headers[key.strip().lower()] = value.strip()

    content_length = int(headers.get("content-length", "0"))
    while len(body) < content_length:
        chunk = sock.recv(content_length - len(body))
        if not chunk:
            raise RuntimeError("socket closed before full body")
        body += chunk

    full = header_text + "\r\n\r\n" + body.decode("utf-8", errors="replace")
    return status_line, headers, body, full

s = socket.create_connection(("127.0.0.1", port), timeout=3)
s.settimeout(3)

req1 = (
    "GET /first HTTP/1.1\r\n"
    "Host: localhost\r\n"
    "Connection: keep-alive\r\n"
    "\r\n"
)
req2 = (
    "GET /second HTTP/1.1\r\n"
    "Host: localhost\r\n"
    "Connection: close\r\n"
    "\r\n"
)

s.sendall(req1.encode("ascii"))
status1, headers1, body1, full1 = read_response(s)

s.sendall(req2.encode("ascii"))
status2, headers2, body2, full2 = read_response(s)

s.close()

print("--- Response 1 ---")
print(full1)
print("--- Response 2 ---")
print(full2)

ok = (
    status1 == "HTTP/1.1 200 OK"
    and status2 == "HTTP/1.1 200 OK"
    and headers1.get("connection", "").lower() == "keep-alive"
    and headers2.get("connection", "").lower() == "close"
    and body1 == b"Hello from Proxy Core"
    and body2 == b"Hello from Proxy Core"
)

sys.exit(0 if ok else 1)
PY
  ); then
    printf '%s\n' "$output"
    pass 'keep_alive works across two requests on one connection'
  else
    printf '%s\n' "$output"
    fail 'keep_alive behavior mismatch'
  fi
}

run_timeout_test() {
  local output

  printf '\n=== timeout ===\n'
  printf 'Request: send partial request, wait > 10s, then continue\n\n'

  if output=$(python3 - "$PORT" <<'PY'
import socket
import sys
import time

port = int(sys.argv[1])
s = socket.create_connection(("127.0.0.1", port), timeout=3)
s.settimeout(3)

s.sendall(b"GET /slow HTTP/1.1\r\n")
print("Sent partial request line. Sleeping 11s...")
time.sleep(11.0)

closed = False
result = ""

try:
    s.sendall(b"Host: localhost\r\n\r\n")
    data = s.recv(1024)
    if data == b"":
        closed = True
        result = "Socket closed (EOF)"
    else:
        result = "Received data: " + data.decode("utf-8", errors="replace")
except (BrokenPipeError, ConnectionResetError, TimeoutError, OSError) as ex:
    closed = True
    result = "Connection closed with exception: " + type(ex).__name__

s.close()
print(result)
sys.exit(0 if closed else 1)
PY
  ); then
    printf '%s\n' "$output"
    pass 'timeout closes slow connection'
  else
    printf '%s\n' "$output"
    fail 'timeout test failed (connection stayed open)'
  fi
}

run_header_limit_test() {
  local output

  printf '\n=== header_limit ===\n'
  printf 'Request: send >16KB header and inspect response\n\n'

  if ! output=$(python3 - "$PORT" <<'PY'
import socket
import sys

port = int(sys.argv[1])
huge_header = "a" * (17 * 1024)
request = (
    "GET /big HTTP/1.1\r\n"
    "Host: localhost\r\n"
    f"X-Huge: {huge_header}\r\n"
    "\r\n"
)

s = socket.create_connection(("127.0.0.1", port), timeout=3)
s.settimeout(3)
s.sendall(request.encode("ascii"))

data = b""
try:
    while True:
        chunk = s.recv(4096)
        if not chunk:
            break
        data += chunk
except ConnectionResetError:
    pass

s.close()
print(data.decode("utf-8", errors="replace"))
PY
  ); then
    fail 'header_limit test failed to execute'
    return
  fi

  printf '%s\n' "$output"
  assert_contains "$output" '431 Request Header Fields Too Large' 'header_limit returns 431'
}

run_malformed_request_test() {
  local output

  printf '\n=== malformed ===\n'
  printf 'Request: malformed request line (missing HTTP version)\n\n'

  if ! output=$(python3 - "$PORT" <<'PY'
import socket
import sys

port = int(sys.argv[1])
request = b"GET /broken\r\nHost: localhost\r\n\r\n"

s = socket.create_connection(("127.0.0.1", port), timeout=3)
s.settimeout(3)
s.sendall(request)

data = b""
try:
    while True:
        chunk = s.recv(4096)
        if not chunk:
            break
        data += chunk
except ConnectionResetError:
    pass

s.close()
print(data.decode("utf-8", errors="replace"))
PY
  ); then
    fail 'malformed test failed to execute'
    return
  fi

  printf '%s\n' "$output"
  assert_contains "$output" '400 Bad Request' 'malformed request returns 400'
}

run_single_test() {
  local test_name="$1"
  case "$test_name" in
    basic_get)
      run_basic_get_test
      ;;
    post_body)
      run_post_body_test
      ;;
    keep_alive)
      run_keep_alive_test
      ;;
    timeout)
      run_timeout_test
      ;;
    header_limit)
      run_header_limit_test
      ;;
    malformed)
      run_malformed_request_test
      ;;
  esac
}

compile_project() {
  mkdir -p "$BUILD_DIR"

  local source_files=()
  while IFS= read -r source_file; do
    source_files+=("$source_file")
  done < <(find "$JAVA_SRC_DIR" -type f -name '*.java')

  if [[ "${#source_files[@]}" -eq 0 ]]; then
    printf 'No Java source files found under: %s\n' "$JAVA_SRC_DIR"
    return 1
  fi

  javac -d "$BUILD_DIR" "${source_files[@]}"
}

main() {
  trap cleanup EXIT

  parse_args "$@"

  require_tool javac
  require_tool java
  require_tool curl
  require_tool python3

  cd "$PROJECT_ROOT"

  printf 'Compiling Java sources...\n'
  if ! compile_project; then
    printf 'Compilation failed.\n'
    exit 1
  fi

  : > "$SERVER_LOG"

  printf 'Starting ProxyServer on port %s...\n' "$PORT"
  java -cp "$BUILD_DIR" com.reverseproxy.core.ProxyServer "$PORT" > "$SERVER_LOG" 2>&1 &
  SERVER_PID=$!

  if ! wait_for_server; then
    printf 'Server failed to start. See log: %s\n' "$SERVER_LOG"
    exit 1
  fi

  printf 'Running selected tests: %s\n' "${REQUESTED_TESTS[*]}"

  local test_name
  for test_name in "${REQUESTED_TESTS[@]}"; do
    run_single_test "$test_name"
  done

  printf '\nTest summary: %d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
  printf 'Server log: %s\n' "$SERVER_LOG"

  if (( FAIL_COUNT > 0 )); then
    exit 1
  fi
}

main "$@"
