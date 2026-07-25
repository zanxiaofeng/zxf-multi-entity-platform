---
paths:
  - "**/*.java"
---
# Java 编码规范：工具库与简洁性实践

**版本：** 2.0
**生效日期：** 2026-07-03
**适用范围：** 所有基于 Java 21+ 的后端项目（含 Spring Boot 4.0+）

***

## 1. 核心原则

### 1.1 可读性是第一原则

> Code is read more than written. 优先选择意图清晰、易于维护的写法，避免过度抽象或炫技。

### 1.2 能用 1 行完成的代码绝不用 2 行

> Concise but not cryptic. 利用现代语法和工具库减少样板代码，但确保不牺牲可读性。

**注意**：这里的"简洁"指**代码意图的简洁**，而非字符数。工具方法（如 `StringUtils.defaultString()`）在表达"提供默认值"这一意图时，比三元表达式更清晰，应优先使用。

### 1.3 优先使用框架/库能力

> Don't reinvent the wheel. 按照以下优先级选择实现方式：

| 优先级 | 类型 | 说明 |
|--------|------|------|
| 1 | JDK 原生 + Spring 内置工具 | Spring 项目两者**同级优先**(均零额外依赖):JDK 现代 API(record / sealed / virtual thread / java.time / Stream)+ Spring 工具(`StringUtils` / `CollectionUtils` / `ObjectUtils` / `RestClient`)。选更可读的,详见 §2.1 对照表 |
| 2 | Lombok | 减少 getter/setter、构造器、日志等样板代码(@Data / @Builder / @Slf4j / @RequiredArgsConstructor / @UtilityClass) |
| 3 | Apache Commons | 仅当 JDK + Spring 均无法简洁实现时,引入**具体模块**(commons-lang3 等),注释原因(§2.4) |
| 4 | 其他第三方库 | Guava、Hutool 等,仅在以上均无法简洁实现时按需引入模块(§2.5) |

***

## 2. 各层次详细规范

### 2.1 简洁实现优先（JDK 原生 + Spring 内置工具）

**原则：** 优先「简洁 + 意图清晰」。Spring 项目中，Spring 内置工具（`org.springframework.util.*`，零额外依赖）与 JDK 原生 API **同级优先**，选更可读的；避免 Apache Commons / Hutool / Guava 等第三方库，除非能显著简化（引入见 §2.4 / §2.5）。**切勿为「JDK 原生」而写 `str == null || str.isBlank()` 这类啰嗦判空** —— Spring `StringUtils.hasText(str)` 更清晰且与框架一致。

#### 常用替代对照表

| 需求 | 避免 | 推荐 |
|------|------|------|
| 字符串判空白 | Apache `StringUtils.isBlank`（第三方）;`str == null \|\| str.isBlank()`（啰嗦） | Spring `StringUtils.hasText(str)`（非空白）/ `!StringUtils.hasText(str)`（空白）— 与 §2.3 一致 |
| 集合判空 | 手动 `list == null \|\| list.isEmpty()`（啰嗦） | Spring `CollectionUtils.isEmpty(list)` |
| 集合创建 | Guava `Lists.newArrayList(...)` | `List.of(...)` (JDK 9+) |
| 文件读取 | Commons-io `FileUtils.readFileToString(...)` | `Files.readString(Path.of(...))` (JDK 11+) |
| HTTP 调用 | Hutool `HttpUtil`（第三方） | Spring `RestClient`（Spring 项目，§2.3）/ JDK `HttpClient`（非 Spring） |
| 日期时间 | `Date / Calendar / DateUtils` | `java.time.*` (JDK 8+) |
| Base64 编码 | Commons-codec `Base64.encodeBase64String` | `java.util.Base64` (JDK 8+) |
| 数值范围限制 | `Math.min(Math.max(val, min), max)` | `Math.clamp(val, min, max)` (JDK 21) |
| 字符串默认值 | `str != null ? str : ""` 三元 | Spring `StringUtils.defaultString(str)` — 工具方法意图更清晰 |
| 对象默认值 | `obj != null ? obj : default` 三元 | Spring `ObjectUtils.defaultIfNull(obj, default)` — 工具方法意图更清晰 |
| 类型判断分支 | `if (obj instanceof X) { X x = (X) obj; ... }` | `if (obj instanceof X x) { ... }` (JDK 16+ 模式匹配) |
| 获取集合首/末元素 | 手动 `get(0)` / `get(size()-1)` | `sequencedCollection.getFirst()` / `getLast()` (JDK 21) |

#### JDK 21 新特性示例

```java
// 模式匹配 switch（JDK 21 正式版）
String formatted = switch (obj) {
    case Integer i -> String.format("int %d", i);
    case Long l    -> String.format("long %d", l);
    case Double d  -> String.format("double %f", d);
    case String s  -> String.format("String %s", s);
    case null      -> "null";
    default        -> obj.toString();
};

// Record Pattern（JDK 21 正式版）
if (obj instanceof Point(int x, int y)) {
    System.out.println("x=" + x + ", y=" + y);
}

// SequencedCollection - 获取首末元素（JDK 21）
SequencedCollection<String> seq = new ArrayList<>(List.of("a", "b", "c"));
String first = seq.getFirst();   // "a"
String last  = seq.getLast();    // "c"

// Math.clamp - 数值范围限制（JDK 21）
int clamped = Math.clamp(value, 0, 100);

// Virtual Threads - 虚拟线程（JDK 21）

// 方式一：Spring Boot 配置（推荐）— application.yml
// spring.threads.virtual.enabled=true
// → Tomcat 请求处理、@Async、ScheduledTask 自动使用虚拟线程，无需代码改动

// 方式二：手动创建（高级场景）
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var future = executor.submit(() -> doSomething());
    future.get(); // 必须检查异常，避免丢失
}
// 注意：避免 synchronized（改用 ReentrantLock 防止 carrier thread pinning）
// 注意：不要池化虚拟线程，每次任务创建新线程即可
```

