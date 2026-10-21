## What

<!-- One or two sentences on the change. -->

## Why

<!-- Link the issue or explain the motivation. -->

## Checklist

- [ ] Tests added/updated (`make test`)
- [ ] If the Redis key layout, hash ring or Lua script changed: both services updated and the conformance job passes
- [ ] If metrics/alerts changed: dashboard and `docs/RUNBOOK.md` updated
- [ ] If infrastructure changed: `terraform plan` output reviewed
- [ ] ADR added for any architectural decision (`docs/adr`)
