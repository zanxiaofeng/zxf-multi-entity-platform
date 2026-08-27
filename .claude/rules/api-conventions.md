---
paths:
  - "**/infrastructure/adapter/in/**/*.java"
  - "**/docs/design/**/*.md"
---
# API Design Conventions

**版本：** 1.2（2026-08-22 同步主线规范源：Error Response 统一为含 `errors[]` 标准结构并补结构一致性 callout；触发面收窄至 docs/design；项目选型记录迁至项目 CLAUDE.md「规范适配」段）

> Controller、Request/Response DTO、WebMapper、ApiResponse、GlobalExceptionHandler 均位于入站适配器 `infrastructure/adapter/in/web/`（见 `architecture.md` §5.4）。

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

统一响应信封 `ApiResponse<T>`（`infrastructure/adapter/in/web/common/ApiResponse.java`）；`code` 为语义化字符串：成功 `000000`，业务错误取领域异常的 `CODE` 常量（如 `USER_NOT_FOUND`），传输层错误用传输层常量（`VALIDATION_ERROR` 等，见 `exception-handling.md` §3.2/§6.2）。

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
  "code": "VALIDATION_ERROR",
  "data": null,
  "message": "Request validation failed",
  "timestamp": "2026-04-27T12:00:00+08:00",
  "traceId": "abc123",
  "errors": [
    { "field": "email", "message": "must be a valid email", "rejectedValue": "invalid" }
  ]
}
```

> **信封结构与 `exception-handling.md` §6.1、`contract-test.md` 保持一致**：字段级校验明细进 `errors[]` 数组（field/message，`rejectedValue` 对敏感字段脱敏为 `***`），`message` 只放汇总描述——禁止「明细拼 message」与「errors[] 数组」两种结构混用；存量代码若为旧变体，须全项目统一切换。

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
- Service layer accepts `Pageable`, returns `Page<{Entity}Dto>`; `{Entity}WebMapper` converts to `Page<{Entity}Response>`
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
| **404 Not Found** | Resource does not exist | `findById` returns empty → 领域 `NotFoundException` |
| **409 Conflict** | State conflict | Duplicate resource, optimistic lock failure, insufficient stock 等 `DomainException` |
| **422 Unprocessable Entity** | Semantically invalid request | Business rule violation, invalid state transition |

> HTTP 映射由 `GlobalExceptionHandler` 逐领域异常声明（`exception-handling.md` §6.2），Controller 不写 try-catch。

## DELETE Convention

DELETE endpoints must return **204 No Content** (not 200 OK). The response has no body.

```java
@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable String id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
}
```

## @PathVariable Validation

All path variable IDs must use validation constraints to reject illegal values.

```java
@GetMapping("/{id}")
public ResponseEntity<ApiResponse<{Entity}Response>> getById(
        @PathVariable @Pattern(regexp = "^\\d+$") String id) {
    return ResponseEntity.ok(ApiResponse.success(service.findById(id)));
}
```

**Rules:**
- 标识符为字符串值对象时用 `@Pattern`；数值 ID 用 `@Positive`（rejects `0` and negative values；按 Jakarta Validation 规范，**null 视为 valid**，`@PathVariable` 缺失时 Spring 已先返回 400）
- This applies to all HTTP methods: GET, PUT, PATCH, DELETE
- For composite keys or non-ID path variables, use the most appropriate constraint (`@NotBlank`, `@Pattern`, etc.)

> 参数校验的完整规范（声明式 Bean Validation、命令式断言、`@Valid` vs `@Validated` 等）见 `validation.md`。