***

### 2.2 Lombok 简化代码

**原则：** 在允许使用 Lombok 的项目中，使用注解替代手写样板代码。

#### 推荐注解

| 注解 | 用途 | 示例 |
|------|------|------|
| `@Data` | 生成 getter/setter、toString、equals、hashCode | `@Data public class User { ... }` — **仅适用于非 JPA 的 DTO/VO。JPA Entity 必须使用 `@Getter` + 手动 equals/hashCode（见 architecture.md）** |
| `@Builder` | 生成建造者模式 | `@Builder @Data public class Order { ... }` |
| `@Slf4j` | 自动创建 log 对象 | `@Slf4j public class Service { ... }` |
| `@AllArgsConstructor` / `@NoArgsConstructor` | 生成构造器 | - |
| `@Value` | 创建不可变类 | `@Value public class Config { ... }` |
| `@UtilityClass` | 工具类(全静态方法,不可实例化/继承) | `@UtilityClass public class MaskUtils { ... }` — **所有工具类(main + test)统一用此注解,详见下方「工具类(@UtilityClass)」小节** |

#### 限制

- 若团队禁用 Lombok，则手动编写等价代码，但必须遵循可读性原则。
- 避免滥用 `@Data` 在 JPA 实体上，可能导致循环依赖问题。
- `@ToString.Exclude` 排除敏感字段（密码、密钥等），避免日志泄露。
- JPA 实体使用 `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` + `@EqualsAndHashCode.Include` 指定业务主键，避免懒加载字段触发 N+1 查询。

#### 工具类(@UtilityClass)

**所有工具类(仅含静态方法、无实例状态 —— main 代码与 test 代码统一)必须使用 `@UtilityClass`,禁止手写 `private` 构造器 + `static` 方法。**

`@UtilityClass` 自动:类标 `final` + 生成 `private` 构造器(throw)+ 所有方法/字段 static 化。

```java
@UtilityClass
public class MaskUtils {
    public String mask(String value, int visiblePrefix) { // 自动 static,无需手写 static 关键字
        if (value == null || value.length() <= visiblePrefix) {
            return "***";
        }
        return value.substring(0, visiblePrefix) + "****";
    }
}
```

**规则:**
- **不要再手写 `static` 关键字**(`@UtilityClass` 自动 static 化);**不要同时写 `final`**(`@UtilityClass` 自动 final,显式 `final` 冗余)
- **调用方禁止 `import static`**(Lombok 生成的 static 方法与 javac static-import 解析不兼容,SB4 + Lombok 1.18.46 实测 test-compile 报 `cannot find symbol`)→ 用显式 `类名.方法`,如 `MaskUtils.mask(phone, 3)`
- Spring Bean(`@Component` / `@Service` / `@Repository`)不算工具类,不要用 `@UtilityClass`(Bean 需可实例化、由容器管理)
- 仅当项目禁用 Lombok 时,才手写 `final class X { private X() {} public static ... }`

***

### 2.3 Spring / Spring Boot 内置能力

**原则：** Spring 项目中，优先使用 spring-core、spring-web 等模块提供的工具类。

#### 常用工具类

| 类 | 常用方法 | 说明 |
|----|----------|------|
| `org.springframework.util.StringUtils` | `hasText()`, `trimAllWhitespace()`, `commaDelimitedListToSet()` | 字符串工具 |
| `org.springframework.util.CollectionUtils` | `isEmpty()`, `mergeArrayIntoCollection()` | 集合工具 |
| `org.springframework.util.Assert` | `notNull()`, `hasLength()`, `state()`, `isTrue()` | 参数校验（Spring Boot 项目首选） |
| `org.springframework.util.FileCopyUtils` | `copy()`, `copyToByteArray()` | 文件/流复制 |
| `org.springframework.web.client.RestClient` | `get()`, `post()`, `delete()` | HTTP 客户端（Spring Framework 7，推荐替代 RestTemplate） |
| `org.springframework.beans.BeanUtils` | `copyProperties()`, `instantiateClass()` | Bean 操作 |

#### Spring Boot 4.0 特性

| 特性 | 配置/说明 |
|------|-----------|
| Virtual Threads | `spring.threads.virtual.enabled=true` — 一键启用虚拟线程（Tomcat 请求处理、`@Async`、`ScheduledTask`），SB4 对虚拟线程集成进一步优化 |
| RestClient | 替代 RestTemplate 的现代 HTTP 客户端，Fluent API（新模块首选，需 `spring-boot-starter-restclient`） |
| JSpecify null-safety | 框架全量采用 `org.jspecify.annotations`，配合 null checker 可编译期发现 NPE |
| Jackson 3 | 默认 JSON 库，自动配置 `JsonMapper` 并自动发现 classpath 上所有模块 |

> **说明：** 已有项目使用 `RestTemplate` 作为下游 HTTP 客户端可继续使用。新模块推荐使用 `RestClient`。

#### RestClient 示例（新模块推荐）

```java
// 注入 RestClient（替代 RestTemplate）
private final RestClient restClient;

// GET 请求
String result = restClient.get()
    .uri("https://api.example.com/users/{id}", userId)
    .retrieve()
    .body(String.class);

// POST 请求
User created = restClient.post()
    .uri("/users")
    .body(newUser)
    .retrieve()
    .body(User.class);
```

***

### 2.4 Apache Commons 库

**原则：** 当 JDK 和 Spring 均无法简洁实现时，引入具体的 Commons 模块，并注释说明原因。

#### 常用模块与场景

