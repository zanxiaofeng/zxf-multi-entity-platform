---
paths:
  - "**/interfaces/**/*.java"
  - "**/application/**/*.java"
  - "**/domain/**/*.java"
  - "**/infrastructure/**/*Config*.java"
  - "**/infrastructure/**/*ExceptionHandler*.java"
---
# Bean Validation 与参数校验规范

**适用范围：** JDK 21 + Spring Boot 4.0 + Jakarta Validation 3.1

***

## 1. 分层验证职责

| 层 | 验证方式 | 验证内容 |
|----|---------|---------|
| Controller | `@Valid` / `@Validated` | 格式验证（非空、长度、格式、正则） |
| Service | `@Validated` + Bean Validation 或 `Assert` | 业务验证（存在性、状态、权限） |
| Entity | `@PrePersist` / `@PreUpdate` | 不变式（数据一致性约束） |

***

## 2. Controller 层参数验证

### 请求体 `@RequestBody`

```java
@PostMapping
public ApiResponse<Long> create(@RequestBody @Valid UserCreateDTO dto) {
    return ApiResponse.success(userService.create(dto));
}
```

### 路径变量 `@PathVariable`

```java
@GetMapping("/{id}")
public ApiResponse<UserVO> getById(
        @PathVariable @Positive(message = "ID必须为正数") Long id) { ... }
```

### 查询参数 `@RequestParam`

```java
// 返回类型统一用 Spring Data Page(与 api-conventions / ApiResponse 一致);
// 分页参数实际应绑 Pageable(@PageableDefault),此处用 @RequestParam 仅示例 @Min/@Max 约束写法
@GetMapping
public ApiResponse<Page<UserVO>> list(
        @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
        @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) { ... }
```

### 查询对象 `@ParameterObject`

```java
@Data @Builder
public class UserQueryDTO {
    @Min(1) private Integer pageNum = 1;
    @Min(1) @Max(100) private Integer pageSize = 10;
    @Pattern(regexp = "^[a-zA-Z0-9_]*$") private String username;

    // 跨字段验证
    @AssertTrue(message = "结束日期必须晚于开始日期")
    public boolean isDateRangeValid() {
        if (startDate == null || endDate == null) return true;
        return !endDate.isBefore(startDate);
    }
}
```

***

## 3. DTO 字段验证注解

### 注解速查表

| 注解 | 用途 | 示例 |
|------|------|------|
| `@NotNull` | 值不为 null | `@NotNull Integer age` |
| `@NotBlank` | 字符串非空白 | `@NotBlank String username` |
| `@NotEmpty` | 集合/数组/字符串非空 | `@NotEmpty List<String> tags` |
| `@Size` | 长度/大小范围 | `@Size(min = 3, max = 20)` |
| `@Min` / `@Max` | 数值范围（整数） | `@Min(0) @Max(150)` |
| `@DecimalMin` / `@DecimalMax` | 数值范围（小数） | `@DecimalMin("0.00")` |
| `@Digits` | 数字精度 | `@Digits(integer = 9, fraction = 2)` |
| `@Pattern` | 正则匹配 | `@Pattern(regexp = "^1[3-9]\\d{9}$")` |
| `@Email` | 邮箱格式 | `@Email String email` |
| `@Past` / `@Future` | 过去/未来时间 | `@Past LocalDate birthday` |
| `@Positive` / `@PositiveOrZero` | 正数 | `@Positive Long id` |
| `@Negative` / `@NegativeOrZero` | 负数 | `@Negative BigDecimal balance` |
| `@AssertTrue` / `@AssertFalse` | 布尔断言 | `@AssertTrue Boolean agreed` |
| `@Valid` | 级联验证嵌套对象 | `@Valid AddressDTO address` |

### 字段验证示例

```java
public record UserCreateDTO(
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度3-20")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "用户名必须字母开头")
    String username,

    @NotBlank @Email(message = "邮箱格式不正确") @Size(max = 100)
    String email,

    @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    String phone,

    @NotNull @Min(0) @Max(150)
    Integer age,

    @NotNull @DecimalMin("0.00") @Digits(integer = 9, fraction = 2)
    BigDecimal balance,

    @Valid @NotNull  // 级联验证嵌套对象
    AddressDTO address
) {}
```

### 列表/集合元素验证

```java
@Data
public class BatchCreateRequest {
    @NotEmpty @Size(max = 100)
    @Valid  // 级联验证列表元素
    private List<@NotNull UserCreateDTO> users;
}
```

### Map 验证

