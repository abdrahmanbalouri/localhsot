#!/usr/bin/env python3
import sys, os, cgi

print("Content-Type: text/html")
print("Status: 200 OK")
print()
print("<html><body>")
print("<h1>CGI Test</h1>")
print("<p>Method: {}</p>".format(os.environ.get("REQUEST_METHOD", "unknown")))
print("<p>Path Info: {}</p>".format(os.environ.get("PATH_INFO", "unknown")))
print("<p>Query String: {}</p>".format(os.environ.get("QUERY_STRING", "")))
body = sys.stdin.read()
if body:
    print("<p>Body: {}</p>".format(body[:200]))
print("</body></html>")
