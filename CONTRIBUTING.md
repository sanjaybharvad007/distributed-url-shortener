# Contributing

## Development loop

```bash
make up          # full stack in Docker
make test        # Java unit + integration, Go with -race
make lint        # Spotless, go vet / golangci-lint, terraform fmt + validate
make smoke       # end-to-end check against the running stack
```

## Rules of the road

- **Shared contracts live in two languages.** The Redis key layout (`RedisKeys.java` ↔ `cache/keys.go`),
  the hash ring and the token-bucket Lua script must change together. CI's `conformance` job compares
  the vector file and the Lua script byte-for-byte; the ring tests in both services load the same vectors.
- **Every alert has a runbook entry.** Add a section to `docs/RUNBOOK.md` and link it from the rule's
  `runbook` annotation.
- **Architecture decisions get an ADR** in `docs/adr` (copy the format of the existing ones).
- **No long-lived cloud credentials.** CI/CD authenticates to AWS via GitHub OIDC only.
- Conventional commit messages (`feat:`, `fix:`, `docs:`, `infra:`, `ci:`) keep the history scannable.
