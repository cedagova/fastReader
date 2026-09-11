# Android client authentication through reader-api

- Audit ID: `A83`
- Audit key: `android-client-auth`
- Status: In progress
- Dossier PR: https://github.com/cedagova/fastReader/pull/83
- Started: 2026-09-11
- Decision owner: Cesar Gonzalez (cedagova)
- Lead investigator: Claude (audit-lead, cedagova)
- Independent reviewer: Unassigned

## Question

What must a native Android client do, and what must Chunipers/reader-api (with reader-db / Supabase Auth where needed) provide, so that the client can sign in, keep a session, refresh it, and call protected routes with the same identity model reader-web uses today, and which gaps stand in the way?

## Why this matters

AUDIT-TODO: Explain the practical decision this audit must support.

## Targets and baselines

| Repository | Full commit SHA |
| --- | --- |
| `cedagova/fastReader` | `752cfcc9a93d767d56117a4ce11b31a509d7b084` |
| `Chunipers/reader-api` | `fbaa90db5495aa7b6cf995bceb3a54e2f6039ff9` |
| `Chunipers/reader-db` | `9391b283f2a31fff1b664304104338748d881739` |
| `Chunipers/reader-web` | `15a35625e715cd047eed6c2f887c42e647722b95` |

## Scope

### Included

- AUDIT-TODO: List each included surface.

### Excluded

- AUDIT-TODO: List explicit exclusions.

### Scope changes

None.

## Methods

- AUDIT-TODO: Name the evidence methods and the claims each method can prove.

## Coverage inventory

| Surface | Scope | Evidence examined | Result |
| --- | --- | --- | --- |
| AUDIT-TODO | In | AUDIT-TODO | AUDIT-TODO |

## Findings

### Finding index

| ID | Title | Decision | Confidence | Review | Planning readiness | Outcome issue | Outcome umbrella |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `A83-F001` | AUDIT-TODO | Candidate | Low | Pending | Pending | Not required | Not required |

## A83-F001 — AUDIT-TODO: Finding title

- Decision: Candidate
- Confidence: Low
- Review: Pending
- Planning readiness: Pending
- Cause status: Unknown
- Expected implementation repositories: AUDIT-TODO: list one or more evidence-backed `owner/repository` values separated by commas.
- Outcome issue: Not required
- Outcome umbrella: Not required

### Criterion

AUDIT-TODO: State the desired condition or standard.

### Condition and evidence

AUDIT-TODO: State what exists on the pinned baseline and provide reproducible evidence. Label each item Direct, Indirect, or Inference.

### Cause

Unknown.

### Effect

AUDIT-TODO: Explain the practical consequence.

### Recommended outcome

AUDIT-TODO: State what should become true without prescribing unnecessary implementation details.

### Outcome boundary

AUDIT-TODO: State what belongs in this outcome and what does not.

### Cohesion rationale

AUDIT-TODO: Explain why this is one coherent product or system outcome rather than several unrelated findings.

### Outcome acceptance

AUDIT-TODO: List observable conditions that would demonstrate the outcome. Do not prescribe tasks or pull requests.

### Planning inputs

AUDIT-TODO: Record affected surfaces, constraints, dependencies, migration concerns, open questions, and the evidence-backed expected repository footprint a planner must consider.

### Limitations

AUDIT-TODO: Record uncertainty, exclusions, and important counterexamples.

### Decision rationale

Pending.

## Cross-finding analysis

### Duplicates and interactions

None identified.

### Dependencies

None identified.

### Residual unknowns

- AUDIT-TODO: List unresolved questions or state `None.`

## Completion gate

- [ ] The decision owner approved the charter.
- [ ] Every target has a full baseline commit SHA.
- [ ] The coverage inventory accounts for every in-scope surface.
- [ ] Every claim has proportionate, reproducible evidence.
- [ ] The independent review is complete.
- [ ] Every review challenge and gap is reconciled or named as unresolved.
- [ ] Every finding is accepted, rejected, or deferred.
- [ ] Every finding records the evidence-backed repositories expected to change if its recommendation is accepted.
- [ ] Every accepted finding has `Planning readiness: Ready` from the independent reviewer.
- [ ] Every accepted finding links a planning-ready outcome issue.
- [ ] Every outcome issue is a native child of the audit's same-repository outcome umbrella.
- [ ] `summary.md` answers the original question.
- [ ] The Decision-ready semantic anchor has an approved independent review verdict.
- [ ] Any post-review completion delta is limited to mechanical owner decisions and handoff fields.
- [ ] Structural validation passes.
- [ ] The dossier pull request is complete and ready to coordinate downstream delivery.
