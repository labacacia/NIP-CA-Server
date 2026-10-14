# Review Guide

Start with [START-HERE.md](../START-HERE.md), locate the boundary in the [repository map](00-overview/repository-map.md), and read the [security overview](00-overview/security-overview.md).

## Verification

- `dotnet restore NPS.NipCaServer.csproj`
- `dotnet build NPS.NipCaServer.csproj -c Release --no-restore`
- build the container when Docker changes.

Record exact commands and outcomes in the pull request. Review responsibility, failure modes, cancellation, security, tests, documentation, and canonical-source ownership.
