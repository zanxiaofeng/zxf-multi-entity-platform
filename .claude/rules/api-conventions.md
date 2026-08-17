---
paths:
  - "**/interfaces/**/*.java"
  - "**/docs/**/*.md"
---
# API Design Conventions

## URL Pattern
- Base path: `/api/v{version}/{resource}`
- Plural nouns: `/users`, `/orders`
- No verbs in URL (use HTTP Method)

## HTTP Method Semantics
| Method | Purpose | Success Code |
|--------|---------|-------------|
| GET    | Query   | 200 |
| POST   | Create  | 201 |
| PUT    | Full update | 200 |
| PATCH  | Partial update | 200 |
| DELETE | Delete  | **204** |

## Response Body
```json
{
  "code": "000000",
  "data": { },
  "message": null,
  "timestamp": "2026-04-27T12:00:00+08:00",
  "traceId": "abc123"
}
```

## Error Response
```json
{
  "code": "002001",
  "data": null,
  "message": "Request validation failed",
  "timestamp": "2026-04-27T12:00:00+08:00",
  "traceId": "abc123",
  "errors": [
    { "field": "email", "message": "must be a valid email", "rejectedValue": "invalid" }
  ]
}
```

## Downstream Side Effects
When an endpoint triggers a downstream call, document it in the API spec:
- Endpoint URL, payload format, failure mode
- Example: `POST /api/v1/{resource}` sends `POST /api/v1/{downstream-service}/{event-name}`

To add a new endpoint, use the `/add-endpoint` skill or follow the step-by-step process defined there.

## API Versioning Strategy
- **URL-based versioning**: `/api/v1/...`, `/api/v2/...`
- **When to bump version**: breaking changes (removing fields, changing types, renaming endpoints)
- **Non-breaking changes** (adding optional fields, new endpoints) do NOT require version bump
- **Version coexistence**: both versions run simultaneously, old version deprecated with sunset header
- **Controller organization**: `{Entity}V1Controller`, `{Entity}V2Controller` — separate classes, same or different packages
- **Deprecation**: `@Deprecated` annotation + `Sunset` response header, minimum 6 months overlap before removal

## Pagination Conventions

All list endpoints must accept Spring Data `Pageable` and return `ApiResponse<Page<T>>`.

### Request Parameters

| Parameter | Type   | Default | Description |
|-----------|--------|---------|-------------|
| `page`    | int    | 0       | Zero-based page index |
| `size`    | int    | 20      | Page size |
| `sort`    | string | —       | `field,asc` or `field,desc` (repeatable) |

### Controller Example

```java
@GetMapping
public ResponseEntity<ApiResponse<Page<{Entity}Response>>> list(
        @PageableDefault(size = 20) Pageable pageable) {
    return ResponseEntity.ok(ApiResponse.success(service.list(query, pageable)));
}
```

**Rules:**
- Use `@PageableDefault(size = 20)` for the default page size; cap the upper bound globally via `spring.data.web.pageable.max-page-size: 100` to prevent unbounded queries（`@PageableDefault` 没有 `maxPageSize` 属性）
- Service layer accepts `Pageable`, returns `Page<{Entity}>`, Mapper converts to `Page<{Entity}Response>`
- Never accept raw `page`/`size` parameters — let Spring Data bind `Pageable` automatically

### Response Format

```json
{
  "code": "000000",
  "data": {
    "content": [
      { "id": 1, "name": "example" }
    ],
    "totalElements": 150,
    "totalPages": 8,
    "number": 0,
    "size": 20
  },
  "timestamp": "2026-04-27T12:00:00+08:00",
  "traceId": "abc123"
}
```

## HTTP Status Codes

### Success Codes

| Code | Meaning | When to Use |
|------|---------|-------------|
| **200 OK** | Successful retrieval or update | GET, PUT, PATCH responses |
| **201 Created** | Resource created successfully | POST responses; include `Location` header |
| **204 No Content** | Successful with no response body | DELETE responses |
| **202 Accepted** | Request accepted for async processing | Long-running operations, async tasks |

### Error Codes

| Code | Meaning | When to Use |
|------|---------|-------------|
| **400 Bad Request** | Malformed request or validation failure | Invalid JSON, missing required fields |
| **404 Not Found** | Resource does not exist | `findById` returns empty |
| **409 Conflict** | State conflict | Duplicate resource, optimistic lock failure |
| **422 Unprocessable Entity** | Semantically invalid request | Business rule violation, invalid state transition |

### DELETE Convention

DELETE endpoints must return **204 No Content** (not 200 OK). The response has no body.

```java
@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable @Positive Long id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
}
```

This aligns with the HTTP Method Semantics table above where DELETE returns **204**.

## @PathVariable Validation

All path variable IDs must use `@Positive` validation to reject zero and negative values.

```java
@GetMapping("/{id}")
public ResponseEntity<ApiResponse<{Entity}Response>> getById(
        @PathVariable @Positive Long id) {
    return ResponseEntity.ok(ApiResponse.success(service.findById(id)));
}
```

**Rules:**
- Every `@PathVariable` representing an entity ID must be annotated with `@Positive`
- This applies to all HTTP methods: GET, PUT, PATCH, DELETE
- `@Positive` rejects `0` and negative values(按 Jakarta Validation 规范,**null 视为 valid**;若需非空请叠加 `@NotNull`。`@PathVariable` 缺失时 Spring 已先返回 400,不会进入方法为 null)
- For composite keys or non-ID path variables, use the most appropriate constraint (`@NotBlank`, `@Pattern`, etc.)

> 参数校验的完整规范（声明式 Bean Validation、命令式断言、`@Valid` vs `@Validated` 等）见 `validation.md`。
