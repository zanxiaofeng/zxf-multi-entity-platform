---
paths:
  - "**/*.java"
  - "**/*.groovy"
  - "**/*.md"
---
# TDD Workflow

## Step-by-Step (Strict Order)

1. **Requirement Analysis**: Read the corresponding requirement doc under `docs/requirements/`
2. **Prepare Test Data**: Add seed data to `src/test/resources/sql-data/init/data.sql`, create JSON fixtures under `src/test/resources/test-data/{entity}/`
3. **Write Failing E2E Test (Red)**: Write an e2e test (`{Entity}FlowTest` in `e2e/`) using `@SpringBootTest` + `@AutoConfigureMockMvc` + MockMvc + JSON fixtures + @Sql seed data. Stub downstream calls via MockFactory where applicable.
4. **Write Failing Unit Tests (Red)**: Write unit tests for Service logic (mock port/out), Mapper transformations, and Entity domain methods. These tests isolate individual classes and must fail before implementation exists.
5. **Minimal Implementation (Green)**: Implement in hexagonal order — Domain model → Application ports & service → Infrastructure adapters (persistence → web). Write only enough code to make the e2e test and unit tests pass.
6. **Refactor**: Check against `.claude/rules/` (see `code-review.md` for unified checklist), extract duplicates, optimize naming
7. **Contract Test (API layer)**: Write Contract for each new endpoint, generate Stub and verify API contract
8. **Documentation Update**: Update `docs/design/api-spec-v1.md`, `docs/design/domain-model.md`, and `CLAUDE.md` Sprint status

## Downstream Integration Order

When a feature requires calling a downstream service, follow this order:

1. **Define Gateway interface FIRST** — create `{Service}Gateway` interface in `application/port/out/` before writing any tests
2. **Create MockFactory simultaneously with e2e test** — build `{Service}MockFactory`/`{Service}MockVerifier` in `support/mocks/` alongside the failing e2e test
3. Implement `{Service}GatewayAdapter` in `infrastructure/adapter/out/external/` during the Green phase
4. Add `{Service}Config` in `infrastructure/adapter/out/external/config/` if not already present（配置就近管理）
5. Add downstream base URL to `application.yml` and `application-test.yml`（`app.downstream.{service}.base-url`）

**Why this order matters:**
- The Gateway interface defines the contract the application layer depends on
- MockFactory lets the e2e test stub downstream calls from the start
- Implementation comes last, guided by the tests

## "Done" Criteria

An endpoint or feature is complete only when ALL of the following pass:

| Criterion | Verification |
|-----------|--------------|
| Happy path e2e test passes | `{Entity}FlowTest` — create/read/update/delete with valid input returns expected status and body |
| Error scenario tests pass | Validation errors (400), not found (404), conflict (409), unprocessable entity (422) |
| Unit tests for domain methods pass | `{ClassUnderTest}Test` — Service (mock port/out), Entity domain methods, PersistenceMapper/WebMapper |
| Contract test passes for each endpoint | `*ContractTest` — API contract verified via Spring Cloud Contract |

**Additional quality gates:**
- No `@DirtiesContext` unless explicitly justified
- All `@Sql` scripts are idempotent (safe to run multiple times)
- No hardcoded test data — use constants or test fixtures
- WireMock stubs cover both success and error responses from downstream
- 外部调用不得出现在 `@Transactional` 方法内（经 EventPublisher + afterCommit，见 `service-conventions.md` §3）
