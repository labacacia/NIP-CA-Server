# Security Overview

CA keys, passphrases, operator keys, database credentials, issued state, and revocations are sensitive. Review all issuance, revocation, crypto, storage, API, and deployment changes.

## Review rules

- Validate external input and fail closed.
- Keep secrets out of source, fixtures, logs, and errors.
- Bound network, parser, file, memory, and concurrency work where applicable.
- Pin and review automation dependencies.
- Add focused tests for changed security behavior.
- Follow [SECURITY.md](../../SECURITY.md) for reports.
