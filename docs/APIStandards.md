# TradeMesh REST API Standards

This document defines the REST API standards and best practices for the TradeMesh platform. It serves as the authoritative guide for designing, implementing, and maintaining REST endpoints across the system.

**Audience:** Experienced backend engineers

---

## Table of Contents

1. [Purpose](#purpose)
2. [API Design Principles](#api-design-principles)
3. [URL Standards](#url-standards)
4. [HTTP Methods](#http-methods)
5. [HTTP Status Codes](#http-status-codes)
6. [Request Standards](#request-standards)
7. [Response Standards](#response-standards)
8. [Error Handling](#error-handling)
9. [DTO Standards](#dto-standards)
10. [Swagger / OpenAPI Standards](#swagger--openapi-standards)
11. [Versioning Strategy](#versioning-strategy)
12. [Security](#security)
13. [Performance](#performance)
14. [Market Intelligence API Guidelines](#market-intelligence-api-guidelines)
15. [Architectural Rules](#architectural-rules)
16. [Current Compliance](#current-compliance)

---

## Purpose

The REST API design for TradeMesh serves the following goals:

- **Consistency:** All APIs follow uniform patterns for naming, structure, and behavior
- **Simplicity:** Endpoints are intuitive and easy to understand for both humans and machines
- **Predictability:** Clients can reliably predict API behavior based on HTTP semantics
- **Discoverability:** APIs are self-documenting through OpenAPI/Swagger specifications
- **Maintainability:** Clear standards reduce cognitive load and enable rapid onboarding
- **Scalability:** Stateless design and reactive patterns support high concurrency
- **Reliability:** Structured error handling and validation ensure robust operations

---

## API Design Principles

### Consistency

All REST APIs follow consistent patterns:

- **Naming:** Use lowercase, hyphenated paths (e.g., `/paper-trades`, `/market-intelligence`)
- **Structure:** Endpoints follow resource-oriented design with clear hierarchies
- **Behavior:** Same HTTP method always performs the same type of operation across all endpoints
- **Response Format:** All responses use consistent JSON structure with metadata

### Simplicity

- Endpoints expose business concepts, not implementation details
- Avoid deeply nested paths (max 3 levels: `/resource/id/sub-resource`)
- Use query parameters for filtering, sorting, and pagination
- Keep request/response payloads focused and minimal

### Predictability

- **Stateless Design:** Each request contains all necessary context; no session state on server
- **Idempotency:** GET, HEAD, OPTIONS, and DELETE operations are idempotent
- **Safe Operations:** GET requests never modify server state
- **Consistent Semantics:** HTTP methods always follow REST conventions

### Versioning Strategy

**Current Approach:** No explicit API versioning in URL paths

**Rationale:**
- TradeMesh is a single-tenant, internal trading platform
- All clients are updated simultaneously with the application
- Breaking changes are managed through coordinated deployments
- If versioning becomes necessary in the future, use header-based versioning (`Accept: application/vnd.trademesh.v1+json`) rather than URL paths

**Future Consideration:** If multi-tenant or third-party integrations are introduced, migrate to header-based versioning.

### Stateless Design

- No session state stored on the server
- Authentication tokens (Zerodha access tokens) stored in Redis with TTL
- Each request is independent and can be processed by any server instance
- Enables horizontal scaling and load balancing

### Idempotency

- **GET:** Always safe and idempotent; returns same result on repeated calls
- **POST:** Creates new resources; not idempotent (repeated calls create multiple resources)
- **PUT:** Replaces entire resource; idempotent (repeated calls produce same result)
- **PATCH:** Partial updates; should be idempotent when possible
- **DELETE:** Removes resource; idempotent (deleting non-existent resource returns 404)

### REST Resource Naming

- Use **plural nouns** for collections: `/paper-trades`, `/strategies`, `/positions`
- Use **singular nouns** for specific resources: `/paper-trades/{id}`, `/intelligence/{symbol}`
- Use **hierarchical paths** for sub-resources: `/paper-trades/{id}/close`, `/trading/positions/open`
- Avoid verbs in URLs; use HTTP methods instead
- Use hyphens (not underscores) for multi-word paths

---

## URL Standards

### Base Path Conventions

All API endpoints are prefixed with the application context path (typically `/`).

**Endpoint Groups:**

| Group | Base Path | Purpose |
|-------|-----------|---------|
| Paper Trading | `/paper/trades` | View and manage simulated trades |
| Market Intelligence | `/intelligence` | Market analysis and metrics |
| Strategy | `/strategy` | Strategy evaluation and status |
| Trading | `/trading` | Manual trading operations |
| Market Data | `/market` | Live price feeds and streams |
| Admin | `/admin` | Administrative operations |
| Zerodha Auth | `/zerodha` | OAuth2 authentication |

### Resource Naming

- **Plural for collections:** `/paper/trades`, `/strategies`
- **Singular for specific items:** `/paper/trades/{id}`, `/intelligence/{symbol}`
- **Hierarchical for sub-resources:** `/paper/trades/{id}/close`, `/trading/positions/open`

### Plural vs Singular Resources

**Use Plural:**
- Collection endpoints: `GET /paper/trades` (list all trades)
- Sub-resource collections: `GET /paper/trades/{id}/legs` (list legs of a trade)

**Use Singular:**
- Specific resource: `GET /paper/trades/{id}` (get one trade)
- Singleton resources: `GET /market/status` (single status object)

### Path Variables

- Use **UUIDs** for primary keys: `/paper/trades/{id}` where `id` is a UUID
- Use **business identifiers** for domain concepts: `/intelligence/{symbol}` where `symbol` is "NIFTY50"
- Use **hyphens** in path variable names: `/paper-trades/{trade-id}` (not `/paper_trades/{trade_id}`)

### Query Parameters

Query parameters are used for:

- **Filtering:** `?symbol=NIFTY50&status=OPEN`
- **Sorting:** `?sort=entryTime:desc`
- **Pagination:** `?page=1&size=20`
- **Optional behavior:** `?includeMetadata=true`

**Naming Convention:**
- Use camelCase for query parameter names: `?pageSize=20`, `?sortBy=date`
- Use consistent names across all endpoints

### Filtering

- Use query parameters for filtering: `GET /paper/trades?symbol=NIFTY50&status=OPEN`
- Support multiple values: `?status=OPEN,CLOSED`
- Use standard operators: `?pnl:gt=100` (greater than), `?pnl:lt=-50` (less than)

**Current Implementation:**
- Filtering is implemented at the service layer
- Controllers accept query parameters and pass them to services
- Example: `GET /paper/trades/symbol/{symbol}` filters by symbol in path

### Sorting

- Use `sort` query parameter: `?sort=entryTime:desc,symbol:asc`
- Default sort order is ascending unless specified otherwise
- Support multiple sort fields

**Current Implementation:**
- Sorting is handled by repository queries
- Example: `findAllOpenTrades()` returns trades in insertion order

### Pagination

- Use `page` and `size` query parameters: `?page=0&size=20`
- Return pagination metadata in response headers or body
- Default page size: 20 items

**Current Implementation:**
- TradeMesh uses reactive streams (Flux/Mono) instead of traditional pagination
- Endpoints return `Flux<T>` for collections (streaming results)
- Pagination can be added via `skip()` and `take()` operators if needed

---

## HTTP Methods

### GET

**Purpose:** Retrieve data without modifying server state

**Characteristics:**
- Safe (no side effects)
- Idempotent (repeated calls return same result)
- Cacheable
- No request body

**Examples:**
- `GET /paper/trades` — List all trades
- `GET /paper/trades/{id}` — Get specific trade
- `GET /intelligence/{symbol}` — Get market intelligence
- `GET /market/price/{symbol}` — Get current price

**Response:** 200 OK with response body

### POST

**Purpose:** Create new resources or trigger actions

**Characteristics:**
- Not safe (modifies server state)
- Not idempotent (repeated calls create multiple resources)
- Not cacheable
- May have request body

**Examples:**
- `POST /paper/trades/{id}/close` — Close a trade
- `POST /trading/run/{symbol}` — Trigger trading cycle
- `POST /admin/bootstrap/historical-prices` — Bootstrap data

**Response:** 201 Created (for resource creation) or 200 OK (for actions)

### PUT

**Purpose:** Replace entire resource

**Characteristics:**
- Not safe (modifies server state)
- Idempotent (repeated calls produce same result)
- Not cacheable
- Requires request body

**Current Usage:** Not used in TradeMesh (no update endpoints)

**Response:** 200 OK or 204 No Content

### PATCH

**Purpose:** Partial update of resource

**Characteristics:**
- Not safe (modifies server state)
- Should be idempotent when possible
- Not cacheable
- Requires request body

**Current Usage:** Not used in TradeMesh

**Response:** 200 OK or 204 No Content

### DELETE

**Purpose:** Remove resource

**Characteristics:**
- Not safe (modifies server state)
- Idempotent (deleting non-existent resource returns 404)
- Not cacheable
- No request body

**Current Usage:** Not used in TradeMesh (trades are closed, not deleted)

**Response:** 204 No Content or 200 OK

---

## HTTP Status Codes

### Success Codes

| Code | Name | Usage |
|------|------|-------|
| 200 | OK | Successful GET, POST, or PUT request |
| 201 | Created | Resource successfully created (not used in TradeMesh) |
| 204 | No Content | Successful DELETE or action with no response body |

### Redirection Codes

| Code | Name | Usage |
|------|------|-------|
| 302 | Found | Redirect to Zerodha login page (`GET /zerodha/login`) |
| 304 | Not Modified | Cached response is still valid (not used) |

### Client Error Codes

| Code | Name | Usage |
|------|------|-------|
| 400 | Bad Request | Invalid request parameters or validation failure |
| 401 | Unauthorized | Missing or invalid authentication token |
| 403 | Forbidden | Authenticated but not authorized for resource |
| 404 | Not Found | Resource does not exist |
| 409 | Conflict | Request conflicts with current state (e.g., closing non-open trade) |
| 422 | Unprocessable Entity | Request is well-formed but contains semantic errors |
| 429 | Too Many Requests | Rate limit exceeded |

### Server Error Codes

| Code | Name | Usage |
|------|------|-------|
| 500 | Internal Server Error | Unexpected server error |
| 502 | Bad Gateway | Upstream service unavailable (Zerodha API) |
| 503 | Service Unavailable | Server temporarily unavailable or circuit breaker open |
| 504 | Gateway Timeout | Upstream service timeout |

---

## Request Standards

### JSON Naming Conventions

- **Field Names:** Use camelCase (not snake_case or PascalCase)
  - ✅ `entryCredit`, `strategyName`, `unrealizedPnl`
  - ❌ `entry_credit`, `strategy_name`, `EntryCredit`

- **Enum Values:** Use UPPER_SNAKE_CASE
  - ✅ `"status": "OPEN"`, `"signalType": "ENTER"`
  - ❌ `"status": "open"`, `"signalType": "enter"`

- **Boolean Fields:** Use `is` prefix for clarity
  - ✅ `"isSafeToTrade": true`, `"isExecutable": false`
  - ❌ `"safeToTrade": true`, `"executable": false`

### Validation Rules

**Input Validation:**
- Validate all request parameters before passing to service layer
- Return 400 Bad Request with detailed error messages for validation failures
- Validate data types, ranges, and required fields

**Example:**
```json
{
  "status": 400,
  "message": "Validation failed",
  "errors": [
    {
      "code": "INVALID_SYMBOL",
      "message": "Symbol must be NIFTY50 or NIFTYBANK",
      "context": {"field": "symbol", "value": "INVALID"}
    }
  ]
}
```

### Required vs Optional Fields

- **Required Fields:** Always present in request/response
- **Optional Fields:** May be null or omitted
- Document which fields are required in OpenAPI spec

**Convention:**
- Request DTOs use `@NotNull` and `@NotBlank` annotations
- Response DTOs include all fields (null if not available)

### Date/Time Formats

- **ISO 8601 Format:** `2026-06-29T23:01:00Z` (UTC with timezone)
- **Local Date:** `2026-06-29` (YYYY-MM-DD)
- **Local Time:** `23:01:00` (HH:mm:ss)

**Java Types:**
- `LocalDateTime` → ISO 8601 string with timezone
- `LocalDate` → YYYY-MM-DD string
- `Instant` → ISO 8601 UTC string

**Example:**
```json
{
  "entryTime": "2026-06-29T09:30:00+05:30",
  "tradeDate": "2026-06-29",
  "generatedAt": "2026-06-29T23:01:00Z"
}
```

### UUID Usage

- Use **UUID v4** for all primary keys and unique identifiers
- Format: `550e8400-e29b-41d4-a716-446655440000`
- Always use string representation in JSON (not binary)

**Example:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "signalId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

### Enum Handling

- Use **string values** for enums in JSON (not numeric codes)
- Use **UPPER_SNAKE_CASE** for enum values
- Document all possible values in OpenAPI spec

**Example:**
```json
{
  "status": "OPEN",
  "signalType": "ENTER",
  "confidence": "HIGH",
  "marketCondition": "TRENDING_BULLISH"
}
```

**Supported Enums:**
- `TradeStatus`: OPEN, CLOSED, CANCELLED
- `SignalType`: ENTER, EXIT, ADJUST
- `Confidence`: LOW, MEDIUM, HIGH
- `MarketCondition`: SIDEWAYS, TRENDING_BULLISH, TRENDING_BEARISH, EXPIRY_DAY, HIGH_VOLATILITY, NO_TRADE

---

## Response Standards

### Response Structure

All successful responses follow a consistent structure:

**For Single Resources:**
```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "symbol": "NIFTY50",
    "entryCredit": 150.50
  },
  "timestamp": "2026-06-29T23:01:00+05:30",
  "requestId": "abc-123-def"
}
```

**For Collections (Flux):**
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "symbol": "NIFTY50"
  },
  {
    "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "symbol": "BANKNIFTY"
  }
]
```

**For Streaming (Server-Sent Events):**
```
data: {"symbol":"NIFTY50","lastPrice":21500.50,"timestamp":"2026-06-29T23:01:00Z"}
data: {"symbol":"NIFTY50","lastPrice":21501.00,"timestamp":"2026-06-29T23:01:01Z"}
```

### DTO Usage

**Response DTOs:**
- Never return JPA entities directly
- Always map entities to DTOs before returning
- DTOs contain only fields needed by the client
- DTOs are immutable (use `@Data` with `@Builder`)

**Example:**
```java
// ❌ Wrong: Returning entity
@GetMapping("/{id}")
public Mono<ResponseEntity<PaperTrade>> getTrade(@PathVariable UUID id) {
    return repository.findById(id)
            .map(ResponseEntity::ok);
}

// ✅ Correct: Returning DTO
@GetMapping("/{id}")
public Mono<ResponseEntity<PaperTradeDTO>> getTrade(@PathVariable UUID id) {
    return repository.findById(id)
            .map(trade -> new PaperTradeDTO(trade))
            .map(ResponseEntity::ok);
}
```

### Error Responses

See [Error Handling](#error-handling) section for detailed error response format.

### Success Responses

**Single Resource:**
```json
{
  "status": 200,
  "message": "Market intelligence retrieved successfully",
  "data": {
    "symbol": "NIFTY50",
    "spotPrice": 21500.50,
    "vixData": {...},
    "marketCondition": "TRENDING_BULLISH",
    "isSafeToTrade": true
  },
  "timestamp": "2026-06-29T23:01:00+05:30",
  "requestId": "abc-123-def"
}
```

**Collection (Streaming):**
```json
[
  {"id": "...", "symbol": "NIFTY50", "status": "OPEN"},
  {"id": "...", "symbol": "BANKNIFTY", "status": "CLOSED"}
]
```

### Pagination Responses

**Current Implementation:** Uses reactive streams (Flux) instead of traditional pagination

**If Pagination is Needed:**
```json
{
  "status": 200,
  "data": [
    {"id": "...", "symbol": "NIFTY50"},
    {"id": "...", "symbol": "BANKNIFTY"}
  ],
  "pagination": {
    "page": 0,
    "size": 20,
    "totalElements": 150,
    "totalPages": 8,
    "hasNext": true,
    "hasPrevious": false
  }
}
```

### Response Wrapper Recommendation

**Decision:** Use response wrappers for single resources, but NOT for collections

**Rationale:**

**For Single Resources (Recommended):**
- Provides consistent metadata (timestamp, requestId, status)
- Enables structured error handling
- Allows adding metadata without breaking client contracts
- Example: `GET /intelligence/{symbol}` returns wrapped response

**For Collections (Not Recommended):**
- Streaming responses (Flux) are more efficient
- Clients expect arrays for list endpoints
- Pagination can be added via query parameters if needed
- Example: `GET /paper/trades` returns `Flux<PaperTrade>` (unwrapped)

**Implementation:**
- Controllers returning `Mono<ResponseEntity<T>>` use wrappers
- Controllers returning `Flux<T>` do not use wrappers
- Consistent with Spring WebFlux reactive patterns

---

## Error Handling

### Error Response Format

All error responses follow a consistent structure:

```json
{
  "status": 400,
  "message": "Validation failed",
  "path": "/paper/trades/invalid-id/close",
  "timestamp": "2026-06-29T23:01:00+05:30",
  "requestId": "abc-123-def-456",
  "errors": [
    {
      "code": "INVALID_UUID",
      "message": "Trade ID must be a valid UUID",
      "context": {
        "field": "id",
        "value": "invalid-id",
        "pattern": "UUID v4"
      }
    }
  ]
}
```

### Error Response Fields

| Field | Type | Description |
|-------|------|-------------|
| `status` | int | HTTP status code |
| `message` | string | Human-readable error message |
| `path` | string | Request path that caused the error |
| `timestamp` | string | ISO 8601 timestamp when error occurred |
| `requestId` | string | Unique request ID for tracing |
| `errors` | array | List of error details |

### Error Detail Fields

| Field | Type | Description |
|-------|------|-------------|
| `code` | string | Machine-readable error code |
| `message` | string | Human-readable error message |
| `context` | object | Additional context (field name, value, etc.) |
| `stackTrace` | string | Stack trace (development only) |

### Error Codes

**Validation Errors:**
- `INVALID_ARGUMENT` — Invalid request parameter
- `INVALID_UUID` — UUID format invalid
- `INVALID_SYMBOL` — Symbol not supported
- `INVALID_DATE_RANGE` — Date range invalid

**Business Logic Errors:**
- `TRADE_NOT_OPEN` — Cannot close non-open trade
- `POSITION_NOT_FOUND` — Position does not exist
- `RISK_LIMIT_EXCEEDED` — Risk limit violation
- `DAILY_LOSS_LIMIT_EXCEEDED` — Daily loss limit reached

**API Errors:**
- `API_TIMEOUT` — Zerodha API timeout
- `API_RATE_LIMIT` — Rate limit exceeded
- `API_ERROR` — Zerodha API error

**Database Errors:**
- `DB_CONNECTION_ERROR` — Database connection failed
- `DB_TIMEOUT` — Database query timeout
- `DB_CONSTRAINT_VIOLATION` — Constraint violation

**Cache Errors:**
- `CACHE_UNAVAILABLE` — Redis unavailable
- `CACHE_TIMEOUT` — Cache operation timeout

**System Errors:**
- `CIRCUIT_BREAKER_OPEN` — Circuit breaker is open
- `INTERNAL_SERVER_ERROR` — Unexpected server error

### Global Exception Handler

All exceptions are handled by `GlobalExceptionHandler`:

```java
@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(TradeMeshException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleTradeMeshException(...) { ... }
    
    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleIllegalArgumentException(...) { ... }
    
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> handleGenericException(...) { ... }
}
```

### Exception Hierarchy

```
TradeMeshException (base)
├── ApiException (Zerodha API errors)
├── DatabaseException (R2DBC/PostgreSQL errors)
├── CacheException (Redis errors)
├── RiskException (Risk validation failures)
└── ExecutionException (Order execution failures)
```

---

## DTO Standards

### DTO Naming

- **Request DTOs:** Suffix with `Request` (e.g., `CloseTradeRequest`)
- **Response DTOs:** Suffix with `Response` or use entity name (e.g., `PaperTradeDTO`, `MarketIntelligence`)
- **Query DTOs:** Suffix with `Query` (e.g., `TradeFilterQuery`)

**Current Implementation:**
- Most responses use entity names directly (e.g., `PaperTrade`, `MarketIntelligence`)
- Request bodies are minimal (usually just query parameters)

### Request DTOs

**Purpose:** Validate and structure incoming request data

**Characteristics:**
- Immutable (use `@Data` with `@Builder`)
- Include validation annotations (`@NotNull`, `@NotBlank`, `@Min`, `@Max`)
- Document all fields with Javadoc
- Never expose internal implementation details

**Example:**
```java
@Data
@Builder
public class CloseTradeRequest {
    @NotNull
    private UUID tradeId;
    
    @NotBlank
    private String reason;
}
```

### Response DTOs

**Purpose:** Structure outgoing response data

**Characteristics:**
- Immutable (use `@Data` with `@Builder`)
- Never return JPA entities directly
- Include only fields needed by client
- Document all fields with Javadoc

**Example:**
```java
@Data
@Builder
public class PaperTradeDTO {
    private UUID id;
    private String symbol;
    private String strategyName;
    private TradeStatus status;
    private Double entryCredit;
    private Double unrealizedPnl;
    private LocalDateTime entryTime;
}
```

### Mapping Rules

**Entity to DTO Mapping:**
- Use constructor or builder pattern
- Never use reflection-based mappers (MapStruct, ModelMapper)
- Keep mapping logic in service layer or dedicated mapper classes

**Example:**
```java
// In service layer
public Mono<PaperTradeDTO> getTrade(UUID id) {
    return repository.findById(id)
            .map(trade -> new PaperTradeDTO(
                trade.getId(),
                trade.getSymbol(),
                trade.getStrategyName(),
                // ... other fields
            ));
}
```

### Why Entities Must Never Be Returned Directly

**Reasons:**

1. **Decoupling:** API contract is independent of database schema
2. **Security:** Prevents accidental exposure of sensitive fields
3. **Flexibility:** Can change entity structure without breaking API
4. **Lazy Loading:** Entities may have uninitialized lazy-loaded fields
5. **Serialization:** JPA entities may have circular references or proxy objects
6. **Versioning:** Different API versions can return different DTOs from same entity

**Example of Problem:**
```java
// ❌ Wrong: Returning entity directly
@GetMapping("/{id}")
public Mono<ResponseEntity<PaperTrade>> getTrade(@PathVariable UUID id) {
    return repository.findById(id)
            .map(ResponseEntity::ok);  // Returns entity with all fields
}

// If entity has @JsonIgnore fields, they're still in memory
// If entity has lazy-loaded collections, they may cause N+1 queries
// If entity structure changes, API contract breaks
```

---

## Swagger / OpenAPI Standards

### Grouping

Endpoints are grouped by domain using `@Tag` annotation:

```java
@Tag(name = "Paper Trading", description = "Paper trading endpoints for viewing trades and P&L")
@RestController
@RequestMapping("/paper/trades")
public class PaperTradeController { ... }
```

**Tag Groups:**
- Paper Trading
- Market Intelligence
- Strategy
- Trading
- Market Data
- Admin
- Zerodha Auth

### Naming

- **Operation Summary:** Short, action-oriented description
  - ✅ "Get all currently open paper trades"
  - ❌ "Retrieves open trades"

- **Parameter Names:** Use descriptive names matching path/query variables
  - ✅ `@Parameter(description = "Trading symbol (e.g., NIFTY50, NIFTYBANK)")`
  - ❌ `@Parameter(description = "symbol")`

### Descriptions

- **Endpoint Description:** Explain what the endpoint does and when to use it
- **Parameter Description:** Explain the parameter and valid values
- **Response Description:** Explain the response structure and fields

**Example:**
```java
@Operation(summary = "Get complete market intelligence for a symbol")
@GetMapping(value = "/{symbol}", produces = MediaType.APPLICATION_JSON_VALUE)
public Mono<ResponseEntity<MarketIntelligence>> getIntelligence(
        @Parameter(description = "Trading symbol (e.g., NIFTY50, NIFTYBANK)")
        @PathVariable String symbol) {
    // ...
}
```

### Examples

Include realistic examples in OpenAPI spec:

```java
@Operation(
    summary = "Get market intelligence",
    description = "Returns complete market intelligence including VIX, OI, max pain, and market condition"
)
@GetMapping("/{symbol}")
public Mono<ResponseEntity<MarketIntelligence>> getIntelligence(
        @Parameter(
            description = "Trading symbol",
            example = "NIFTY50"
        )
        @PathVariable String symbol) {
    // ...
}
```

### Tags

Use consistent tags for grouping related endpoints:

```java
@Tag(name = "Paper Trading", description = "Paper trading endpoints")
@RestController
public class PaperTradeController { ... }

@Tag(name = "Market Intelligence", description = "Market analysis endpoints")
@RestController
public class MarketIntelligenceController { ... }
```

### Response Documentation

Document all possible response codes and structures:

```java
@Operation(
    summary = "Get market intelligence",
    responses = {
        @ApiResponse(responseCode = "200", description = "Market intelligence retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid symbol"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    }
)
@GetMapping("/{symbol}")
public Mono<ResponseEntity<MarketIntelligence>> getIntelligence(
        @PathVariable String symbol) {
    // ...
}
```

---

## Versioning Strategy

### Recommended Approach: Header-Based Versioning

**For Future Use:** If API versioning becomes necessary, use header-based versioning instead of URL paths.

**Format:**
```
Accept: application/vnd.trademesh.v1+json
```

**Advantages:**
- Cleaner URLs (no `/v1/`, `/v2/` in paths)
- Easier to deprecate old versions
- Supports multiple versions simultaneously
- Follows REST principles more closely

**Implementation:**
```java
@GetMapping("/{symbol}")
@RequestMapping(produces = "application/vnd.trademesh.v1+json")
public Mono<ResponseEntity<MarketIntelligence>> getIntelligence(
        @PathVariable String symbol) {
    // ...
}
```

### Current Approach: No Versioning

**Rationale:**
- Single-tenant, internal platform
- All clients updated simultaneously
- Breaking changes managed through coordinated deployments
- Simpler implementation and maintenance

### Deprecation Strategy

When versioning is introduced:

1. **Announce Deprecation:** Notify clients 2-3 months in advance
2. **Add Deprecation Header:** `Deprecation: true`, `Sunset: <date>`
3. **Provide Migration Path:** Document how to migrate to new version
4. **Support Old Version:** Keep old version working for transition period
5. **Remove Old Version:** After transition period, remove old version

---

## Security

### Authentication

**Current Implementation:** OAuth2 with Zerodha Kite Connect

**Flow:**
1. User visits `GET /zerodha/login`
2. Redirected to Zerodha login page
3. After login, redirected to `GET /zerodha/callback?request_token=xxx`
4. Access token exchanged and stored in Redis with 8-hour TTL
5. Subsequent API calls use access token from Redis

**Token Storage:**
- Stored in Redis with key: `zerodha:access_token`
- TTL: 8 hours
- Automatically refreshed on each successful API call

### Authorization

**Current Implementation:** No role-based access control (RBAC)

**Recommendation:** If multi-user support is added:
- Implement role-based authorization
- Use Spring Security with custom `GrantedAuthority`
- Protect sensitive endpoints (e.g., `/admin/*`)

### Sensitive Fields

**Fields to Mask in Logs:**
- Zerodha access tokens
- Request tokens
- API keys
- User credentials

**Implementation:**
```java
public class SensitiveDataMasker {
    public static String maskRequestToken(String token) {
        if (token == null || token.length() < 8) return "[REDACTED]";
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
```

### Input Validation

**Validation Layers:**

1. **Controller Level:** Validate request parameters
   ```java
   @GetMapping("/{symbol}")
   public Mono<ResponseEntity<MarketIntelligence>> getIntelligence(
           @PathVariable @NotBlank String symbol) {
       // ...
   }
   ```

2. **Service Level:** Validate business logic
   ```java
   public Mono<MarketIntelligence> generateIntelligence(String symbol) {
       if (!SUPPORTED_SYMBOLS.contains(symbol)) {
           return Mono.error(new IllegalArgumentException("Unsupported symbol: " + symbol));
       }
       // ...
   }
   ```

3. **Database Level:** Constraints and triggers
   - NOT NULL constraints
   - UNIQUE constraints
   - CHECK constraints

---

## Performance

### Pagination

**Current Implementation:** Reactive streams (Flux) instead of traditional pagination

**Advantages:**
- Efficient memory usage (streaming)
- Real-time data delivery
- No need to count total records
- Scales to large datasets

**If Pagination is Needed:**
```java
@GetMapping
public Flux<PaperTrade> getTrades(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    return repository.findAll()
            .skip((long) page * size)
            .take(size);
}
```

### Payload Size

**Guidelines:**
- Keep response payloads under 1 MB
- Use field selection if needed: `?fields=id,symbol,status`
- Compress large responses with gzip

**Current Implementation:**
- Most endpoints return focused DTOs
- Collections use streaming (Flux) to avoid loading all data in memory

### Compression

**Enabled by Default:**
- Spring Boot enables gzip compression for responses > 1 KB
- Configure in `application.yml`:
  ```yaml
  server:
    compression:
      enabled: true
      min-response-size: 1024
  ```

### Caching

**Cache Strategy:**

| Resource | TTL | Strategy |
|----------|-----|----------|
| Market Intelligence | 5 minutes | Redis cache |
| VIX Data | 5 minutes | Redis cache |
| Option Chain | 1 minute | Redis cache |
| Max Pain | 15 minutes | Redis cache |
| Market Metrics | 5 minutes | Redis cache |
| Price Ticks | 60 seconds | Redis cache |

**Implementation:**
```java
@Cacheable(value = "marketIntelligence", key = "#symbol")
public Mono<MarketIntelligence> generateIntelligence(String symbol) {
    // ...
}
```

### Rate Limiting

**Current Implementation:** Not implemented

**Recommendation:** If needed, use Spring Cloud Gateway or custom interceptor

**Example:**
```java
@RateLimiter(name = "api", fallbackMethod = "rateLimitFallback")
@GetMapping("/{symbol}")
public Mono<ResponseEntity<MarketIntelligence>> getIntelligence(
        @PathVariable String symbol) {
    // ...
}
```

---

## Market Intelligence API Guidelines

### VIX Endpoint

**Purpose:** Monitor volatility index for trading decisions

**Endpoint:** `GET /intelligence/vix`

**Response:**
```json
{
  "currentVix": 14.2,
  "previousClose": 13.8,
  "percentChange": 2.9,
  "vixLevel": "LOW",
  "safeToTrade": true,
  "timestamp": "2026-06-29T23:01:00Z"
}
```

**VIX Levels:**
- `LOW`: VIX < 15 (safe to trade)
- `MODERATE`: VIX 15-20 (normal conditions)
- `HIGH`: VIX 20-30 (elevated volatility)
- `EXTREME`: VIX > 30 (high risk)

### Max Pain Endpoint

**Purpose:** Calculate strike price where maximum options expire worthless

**Endpoint:** `GET /intelligence/maxpain/{symbol}`

**Response:**
```json
{
  "symbol": "NIFTY50",
  "maxPainStrike": 22000.0,
  "spotPrice": 21500.50,
  "distancePercent": 2.33,
  "timestamp": "2026-06-29T23:01:00Z"
}
```

**Usage:**
- Identify likely expiration price
- Plan entry/exit strategies
- Validate support/resistance levels

### Open Interest Endpoint

**Purpose:** Analyze open interest patterns for market bias

**Endpoint:** `GET /intelligence/oi/{symbol}`

**Response:**
```json
{
  "symbol": "NIFTY50",
  "maxCallOIStrike": 22000.0,
  "maxPutOIStrike": 21500.0,
  "marketBias": "BULLISH",
  "callOIWalls": [
    {"strike": 22000.0, "oi": 5000000},
    {"strike": 22100.0, "oi": 4500000}
  ],
  "putOIWalls": [
    {"strike": 21500.0, "oi": 6000000},
    {"strike": 21400.0, "oi": 5500000}
  ],
  "timestamp": "2026-06-29T23:01:00Z"
}
```

**Market Bias:**
- `BULLISH`: More put OI (support wall)
- `BEARISH`: More call OI (resistance wall)
- `NEUTRAL`: Balanced OI

### Option Chain Endpoint

**Purpose:** Fetch raw option chain data (debug only)

**Endpoint:** `GET /intelligence/optionchain/{symbol}` (non-prod only)

**Response:**
```json
{
  "symbol": "NIFTY50",
  "spotPrice": 21500.50,
  "expiryDate": "2026-07-03",
  "strikes": [
    {
      "strike": 21500.0,
      "callOI": 1000000,
      "callIV": 15.06,
      "putOI": 1200000,
      "putIV": 14.95
    }
  ]
}
```

### PCR (Put-Call Ratio) Endpoint

**Purpose:** Measure market sentiment via put/call ratio

**Included in:** Market Metrics response

**Formula:** Total Put OI / Total Call OI

**Interpretation:**
- PCR < 0.8: Bullish (more calls)
- PCR 0.8-1.2: Neutral (balanced)
- PCR > 1.2: Bearish (more puts)

### IV (Implied Volatility) Endpoint

**Purpose:** Monitor option pricing volatility

**Included in:** Market Metrics response

**Fields:**
- `currentAtmIv`: Current ATM IV percentage
- `ivr`: IV Rank (0-100)
- `ivHvRatio`: IV/HV ratio

**Usage:**
- High IV: Sell options (premium high)
- Low IV: Buy options (premium low)
- IV Rank: Relative IV level vs 365-day range

### Raw vs Derived Intelligence

**Raw Market APIs:**
- Return unprocessed market data
- Examples: Option Chain, VIX, OI
- Used by strategies for calculations
- Cached for 1-15 minutes

**Derived Intelligence APIs:**
- Return processed, analyzed data
- Examples: Market Intelligence, Market Metrics
- Combine multiple raw data sources
- Cached for 5 minutes
- Include business logic and classifications

**Separation Principle:**
- Raw APIs: `GET /intelligence/optionchain/{symbol}`, `GET /intelligence/vix`
- Derived APIs: `GET /intelligence/{symbol}`, `GET /intelligence/metrics/{symbol}`

---

## Architectural Rules

### Controllers Return DTOs Only

**Rule:** Controllers must never return JPA entities directly

**Rationale:**
- Decouples API contract from database schema
- Prevents accidental exposure of sensitive fields
- Enables different API versions with same entity
- Avoids lazy-loading and proxy issues

**Implementation:**
```java
// ❌ Wrong
@GetMapping("/{id}")
public Mono<ResponseEntity<PaperTrade>> getTrade(@PathVariable UUID id) {
    return repository.findById(id).map(ResponseEntity::ok);
}

// ✅ Correct
@GetMapping("/{id}")
public Mono<ResponseEntity<PaperTradeDTO>> getTrade(@PathVariable UUID id) {
    return repository.findById(id)
            .map(trade -> new PaperTradeDTO(trade))
            .map(ResponseEntity::ok);
}
```

### Controllers Never Expose Entities

**Rule:** Controllers must not expose internal entity structure

**Rationale:**
- Prevents tight coupling between API and database
- Allows schema changes without breaking API
- Improves security by hiding implementation details

**Implementation:**
```java
// ❌ Wrong: Exposes entity fields
@GetMapping("/{id}")
public Mono<ResponseEntity<PaperTrade>> getTrade(@PathVariable UUID id) {
    return repository.findById(id).map(ResponseEntity::ok);
}

// ✅ Correct: Uses DTO
@GetMapping("/{id}")
public Mono<ResponseEntity<PaperTradeDTO>> getTrade(@PathVariable UUID id) {
    return service.getTrade(id).map(ResponseEntity::ok);
}
```

### Business Logic Belongs in Services

**Rule:** All business logic must be in service layer, not controllers

**Rationale:**
- Enables reuse across multiple controllers
- Simplifies testing (test services, not controllers)
- Maintains separation of concerns
- Supports both REST and scheduled execution

**Implementation:**
```java
// ❌ Wrong: Business logic in controller
@PostMapping("/{id}/close")
public Mono<ResponseEntity<PaperTrade>> closeTrade(@PathVariable UUID id) {
    return repository.findById(id)
            .flatMap(trade -> {
                if (trade.getStatus() != TradeStatus.OPEN) {
                    return Mono.error(new IllegalStateException("Trade not open"));
                }
                trade.setStatus(TradeStatus.CLOSED);
                trade.setExitTime(LocalDateTime.now());
                return repository.save(trade);
            })
            .map(ResponseEntity::ok);
}

// ✅ Correct: Business logic in service
@PostMapping("/{id}/close")
public Mono<ResponseEntity<PaperTrade>> closeTrade(@PathVariable UUID id) {
    return service.closeTrade(id)
            .map(ResponseEntity::ok);
}
```

### Validation Occurs Before Service Execution

**Rule:** Validate all inputs before calling service methods

**Rationale:**
- Fail fast with clear error messages
- Prevent invalid data from reaching business logic
- Improves performance by avoiding unnecessary processing

**Implementation:**
```java
// ❌ Wrong: Validation in service
@PostMapping("/{id}/close")
public Mono<ResponseEntity<PaperTrade>> closeTrade(@PathVariable UUID id) {
    return service.closeTrade(id)  // Service validates
            .map(ResponseEntity::ok);
}

// ✅ Correct: Validation in controller
@PostMapping("/{id}/close")
public Mono<ResponseEntity<PaperTrade>> closeTrade(
        @PathVariable @NotNull UUID id) {  // Validation here
    return service.closeTrade(id)
            .map(ResponseEntity::ok);
}
```

### Services Do Not Return Persistence Entities

**Rule:** Services must return DTOs or domain objects, never JPA entities

**Rationale:**
- Prevents lazy-loading issues
- Decouples service interface from persistence layer
- Enables caching without entity state issues

**Implementation:**
```java
// ❌ Wrong: Service returns entity
public Mono<PaperTrade> getTrade(UUID id) {
    return repository.findById(id);  // Returns entity
}

// ✅ Correct: Service returns DTO
public Mono<PaperTradeDTO> getTrade(UUID id) {
    return repository.findById(id)
            .map(trade -> new PaperTradeDTO(trade));
}
```

### REST APIs Must Not Expose Internal Implementation Details

**Rule:** API responses must not reveal internal architecture or implementation

**Rationale:**
- Improves security (attackers can't exploit implementation details)
- Enables internal refactoring without breaking API
- Maintains clean separation between public and internal APIs

**Implementation:**
```java
// ❌ Wrong: Exposes internal details
{
  "id": "...",
  "hibernateLazyInitializer": {...},
  "handler": {...},
  "internalState": "..."
}

// ✅ Correct: Clean DTO
{
  "id": "...",
  "symbol": "NIFTY50",
  "status": "OPEN",
  "entryCredit": 150.50
}
```

---

## Current Compliance

### Good Practices Already Implemented

✅ **Consistent URL Structure**
- All endpoints follow resource-oriented design
- Clear hierarchies: `/paper/trades`, `/intelligence/{symbol}`, `/trading/positions/open`
- Plural resource names for collections

✅ **Comprehensive Swagger/OpenAPI Documentation**
- All endpoints documented with `@Operation` and `@Parameter`
- Clear descriptions and examples
- Organized by tags (Paper Trading, Market Intelligence, etc.)

✅ **Structured Error Handling**
- Global exception handler (`GlobalExceptionHandler`)
- Consistent error response format with error codes
- Proper HTTP status code mapping

✅ **Reactive Architecture**
- Spring WebFlux for non-blocking I/O
- Proper use of `Mono` and `Flux` for async operations
- Efficient resource utilization

✅ **Separation of Concerns**
- Controllers handle HTTP concerns only
- Services contain business logic
- Repositories handle data access

✅ **Logging Standards**
- SLF4J for structured logging
- Appropriate log levels (INFO, DEBUG, ERROR)
- Sensitive data masking

✅ **DTO Usage**
- Response DTOs for most endpoints
- Proper mapping from entities to DTOs
- Immutable DTO design with `@Builder`

### Inconsistencies Identified

⚠️ **Inconsistent Response Wrapping**
- Some endpoints return wrapped responses: `ResponseEntity<Map<String, Object>>`
- Others return unwrapped: `Flux<PaperTrade>`
- **Recommendation:** Use wrappers for single resources, not for collections

**Current:**
```java
// Wrapped (single resource)
@GetMapping("/{symbol}")
public Mono<ResponseEntity<MarketIntelligence>> getIntelligence(...) { ... }

// Unwrapped (collection)
@GetMapping("/open")
public Flux<PaperTrade> getOpenTrades() { ... }
```

**Recommendation:** Keep this pattern (it's correct)

⚠️ **Direct Entity Returns in Some Endpoints**
- `PaperTradeController.getOpenTrades()` returns `Flux<PaperTrade>` (entity)
- `PaperTradeController.getTrade()` returns `Mono<ResponseEntity<PaperTrade>>` (entity)
- **Recommendation:** Create `PaperTradeDTO` and map entities to DTOs

**Current:**
```java
@GetMapping("/open")
public Flux<PaperTrade> getOpenTrades() {
    return repository.findAllOpenTrades();
}
```

**Recommended:**
```java
@GetMapping("/open")
public Flux<PaperTradeDTO> getOpenTrades() {
    return repository.findAllOpenTrades()
            .map(PaperTradeDTO::new);
}
```

⚠️ **Inconsistent Error Response Format**
- Some endpoints return custom error maps: `Map<String, Object>`
- Others use `ErrorResponse` DTO
- **Recommendation:** Always use `ErrorResponse` for consistency

**Current:**
```java
// Custom error map
Map<String, Object> errorResponse = new HashMap<>();
errorResponse.put("error", "Error message");
return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));

// Should use ErrorResponse
ErrorResponse error = ErrorResponse.builder()
        .status(500)
        .message("Error message")
        .build();
return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error));
```

⚠️ **Inconsistent Query Parameter Handling**
- Some endpoints use path variables: `GET /paper/trades/symbol/{symbol}`
- Others use query parameters: `GET /paper/trades?symbol=NIFTY50`
- **Recommendation:** Use path variables for required filters, query parameters for optional

**Current:**
```java
// Path variable (good for required)
@GetMapping("/symbol/{symbol}")
public Flux<PaperTrade> getTradesBySymbol(@PathVariable String symbol) { ... }

// Could also support query parameter
@GetMapping
public Flux<PaperTrade> getTrades(@RequestParam(required = false) String symbol) { ... }
```

⚠️ **Missing Request/Response DTOs**
- Some endpoints use `Map<String, Object>` for responses
- Should use typed DTOs for better documentation and type safety
- **Recommendation:** Create specific DTOs for complex responses

**Current:**
```java
// Using Map
public Mono<ResponseEntity<Map<String, Object>>> getWeeklyPnL() {
    Map<String, Object> response = new HashMap<>();
    response.put("weekStartDate", weekStart);
    response.put("totalTrades", trades.size());
    // ...
    return Mono.just(ResponseEntity.ok(response));
}

// Should use DTO
public Mono<ResponseEntity<WeeklyPnLDTO>> getWeeklyPnL() {
    return Mono.just(ResponseEntity.ok(
        WeeklyPnLDTO.builder()
            .weekStartDate(weekStart)
            .totalTrades(trades.size())
            .build()
    ));
}
```

### Recommended Improvements

**Priority 1: High Impact**

1. **Create Response DTOs for Complex Responses**
   - `WeeklyPnLDTO` for `/paper/trades/pnl/weekly`
   - `TradingStatsDTO` for `/paper/trades/stats`
   - `ApplicableStrategiesDTO` for `/strategy/applicable/{symbol}`
   - Benefits: Better documentation, type safety, easier testing

2. **Standardize Error Responses**
   - Always use `ErrorResponse` DTO
   - Remove custom error maps
   - Ensure all error handlers use consistent format
   - Benefits: Predictable error handling, easier client integration

3. **Create Entity-to-DTO Mappers**
   - `PaperTradeDTO` for `PaperTrade` entity
   - `StrategyResultDTO` for `StrategyResult`
   - Use in all controller responses
   - Benefits: Decouples API from database, improves security

**Priority 2: Medium Impact**

4. **Add Request Validation**
   - Use `@NotNull`, `@NotBlank`, `@Min`, `@Max` annotations
   - Validate path variables and query parameters
   - Return 400 Bad Request with detailed error messages
   - Benefits: Fail fast, better error messages

5. **Implement Pagination for Large Collections**
   - Add `page` and `size` query parameters
   - Return pagination metadata
   - Benefits: Better performance, scalability

6. **Add Caching Headers**
   - Set `Cache-Control` headers for cacheable responses
   - Example: `Cache-Control: max-age=300` for 5-minute cache
   - Benefits: Reduced server load, faster client responses

**Priority 3: Low Impact**

7. **Add API Rate Limiting**
   - Implement per-endpoint rate limits
   - Return 429 Too Many Requests when exceeded
   - Benefits: Prevents abuse, protects system

8. **Add Request/Response Logging**
   - Log all requests and responses (with sensitive data masked)
   - Include request ID for tracing
   - Benefits: Better debugging, audit trail

9. **Add API Versioning**
   - Implement header-based versioning for future use
   - Document versioning strategy
   - Benefits: Enables backward compatibility

---

## Summary

TradeMesh follows solid REST API design principles with a reactive, non-blocking architecture. The platform demonstrates good practices in error handling, documentation, and separation of concerns.

**Key Strengths:**
- Consistent URL structure and resource naming
- Comprehensive Swagger/OpenAPI documentation
- Structured error handling with global exception handler
- Reactive architecture with Spring WebFlux
- Clear separation between controllers, services, and repositories

**Areas for Improvement:**
- Standardize response wrapping (use DTOs instead of Maps)
- Create entity-to-DTO mappers for all responses
- Implement consistent error response format
- Add request validation annotations
- Create specific DTOs for complex responses

**Next Steps:**
1. Create response DTOs for complex endpoints
2. Standardize error response format
3. Add request validation
4. Implement entity-to-DTO mapping
5. Add caching headers for performance

---

**Document Version:** 1.0  
**Last Updated:** 2026-06-29  
**Maintained By:** TradeMesh Engineering Team
