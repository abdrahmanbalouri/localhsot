import os
import sys

print("Content-Type: text/html")
print("")
print("<html><body>")
print("<h1>CGI Test Script</h1>")
print("<p><b>Request Method:</b> " + os.environ.get("REQUEST_METHOD", "N/A") + "</p>")
print("<p><b>Path Info:</b> " + os.environ.get("PATH_INFO", "N/A") + "</p>")
print("<p><b>Query String:</b> " + os.environ.get("QUERY_STRING", "N/A") + "</p>")
print("<h2>Environment Variables</h2>")
print("<ul>")
for key, value in os.environ.items():
    print(f"<li>{key}: {value}</li>")
print("</ul>")
print("</body></html>")
