# ADR 0013: Text-only information responses, file storage deferred

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

When an officer needs more from a claimant, the claimant has to answer. In practice an answer often includes photos, a repair estimate or a police report. Supporting attachments means choosing a storage location, a size limit, allowed content types, malware scanning, access control per claim and retention. That is a substantial feature and not needed to demonstrate the workflow.

## Decision

- An information request has a text `question` (10 to 1000 characters). The claimant's answer is a text `responseText` (10 to 2000 characters). Neither can carry a file.
- The claim stores a police report **number** for motor claims, not the report itself.
- File and document uploads are listed as out of scope. The design notes the extension point: a **storage port** owned by `claim`, an attachment table, and an adapter for local disk or object storage.
- Officers can still ask the claimant to give references (for example a workshop name or a report number) in text.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Store files as database blobs | Grows the database quickly and complicates backups; still needs limits and scanning |
| Store files on local disk | Not portable across instances or containers without a volume strategy |
| Object storage with signed URLs | The right production answer, but needs an external service and credentials |
| Accept a URL to a file stored elsewhere | Shifts trust and security questions to arbitrary external locations |

## Consequences

**Good**
- A smaller, safer scope: no file-handling attack surface, no storage dependency, simpler tests.
- The request and response flow is fully testable with plain JSON.

**Bad**
- Claimants cannot provide photographs or documents, which limits realism for motor and property claims.
- Evidence has to be handled outside the platform until the storage port is added.
- Adding attachments later needs a migration, a port with an adapter, new endpoints and a security review.
