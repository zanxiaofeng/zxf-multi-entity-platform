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
3. **Write Failing API Test (Red)**: Write an API test (`*ApiTests`) using WebTestClient + JSON fixtures + @Sql seed data. Stub downstream calls via MockFactory where applicable.
4. **Write Failing Unit Tests (Red)**: Write unit tests for Service logic, Mapper transformations, and Entity domain methods. These tests isolate individual classes and must fail before implementation exists.
5. **Minimal Implementation (Green)**: Implement Controller → Service → Repository in layers. Write only enough code to make the API test and unit tests pass.
6. **Refactor**: Check against `.claude/rules/` (see `code-review.md` for unified checklist), extract duplicates, optimize naming
7. **Contract Test (API layer)**: Write Contract for each new endpoint, generate Stub and verify API contract
8. **Documentation Update**: Update `docs/design/api-spec-v1.md`, `docs/design/domain-model.md`, and `CLAUDE.md` Sprint status

## Downstream Integration Order

When a feature requires calling a downstream service, follow this order:

1. **Define Downstream Client interface FIRST** — create `{ServiceName}Client` interface in `domain/downstream/` before writing any tests
2. **Create MockFactory simultaneously with API test** — build `{Service}MockFactory`/`{Service}MockVerifier` in `support/mocks/` alongside the failing API test
3. Implement `{ServiceName}ClientImpl` in `infrastructure/downstream/` during the Green phase
4. Add `{Feature}Config` in `infrastructure/config/` if not already present
5. Add downstream base URL to `application.yml` and `application-test.yml`

**Why this order matters:**
- The Client interface defines the contract the domain layer depends on
- MockFactory lets the API test stub downstream calls from the start
- Implementation comes last, guided by the tests

## "Done" Criteria

An endpoint or feature is complete only when ALL of the following pass:

| Criterion | Verification |
|-----------|-------------|
| Happy path API test passes | `*ApiTests` — create/read/update/delete with valid input returns expected status and body |
| Error scenario tests pass | Validation errors (400), not found (404), conflict (409), unprocessable entity (422) |
| Unit tests for domain methods pass | `{ClassUnderTest}Test` — Service, Mapper, Entity domain methods |
| Contract test passes for each endpoint | `*ContractTest` — API contract verified via Spring Cloud Contract |

**Additional quality gates:**
- No `@DirtiesContext` unless explicitly justified
- All `@Sql` scripts are idempotent (safe to run multiple times)
- No hardcoded test data — use constants or test fixtures
- WireMock stubs cover both success and error responses from downstream