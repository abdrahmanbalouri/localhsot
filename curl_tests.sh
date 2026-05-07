#!/bin/bash

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
HOST_HEADER="${HOST_HEADER:-localhost}"

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

pass() {
  echo -e "${GREEN}PASS${NC} $1"
}

fail() {
  echo -e "${RED}FAIL${NC} $1"
}

status_code() {
  curl -s -o /tmp/java_server_curl_body.txt -w "%{http_code}" -H "Host: $HOST_HEADER" "$@"
}

expect_status() {
  local name="$1"
  local expected="$2"
  shift 2

  local status
  status=$(status_code "$@")
  if [ "$status" = "$expected" ]; then
    pass "$name ($status)"
  else
    fail "$name (expected $expected, got $status)"
    sed -n '1,20p' /tmp/java_server_curl_body.txt
  fi
}

echo "Testing $BASE_URL with Host: $HOST_HEADER"
echo

expect_status "GET /" 200 "$BASE_URL/"

echo -n "API response... "
api_body=$(curl -s -H "Host: $HOST_HEADER" "$BASE_URL/api")
if echo "$api_body" | grep -q '"status":"ok"'; then
  pass "GET /api"
else
  fail "GET /api"
  echo "$api_body"
fi

expect_status "redirect /old" 301 -i "$BASE_URL/old"

echo -n "CGI GET... "
cgi_body=$(curl -s -H "Host: $HOST_HEADER" "$BASE_URL/cgi-bin/hello.py?name=test")
if echo "$cgi_body" | grep -q "CGI Test Script"; then
  pass "GET /cgi-bin/hello.py"
else
  fail "GET /cgi-bin/hello.py"
  echo "$cgi_body" | sed -n '1,20p'
fi

expect_status "POST upload" 201 -X POST --data "hello from curl" "$BASE_URL/curl_test.txt"

echo -n "GET uploaded file... "
uploaded_body=$(curl -s -H "Host: $HOST_HEADER" "$BASE_URL/curl_test.txt")
if [ "$uploaded_body" = "hello from curl" ]; then
  pass "GET /curl_test.txt"
else
  fail "GET /curl_test.txt"
  echo "$uploaded_body"
fi

expect_status "DELETE uploaded file" 204 -X DELETE "$BASE_URL/curl_test.txt"
expect_status "GET deleted file returns 404" 404 "$BASE_URL/curl_test.txt"
expect_status "method not allowed on /readonly" 405 -X POST --data "blocked" "$BASE_URL/readonly/test.txt"
expect_status "missing page" 404 "$BASE_URL/not-found-page"

echo
echo "Done."