| 模块 | 常见用途 | 典型场景 |
|------|----------|----------|
| commons-lang3 | 字符串增强（StringUtils）、对象工具（ObjectUtils）、枚举（EnumUtils） | 需要 `join()` 复杂分隔符、`abbreviate()` 等高级操作 |
| commons-io | 文件/流操作（FileUtils、IOUtils） | 递归删除目录、复制大文件、读取资源流 |
| commons-collections4 | 高级集合（Bag、BidiMap）、集合工具（CollectionUtils） | 需要集合交集、差集或双向 Map |
| commons-codec | 编码解码（Base64、Hex、DigestUtils） | 需要 MD5 或 Hex 编码（若 JDK 未覆盖） |
| commons-pool2 | 对象池 | 连接池、资源池 |

#### 引入规范

- 只引入具体模块，避免 commons 父依赖。
- 在 pom.xml 或 build.gradle 中添加注释说明引入原因。

***

### 2.5 其他第三方库（Guava、Hutool 等）

**原则：** 仅在以上所有层次都无法满足时使用，并严格按需引入模块。

#### 典型场景与推荐库

| 场景 | 推荐库 | 原因 |
|------|--------|------|
| 本地缓存 | Guava `CacheBuilder` 或 Caffeine | JDK 无原生支持，且实现复杂 |
| 限流 | Guava `RateLimiter` | 简单可靠的令牌桶实现 |
| 不可变集合增强 | Guava `ImmutableXXX` | JDK 不可变集合功能有限（如 builder 模式） |
| Excel 简单读写 | Hutool `ExcelUtil` | 封装简洁，避免直接操作 POI |
| 验证码生成 | Hutool `CaptchaUtil` | 开箱即用，无需自己绘图 |
| HTTP 快速调用 | Hutool `HttpUtil` | 一行代码完成，适合简单场景 |

***

## 3. 依赖管理规范

1. **按需引入，避免全量依赖** — Commons 只引入需要的模块；Hutool 优先引入模块（如 hutool-http），而非 hutool-all。
2. **版本统一** — Spring Boot 项目利用 BOM 管理版本；非 Spring 项目在 `<dependencyManagement>` 中统一管理。
3. **显式注释** — 引入非 JDK/Spring 依赖时，必须在构建文件或代码中添加注释说明原因。
4. **冲突检查** — 引入新依赖前，运行 `mvn dependency:tree` 或 `gradle dependencies` 检查版本冲突。

***

## 4. 冲突与规避

- **HTTP 客户端：** Spring Boot 4.0 新模块优先使用 `RestClient`，`RestTemplate` 已进入维护模式。
- **文件上传：** 若使用 MultipartFile，禁止额外引入 commons-fileupload。
- **日志门面：** 若项目使用 SLF4J，避免引入 commons-logging。
- **Bean 属性复制：** 优先使用 Spring `BeanUtils.copyProperties()`，避免 Apache Commons BeanUtils。
- **集合工具：** Spring 项目中使用 Spring 的 `CollectionUtils`；避免混用多个库的集合工具。
- **异步编程：** 优先使用 JDK `CompletableFuture`。

***

## 5. 命名规范

- Classes: PascalCase
- Constants: UPPER_SNAKE
- Other: camelCase
- Methods: `findBy` / `existsBy` / `is` / `has` (boolean)
- Packages: all lowercase, no underscores
- Test methods: `test{Action}{Entity}[{Condition}]`

## 6. 依赖注入

- Constructor injection only (`@RequiredArgsConstructor`)
- No field `@Autowired`
- Use `@Qualifier` for multiple beans of same type
- Avoid circular dependencies; if unavoidable, use `@Lazy`

## 7. Optional 使用模式

**推荐：**
```java
// 链式操作
return repository.findById(id)
    .map(mapper::toResponse)
    .orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_NOT_FOUND, id));

// 条件执行
optional.ifPresent(this::process);

// 提供默认值（惰性求值）
String name = optional.orElseGet(() -> generateDefaultName());
```

**反模式：**
```java
// BAD: orElse() 总是急切求值
optional.orElse(expensiveCall());

// BAD: 直接 get() 不检查
optional.get();

// BAD: Optional 作为字段或参数类型
private Optional<String> name;  // 禁止
public void process(Optional<String> input);  // 禁止

// BAD: Optional<Collection<T>> — 返回空集合即可
Optional<List<String>> getNames();  // 禁止，应返回 List<String>
```

**规则：** Optional 仅用于方法返回类型。禁止用于字段、构造器参数、方法参数。

## 8. Record 使用模式

**DTO 全部使用 record**（见 architecture.md）：

```java
// Compact constructor 验证
public record CreateRequest(
    @NotBlank String name,
    @Email String email
) {
    public CreateRequest {
        // 可在此添加跨字段验证
    }
}

// 响应 DTO（无验证注解）
public record EntityResponse(Long id, String name, OffsetDateTime createdAt) {}
```

**record 与 Lombok @Value 选择：**
- `record` 是 Java 21+ 首选（语言级别支持、pattern matching）
- `@Value` 仅在需要继承或 `@Builder` 时使用

## 9. Stream API 最佳实践

**推荐：**
```java
// toList() 替代 collect(Collectors.toList())
List<String> names = users.stream().map(User::getName).toList();

// 处理重复 key
Map<Long, User> byId = users.stream()
    .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

// flatMap 展平
List<Order> allOrders = customers.stream()
    .flatMap(c -> c.getOrders().stream())
    .toList();

// 短路操作
boolean exists = users.stream().anyMatch(u -> u.isActive());
```

**反模式：**
```java
// BAD: peek() 用于非调试目的
stream.peek(System.out::println).toList();

// BAD: 存储 Stream 引用到字段
private Stream<String> names;

// BAD: parallelStream() 不考虑线程安全
list.parallelStream().collect(Collectors.toList());
```

## 10. Sealed 类和接口

