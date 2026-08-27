---
name: implement-feature
description: Implement a new feature following the TDD workflow — from requirement analysis through API tests, implementation, contract tests, and documentation update.
when_to_use: Use when asked to implement a new feature or user story, or when a requirement doc in docs/requirements/ needs to be realized as code.
arguments: [feature-name]
allowed-tools:
  - Bash(mvn test)
  - Bash(mvn compile)
  - Read
  - Write
  - Edit
  - Grep
  - Glob
disallowed-tools:
  - Bash(rm *)
  - Bash(git push *)
paths:
  - "**/*.java"
  - "**/*.groovy"
---

# Implement Feature

!`echo "Current branch: $(git branch --show-current 2>/dev/null || echo 'N/A')"`

## Pre-conditions
- [ ] Requirement doc exists in `docs/requirements/`
- [ ] ADR recorded if new tech introduced

## Steps

0. **Validate pre-conditions** — check that `docs/requirements/{feature-name}.md` exists.
   If not found: output `Requirement doc not found: docs/requirements/{feature-name}.md. Create it first using the template in docs/templates/requirement-template.md.` and stop.

1. **Read requirement doc** — extract business rules and acceptance criteria
2. **Prepare test data** — add seed data to `sql/init/data.sql`, create JSON fixtures under `test-data/$feature-name/`
3. **Write failing e2e test (Red)** — @SpringBootTest + @AutoConfigureMockMvc + MockMvc + JSON fixtures + @Sql seed data + DatabaseVerifier
4. **Minimal implementation (Green)** — Controller -> Service -> Repository in layers
5. **Refactor** — check against conventions in `.claude/rules/`, extract duplicates, optimize naming
6. **Write Contract Test** — Spring Cloud Contract Groovy DSL for each new endpoint
7. **Update documentation** — `docs/design/api-spec-v1.md`, `docs/design/domain-model.md`, and `CLAUDE.md` Sprint status

## Downstream Integration Steps (if applicable)

1. Define `{Service}Gateway` interface in `application/port/out/`
2. Create `{Service}GatewayAdapter` in `infrastructure/adapter/out/external/` using RestClient
3. Add `{Feature}Config` in `infrastructure/config/` if not present
4. Add downstream base URL to `application.yml` and `application-test.yml`
5. Create MockFactory/Verifier in `support/mocks/` for WireMock stubs

## Output
- Implementation code (Domain -> Application -> Infrastructure -> Interfaces)
- E2E tests (`*FlowTest.java`)
- JSON fixtures under `test-data/$feature-name/`
- Contract tests (`*.groovy`)
- Updated documentation

## Validation
- `mvn test` passes
- `./scripts/run-contract-tests.sh` passes (if contract tests added)
