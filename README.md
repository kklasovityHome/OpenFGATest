# OpenFGA Spring Boot Demo

A Spring Boot application demonstrating a complete **dynamic RBAC + ABAC + ReBAC** authorization system built on [OpenFGA](https://openfga.dev/) using the [Java SDK](https://github.com/openfga/java-sdk).

The entire stack — Postgres, OpenFGA, and the Spring Boot app — runs with a single `docker compose up --build`.

---

## Stack

| Component | Version |
|---|---|
| Spring Boot | 4.0.3 |
| Java | 25 (runtime) / 21 (source) |
| OpenFGA | v1.8.3 |
| openfga-sdk (Java) | 0.9.7 |
| Postgres | 16 |

---

## Architecture

```
┌──────────────┐     REST      ┌─────────────────┐    HTTP/gRPC   ┌──────────────┐
│   Client     │ ────────────► │  Spring Boot    │ ─────────────► │   OpenFGA    │
│  (Postman    │               │  :8080          │                │   :8080      │
│   / curl)    │               └─────────────────┘                ��──────┬───────┘
└──────────────┘                                                          │
                                                                    ┌─────▼──────┐
                                                                    │  Postgres  │
                                                                    │  :5432     │
                                                                    └────────────┘
```

The Spring Boot service never stores authorization data itself — everything is delegated to OpenFGA. The app:
- Bootstraps the store and model on startup
- Seeds demo tuples (roles, resources, hierarchy, ABAC grants)
- Exposes a REST API for checks, tuple management, and store bootstrap

---

## Authorization Model

Three types, keeping the schema minimal — all policy specifics live in tuples:

```
user  ──assignee──►  role  ──{view|edit|delete|manage}_access──►  resource
                       │                                               │
                  granted_by                                        parent
                       │                                               │
                      role  (inheritance)                          resource (hierarchy)

group ──member──► role (groups as role assignees)
```

### Key design decisions

- **Role inheritance** via `granted_by`: `(role:editor, granted_by, role:viewer)` means editors also have all viewer permissions. Not the reverse.
- **Resource hierarchy** via `parent`: granting access on `/home` cascades down to all children automatically.
- **Groups** assign to roles, not directly to resources. A user gets a group's permissions by being a `member` of a group that is an `assignee` of a role.
- **ABAC conditions** are generic CEL expressions in the model; specific policy values (`start_hour`, `allowed_cidr`) live in the tuple's condition context — zero model changes for new rules.

### ABAC Conditions

Two built-in generic conditions (model-defined once, values tuned per-tuple):

| Condition | CEL Expression | Runtime param | Stored in tuple |
|---|---|---|---|
| `within_time_window` | `current_hour >= start_hour && current_hour < end_hour` | `current_hour` (auto-resolved from server UTC clock) | `start_hour`, `end_hour` |
| `within_ip_range` | `client_ip.matches(allowed_cidr)` | `client_ip` (must be supplied by caller) | `allowed_cidr` |

---

## Lazy ABAC Context Resolution

The service resolves ABAC context parameters **on demand**, not on every request:

1. **Pre-validation (Layer 1):** Before the first OpenFGA call, `RoleContextRequirementCache` reads the stored tuples for each contextual role (cached after first load), subtracts params already baked into the tuple (`start_hour`, `end_hour`, `allowed_cidr`) from the condition's full declaration, and resolves only the remaining runtime-required params via `AttributeResolverRegistry`. Roles with no conditional tuples pay zero overhead.

2. **Lazy retry (Layer 2):** If OpenFGA throws HTTP 400 `"missing context parameters [x]"` despite Layer 1 (e.g. a just-invalidated cache entry), the missing param names are parsed from the error message and resolved on demand.

Adding a new auto-resolvable parameter requires registering one `Supplier` in `AttributeResolverRegistry` — no other changes.

---

## Running

### Prerequisites

- Docker + Docker Compose

### Start everything

```bash
docker compose up --build
```

This starts Postgres, runs OpenFGA DB migrations, starts OpenFGA, builds the Spring Boot app, and starts it. On first boot the app:
1. Creates an OpenFGA store (`iam-demo`)
2. Writes the authorization model
3. Seeds demo tuples (resource tree, role grants, role inheritance, ABAC grants)

### Ports

| Service | Host port | Purpose |
|---|---|---|
| Spring Boot app | `8080` | REST API |
| OpenFGA HTTP | `8082` | Direct OpenFGA access / Playground UI |
| OpenFGA gRPC | `8083` | gRPC transport |
| Postgres | `5432` | Direct DB access |
| JVM debug | `5005` | Remote debugger (IntelliJ / IDEA) |

### Remote Debugging

Attach a remote debugger in IntelliJ:
1. **Run → Edit Configurations → + → Remote JVM Debug**
2. Host: `localhost`, Port: `5005`
3. Click **Debug** while the stack is running

The app starts with `suspend=n` — it boots normally without waiting for a debugger.

---

## REST API

### Authorization Checks

#### `POST /api/authz/check`

Check whether a user has a relation on a resource.

```json
{
  "userId": "user:alice",
  "relation": "can_edit",
  "objectType": "resource",
  "objectId": "/home/users/index.html",
  "contextualRoles": ["role:editor"],
  "contextualGroups": ["group:engineers"],
  "context": { "client_ip": "10.0.1.55" }
}
```

| Field | Required | Description |
|---|---|---|
| `userId` | ✅ | Subject in `type:id` format |
| `relation` | ✅ | `can_view`, `can_edit`, `can_delete`, `can_manage` |
| `objectType` | ✅ | Always `resource` in this model |
| `objectId` | ✅ | Resource path, e.g. `/home/config` |
| `contextualRoles` | ➖ | Roles injected from IdP JWT — ephemeral, never stored |
| `contextualGroups` | ➖ | Group memberships from IdP — ephemeral, never stored |
| `context` | ➖ | ABAC runtime values. `current_hour` is auto-injected if absent. `client_ip` must be supplied for IP-restricted roles. |

Response:
```json
{ "allowed": true }
```

#### `POST /api/authz/list-objects`

List all resources of a type the user can access.

```json
{
  "userId": "user:alice",
  "relation": "can_view",
  "objectType": "resource",
  "contextualRoles": ["role:admin"]
}
```

Response: `["resource:/home", "resource:/home/users", ...]`

#### `POST /api/authz/list-users`

List all users that have a relation on a resource.

```json
{
  "objectType": "resource",
  "objectId": "/home",
  "relation": "can_view",
  "userType": "user"
}
```

Response: `["user:alice", "user:bob"]`

---

### Tuple Management

#### `POST /api/tuples` — Write tuples

**Unconditional role grant:**
```json
[
  { "user": "role:editor", "relation": "edit_access", "object": "resource:/home/users" }
]
```

**Conditional grant (time-windowed):**
```json
[
  {
    "user": "role:night-auditor",
    "relation": "view_access",
    "object": "resource:/home/config",
    "conditionName": "within_time_window",
    "conditionContext": { "start_hour": 20, "end_hour": 6 }
  }
]
```

**Conditional grant (IP-restricted):**
```json
[
  {
    "user": "role:corp-editor",
    "relation": "edit_access",
    "object": "resource:/home",
    "conditionName": "within_ip_range",
    "conditionContext": { "allowed_cidr": "10\\..*" }
  }
]
```

**Role inheritance:**
```json
[
  { "user": "role:editor", "relation": "granted_by", "object": "role:viewer" }
]
```

**Group → role assignment:**
```json
[
  { "user": "group:engineers", "relation": "assignee", "object": "role:editor" }
]
```

**User → group assignment:**
```json
[
  { "user": "user:alice", "relation": "member", "object": "group:engineers" }
]
```

#### `DELETE /api/tuples` — Delete tuples

Same body format as write, without `conditionName`/`conditionContext`.

---

### Bootstrap

#### `POST /api/bootstrap`

Re-runs the full store + model + seed data initialization. Useful after wiping Postgres.

---

## Demo Data (seeded on startup)

### Resource tree

```
resource:/home
  ├── resource:/home/users
  │     └── resource:/home/users/index.html
  └── resource:/home/config
        └── resource:/home/config/index.html
```

### Role grants

| Role | Access | Resource |
|---|---|---|
| `role:admin` | `manage_access` | `/home` (cascades to all children) |
| `role:editor` | `edit_access` | `/home/users` (cascades to children) |
| `role:viewer` | `view_access` | `/home` (cascades to all children) |
| `role:night-auditor` | `view_access` | `/home/config` *(20:00–06:00 UTC only)* |
| `role:tester` | `view_access` | `/home` *(06:00–20:00 UTC only)* |

### Role inheritance

`role:editor` inherits from `role:viewer` — editors can also view everything a viewer can.

### User assignments

User assignments are **not stored** by default — pass them as `contextualRoles` in requests to simulate IdP JWT injection.

---

## Example Requests

### 1. Admin cascades to all children (stored assignment)

```bash
curl -s -X POST http://localhost:8080/api/tuples \
  -H 'Content-Type: application/json' \
  -d '[{"user":"user:alice","relation":"assignee","object":"role:admin"}]'

curl -s -X POST http://localhost:8080/api/authz/check \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user:alice","relation":"can_manage","objectType":"resource","objectId":"/home/users/index.html"}'
# → { "allowed": true }
```

### 2. Contextual role injection (no stored assignment)

```bash
curl -s -X POST http://localhost:8080/api/authz/check \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "user:frank",
    "relation": "can_edit",
    "objectType": "resource",
    "objectId": "/home/users/index.html",
    "contextualRoles": ["role:editor"]
  }'
# → { "allowed": true }
```

### 3. Time-windowed access (current_hour auto-resolved)

```bash
curl -s -X POST http://localhost:8080/api/authz/check \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "user:eve",
    "relation": "can_view",
    "objectType": "resource",
    "objectId": "/home/config",
    "contextualRoles": ["role:night-auditor"]
  }'
# → { "allowed": true } between 20:00–06:00 UTC, false otherwise
# current_hour is automatically resolved from the server clock
```

Override the clock for testing:
```bash
-d '{ ..., "context": { "current_hour": 21 } }'
```

### 4. IP-restricted access

```bash
curl -s -X POST http://localhost:8080/api/authz/check \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "user:bob",
    "relation": "can_edit",
    "objectType": "resource",
    "objectId": "/home",
    "contextualRoles": ["role:corp-editor"],
    "context": { "client_ip": "10.0.1.55" }
  }'
# → { "allowed": true } — IP matches the stored CIDR
```

### 5. Group-based access

```bash
# Grant role:viewer to group:auditors
curl -s -X POST http://localhost:8080/api/tuples \
  -H 'Content-Type: application/json' \
  -d '[{"user":"group:auditors","relation":"assignee","object":"role:viewer"}]'

# Check with contextual group membership (no stored user→group tuple needed)
curl -s -X POST http://localhost:8080/api/authz/check \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "user:carol",
    "relation": "can_view",
    "objectType": "resource",
    "objectId": "/home",
    "contextualGroups": ["group:auditors"]
  }'
# → { "allowed": true }
```

---

## Project Structure

```
src/main/java/com/example/fgasdk/
├── abac/
│   ├── AttributeResolver.java          # @FunctionalInterface for param resolvers
│   ├── AttributeResolverRegistry.java  # Registry: param name → Supplier<Object>
│   └── RoleContextRequirementCache.java# Per-role runtime param requirement cache
├── config/
│   ├── FgaClientConfig.java            # OpenFGA SDK client bean
│   └── FgaProperties.java              # openfga.* config properties
├── controller/
│   ├── AuthzController.java            # POST /api/authz/{check,list-objects,list-users}
│   ├── BootstrapController.java        # POST /api/bootstrap
│   └── TupleController.java            # POST/DELETE /api/tuples
├── dto/
│   ├── CheckRequest.java               # Check request (userId, relation, contextualRoles, ...)
│   ├── CheckResponse.java              # { allowed: bool }
│   ├── ListObjectsRequest.java
│   ├── ListUsersRequest.java
│   └── TupleRequest.java               # Tuple write/delete (user, relation, object, condition*)
├── init/
│   └── DataInitializer.java            # ApplicationRunner — bootstrap + seed on startup
├── model/
│   └── AuthorizationModels.java        # IAM_MODEL JSON constant (the FGA schema)
└── service/
    ├── FgaCheckService.java            # check(), listObjects(), listUsers() + ABAC resolution
    ├── FgaModelService.java            # writeModel(), readCurrentModel()
    ├── FgaStoreService.java            # createStore(), listStores()
    └── FgaTupleService.java            # writeTuples(), deleteTuples() + cache invalidation
```

---

## Configuration

All configuration is in `application.properties` and overridable via environment variables:

| Property | Env var | Default | Description |
|---|---|---|---|
| `openfga.api-url` | `OPENFGA_API_URL` | `http://localhost:8082` | OpenFGA server URL |
| `openfga.store-id` | `OPENFGA_STORE_ID` | *(empty — auto-created)* | Skip store creation if set |
| `openfga.model-id` | `OPENFGA_MODEL_ID` | *(empty — auto-written)* | Skip model write if set |
| `openfga.api-token` | `OPENFGA_API_TOKEN` | *(empty)* | Pre-shared key if auth enabled |
| `openfga.seed-data` | — | `true` | Set `false` after first run to skip re-seeding |

---

## Postman Collection

A Postman collection is included at `FGA-Check-Service.postman_collection.json`. Import it to test all endpoints with pre-built examples including ABAC context, contextual roles/groups, and tuple management.
