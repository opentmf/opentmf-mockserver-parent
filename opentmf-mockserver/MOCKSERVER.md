# MockServer Feature Matrix

This project embeds a subset of [MockServer](https://github.com/mock-server/mockserver) (v5.15.0)
by James D Bloom, migrated to Jackson 3.x and trimmed for JSON-only TMF mock usage. This document
lists what is carried over and what is intentionally excluded.

## Included

### Core Server

| Feature                 | Details                                                   |
|-------------------------|-----------------------------------------------------------|
| Netty HTTP server       | `MockServer`, `ClientAndServer`, CLI via `Main`           |
| Expectation engine      | Create, match, prioritise, and expire expectations        |
| Request matching        | Method, path, headers, query parameters, cookies, body    |
| Verification            | `PUT /mockserver/verify` and `/mockserver/verifySequence` |
| Logging & retrieval     | `PUT /mockserver/retrieve` (requests, expectations, logs) |
| Clear & reset           | `PUT /mockserver/clear`, `/mockserver/reset`              |
| Port binding            | `PUT /mockserver/bind` for dynamic port allocation        |
| Expectation persistence | JSON initialisation file and `ExpectationFileWatcher`     |
| CORS                    | Built-in CORS handling for browser clients                |

### Body Matching

| Matcher      | Description                                      |
|--------------|--------------------------------------------------|
| Exact string | Literal string equality                          |
| Substring    | Contains check                                   |
| Regex        | Java regex against body                          |
| JSON         | Structural JSON comparison                       |
| JSON Schema  | Validate body against a JSON Schema (Draft 7)    |
| JSONPath     | Match when a JSONPath expression returns results |
| Binary       | Byte-array equality                              |
| Parameter    | Form-encoded parameter matching                  |

### Actions

| Action                   | Description                                                                |
|--------------------------|----------------------------------------------------------------------------|
| Response                 | Return a static response                                                   |
| Response class callback  | Invoke a server-side Java class implementing `ExpectationResponseCallback` |
| Response object callback | Invoke a client-supplied callback over WebSocket                           |
| Forward                  | Forward the request to another host                                        |
| Forward class callback   | Invoke a server-side Java class to modify the forwarded request            |
| Forward object callback  | Invoke a client-supplied callback to modify the forwarded request          |
| Error                    | Drop the connection or return garbage bytes                                |

### TLS / SSL

| Feature            | Details                                                    |
|--------------------|------------------------------------------------------------|
| Server-side TLS    | Auto-generated self-signed certificates via BouncyCastle   |
| Client-side TLS    | Outbound HTTPS when forwarding requests                    |
| SNI                | Server Name Indication for multi-domain certificates       |
| HTTP/2 + ALPN      | Application-Layer Protocol Negotiation for HTTP/2 over TLS |
| Mutual TLS         | Configurable via `tlsMutualAuthenticationRequired`         |
| Certificate export | PEM and KeyStore export utilities                          |

### Networking

| Feature             | Details                                                                |
|---------------------|------------------------------------------------------------------------|
| HTTP/1.1 and HTTP/2 | Both supported on server and client side                               |
| Port forwarding     | `proxyRemotePort` / `proxyRemoteHost` for simple port-level forwarding |
| WebSocket callbacks | Bidirectional callback channel between client and server               |

### Serialization

Full JSON serialization and deserialization for expectations, requests, responses, and all
supported body types. Java code generation for requests is also included
(`PUT /mockserver/retrieve?type=REQUEST&format=JAVA`).

## Excluded

These features are intentionally removed or stubbed out. Attempting to use them will result in
a clear error message (HTTP 400 or `UnsupportedOperationException`).

### OpenAPI / Swagger

MockServer supports defining expectations from OpenAPI specifications. This requires
`swagger-parser` which pulls in Jackson 2.x and numerous transitive dependencies.

- `PUT /mockserver/openapi` returns **400 Bad Request**.
- `HttpRequestsPropertiesMatcher` throws `UnsupportedOperationException` for OpenAPI definitions.

### Template Engines (JavaScript / Velocity)

MockServer supports dynamic response and forward templates using JavaScript (Nashorn/GraalJS) or
Apache Velocity. These add significant runtime weight.

- `RESPONSE_TEMPLATE` and `FORWARD_TEMPLATE` action types throw `UnsupportedOperationException`.

### XML / XPath / XML Schema Body Matching

MockServer supports XML body matching (exact, XPath, XML Schema). Since this project is JSON-only,
these matchers are removed.

- `Body.Type` enum values `XML`, `XML_SCHEMA`, `XPATH` still exist for wire compatibility but have
  no matcher implementation.

### Dashboard UI

MockServer ships a web-based dashboard for inspecting expectations and logs.

- The dashboard static assets and handler are not included.
- `MockServerClient.openUI()` will open a URL that returns 404.

### SOCKS Proxy / HTTP CONNECT

MockServer can act as a full HTTP/SOCKS proxy, intercepting traffic via `CONNECT` tunnelling or
SOCKS5 negotiation.

- `CONNECT` requests are rejected with a log message.
- SOCKS detection in `PortUnificationHandler` logs a removal notice.

### Prometheus Metrics

MockServer exposes Prometheus-compatible metrics for request counts and latencies.

- `Metrics` class is a no-op stub — all counters are ignored.

### Servlet Container Deployment

MockServer can be deployed as a WAR in a servlet container (Tomcat, Jetty, etc.).

- `javax.servlet` dependency is removed. WAR packaging is not supported.

## Usage Examples

The examples below walk through a complete lifecycle, from verifying the server is running to
cleaning up. All commands assume the server is running on `http://localhost:1080`.

### 1. Check Server Status

```shell
curl -s -X PUT http://localhost:1080/mockserver/status | jq .
```

```json
{
  "ports": [
    1080
  ]
}
```

### 2. View the OpenAPI Specification

```shell
curl -s http://localhost:1080/mockserver/openapi | head -20
```

### 3. Create Expectations

Register a full set of TMF resource expectations for a domain:

```shell
# POST — create resources
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
  "httpRequest": { "method": "POST", "path": "/tmf-api/serviceOrdering/v4/serviceOrder" },
  "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicPostCallback" }
}'

# Bulk Create (TMF630 §6.2) — create multiple resources atomically
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
  "httpRequest": { "method": "PATCH", "path": "/tmf-api/serviceOrdering/v4/serviceOrder",
                   "headers": { "Content-Type": ["application/json-patch+json"] } },
  "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicJsonPatchCollectionCallback" }
}'

# PUT — create or replace a resource by ID (RFC 9110 §9.3.4, idempotent)
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
  "httpRequest": { "method": "PUT", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*" },
  "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicPutCallback" }
}'

# GET by ID — retrieve a single resource
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
  "httpRequest": { "method": "GET", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*" },
  "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicGetCallback" }
}'

# GET List — retrieve all resources with paging
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
  "httpRequest": { "method": "GET", "path": "/tmf-api/serviceOrdering/v4/serviceOrder" },
  "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicGetListCallback" }
}'

# JSON Patch (RFC 6902)
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
  "httpRequest": { "method": "PATCH", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*",
                   "headers": { "Content-Type": ["application/json-patch+json"] } },
  "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicJsonPatchCallback" }
}'

# Merge Patch (RFC 7396)
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
  "httpRequest": { "method": "PATCH", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*",
                   "headers": { "Content-Type": ["application/merge-patch+json"] } },
  "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicMergePatchCallback" }
}'

# DELETE
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
  "httpRequest": { "method": "DELETE", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*" },
  "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicDeleteCallback" }
}'
```

#### Initializing Expectations from a JSON File

Instead of registering each expectation with `PUT /mockserver/expectation` after startup, you
can pre-load a batch from a JSON file. Set `MOCKSERVER_INITIALIZATION_JSON_PATH` to the path
(or glob) of a file whose contents are a JSON array of expectation objects -- the same shape
the `PUT /mockserver/expectation` API accepts. The server reads them at startup before serving
any traffic.

```shell
docker run -p 1080:1080 \
  -e MOCKSERVER_INITIALIZATION_JSON_PATH=/config/expectations.json \
  -v /path/to/expectations.json:/config/expectations.json \
  local/opentmf-mockserver:2.1.0-SNAPSHOT
```

Example `expectations.json` registering the full TMF callback set for one domain:

```json
[
  {
    "httpRequest": { "method": "POST", "path": "/tmf-api/serviceOrdering/v4/serviceOrder" },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicPostCallback" }
  },
  {
    "httpRequest": { "method": "PATCH", "path": "/tmf-api/serviceOrdering/v4/serviceOrder",
                     "headers": { "Content-Type": ["application/json-patch+json"] } },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicJsonPatchCollectionCallback" }
  },
  {
    "httpRequest": { "method": "PUT", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*" },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicPutCallback" }
  },
  {
    "httpRequest": { "method": "GET", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*" },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicGetCallback" }
  },
  {
    "httpRequest": { "method": "GET", "path": "/tmf-api/serviceOrdering/v4/serviceOrder.*" },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicGetListCallback" }
  },
  {
    "httpRequest": { "method": "PATCH", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*",
                     "headers": { "Content-Type": ["application/json-patch+json"] } },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicJsonPatchCallback" }
  },
  {
    "httpRequest": { "method": "PATCH", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*",
                     "headers": { "Content-Type": ["application/merge-patch+json"] } },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicMergePatchCallback" }
  },
  {
    "httpRequest": { "method": "DELETE", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*" },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicDeleteCallback" }
  }
]
```

The path supports globs (e.g. `/config/expectations-*.json`), so you can split bundles across
multiple files. Set `MOCKSERVER_WATCH_INITIALIZATION_JSON=true` to hot-reload the file on change
without restarting the server.

A configured path that matches no file, or a file that does not parse, fails startup (exit
status 1) with the path in the message -- unlike upstream MockServer, which warns and starts
without those expectations. With the watcher on, a bad edit is logged and skipped.

The same document can be written as YAML (so it can carry comments) and loaded with
`MOCKSERVER_INITIALIZATION_YAML_PATH`; both paths may be set together, with the same strict
startup behaviour. The YAML file is not watched. See [the README](../README.md#initializing-expectations-from-a-file-json-or-yaml).

### 4. Create a Resource

```shell
curl -s -X POST http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder \
  -H "Content-Type: application/json" \
  -d '{"description": "Install fibre to building 7", "priority": "1"}' | jq .
```

The response includes auto-generated fields:

```json
{
  "id": "a1b2c3d4-...",
  "href": "/tmf-api/serviceOrdering/v4/serviceOrder/a1b2c3d4-...",
  "state": "acknowledged",
  "revision": 1,
  "createdDate": "2026-03-22T18:00:00.000Z",
  "createdBy": "anonymous",
  "description": "Install fibre to building 7",
  "priority": "1"
}
```

### 5. Retrieve the Resource by ID

On first GET, the state transitions from `acknowledged` to `completed`:

```shell
curl -s http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder/a1b2c3d4-... | jq .state
```

```json
"completed"
```

### 6. Patch the Resource

**Merge Patch** — update a field:

```shell
curl -s -X PATCH http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder/a1b2c3d4-... \
  -H "Content-Type: application/merge-patch+json" \
  -d '{"priority": "2"}' | jq '{priority, revision}'
```

```json
{
  "priority": "2",
  "revision": 3
}
```

**JSON Patch** — add a note:

```shell
curl -s -X PATCH http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder/a1b2c3d4-... \
  -H "Content-Type: application/json-patch+json" \
  -d '[{"op": "add", "path": "/note", "value": "Escalated to L2"}]' | jq '{note, revision}'
```

```json
{
  "note": "Escalated to L2",
  "revision": 4
}
```

### 7. List Resources with Paging

```shell
curl -s "http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder?offset=0&limit=5" \
  -D- -o /dev/null 2>&1 | grep -iE "x-total-count|x-result-count|content-range"
```

```
X-Total-Count: 1
X-Result-Count: 1
Content-Range: items 1-1/1
```

### 8. Retry with `Idempotency-Key`

All mutating callbacks (POST, PUT, PATCH, DELETE) honour an `Idempotency-Key` request header.
A retry with the same key on the same `(method, path)` replays the original 2xx response with an
`X-Idempotent-Replay: true` marker, and the resource's TTL counter is reset on every replay so it
survives as long as the client keeps retrying. See the
[Idempotency-Key Handling](README.md#idempotency-key-handling) section in the README for the full
behaviour matrix.

```shell
KEY=$(uuidgen)

curl -s -X POST http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"description": "Install fibre"}' | jq .id

curl -s -i -X POST http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"description": "Install fibre"}' | grep -iE "x-idempotent-replay|HTTP/"
```

```
HTTP/1.1 201 Created
X-Idempotent-Replay: true
```

### 8. Verify a Request Was Received

```shell
curl -s -X PUT http://localhost:1080/mockserver/verify \
  -H "Content-Type: application/json" -d '{
  "httpRequest": {
    "method": "POST",
    "path": "/tmf-api/serviceOrdering/v4/serviceOrder"
  },
  "times": { "atLeast": 1 }
}'
# Returns 202 if the POST was received at least once, 406 otherwise.
```

### 9. Retrieve Active Expectations

```shell
curl -s -X PUT "http://localhost:1080/mockserver/retrieve?type=active_expectations" \
  -H "Content-Type: application/json" -d '{}' | jq '.[].httpRequest.path'
```

### 10. Delete a Resource

```shell
curl -s -X DELETE http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder/a1b2c3d4-... \
  -w "\nHTTP %{http_code}\n"
```

```
HTTP 204
```

### 11. Clear a Single Expectation

```shell
curl -s -X PUT "http://localhost:1080/mockserver/clear?type=expectations" \
  -H "Content-Type: application/json" \
  -d '{"path": "/tmf-api/serviceOrdering/v4/serviceOrder"}'
```

### 12. Reset Everything

```shell
curl -s -X PUT http://localhost:1080/mockserver/reset
```

Clears all expectations, recorded requests, and logs.

## Configuration

All original MockServer configuration properties (`mockserver.*` system properties and
`mockserver.properties` file) are still parsed and respected where the underlying feature is
included. Properties for excluded features (e.g. `mockserver.velocityDisallowClassLoading`,
`mockserver.javascriptDisallowedClasses`) are accepted but have no effect.

See the [MockServer documentation](https://www.mock-server.com/mock_server/configuration_properties.html)
for the full list of configuration properties.
