# opentmf-mockserver

General-purpose, [TMF-630](https://www.tmforum.org/resources/specification/tmf630-rest-api-design-guidelines-4-2-0/)
-compatible dynamic mock server built on top of [MockServer](https://github.com/mock-server/mockserver). Ships with *
*out-of-the-box Keycloak-like OIDC support** -- realms, clients, users, roles, real signed JWTs, JWKS, and token
enforcement are all included without any external dependencies.

> **MockServer feature matrix** -- This project embeds a subset of MockServer. For a detailed
> breakdown of what is included and what is intentionally excluded (OpenAPI, XML matching,
> dashboard, proxy, templates, etc.), see [MOCKSERVER.md](opentmf-mockserver/MOCKSERVER.md).

<!-- TOC -->

* [opentmf-mockserver](#opentmf-mockserver)
    * [Features](#features)
    * [Quick Start](#quick-start)
        * [Prerequisites](#prerequisites)
        * [Docker](#docker)
        * [Standalone](#standalone)
    * [Dynamic Callbacks](#dynamic-callbacks)
        * [POST (DynamicPostCallback)](#post-dynamicpostcallback)
        * [Bulk Create (DynamicJsonPatchCollectionCallback)](#bulk-create-dynamicjsonpatchcollectioncallback)
        * [PUT (DynamicPutCallback)](#put-dynamicputcallback)
        * [GET by ID (DynamicGetCallback)](#get-by-id-dynamicgetcallback)
        * [GET List (DynamicGetListCallback)](#get-list-dynamicgetlistcallback)
        * [JSON Patch (DynamicJsonPatchCallback)](#json-patch-dynamicjsonpatchcallback)
        * [Merge Patch (DynamicMergePatchCallback)](#merge-patch-dynamicmergepatchcallback)
        * [DELETE (DynamicDeleteCallback)](#delete-dynamicdeletecallback)
        * [Idempotency-Key Handling](#idempotency-key-handling)
    * [Keycloak Mock](#keycloak-mock)
        * [What You Get for Free](#what-you-get-for-free)
        * [Default Configuration](#default-configuration)
        * [Obtaining Tokens](#obtaining-tokens)
        * [OIDC Discovery and JWKS](#oidc-discovery-and-jwks)
        * [Custom Keycloak Configuration](#custom-keycloak-configuration)
    * [Keycloak Admin REST API](#keycloak-admin-rest-api)
        * [Admin Endpoints](#admin-endpoints)
        * [Admin API Examples](#admin-api-examples)
    * [Token Enforcement and Role-Based Access](#token-enforcement-and-role-based-access)
        * [Validating Against an External Keycloak](#validating-against-an-external-keycloak)
        * [Role Matrix](#role-matrix)
        * [Customizing Required Roles per HTTP Method](#customizing-required-roles-per-http-method)
        * [Customizing the Roles Claim Path](#customizing-the-roles-claim-path)
    * [Environment Variables](#environment-variables)
    * [Content-Range Calculations](#content-range-calculations)
    * [API Reference](#api-reference)
    * [Test Support (opentmf-mockserver-test-support)](#test-support-opentmf-mockserver-test-support)
    * [MockServer Feature Matrix](#mockserver-feature-matrix)
    * [Acknowledgments](#acknowledgments)
    * [Release Notes](#release-notes)

<!-- TOC -->

## Features

- **Zero-configuration TMF mocking** -- POST, GET, GET List, JSON Patch, Merge Patch, and DELETE with automatic `id`,
  `href`, state transitions, audit fields, and paging.
- **Built-in Keycloak mock** -- real RSA-signed JWTs, OIDC discovery, JWKS endpoint, Admin REST API, configurable realms
  / clients / users / roles / groups. No real Keycloak needed.
- **Token enforcement** -- optionally validate Bearer tokens on every request, with role-based access control (reader /
  writer / admin).
- **External IdP support** -- validate tokens against a real Keycloak (or any OIDC provider) via `JWKS_URI` or
  `TOKEN_ISSUER` auto-discovery.
- **TMF-630 compliant** -- Content-Range, X-Total-Count, X-Result-Count headers, status-field lifecycle, versioned
  entities, query parameters, jsonPath filters, `fields=` parameter.

## Quick Start

### Prerequisites

- **Java 17.x** — the build enforces `[17,18)` at Maven's `validate` phase. Newer JDKs
  are rejected.
- **Maven 3.9.x** — enforced as `[3.9,3.10)`.
- **Docker** — required only for the `docker` profile (image build + Trivy scan).

### Docker

```shell
# Build the image AND run a Trivy scan (all-severity HTML report + HIGH/CRITICAL
# gate with --ignore-unfixed). The build fails on any fixable HIGH/CRITICAL
# finding; accepted findings live in opentmf-mockserver/.trivyignore.
mvn -P docker clean package

# HTML report is written to opentmf-mockserver/target/trivy-report.html

# Run
docker run -p 1080:1080 local/opentmf-mockserver:<version>
```

The server starts on port 1080. Keycloak endpoints and JWKS are available immediately -- no extra setup needed.

### Standalone

```shell
# Build (produces target/opentmf-mockserver-*.jar + target/libs/)
mvn clean package

# Start
java -Dmockserver.initializationClass=org.opentmf.mockserver.callback.JwksExpectationInitializer \
  -cp target/opentmf-mockserver-*.jar:target/libs/* \
  org.mockserver.cli.Main -serverPort 1080
```

### Initializing Expectations from a File (JSON or YAML)

Expectations can be pre-loaded at startup from a file instead of being registered with
`PUT /mockserver/expectation`. Two properties exist; set either or both (each is also readable
as the environment variable in the second column):

| Property                            | Environment variable                  | File content                       |
|-------------------------------------|---------------------------------------|------------------------------------|
| `mockserver.initializationJsonPath` | `MOCKSERVER_INITIALIZATION_JSON_PATH` | JSON array of expectations         |
| `mockserver.initializationYamlPath` | `MOCKSERVER_INITIALIZATION_YAML_PATH` | the same document, written as YAML |

Both files hold the expectation model the `PUT /mockserver/expectation` API accepts; YAML is
parsed into exactly the same objects, so the two formats are interchangeable. The value of YAML
is comments: a hand-maintained file standing in for several backends can say which backend a
block belongs to and why a status code is expected. Both paths accept globs
(e.g. `/config/expectations-*.yaml`).

```yaml
# --- SMS gateway (Kannel) ---------------------------------------------
# 202, not 200: the gateway ACKs and delivers asynchronously.
- httpRequest:  { method: POST, path: /cgi-bin/sendsms }
  httpResponse: { statusCode: 202 }

# --- TMF service ordering: dynamic callbacks --------------------------
- httpRequest: { method: POST, path: /tmf-api/serviceOrdering/v4/serviceOrder }
  httpResponseClassCallback:
    callbackClass: org.opentmf.mockserver.callback.DynamicPostCallback
- httpRequest: { method: GET, path: /tmf-api/serviceOrdering/v4/serviceOrder/.* }
  httpResponseClassCallback:
    callbackClass: org.opentmf.mockserver.callback.DynamicGetCallback
```

```shell
docker run -p 1080:1080 \
  -e MOCKSERVER_INITIALIZATION_YAML_PATH=/config/expectations.yaml \
  -v /path/to/expectations.yaml:/config/expectations.yaml \
  ghcr.io/opentmf/opentmf-mockserver:<version>
```

The two initializers differ in how they treat a bad file:

- **YAML** — a path (or glob) that matches no file, or a file that does not parse into
  expectations, **fails startup** with the path in the message
  (`failed to load YAML initialization file "<path>" (mockserver.initializationYamlPath): ...`).
  The process exits with status 1, so a container crash-loops; it never comes up with a silently
  empty expectation set. The YAML file is not watched;
  restart to pick up changes.
- **JSON** — unchanged MockServer behaviour: a missing or invalid file is logged as a warning and
  skipped. `MOCKSERVER_WATCH_INITIALIZATION_JSON=true` hot-reloads it on change.

See [Initializing Expectations from a JSON File](opentmf-mockserver/MOCKSERVER.md#initializing-expectations-from-a-json-file)
for a complete callback bundle for one TMF domain.

## Dynamic Callbacks

All callbacks share these behaviors:

- Payloads are cached in-memory with a configurable TTL (default: 2 hours).
- GET, POST, and PATCH operations reset the eviction timer.
- `id`, `href`, `createdDate`, `createdBy`, `updatedDate`, `updatedBy`, `revision`, and state fields are managed
  automatically.
- Versioned entities are supported via `:(version=XYZ)` in the path or `?version=XYZ` query parameter.

Token and OIDC endpoints are auto-registered. TMF resource endpoints require an expectation per
domain; each subsection below shows the registration call alongside the callback's behavior and an
example exchange. The examples all use `/tmf-api/serviceOrdering/v4/serviceOrder` as the domain.

> **Tip:** Instead of registering each expectation with `PUT /mockserver/expectation` after
> startup, you can pre-load a batch from a JSON or YAML file via
> `MOCKSERVER_INITIALIZATION_JSON_PATH` / `MOCKSERVER_INITIALIZATION_YAML_PATH`. See
> [Initializing Expectations from a File](#initializing-expectations-from-a-file-json-or-yaml).

### POST (DynamicPostCallback)

**Register the expectation:**

```shell
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
    "httpRequest": { "method": "POST", "path": "/tmf-api/serviceOrdering/v4/serviceOrder" },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicPostCallback" }
  }'
```

**Behavior:**

- Generates a UUID `id` if not provided; returns 400 if the ID already exists.
- Sets `createdDate`, `createdBy`, `revision`, `href`, and the initial state field.
- Sets `version="0"` for versioned entities if not provided.
- Returns **201 Created**.

State field mapping by path:

| Type      | Field             | Initial        | Final       |
|-----------|-------------------|----------------|-------------|
| Orders    | `state`           | `acknowledged` | `completed` |
| Inventory | `status`          | `created`      | `active`    |
| Catalog   | `lifecycleStatus` | `inStudy`      | `inDesign`  |
| Candidate | `lifecycleStatus` | `inStudy`      | `inDesign`  |
| Default   | `state`           | `acknowledged` | `completed` |

**Example request:**

```shell
curl -s -X POST http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder \
  -H "Content-Type: application/json" \
  -d '{"description":"Install fibre to building 7","priority":"1"}'
```

**Example response** *(HTTP 201)*:

```json
{
  "id": "0QB98VRNHGM4G",
  "href": "/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G",
  "state": "acknowledged",
  "revision": 0,
  "createdDate": "2026-05-10T18:00:00.000Z",
  "createdBy": "anonymous",
  "description": "Install fibre to building 7",
  "priority": "1"
}
```

### Bulk Create (DynamicJsonPatchCollectionCallback)

Implements [TMF630 Part 1 §6.2 "Creating Multiple Resources"](https://www.tmforum.org/resources/specification/tmf630-rest-api-design-guidelines-4-2-0/).

**Register the expectation:**

```shell
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
    "httpRequest": { "method": "PATCH", "path": "/tmf-api/serviceOrdering/v4/serviceOrder",
                     "headers": { "Content-Type": ["application/json-patch+json"] } },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicJsonPatchCollectionCallback" }
  }'
```

**Behavior:**

- Bound to `PATCH /{basePath}` (the collection URL, **no id segment**) with
  `Content-Type: application/json-patch+json`.
- Body MUST be a non-empty JSON array of `{"op":"add", "path":"/", "value":{...}}` operations.
  Other ops or paths return **400**; an empty array or non-array body also returns **400**.
- Each `value` runs through the same flow as a single POST: `id` is generated when missing,
  `href` is set, the state/status field is defaulted, audit fields and `ADDITIONAL_FIELDS` are
  applied.
- Atomic per RFC 5789. A duplicate `id` within the batch -- or against an already-cached
  resource -- returns **409 Conflict** with no resources committed.
- Response is **200 OK** with a JSON array of the created resources. `?fields=none` projects
  each item to `id` and `href` only; `?fields=description,name` projects to those plus `id`
  and `href`.

**Example request:**

```shell
curl -s -X PATCH http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder \
  -H "Content-Type: application/json-patch+json" \
  -d '[
    {"op":"add","path":"/","value":{"description":"Install fibre to building 7"}},
    {"op":"add","path":"/","value":{"description":"Activate VPN tunnel"}}
  ]'
```

**Example response** *(HTTP 200)*:

```json
[
  {
    "id": "0QB98VRNHGM4G",
    "href": "/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G",
    "state": "acknowledged",
    "revision": 0,
    "createdDate": "2026-05-10T18:00:00.000Z",
    "createdBy": "anonymous",
    "description": "Install fibre to building 7"
  },
  {
    "id": "0QB98VRNHGM4H",
    "href": "/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4H",
    "state": "acknowledged",
    "revision": 0,
    "createdDate": "2026-05-10T18:00:00.000Z",
    "createdBy": "anonymous",
    "description": "Activate VPN tunnel"
  }
]
```

### PUT (DynamicPutCallback)

Implements [RFC 9110 §9.3.4](https://www.rfc-editor.org/rfc/rfc9110.html#name-put) PUT semantics:
the request body is the complete desired state of the resource at the URI, and the operation is
idempotent.

**Register the expectation:**

```shell
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
    "httpRequest": { "method": "PUT", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*" },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicPutCallback" }
  }'
```

**Behavior:**

- The id (and optional `:(version=XYZ)`) in the URI are authoritative. If the body contains an
  `id` or `version` that conflicts with the URI, returns **400**.
- If no cached payload matches the URI, the resource is **created**: `id`, `version` (defaulting
  to `"0"` for versioned entities when not supplied), `href`, the initial state field,
  `createdBy`/`createdDate`/`revision=0`, and any `ADDITIONAL_FIELDS` are populated. Returns
  **201 Created**.
- If a cached payload exists, it is **replaced** wholesale by the request body. `id`, `version`,
  `href`, `createdBy`, and `createdDate` are carried over from the existing entry; `revision` is
  incremented; `updatedBy`/`updatedDate` are stamped. Returns **200 OK**.
- Idempotent: replaying the same PUT converges to the same observable resource state (modulo
  `updatedDate`/`updatedBy`/`revision`, which by design record the update event).
- Non-object body or invalid JSON returns **400**.

**Example request** *(create — id not yet in cache)*:

```shell
curl -s -X PUT http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G \
  -H "Content-Type: application/json" \
  -d '{"description":"Install fibre to building 7","priority":"1"}'
```

**Example response** *(HTTP 201)*:

```json
{
  "id": "0QB98VRNHGM4G",
  "href": "/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G",
  "state": "acknowledged",
  "revision": 0,
  "createdDate": "2026-05-10T18:00:00.000Z",
  "createdBy": "anonymous",
  "description": "Install fibre to building 7",
  "priority": "1"
}
```

**Example request** *(replace — same URI, new body)*:

```shell
curl -s -X PUT http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G \
  -H "Content-Type: application/json" \
  -d '{"description":"Install fibre to building 8","priority":"2","state":"completed"}'
```

**Example response** *(HTTP 200)*:

```json
{
  "id": "0QB98VRNHGM4G",
  "href": "/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G",
  "state": "completed",
  "revision": 1,
  "createdDate": "2026-05-10T18:00:00.000Z",
  "createdBy": "anonymous",
  "updatedDate": "2026-05-10T18:00:05.000Z",
  "updatedBy": "anonymous",
  "description": "Install fibre to building 8",
  "priority": "2"
}
```

### GET by ID (DynamicGetCallback)

**Register the expectation:**

```shell
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
    "httpRequest": { "method": "GET", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*" },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicGetCallback" }
  }'
```

**Behavior:**

- Returns the cached payload for the given ID (404 if not found).
- On first GET, transitions the state field from initial to final value and sets `updatedDate`, `updatedBy`, `revision`.
- Supports `fields=` to project specific attributes; `id` and `href` are always included. The TMF-630
  sentinel `fields=none` (case-insensitive) projects the response to only `id` and `href`.
- Returns **200 OK**.

**Example request:**

```shell
curl -s http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G
```

**Example response** *(HTTP 200, after state transition on first GET)*:

```json
{
  "id": "0QB98VRNHGM4G",
  "href": "/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G",
  "state": "completed",
  "revision": 1,
  "createdDate": "2026-05-10T18:00:00.000Z",
  "createdBy": "anonymous",
  "updatedDate": "2026-05-10T18:00:01.000Z",
  "updatedBy": "anonymous",
  "description": "Install fibre to building 7"
}
```

### GET List (DynamicGetListCallback)

**Register the expectation:**

```shell
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
    "httpRequest": { "method": "GET", "path": "/tmf-api/serviceOrdering/v4/serviceOrder.*" },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicGetListCallback" }
  }'
```

**Behavior:**

- Returns all cached payloads for the domain, filtered/sorted/paged per TMF-630.
- Supports query parameters: `offset`, `limit`, `sort`, `fields`, and attribute-based filtering.
  `fields=none` (case-insensitive) projects each item to only `id` and `href`.
- Sets `X-Total-Count`, `X-Result-Count`, and `Content-Range` headers.
- Returns **200 OK** or **416 Range Not Satisfiable** if offset exceeds total count.

**Example request:**

```shell
curl -s -i 'http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder?offset=0&limit=2&sort=-createdDate'
```

**Example response** *(HTTP 200, headers + body)*:

```
X-Total-Count: 5
X-Result-Count: 2
Content-Range: items 1-2/5
Content-Type: application/json
```

```json
[
  {
    "id": "0QB98VRNHGM4G",
    "href": "/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G",
    "state": "completed",
    "description": "Install fibre to building 7"
  },
  {
    "id": "0QB98VRNHGM4H",
    "href": "/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4H",
    "state": "completed",
    "description": "Activate VPN tunnel"
  }
]
```

### JSON Patch (DynamicJsonPatchCallback)

**Register the expectation:**

```shell
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
    "httpRequest": { "method": "PATCH", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*",
                     "headers": { "Content-Type": ["application/json-patch+json"] } },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicJsonPatchCallback" }
  }'
```

**Behavior:**

- Applies an [RFC 6902](https://datatracker.ietf.org/doc/html/rfc6902) JSON Patch to the cached payload.
- Sets `updatedDate`, `updatedBy` and increments `revision`.
- Returns **200 OK** (or 404/400 on error).

**Example request:**

```shell
curl -s -X PATCH http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G \
  -H "Content-Type: application/json-patch+json" \
  -d '[{"op":"add","path":"/note","value":"Escalated to L2"}]'
```

**Example response** *(HTTP 200)*:

```json
{
  "id": "0QB98VRNHGM4G",
  "href": "/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G",
  "state": "completed",
  "revision": 2,
  "createdDate": "2026-05-10T18:00:00.000Z",
  "createdBy": "anonymous",
  "updatedDate": "2026-05-10T18:00:02.000Z",
  "updatedBy": "anonymous",
  "description": "Install fibre to building 7",
  "note": "Escalated to L2"
}
```

### Merge Patch (DynamicMergePatchCallback)

**Register the expectation:**

```shell
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
    "httpRequest": { "method": "PATCH", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*",
                     "headers": { "Content-Type": ["application/merge-patch+json"] } },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicMergePatchCallback" }
  }'
```

**Behavior:**

- Applies an [RFC 7396](https://datatracker.ietf.org/doc/html/rfc7396) JSON Merge Patch to the cached payload.
- Sets `updatedDate`, `updatedBy` and increments `revision`.
- Returns **200 OK** (or 404/400 on error).

**Example request:**

```shell
curl -s -X PATCH http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G \
  -H "Content-Type: application/merge-patch+json" \
  -d '{"priority":"2"}'
```

**Example response** *(HTTP 200)*:

```json
{
  "id": "0QB98VRNHGM4G",
  "href": "/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G",
  "state": "completed",
  "revision": 3,
  "createdDate": "2026-05-10T18:00:00.000Z",
  "createdBy": "anonymous",
  "updatedDate": "2026-05-10T18:00:03.000Z",
  "updatedBy": "anonymous",
  "description": "Install fibre to building 7",
  "priority": "2"
}
```

### DELETE (DynamicDeleteCallback)

**Register the expectation:**

```shell
curl -s -X PUT http://localhost:1080/mockserver/expectation \
  -H "Content-Type: application/json" -d '{
    "httpRequest": { "method": "DELETE", "path": "/tmf-api/serviceOrdering/v4/serviceOrder/.*" },
    "httpResponseClassCallback": { "callbackClass": "org.opentmf.mockserver.callback.DynamicDeleteCallback" }
  }'
```

**Behavior:**

- Removes the cached payload for the given ID (404 if not found).
- Returns **204 No Content**.

**Example request:**

```shell
curl -s -X DELETE -i http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G
```

**Example response** *(HTTP 204, no body)*:

```
HTTP/1.1 204 No Content
```

### Idempotency-Key Handling

Every mutating callback (POST, PUT, PATCH, DELETE) recognises the
[`Idempotency-Key` request header](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/).
Clients opt in by sending an opaque key (up to **255** characters); clients that don't send the
header behave exactly as before.

**Behavior:**

- On a successful (2xx) mutation, the server stashes the response under the request's
  `Idempotency-Key`. A retry of the **same `(HTTP method, request path)`** with the same key
  replays the original response verbatim, adds an **`X-Idempotent-Replay: true`** marker header,
  and resets ("touches") the underlying resource's cache TTL.
- A POST replay returns the original generated `id` and body — even though the second call would
  otherwise produce a fresh `id`.
- A DELETE replay still returns **204** even after the resource is gone (the recorded response
  outlives the deletion).
- A same key reused on a **different** `(method, path)` returns **422 Unprocessable Entity**.
- Keys longer than 255 characters return **400 Bad Request**.
- Records expire on the same TTL as `PayloadCache` (`CACHE_DURATION_MILLIS`, default 2 h) and are
  also dropped eagerly when the underlying resource is evicted by TTL — so a same-key retry after
  eviction is treated as a fresh request rather than replaying a stale 2xx for a resource that no
  longer exists.

**Example — POST retry replays the original 201:**

```shell
KEY=$(uuidgen)

# First call: creates the resource.
curl -s -X POST http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"description":"Install fibre"}'

# Retry with the same key: server replays the same body and id.
curl -s -i -X POST http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"description":"Install fibre"}'
# HTTP/1.1 201 Created
# X-Idempotent-Replay: true
# Content-Type: application/json
# {"id":"0QB98VRNHGM4G","href":"/tmf-api/.../0QB98VRNHGM4G",...}
```

**Example — DELETE retry replays 204:**

```shell
KEY=$(uuidgen)

curl -s -X DELETE -H "Idempotency-Key: $KEY" \
  http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G
# 204 No Content

curl -s -i -X DELETE -H "Idempotency-Key: $KEY" \
  http://localhost:1080/tmf-api/serviceOrdering/v4/serviceOrder/0QB98VRNHGM4G
# HTTP/1.1 204 No Content
# X-Idempotent-Replay: true
```

## Keycloak Mock

### What You Get for Free

On startup, the following endpoints are automatically registered for each configured realm (no expectations to create):

| Endpoint                                               | Description                                        |
|--------------------------------------------------------|----------------------------------------------------|
| `GET /realms/{realm}/protocol/openid-connect/certs`    | JWKS (public keys for token verification)          |
| `GET /realms/{realm}/.well-known/openid-configuration` | OIDC discovery document                            |
| `POST /realms/{realm}/protocol/openid-connect/token`   | Token endpoint (issue JWTs)                        |
| `GET /admin/realms/{realm}/...`                        | Keycloak Admin REST API (users, groups, roles, ...) |
| `GET /.well-known/jwks.json`                           | Global JWKS (backward-compatible)                  |
| `GET /mockserver/openapi`                              | OpenAPI 3.1 specification (YAML)                   |

All issued tokens are **real, parsable, RSA-signed JWTs** with Keycloak-compatible claims (`iss`, `sub`, `azp`,
`realm_access`, `resource_access`, `preferred_username`, `exp`, etc.).

### Default Configuration

The built-in default configuration provides a ready-to-use setup:

**Realm:** `realm1`

**Clients:**

| Client ID  | Type         | Secret          | Allowed Grants              |
|------------|--------------|-----------------|-----------------------------|
| `client1`  | Confidential | `client1Secret` | `client_credentials`        |
| `client2`  | Confidential | `client2Secret` | `password`                  |
| `uiClient` | Public       | --              | `password`, `refresh_token` |

**Groups:** `admins`, `developers` (sub-groups: `backend`, `frontend`), `viewers`

**Users:**

| Username     | Password     | Roles                       | Groups        |
|--------------|--------------|-----------------------------|---------------|
| `admin_usr`  | `admin_pwd`  | `admin`, `writer`, `reader` | `admins`      |
| `writer_usr` | `writer_pwd` | `writer`, `reader`          | `developers`  |
| `reader_usr` | `reader_pwd` | `reader`                    | `viewers`     |

### Obtaining Tokens

```shell
# client_credentials (service account)
curl -s -X POST 'http://localhost:1080/realms/realm1/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&client_id=client1&client_secret=client1Secret'

# password grant (user login)
curl -s -X POST 'http://localhost:1080/realms/realm1/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password&client_id=uiClient&username=admin_usr&password=admin_pwd'

# refresh_token grant
curl -s -X POST 'http://localhost:1080/realms/realm1/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=refresh_token&client_id=uiClient&refresh_token=<refresh_token_from_above>'
```

The response follows the standard OAuth 2.0 token response format:

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "refresh_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6...",
  "id_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6...",
  "scope": "openid profile email"
}
```

### OIDC Discovery and JWKS

```shell
# Discovery document
curl -s http://localhost:1080/realms/realm1/.well-known/openid-configuration | jq .

# JWKS (public keys)
curl -s http://localhost:1080/realms/realm1/protocol/openid-connect/certs | jq .
```

### Custom Keycloak Configuration

Override the default configuration by providing a JSON file:

```shell
# Docker
docker run -p 1080:1080 \
  -v /path/to/my-keycloak-config.json:/config/keycloak-mock.json \
  local/opentmf-mockserver:<version>

# Or via environment variable
docker run -p 1080:1080 \
  -e KEYCLOAK_CONFIG=/config/my-config.json \
  -v /path/to/my-config.json:/config/my-config.json \
  local/opentmf-mockserver:<version>
```

The JSON format:

```json
{
  "baseUrl": "http://localhost:1080",
  "realms": [
    {
      "name": "my-realm",
      "expiresIn": 1800,
      "roles": ["admin", "user"],
      "groups": [
        { "name": "team-a", "subGroups": ["backend", "frontend"] },
        { "name": "team-b", "subGroups": [] }
      ],
      "clients": [
        {
          "clientId": "my-app",
          "clientSecret": "secret",
          "publicClient": false,
          "expiresIn": 300,
          "allowedGrantTypes": ["client_credentials", "password"],
          "serviceAccountRoles": ["admin"]
        },
        {
          "clientId": "my-spa",
          "publicClient": true,
          "allowedGrantTypes": ["password", "refresh_token"]
        }
      ],
      "users": [
        {
          "username": "alice",
          "password": "alice123",
          "email": "alice@example.com",
          "firstName": "Alice",
          "lastName": "Smith",
          "roles": ["admin", "user"],
          "groups": ["team-a"]
        }
      ]
    }
  ]
}
```

The `expiresIn` field (in seconds) controls the lifetime of issued access and ID tokens. It can be set at
the realm level (applies to all clients) or per client (overrides the realm setting). If omitted, the default
is **3600** seconds (1 hour).

## Keycloak Admin REST API

A read-only subset of the [Keycloak Admin REST API](https://www.keycloak.org/docs-api/latest/rest-api/index.html) is
automatically registered for every configured realm. When token enforcement is enabled (`ENFORCE_TOKEN=true`), a valid
Bearer token with the `admin` role is required.

### Admin Endpoints

| Endpoint                                                   | Description                            |
|------------------------------------------------------------|----------------------------------------|
| `GET /admin/realms/{realm}`                                | Realm representation                   |
| `GET /admin/realms/{realm}/users`                          | List users (supports `username`, `search`, `first`, `max`) |
| `GET /admin/realms/{realm}/users/count`                    | User count                             |
| `GET /admin/realms/{realm}/users/{id}`                     | Single user by ID                      |
| `GET /admin/realms/{realm}/users/{id}/role-mappings/realm` | Realm roles for a user                 |
| `GET /admin/realms/{realm}/users/{id}/groups`              | Groups a user belongs to               |
| `GET /admin/realms/{realm}/groups`                         | List groups (supports `search`, `first`, `max`) |
| `GET /admin/realms/{realm}/groups/count`                   | Group count                            |
| `GET /admin/realms/{realm}/groups/{id}`                    | Single group by ID (includes sub-groups) |
| `GET /admin/realms/{realm}/groups/{id}/members`            | Members of a group                     |
| `GET /admin/realms/{realm}/roles`                          | List realm roles                       |
| `GET /admin/realms/{realm}/roles/{name}`                   | Single role by name                    |
| `GET /admin/realms/{realm}/roles/{name}/users`             | Users with a specific role             |
| `GET /admin/realms/{realm}/clients`                        | List clients                           |

All IDs are deterministic UUIDs derived from the realm and entity name, so they remain stable across restarts.

### Admin API Examples

```shell
# Get a token with admin role
TOKEN=$(curl -s -X POST 'http://localhost:1080/realms/realm1/protocol/openid-connect/token' \
  -d 'grant_type=client_credentials&client_id=client1&client_secret=client1Secret' | jq -r .access_token)

# List all users
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:1080/admin/realms/realm1/users | jq .

# Search users
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:1080/admin/realms/realm1/users?search=admin' | jq .

# List groups
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:1080/admin/realms/realm1/groups | jq .

# List realm roles
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:1080/admin/realms/realm1/roles | jq .

# Get users with a specific role
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:1080/admin/realms/realm1/roles/admin/users | jq .
```

## Token Enforcement and Role-Based Access

Enable token validation on all dynamic callbacks with a single environment variable:

```shell
docker run -p 1080:1080 -e ENFORCE_TOKEN=true local/opentmf-mockserver:<version>
```

When enabled, every request to a dynamic callback must include a valid `Authorization: Bearer <token>` header. The
token's signature, expiration, and (optionally) issuer are verified. In addition, the token's roles are checked against
the operation being performed.

### Validating Against an External Keycloak

You can point the enforcer at a real Keycloak (or any OIDC provider) instead of the built-in mock keys:

```shell
# Option 1: Explicit JWKS URI (takes precedence)
docker run -p 1080:1080 \
  -e ENFORCE_TOKEN=true \
  -e JWKS_URI=https://keycloak.example.com/realms/myrealm/protocol/openid-connect/certs \
  -e TOKEN_ISSUER=https://keycloak.example.com/realms/myrealm \
  local/opentmf-mockserver:<version>

# Option 2: OIDC auto-discovery (JWKS URI is resolved from the issuer's discovery endpoint)
docker run -p 1080:1080 \
  -e ENFORCE_TOKEN=true \
  -e TOKEN_ISSUER=https://keycloak.example.com/realms/myrealm \
  local/opentmf-mockserver:<version>
```

When `TOKEN_ISSUER` is set, the `iss` claim in the token is also validated against it.

### Role Matrix

By default, roles are extracted from the JWT's `realm_access.roles` claim (Keycloak standard); when
that claim is absent, a top-level `roles` claim is used as a fallback.

| HTTP Method | Default Required Role (any of) | Configurable via |
|-------------|--------------------------------|------------------|
| GET         | `reader`, `writer`, `admin`    | `ROLES_GET`      |
| POST        | `writer`, `admin`              | `ROLES_POST`     |
| PUT         | `writer`, `admin`              | `ROLES_PUT`      |
| PATCH       | `writer`, `admin`              | `ROLES_PATCH`    |
| DELETE      | `admin`                        | `ROLES_DELETE`   |

The GET List endpoint shares `ROLES_GET` with single-resource GET. Insufficient roles return
**403 Forbidden**; missing/invalid tokens return **401 Unauthorized**.

### Customizing Required Roles per HTTP Method

Each method's required roles can be overridden with the matching env var. The value is a
comma-separated list -- a request whose token carries **any one** of the listed roles passes.

```shell
docker run -p 1080:1080 \
  -e ENFORCE_TOKEN=true \
  -e TOKEN_ISSUER=https://keycloak.example.com/realms/myrealm \
  -e ROLES_GET=service-reader,service-admin \
  -e ROLES_POST=service-writer,service-admin \
  -e ROLES_DELETE=service-admin \
  local/opentmf-mockserver:latest
```

Setting an env var to the **empty string** disables the role check for that method, keeping only
signature/expiry/issuer validation:

```shell
# Anyone with a signature-valid token can GET; writes still require the default writer/admin
-e ROLES_GET=
```

The internal Keycloak Admin REST API endpoints (`/admin/realms/...`) always require `admin` and
are not affected by these variables.

### Customizing the Roles Claim Path

If your Keycloak (or other IdP) emits roles at a different location than `realm_access.roles`, set
`ROLES_CLAIM_PATH` to a dotted JSON path into the token payload. The leaf must resolve to a JSON
array of strings.

```shell
# Keycloak client roles for a specific client
-e ROLES_CLAIM_PATH=resource_access.my-client.roles

# Flat list at top level
-e ROLES_CLAIM_PATH=groups

# Auth0-style namespaced custom claim
-e ROLES_CLAIM_PATH=https://example.com/roles
```

When `ROLES_CLAIM_PATH` is set, the default `realm_access.roles` / top-level `roles` fallback chain
is **not** consulted -- the configured path is the single source of truth. If any segment of the
path is missing in the token, or the leaf is not an array of strings, the token is treated as
having no roles (which means **403** for any method whose `ROLES_*` list is non-empty).

Note: client IDs that contain dots (e.g. `resource_access.foo.bar.com.roles`) are not supported by
the dotted-path parser; the dots would be interpreted as path separators.

## Environment Variables

| Variable                              | Default                      | Description                                                       |
|---------------------------------------|------------------------------|-------------------------------------------------------------------|
| `SERVER_PORT`                         | `1080`                       | MockServer listen port                                            |
| `CACHE_DURATION_MILLIS`               | `7200000` (2h)               | Payload cache TTL in milliseconds                                 |
| `ADDITIONAL_FIELDS`                   | --                           | Comma-separated `key` or `key=value` pairs added to POST payloads |
| `CONTENT_RANGE_OFFSET_BASE`           | `1`                          | `0` or `1` -- base for Content-Range offset calculation           |
| `MOCKSERVER_INITIALIZATION_JSON_PATH` | --                           | Path (or glob) to a JSON file with an array of expectations to load on startup |
| `MOCKSERVER_WATCH_INITIALIZATION_JSON`| `false`                      | `true` to hot-reload the initialization file when it changes      |
| `KEYCLOAK_CONFIG`                     | `/config/keycloak-mock.json` | Path to Keycloak mock configuration JSON                          |
| `ENFORCE_TOKEN`                       | `false`                      | `true` to require valid Bearer JWT on all dynamic callbacks       |
| `TOKEN_ISSUER`                        | --                           | Expected `iss` claim; also enables OIDC auto-discovery            |
| `JWKS_URI`                            | --                           | Explicit JWKS endpoint URL (takes precedence over discovery)      |
| `ROLES_CLAIM_PATH`                    | --                           | Dotted path to the roles array in the token (e.g. `resource_access.my-client.roles`). Unset = `realm_access.roles` with top-level `roles` fallback |
| `ROLES_GET`                           | `reader,writer,admin`        | Comma-separated roles accepted on GET (single and list). Empty = skip role check |
| `ROLES_POST`                          | `writer,admin`               | Comma-separated roles accepted on POST. Empty = skip role check   |
| `ROLES_PUT`                           | `writer,admin`               | Comma-separated roles accepted on PUT. Empty = skip role check    |
| `ROLES_PATCH`                         | `writer,admin`               | Comma-separated roles accepted on PATCH (merge / JSON Patch / collection). Empty = skip role check |
| `ROLES_DELETE`                        | `admin`                      | Comma-separated roles accepted on DELETE. Empty = skip role check |

## Content-Range Calculations

The `Content-Range` header follows the TMF-630 REST API Design Guidelines:

`Content-Range: items <start>-<end>/<total>`

Given 23 items in the domain (1-based offset, the default):

| Offset | Limit | Status | Content-Range    |
|:------:|:-----:|:------:|------------------|
|   0    |  10   |  200   | `items 1-10/23`  |
|   10   |  10   |  200   | `items 11-20/23` |
|   20   |  10   |  200   | `items 21-23/23` |
|   30   |  10   |  416   | `items */23`     |

Set `CONTENT_RANGE_OFFSET_BASE=0` for zero-based offset values. Default offset is 0, default limit is 10.

## API Reference

The full API is documented in [opentmf-mockserver-openapi.yaml](opentmf-mockserver/opentmf-mockserver-openapi.yaml) (OpenAPI 3.1). It
covers all three
endpoint groups: the MockServer control plane, the Keycloak OIDC mock, and the dynamic TMF
resource callbacks.

The OpenAPI spec is also served at runtime:

```
GET /mockserver/openapi
```

You can point any OpenAPI viewer (e.g. [Swagger Editor](https://editor.swagger.io),
VS Code OpenAPI extension) directly at `http://localhost:1080/mockserver/openapi`.

## Test Support (`opentmf-mockserver-test-support`)

A sibling artifact `org.opentmf.mockserver:opentmf-mockserver-test-support` ships fluent
JUnit 5 helpers over the same server. Consumer integration tests should stop hand-rolling
the `ClientAndServer` + `Dynamic*Callback` + JWKS + `@DynamicPropertySource` glue and use
this module instead. One implementation, versioned in lockstep with the server.

**→ Full usage guide: [docs/TEST_SUPPORT.md](docs/TEST_SUPPORT.md)** — dependency setup,
per-class vs JVM-shared modes, Spring Boot IT recipe, full builder references (TMF /
stub / verify / OIDC), non-JUnit usage, migration recipe for existing hand-rolled
harnesses, and troubleshooting.

Quick taste:

```java
@SpringBootTest
class DocumentServiceIT {

  @RegisterExtension
  static MockServerSupport mock = MockServerSupport.shared();   // one server, whole JVM

  @DynamicPropertySource
  static void redirect(DynamicPropertyRegistry registry) {
    mock.redirectApiClients(registry, "onedms");    // opentmf.api-clients.onedms.* → mock
    mock.redirectJwks(registry);                    // opentmf.security.jwk-set-uri → mock
  }

  @Test
  void archiveDocument_returns201() {
    mock.tmf("onedms").crud("/document");                        // register Dynamic* callbacks
    mock.stub().get("/kba/{key}").respondJson(200, "{\"v\":1}"); // static stub
    // ... exercise the service under test ...
    mock.verify().post("/document").times(1);                    // fluent verification
  }
}
```

Use `MockServerSupport.create()` for per-test-class isolation, or `MockServerSupport.shared()`
to reuse one JVM-wide instance across every test class (recommended for projects with many
ITs). See the [full guide](docs/TEST_SUPPORT.md#two-lifecycle-modes) for trade-offs.

## MockServer Feature Matrix

This project embeds a tailored subset of MockServer. For a detailed breakdown of included
features (HTTP mocking, JSON matching, TLS, callbacks, forwarding) and excluded features
(OpenAPI, XML/XPath matching, dashboard UI, SOCKS proxy, template engines, Prometheus), see
[MOCKSERVER.md](opentmf-mockserver/MOCKSERVER.md).

## Acknowledgments

This project includes source code from [MockServer](https://github.com/mock-server/mockserver)
(v5.15.0) by James D Bloom, licensed under the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). The `org.mockserver` package
contains code originally from the `mockserver-core`, `mockserver-netty`, and
`mockserver-client-java` modules of that project. The code has been modified to migrate from
Jackson 2.x to Jackson 3.x and to remove features not needed for TMF mock usage (UI dashboard,
proxy/SOCKS, template engines, XML/XPath body matching, OpenAPI/Swagger, and Prometheus metrics).

## Release Notes

See [CHANGELOG.md](CHANGELOG.md) for a detailed list of changes per version.
