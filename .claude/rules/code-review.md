---
paths:
  - "**/*.java"
---
# Code Review Checklist

## Checklist
- [ ] JavaDoc on all public classes and methods
- [ ] Constructor injection (no @Autowired on fields)
- [ ] DTOs use record
- [ ] Service 默认具体 class;仅多实现/策略时抽接口(勿为单实现强抽接口)
- [ ] No business logic in Controller
- [ ] Test naming: `*ApiTests` for API tests, `*ContractTest` for contract
- [ ] Contract Test covers new endpoints
- [ ] Flyway migration for DB changes
- [ ] Error handling uses BusinessException
- [ ] Downstream client interface exists in domain layer (if calling external services)
- [ ] WireMock MockFactory/Verifier present for downstream calls in API tests

## Architecture Review
- [ ] Four-layer separation maintained (Domain -> Application -> Infrastructure -> Interfaces)
- [ ] Domain layer has no Spring/framework dependencies
- [ ] Repository is interface in domain, implementation in infrastructure
- [ ] Downstream client is interface in domain, implementation in infrastructure

## Refactoring Guide

### Pre-conditions
- [ ] All tests pass
- [ ] Code coverage > 80%

### Steps
1. Identify code smells (duplication, long methods, unclear naming)
2. Write characterization tests if coverage is low
3. Apply refactoring incrementally
4. Run tests after each change
5. Update documentation if public API changed

### Safety Rules
- Never refactor without passing tests
- One refactoring at a time
- Prefer small commits
