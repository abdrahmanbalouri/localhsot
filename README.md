# java-localserver

HTTP/1.1 server custom b Java, non-blocking I/O (NIO), single thread, event-driven. khedam b `java.nio` w `java.net` bla ma tst3mel frameworks (Netty, Jetty, ...).

---

## Requirements

- Java 17+
- Python 3 (pour CGI)

---

## Build & Run

```bash
cd java-server
javac src/*.java -d out
java -cp out Main
```

Ou bien avec config file spécifique:

```bash
java -cp out Main config.json
```

---

## Configuration (config.json)

```json
{
  "servers": [
    {
      "host": "127.0.0.1",
      "ports": [8080, 8081],
      "server_name": "localhost",
      "error_pages": {
        "400": "error_pages/400.html",
        "403": "error_pages/403.html",
        "404": "error_pages/404.html",
        "405": "error_pages/405.html",
        "413": "error_pages/413.html",
        "500": "error_pages/500.html"
      },
      "client_body_limit": 1048576,
      "routes": [
        {
          "path": "/",
          "root": "www",
          "default_file": "index.html",
          "methods": ["GET"],
          "directory_listing": false
        },
        {
          "path": "/upload",
          "root": "uploads",
          "methods": ["GET", "POST", "DELETE"],
          "client_body_limit": 21474836480
        },
        {
          "path": "/cgi",
          "root": "cgi",
          "methods": ["GET", "POST", "DELETE"],
          "cgi_extensions": [".py"],
          "client_body_limit": 21474836480
        },
        {
          "path": "/redirect",
          "redirect": "/",
          "methods": ["GET"]
        }
      ]
    }
  ]
}
```

### Options:

| Option | Description |
|--------|-------------|
| `host` | IP li ghadi ysm3 fiha |
| `ports` | lista d ports (9dder tzid bzaaf) |
| `server_name` | Hostname (virtual hosting) |
| `error_pages` | Custom error pages (code → path) |
| `client_body_limit` | Taille max d'body (bytes), supports large `long` values like 21474836480 (20 GiB) |
| `routes[].path` | URL prefix |
| `routes[].root` | Directory racine |
| `routes[].methods` | HTTP methods acceptés |
| `routes[].default_file` | File par défaut pour directory |
| `routes[].redirect` | Redirection (301) |
| `routes[].cgi_extensions` | Extensions CGI (ex: `.py`) |
| `routes[].directory_listing` | Affichage d'contenu d'directory |
| `routes[].client_body_limit` | Override d'limit pour cette route |

---

## Features

- **HTTP/1.1** — requests w responses standards
- **Non-blocking I/O** — `java.nio.channels.Selector`, single thread
- **Multi-ports** — chaque server peut écouter sur plusieurs ports
- **GET / POST / DELETE** — methods allowed by JSON can serve static files; create/delete only under `/upload`
- **Chunked encoding** — Transfer-Encoding: chunked (request + response)
- **CGI** — exécute any `.py` script under `/cgi/` via `ProcessBuilder` for allowed methods
- **Cookies & Sessions** — session_id, stockage côté serveur (1h timeout)
- **Error pages** — 400, 403, 404, 405, 413, 500 (customisables)
- **Client body limit** — contrôle global + par route
- **Redirect** — 301 Moved Permanently
- **Directory listing** — optionnel
- **Timeout** — 30 secondes (connections idle)
- **Détection ports dupliqués** — warning + skip
- **Sécurité** — path traversal prevention (`..` bloqué)
- **Zero crash** — try-catch autour d'tout, jamais d'crash

---

## Testing

### curl

```bash
# Static file
curl http://127.0.0.1:8080/

# 404
curl http://127.0.0.1:8080/nonexistent

# Upload
curl -X POST -d "hello world" http://127.0.0.1:8080/upload/test.txt

# Download uploaded file
curl http://127.0.0.1:8080/upload/test.txt

curl  -H "Host: localhost" http://127.0.0.1:8080/

# Delete
curl -X DELETE http://127.0.0.1:8080/upload/test.txt

# CGI
curl http://127.0.0.1:8080/cgi/test.py

# Redirect
curl -i http://127.0.0.1:8080/redirect

# 405 Method Not Allowed
curl -X DELETE http://127.0.0.1:8080/

# 413 Request Entity Too Large
dd if=/dev/zero bs=11M count=1 | curl -X POST --data-binary @- http://127.0.0.1:8080/upload/big.bin

# Wrong method
curl -X POST http://127.0.0.1:8080/
```

### Stress test (siege)

```bash
siege -b http://127.0.0.1:8080/
```

(99.5% availability target)

### Memory check

```bash
ps aux | grep "java -cp out Main" | awk '{print "RSS: "$6" KB"}'
```

--- 

## Architecture

```
Main.java          → lit config, lance Server
Server.java        → NIO event loop, HTTP parsing, response generation
ConfigLoader.java  → parse JSON → Map<String, Object>
Router.java        → match route (longest prefix)
CGIHandler.java    → ProcessBuilder: python3 script.py
SessionManager.java→ Map<session_id, Session> avec expiry
```

### Flow

```
Client → SocketChannel → Selector (OP_READ)
  → read bytes → tryParse()
    → parse headers + body (chunked/unchunked)
    → process()
      → router.match() → route
      → handleGet / handlePost / handleDelete
        → serveFile / runCGI / create/delete under /upload
    → prepareResponse()
    → Selector (OP_WRITE) → write bytes → close
```

- 1 thread, 1 process
- 1 `Selector` pour toutes les opérations (accept, read, write)
- read/write toujours via `select()`, jamais bloquant directement
- 1 read ou 1 write par client par `select()`

---

## Port issues

- **Même port multiple fois** → server detecte w yskip (warning)
- **Port déjà utilisé** → server catch l'error w ykamel avec les autres ports
- **Port commun entre servers** → chaque server peut partager un port (configure error_pages, routes, etc. séparément via server_name)

---

## File structure

```
java-server/
├── config.json
├── src/
│   ├── Main.java
│   ├── Server.java
│   ├── ConfigLoader.java
│   ├── Router.java
│   ├── CGIHandler.java
│   └── SessionManager.java
├── error_pages/
│   ├── 400.html
│   ├── 403.html
│   ├── 404.html
│   ├── 405.html
│   ├── 413.html
│   └── 500.html
├── www/
│   └── index.html
├── cgi/
│   └── test.py
└── uploads/
```