```java
// 领域事件类型层级
public sealed interface DomainEvent
    permits EntityCreatedEvent, EntityUpdatedEvent, EntityDeletedEvent {
    Long entityId();
    OffsetDateTime occurredAt();
}

public record EntityCreatedEvent(Long entityId, String name, OffsetDateTime occurredAt)
    implements DomainEvent {}
public record EntityUpdatedEvent(Long entityId, String changes, OffsetDateTime occurredAt)
    implements DomainEvent {}
public record EntityDeletedEvent(Long entityId, String reason, OffsetDateTime occurredAt)
    implements DomainEvent {}

// 穷尽匹配
String action = switch (event) {
    case EntityCreatedEvent e -> "created";
    case EntityUpdatedEvent e -> "updated";
    case EntityDeletedEvent e -> "deleted";
};
```

## 11. 异常链

**规则：** 始终保留根本原因（cause）。

```java
// GOOD: 传递 cause
catch (IOException ex) {
    throw new BusinessException(ErrorCode.INTERNAL_ERROR, ex);
}

// BAD: 吞掉原因
catch (IOException ex) {
    throw new BusinessException(ErrorCode.INTERNAL_ERROR);  // 丢失 ex！
}

// BAD: 空 catch 块
catch (Exception ex) {
    // 静默忽略 — 绝对禁止
}
```

**try-with-resources 的 suppressed exceptions：**
```java
try (var stream = Files.newInputStream(path)) {
    // 使用 stream
} // 自动关闭，异常通过 getSuppressed() 获取
```

## 12. 泛型类型安全

**PECS 原则：Producer extends, Consumer super**

```java
// Producer — 从集合读取，用 extends
void printAll(List<? extends Number> numbers);

// Consumer — 向集合写入，用 super
void addAll(List<? super Integer> target, List<Integer> source);

// 泛型方法签名
public static <T extends Comparable<T>> T max(T a, T b) {
    return a.compareTo(b) >= 0 ? a : b;
}
```

**规则：** 避免 `@SuppressWarnings("unchecked")` 除非类型安全已人工验证并注释原因。

## 13. Text Blocks 与字符串格式化

```java
// 多行字符串（SQL、JSON、正则优先使用 text block）
String sql = """
    SELECT u.id, u.name, u.email
    FROM users u
    WHERE u.status = ?
    ORDER BY u.created_at DESC
    """;

// String.formatted() 替代 String.format()
String greeting = "Hello, %s!".formatted(name);

// 拼接少量字符串直接用 +（javac 自动优化为 StringBuilder）
String message = "User " + name + " created at " + createdAt;
```

## 14. 日期时间

- 使用 `OffsetDateTime`，禁止 `Date` / `Calendar` / `LocalDateTime`
- 时区处理：存储用 UTC（`OffsetDateTime.now(ZoneOffset.UTC)`），展示按用户时区转换
- 时间计算：`Duration`（精确时间） / `Period`（日期）
- 格式化：`DateTimeFormatter.ISO_OFFSET_DATE_TIME`

## 15. 不可变集合

```java
// List.of() — 结构不可变，不支持 null 元素
List<String> immutable = List.of("a", "b", "c");

// Collections.unmodifiableList() — 仅视图，底层集合仍可变
List<String> view = Collections.unmodifiableList(mutableList);

// List.copyOf() — 防御性复制为不可变
List<String> copy = List.copyOf(potentiallyMutableList);

// Stream.toList() — 不可修改但类型不同于 List.of()
List<String> fromStream = stream.toList();

// 收集为不可变列表
List<String> collected = stream.collect(Collectors.toUnmodifiableList());
```

## 16. 并发模式（Java 21 上下文）

**方式一：Spring Boot 配置（推荐）**
```yaml
# application.yml
spring.threads.virtual.enabled: true
```
→ Tomcat 请求处理、`@Async`、`ScheduledTask` 自动使用虚拟线程。

**方式二：手动创建（高级场景）**
```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var future = executor.submit(() -> doSomething());
    future.get(); // 必须检查异常
}
```

**兼容性注意事项：**
- 避免 `synchronized` → 改用 `ReentrantLock`（防止 carrier thread pinning）
- 不要池化虚拟线程（VirtualThreadPerTaskExecutor 不是池）
- `CompletableFuture` 用于异步组合
- 不可变集合作为线程安全默认

## 17. Javadoc 标准

所有 `public` / `protected` 类和方法必须有 Javadoc：

```java
/**
 * Creates a new {Entity} with the given request data.
 *
 * @param request the creation request containing required fields
 * @return the created entity response with generated ID
 * @throws BusinessException if entity with same name already exists
 */
EntityResponse create(CreateRequest request);
```

**规则：**
- 方法 Javadoc：`@param`、`@return`、`@throws`
- 使用 `{@code ...}` 和 `{@link ...}` 标签
- record 组件的 Javadoc 写在组件声明上方
- 禁止无意义 Javadoc（如 `/** Gets the name. */`）

## 18. 异常

- Business exceptions extend `BusinessException`
- Never throw raw `RuntimeException`
- 使用 `ErrorCode` 枚举定义错误码，不要硬编码消息

***

## 决策流程

```
功能需求 → JDK 原生可简洁实现?
  是 → 使用 JDK 原生 API
  否 → 项目使用 Spring?
    是 → Spring 内置工具可满足?
      是 → 使用 Spring 工具类
      否 → Commons 库可显著简化?
    否 → Commons 库可显著简化?
      是 → 引入对应 Commons 模块并注释原因
      否 → 其他库提供必需功能?
        是 → 按需引入 Guava/Hutool 等并注释原因
        否 → 手工实现，保持可读性
```

***

## 附录：常用工具库推荐版本

| 库 | 推荐版本 | 说明 |
|----|----------|------|
| commons-lang3 | 3.17.0 | 与 Spring Boot 4.x 兼容 |
| commons-io | 2.18.0 | 稳定版本 |
| commons-collections4 | 4.4 | 避免使用 commons-collections（旧版） |
| commons-codec | 1.17.1 | 编码工具 |
| guava | 33.4.0-jre | 选择 -jre 变体以获得更好的 Java 兼容性 |
| hutool | 5.8.34 | 按需引入模块 |