```java
@Data
public class ConfigUpdateRequest {
    @NotEmpty
    private Map<@NotBlank String, @NotNull String> configs;
}
```

***

## 4. 验证组（分组验证）

```java
// 组定义
public interface ValidationGroups {
    interface Create {}
    interface Update {}
}

// DTO 使用分组
public record UserDTO(
    @Null(groups = Create.class, message = "创建时不能指定ID")
    @NotNull(groups = Update.class, message = "更新时ID不能为空")
    Long id,

    @NotBlank(groups = {Create.class, Update.class})
    @Size(min = 3, max = 20, groups = {Create.class, Update.class})
    String username,

    @NotBlank(groups = Create.class)  // 仅创建时必填
    String password
) {}

// Controller 使用分组
@PostMapping
public ApiResponse<Long> create(
        @RequestBody @Validated(ValidationGroups.Create.class) UserDTO dto) { ... }

@PutMapping("/{id}")
public ApiResponse<Void> update(
        @RequestBody @Validated(ValidationGroups.Update.class) UserDTO dto) { ... }
```

***

## 5. 自定义验证注解

### 手机号验证（模板）

```java
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PhoneValidator.class)
@Documented
public @interface Phone {
    String message() default "手机号格式不正确";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class PhoneValidator implements ConstraintValidator<Phone, String> {
    private static final Pattern PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        return value == null || PATTERN.matcher(value).matches();
    }
}
```

### 枚举值验证

```java
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = EnumValueValidator.class)
@Documented
public @interface EnumValue {
    String message() default "枚举值不合法";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    Class<? extends Enum<?>> enumClass();
}
```

### 自定义验证规则

- 验证器中 `null` 值返回 `true`（让 `@NotNull`/`@NotBlank` 处理空值）
- 需要多条错误信息时，用 `context.disableDefaultConstraintViolation()` + `buildConstraintViolationWithTemplate()`

***

## 6. 返回值验证（可选）

> **Spring Boot 4 已自动配置 `MethodValidationPostProcessor`**(`spring-boot-starter-validation` 提供),**无需手动声明该 Bean**。Service 类加 `@Validated` 即触发方法级参数校验。

如确需开启**返回值校验**,应**按需**配置(非 `setValidateReturnedValue(true)` 全局默认 —— 会"惊喜地"对所有 Bean 返回值校验),在具体场景显式启用。

> **重要：** 仅配置 `MethodValidationPostProcessor` 不够。Service **类**必须添加 `@Validated` 注解，方法级约束才会生效。未加 `@Validated` 的类，其 `@NotNull` 参数注解会被静默忽略。
>
> `setValidateReturnedValue(true)` 全局启用返回值校验，可能影响所有 Bean。如果只需参数校验，可省略此设置。

Service 使用：

```java
@Validated
public class OrderService {
    @NotNull OrderVO getOrder(@NotNull Long orderId);
    @NotEmpty List<@NotNull OrderVO> getUserOrders(@NotNull Long userId);
}
```

***

## 7. 全局异常处理

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String traceId = generateTraceId(request);
        List<ApiResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> ApiResponse.FieldError.builder()
                        .field(e.getField())
                        .message(e.getDefaultMessage())
                        .rejectedValue(e.getRejectedValue())
                        .build())
                .toList();
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getHttpStatus())
                .header("X-Trace-Id", traceId)
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, "Request validation failed", traceId, errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String traceId = generateTraceId(request);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getHttpStatus())
                .header("X-Trace-Id", traceId)
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, "Parameter validation failed", traceId));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException ex, HttpServletRequest request) {
        String traceId = generateTraceId(request);
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus())
                .header("X-Trace-Id", traceId)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage(), traceId));
    }
}
```

***

## 8. 最佳实践

### 推荐做法

- **分层验证**：Controller 做格式验证，Service 做业务验证，Entity 做不变式验证
- **错误消息清晰**：`@NotBlank(message = "用户名不能为空")`
- **使用验证组**区分创建/更新场景
- **Record + 验证**：`public record UserDTO(@NotBlank String username, @Email String email) {}`
- **组合注解**减少重复：将多个验证注解组合为一个自定义注解

### 避免做法

- **过度验证**：`@NotNull @NotEmpty @NotBlank @Size(min=1)` 冗余，选一个即可
- **重复验证**：Controller 和 Service 重复相同格式验证
- **验证器不处理 null**：自定义 `isValid()` 必须处理 `value == null` 返回 `true`
- **忽略 `@Valid` 级联**：嵌套对象必须加 `@Valid` 才会触发验证
