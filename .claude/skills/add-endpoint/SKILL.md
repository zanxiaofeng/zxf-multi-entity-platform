---
name: add-endpoint
description: Add a new REST API endpoint following the contract-first approach with API tests and Spring Cloud Contract tests.
when_to_use: Use when asked to add a new endpoint to an existing resource, or when the API spec defines a new endpoint that needs implementation.
arguments: [http-method, resource-path]
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
  - "**/infrastructure/adapter/in/**/*.java"
  - "**/integration/**/*.java"
---

# Add Endpoint

## Pre-conditions
- [ ] API spec updated in `docs/design/api-spec-v1.md`
- [ ] DTOs defined as `record`
- [ ] Service interface has the method

## Steps

0. **Validate pre-conditions** — verify:
   - `docs/design/api-spec-v1.md` contains the endpoint definition
   - Service interface has the required method
   If any condition fails: output the specific missing item and stop.

1. **Add method to Service implementation** — business logic in Service layer
2. **Create/update Controller endpoint** — return `ApiResponse<T>`, URL follows `/api/v1/{resource}`
3. **Write e2e test** — @SpringBootTest + @AutoConfigureMockMvc + MockMvc + JSON fixtures + @Sql seed data
4. **Write Contract Test** — Spring Cloud Contract Groovy DSL
5. **Update OpenAPI spec** if applicable

## Validation Checklist
- [ ] URL follows `/api/v1/{resource}` pattern
- [ ] HTTP Method matches action semantics
- [ ] Response uses `ApiResponse<T>` wrapper
- [ ] Contract Test covers success scenario
- [ ] Contract Test covers error scenarios (400, 404, 500)
- [ ] Downstream side effects documented in API spec (if applicable)