> **注意：** Spring Boot 4.x 通过 BOM 管理了大部分依赖版本。使用 `spring-boot-dependencies` BOM 时，无需手动指定 Commons 库版本。

***
***

# Java 契约编程规范

**版本：** 2.0
**生效日期：** 2026-07-03
**适用范围：** 所有基于 Java 21+ 的服务端、客户端及基础库代码（含 Spring Boot 4.0+）

***

## 1. 目的

1. 明确方法的前置条件（Preconditions）、后置条件（Postconditions）与类不变式（Class Invariants）。
2. 通过"快失败"（Fail Fast）原则，尽早暴露错误，降低调试成本。
3. 提高代码可读性，使方法的依赖与约束显式化，契约即文档。

***

## 2. 核心原则

### 2.1 契约即代码

方法的契约应当通过代码显式表达，而非仅依赖注释。

### 2.2 区分输入校验与内部断言

| 场景 | 机制 | 用途 |
|------|------|------|
| 外部输入 | `Preconditions` / `Objects.requireNonNull` | 参数、配置、外部系统返回的校验 |
| 内部假设 | `assert` | 验证代码逻辑假设、不变式、后置条件 |

### 2.3 明确异常类型

违反契约应抛出**非受检异常**：

| 异常类型 | 使用场景 |
|----------|----------|
| `IllegalArgumentException` | 参数值不合法 |
| `IllegalStateException` | 对象状态不正确 |
| `NullPointerException` | 参数或状态为 null（优先使用 `Objects.requireNonNull`） |
| `IndexOutOfBoundsException` | 索引越界 |

### 2.4 信息充分原则

异常信息必须包含：参数/状态名称、预期的约束条件、实际值（若安全且有助于排查）。

***

## 3. 参数校验（前置条件）

### 3.1 强制校验规则

所有 `public` / `protected` 方法的所有参数，必须在方法入口处进行校验。

### 3.2 工具选择优先级

| 优先级 | 工具 | 适用场景 |
|--------|------|----------|
| 1 | `Objects.requireNonNull()` | JDK 原生，仅非空校验 |
| 2 | `org.springframework.util.Assert` | Spring Boot 项目首选，零额外依赖 |
| 3 | `Preconditions` (Guava) | 非 Spring 项目或复杂校验场景 |
| 4 | 自建工具类 | 以上均不可用时 |

### 3.3 Spring Boot 项目示例（使用 `org.springframework.util.Assert`）

```java
import org.springframework.util.Assert;

public void updateUser(@NonNull String userId, int age, List<String> tags) {
    Assert.hasText(userId, "userId must not be blank");
    Assert.notNull(tags, "tags must not be null");
    Assert.isTrue(age >= 0 && age <= 150,
        () -> "age must be in range [0, 150], was: " + age);
    Assert.isTrue(!tags.isEmpty(), "tags must not be empty");
}
```

### 3.4 非 Spring 项目示例（Guava Preconditions）

```java
import java.util.Objects;
import com.google.common.base.Preconditions;

public void updateUser(@Nonnull String userId, int age, List<String> tags) {
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(tags, "tags must not be null");
    Preconditions.checkArgument(!userId.isBlank(), "userId must not be blank");
    Preconditions.checkArgument(age >= 0 && age <= 150,
        "age must be in range [0, 150], was: %s", age);
    Preconditions.checkArgument(!tags.isEmpty(), "tags must not be empty");
}
```

***

## 4. 内部断言（不变式与后置条件）

### 4.1 assert 的使用原则

`assert` 仅用于验证**代码内部逻辑假设**，不可用于外部输入校验。

### 4.2 断言开启配置

| 环境 | JVM 参数 | 说明 |
|------|----------|------|
| 开发/测试 | `-ea` | 开启断言 |
| 生产 | `-da` | 关闭断言（默认） |

**重要：** 断言失败应视为编程错误，不应被 try-catch 捕获处理。

***

## 5. 使用注解声明契约

| 注解 | 来源 | 用途 |
|------|------|------|
| `@NonNull` / `@Nullable` | `jakarta.annotation` (Jakarta EE 11) | 应用代码标准注解（SB4 仍可用） |
| `@Nullable` / `@NullMarked` | `org.jspecify.annotations` (JSpecify) | **SB4 框架首选**，见下方 JSpecify 小节 |
| `@Nonnull` / `@CheckForNull` | `jakarta.annotation` | Jakarta 注解 |
| `@NonNull` | `lombok` | Lombok 项目使用 |

> **注意：** Spring Boot 4 基于 Jakarta EE 11，`javax.*` 工件已完全移除（非 deprecated）。应用代码统一使用 `jakarta.annotation`，禁止使用旧版 `javax.annotation`。

#### JSpecify null-safety（SB4 推荐）

