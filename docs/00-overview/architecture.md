# Architecture

## Purpose and lifecycle

Public online CA distribution projected from NPS-Dev.

## Boundaries

Program.cs: composition/API.

Observability/: telemetry.

db/: persistence.

docs/: operations.

Docker assets: deployment.

Keep responsibilities and dependency direction explicit. Do not add layers or cross-repository sources of truth without a concrete requirement. Record architecture-significant decisions under `docs/04-decisions/`.