Spring Boot 4 / Spring Framework 7 全量采用 [JSpecify](https://jspecify.dev/) 注解（`org.jspecify.annotations`）表达 null 语义，框架自身 API 均已标注。应用代码可继续使用 `jakarta.annotation`，但新代码推荐向 JSpecify 演进：

```java
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked                    // 标记「null-safe zone」：包/类级，范围内类型默认非空
public class OrderService {

    public Order findById(Long id) {                  // 默认非空返回
        ...
    }

    public Order findByName(@Nullable String name) {   // 显式标注可空参数
        ...
    }
}
```

**关键规则：**
- `@NullMarked` 标注在包（`package-info.java`）或类上，范围内所有类型默认 non-null，仅需为可空处加 `@Nullable`
- 搭配 null checker（如 Checker Framework）或 Kotlin 可在编译期发现潜在 NPE
- **Actuator endpoint 参数禁止使用 `org.springframework.lang.Nullable`**，必须改用 `org.jspecify.annotations.Nullable`（SB4 已移除对前者的支持）

**强制要求：** 校验逻辑必须与注解声明的契约保持一致。

```java
// 正确：声明与校验一致
public void process(@Nonnull String input) {
    Objects.requireNonNull(input, "input must not be null");
}
```

***

## 6. 异常信息规范

信息格式：`[参数/状态名] must [约束条件], but was: [实际值]`

```java
// 数值范围
Preconditions.checkArgument(age >= 0 && age <= 150,
    "age must be in range [0, 150], but was: %s", age);

// 非空字符串
Preconditions.checkArgument(!name.isBlank(),
    "name must not be blank, but was: '%s'", name);

// 状态检查
if (!isInitialized()) {
    throw new IllegalStateException(
        "service must be initialized before use, current state: UNINITIALIZED");
}
```

***

## 7. 契约与继承

子类重写方法时：
- **不能放宽前置条件**（即允许更多非法输入）
- **不能削弱后置条件**（子类型可提供更强保证，但不能保证更少）
- **不能削弱类不变式**

***

## 8. 类不变式（Class Invariants）

```java
public class BankAccount {
    private String accountId;
    private BigDecimal balance;

    public BankAccount(@Nonnull String accountId, @Nonnull BigDecimal initialBalance) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.balance = Objects.requireNonNull(initialBalance, "initialBalance must not be null");
        Preconditions.checkArgument(initialBalance.compareTo(BigDecimal.ZERO) >= 0,
            "initial balance must be non-negative, was: %s", initialBalance);
        assert invariant() : "class invariant violated after construction";
    }

    private boolean invariant() {
        return accountId != null && !accountId.isEmpty()
            && balance != null && balance.compareTo(BigDecimal.ZERO) >= 0;
    }
}
```

***

## 9. 测试契约

为每个公开方法编写负面测试，验证非法输入抛出预期异常：

```java
@Test
void updateUser_NullUserId_ShouldThrowException() {
    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> service.updateUser(null, 25)
    );
    assertEquals("userId must not be null", exception.getMessage());
}

@ParameterizedTest
@ValueSource(ints = {-1, 151, Integer.MIN_VALUE})
void updateUser_InvalidAge_ShouldThrowException(int invalidAge) {
    assertThrows(IllegalArgumentException.class,
        () -> service.updateUser("user1", invalidAge));
}
```

***

## 10. 代码审查清单

- [ ] 所有 `public` / `protected` 方法是否都有参数校验？
- [ ] Spring Boot 项目是否使用了 `jakarta.annotation`（而非 `javax.annotation`）？
- [ ] Actuator endpoint / 框架集成点是否使用 JSpecify（`org.jspecify.annotations`）而非 `org.springframework.lang.Nullable`？
- [ ] 异常信息是否清晰，包含参数名、期望值和实际值？
- [ ] 是否将 `assert` 误用于外部输入校验？
- [ ] 注解声明（`@NonNull` / `@Nullable`）是否与校验逻辑一致？
- [ ] 子类重写方法是否遵循 LSP 原则？
- [ ] 类不变式是否在关键方法后得到维护？
- [ ] 测试是否覆盖了契约的边界情况？

***

## 快速参考：参数校验模板

```java
// 非空
Objects.requireNonNull(param, "paramName must not be null");

// 字符串非空
Assert.hasText(str, "str must not be blank");

// 数值范围
Assert.isTrue(value >= MIN && value <= MAX,
    () -> "value must be in range [" + MIN + ", " + MAX + "], was: " + value);

// 集合非空
Assert.isTrue(!collection.isEmpty(), "collection must not be empty");

// 状态检查
Assert.state(isReady(), "service must be ready");
```

## 快速参考：断言模板

```java
// 后置条件
assert result != null : "method must not return null";

// 不变式
assert invariant() : "class invariant violated";

// 内部假设
assert index >= 0 && index < size : "index out of bounds";
```

***
***

# Java 对象健身操（Object Calisthenics）

**版本：** 1.0
**生效日期：** 2026-07-17
**来源：** Jeff Bay 发表于《The ThoughtWorks Anthology》的 "Object Calisthenics" 练习（共 9 条规则）
**适用范围：** 所有基于 Java 21+ 的后端项目（含 Spring Boot 4.0+）

***

## 1. 定位：刻意练习 → 生产务实分级

对象健身操是 9 条**刻意严苛**的 OO 设计约束，本意是「健身操」式的刻意练习：在 kata 中 100% 遵守全部规则，用约束倒逼出高内聚、低耦合、封装良好的设计直觉。生产代码按下表分级应用，避免教条化。

### 分级说明

| 级别 | 含义 |
|------|------|
| **强制** | 生产代码必须遵守，Code Review 可拦截 |
| **推荐** | 默认遵守；有充分理由可偏离，偏离处建议注释说明 |
| **健身目标** | 练习中 100% 遵守；生产中作为设计信号（闻到坏味道时的重构方向），不作硬性拦截 |

### 9 条规则速查表

| # | 规则 | 核心意图 | 生产级别 |
|---|------|---------|---------|
| 1 | One Level of Indentation per Method（每方法一层缩进） | 方法只做一件事 | 推荐 |
| 2 | Don't Use the ELSE Keyword（不用 else） | 卫语句 / 多态替代分支堆叠 | **强制** |
| 3 | Wrap All Primitives and Strings（包装原始类型与字符串） | 领域概念类型化 | 分级（§2.3） |
| 4 | First Class Collections（集合一等公民） | 集合与其行为封装成类 | 推荐 |
| 5 | One Dot per Line（一行一个点） | 迪米特法则（LoD） | 推荐（例外见 §2.5） |
| 6 | Don't Abbreviate（不缩写） | 名实相符，拒绝歧义 | **强制** |
| 7 | Keep All Entities Small（保持实体小巧） | 单一职责 | 推荐 |
| 8 | No More Than Two Instance Variables（实例变量 ≤ 2） | 高内聚信号 | 健身目标 |
| 9 | No Getters/Setters/Properties（不用访问器） | Tell, Don't Ask | 分级（§2.9） |

***

## 2. 各规则详解与落地

### 2.1 每方法一层缩进（推荐）

**原文意图：** 方法体内只允许一层缩进。嵌套意味着方法在做多件事，迫使把内层逻辑抽取为命名良好的私有方法。

**生产落地：** 原文要求严格一层；生产以「方法体缩进 ≤ 2 层」为默认目标，练习中按严格一层执行。超限时的重构手法（按优先级）：

1. 卫语句提前返回（与 §2.2 配合）
2. 嵌套循环/条件 → 抽取意图命名的私有方法
3. 集合处理 → Stream API（filter / map / reduce 天然消除嵌套）

```java
// BAD: 三层嵌套，多个意图挤在一起
BigDecimal total = BigDecimal.ZERO;
for (Order order : orders) {
    if (order.isActive()) {
        for (OrderItem item : order.getItems()) {
            if (item.isDiscountable()) {
                total = total.add(item.getPrice().multiply(DISCOUNT_RATE));
            }
        }
    }
}

// GOOD: Stream 一层一个意图
BigDecimal total = orders.stream()
        .filter(Order::isActive)
        .flatMap(order -> order.getItems().stream())
        .filter(OrderItem::isDiscountable)
        .map(item -> item.getPrice().multiply(DISCOUNT_RATE))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
```

**例外：** 防御性资源处理中的 try/catch 嵌套（连接/会话借还、目录预创建、静默关闭）允许至 3 层；更深的嵌套应抽取为 `acquireQuietly()` / `releaseQuietly()` 类方法。

### 2.2 不用 else（强制）

**原文意图：** else 是分支堆叠的温床。消除 else 迫使使用卫语句、多态与表驱动，让主流程保持线性、易读。

**生产落地：** 业务代码禁止 `else` 关键字。替代手法（按场景选择）：

| 场景 | 手法 |
|------|------|
| 前置校验 / 边界条件 | 卫语句（`if (...) return/throw`，主流程保持顶格） |
| 类型 / 状态分支 | switch 表达式 + 模式匹配（JDK 21），或 sealed 类型穷尽匹配 |
| 空值分支 | `Optional.map / orElseGet / orElseThrow` |
| 重复出现的行为分支 | 多态 / 策略模式（分支下沉到类型体系） |
| 状态 → 动作映射 | Map 查表（`Map<Status, Handler>`） |

```java
// BAD
public BigDecimal price(Order order) {
    if (order.isVip()) {
        return order.total().multiply(VIP_RATE);
    } else {
        return order.total();
    }
}

// GOOD: 卫语句
public BigDecimal price(Order order) {
    if (order.isVip()) {
        return order.total().multiply(VIP_RATE);
    }
    return order.total();
}
```

**说明：** 卫语句的 `if (...) return` 不算违规；简单取值的二元选择可用三元表达式，但涉及业务分支时优先上表手法。

### 2.3 包装原始类型与字符串（分级）

**原文意图：** `int`、`String` 等原始类型没有领域语义，导致校验与行为散落在各处；包装为类型后规则内聚，误用在编译期暴露。

**生产落地（与 architecture.md §3.2 判断标准一致）：**

| 场景 | 级别 |
|------|------|
| 字段有格式校验 / 业务运算 / 多字段组合，或同一校验出现在 ≥2 个 DTO 或方法中 | **强制包装为 VO** |
| 简单字符串/数值、仅在单个 DTO 中使用 | 不强制 |
| kata 练习中 | 全部包装（健身目标） |

**Java 21 落地：** 非持久化 VO 首选 `record`（语言级不可变 + 模式匹配）；JPA 持久化 VO 用 `@Embeddable`（示例见 architecture.md §3.2 的 `Email`）。

```java
// record 作轻量 VO：校验内聚在 compact constructor
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Assert.isTrue(amount.signum() >= 0,
            () -> "amount must be non-negative, was: " + amount);
    }

    public Money add(Money other) {
        Assert.isTrue(currency.equals(other.currency), "currency mismatch");
        return new Money(amount.add(other.amount), currency);
    }
}
```

**收益：** `create(Money price)` 从类型上杜绝「元/分混淆」，校验规则不再跨 DTO 重复。

### 2.4 集合一等公民（推荐）

**原文意图：** 任何包含集合成员变量的类不应再有其他成员变量 —— 集合及其操作（过滤、聚合、不变式校验）应封装为专用类。

**生产落地：** 集合上存在 ≥1 条业务操作（过滤规则、聚合计算、不变式校验）时，必须封装为专用类，并提供防御性复制 / 不可变视图 + 意图明确的领域方法。

```java
// GOOD: 集合 + 行为封装为一个类
public class OrderItems {
    private final List<OrderItem> items;

    public OrderItems(List<OrderItem> items) {
        Assert.notEmpty(items, "order must contain at least one item");
        this.items = List.copyOf(items);          // 防御性复制
    }

    public Money totalPrice() {
        return items.stream()
                .map(OrderItem::subtotal)
                .reduce(Money.ZERO, Money::add);
    }

    public List<OrderItem> asList() {
        return items;                             // 已不可变，可直接返回
    }
}
```

**例外（JPA）：** 聚合根的 `@OneToMany` 关联由 JPA 管理，不要求单独包装；但必须通过领域方法操作集合（`order.addItem(item)`），禁止向外部暴露可变集合引用。

### 2.5 一行一个点（推荐）

**原文意图：** `a.getB().getC().doSomething()` 意味着调用方深入了对象的内部结构（违反迪米特法则 / Law of Demeter），任何中间结构变化都会波及所有调用方。

**生产落地：** 禁止跨越领域对象的 getter 链；改为 Tell, Don't Ask —— 在对象上声明意图方法。

```java
// BAD: 调用方知道 Order → Customer → Address 的内部结构
String city = order.getCustomer().getAddress().getCity();

// GOOD: 结构封装在 Order 内
String city = order.shippingCity();
```

**明确例外（不算违规）：**

| 例外类型 | 示例 | 理由 |
|----------|------|------|
| Fluent DSL / 建造者链 | `stream().filter(...).map(...).toList()`、`builder().name(...).build()`、RestClient、AssertJ 断言链 | 同一抽象的连续变换，DSL 本义即链式 |
| 异常 / 错误元数据访问 | `ex.getErrorCode().getHttpStatus()`、`ex.getBindingResult().getFieldErrors()` | 框架契约的固定结构，非领域对象结构 |
| DTO / record 组件访问 | `request.address().city()` | DTO 是纯数据载体，组件即公开契约（见 §2.9） |

### 2.6 不缩写（强制）

**原文意图：** 缩写造成歧义与概念分裂（`usr` / `user` / `account` 是同一事物吗？）；名字要长到说明白为止。

**生产落地（呼应篇章一 §5 命名规范）：** 类、方法、字段、变量命名禁止自造缩写；方法名应完整表达意图（`findActiveOrdersByCustomer` 优于 `findActOrdByCust`）。

**例外（约定俗成的通用缩写）：** `id`、`url`、`uri`、`api`、`http`、`db`、`dto`、`vo`、`dao`、`io`、`xml`、`json`。判断标准：**新成员能否不假思索地读出全称** —— 不能就不是通用缩写。

### 2.7 保持实体小巧（推荐）

**原文意图：** 类不超过 50 行、包不超过 10 个文件 —— 用硬性尺寸上限强制单一职责。

**生产落地（尺寸作为信号而非红线）：**

| 信号 | 阈值 | 动作 |
|------|------|------|
| 方法行数 | > 15 行（不含签名/空行/注释） | 抽取私有方法，每个方法一个抽象层级 |
| 类行数 | > ~200 行 | 审视职责，按变化原因拆分（SRP） |
| 公开方法数 | > ~10 个 | 审视是否承担多个角色，考虑拆分协作者 |
| 包结构 | 按业务聚合组织 | 遵循 architecture.md §2 的分层 + 聚合包结构 |

**健身目标：** kata 中严格执行「类 ≤ 50 行、包 ≤ 10 文件」，体会尺寸约束如何逼出职责拆分。

### 2.8 实例变量 ≤ 2（健身目标）

**原文意图：** 实例变量超过 2 个的类几乎必然内聚度不足 —— 一半字段只被一半方法使用。这是 9 条中最严苛的规则，用于极限训练类的分解。

**生产落地：** 不作硬性拦截，作为**设计信号**：

- 行为类（Service、Domain Service、helper）依赖超过 ~5 个 → 审视是否多个职责挤在一个类里，按用例拆分或聚合协作者（把总是同时出现的几个依赖提炼为一个领域服务）
- 出现「字段分组」现象（一半方法只用一半字段）→ 按分组拆类

**例外：** JPA Entity（字段即表列映射）、DTO/record（数据载体）、`@ConfigurationProperties`（配置绑定）天然多字段，不适用本规则。

### 2.9 不用 getter/setter（分级）

**原文意图：** Tell, Don't Ask —— 不要向对象索要数据再替它做决定，把行为放到数据所在的对象里。getter/setter 泛滥是贫血模型的根源。

**生产落地（与 architecture.md §3.1 一致）：**

| 场景 | 级别 |
|------|------|
| 领域 Entity / VO 暴露 public setter | **禁止** —— 状态变更必须走意图明确的领域方法（`activate()` / `rename()`） |
| 「取数据 → 判断 → 改数据」写在调用方 | **禁止** —— 把判断与修改搬进对象内部 |
| 为绕过封装新增 getter | 避免 —— 先问「调用方真正想完成什么」，在对象上声明该意图方法 |

**明确例外：**

| 例外 | 理由 |
|------|------|
| DTO / record 的访问器 | 纯数据载体，组件即序列化契约 |
| JPA Entity 的 getter | 框架反射与 Mapper 读取的务实需要；但只读够用，**不构成放开 setter 的理由** |
| Controller / Mapper 读取 DTO 字段 | 协议转换层的本职 |

```java
// BAD: 调用方替实体做决定
if (user.getLoginFailures() >= 3) {
    user.setStatus(UserStatus.LOCKED);
}

// GOOD: 行为放在数据所在处
user.recordLoginFailure();   // 内部达到阈值自行锁定

// User 内部
public void recordLoginFailure() {
    this.loginFailures++;
    if (this.loginFailures >= MAX_FAILURES) {
        this.status = UserStatus.LOCKED;
    }
}
```

***

## 3. 练习建议（kata）

对象健身操的价值在「练」不在「背」。推荐方式：

1. 选一个 200~500 行的小题目（Conway's Game of Life、银行转账、购物车计价）
2. **100% 遵守全部 9 条严格版**（包括「类 ≤ 50 行」「实例变量 ≤ 2」「零 getter」）
3. 每违反一条就停下来重构，直到全部满足
4. 复盘：哪些约束逼出了好设计（通常是 2/3/5/9），哪些只是痛苦（通常是 8）—— 这正是上文分级取舍的由来

***

## 4. 与既有规范的关系

本篇章不引入与既有规范冲突的规则，对齐关系如下：

| 健身操规则 | 既有规范 |
|-----------|---------|
| §2.3 包装原始类型 | architecture.md §3.2 Value Object（判断标准一致） |
| §2.9 不用 getter/setter | architecture.md §3.1「领域方法替代 setter」、§8 反模式 #1 贫血 Entity |
| §2.4 集合一等公民 | architecture.md §3.1 聚合根通过领域方法操作关联 |
| §2.6 不缩写 | 篇章一 §5 命名规范 |
| §2.1 一层缩进 / §2.2 不用 else | 篇章一 §1.1 可读性第一原则 |
