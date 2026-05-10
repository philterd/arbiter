# Approval rule sets

Approval rules decide when a document needs **dual approval** — a second sign-off
from a different reviewer before it moves to `APPROVED`. Admins manage them
under **Admin → Approval Rules**.

## Why use rule sets?

Approval rule sets let you target the documents that most need a second pair of
eyes without slowing down the rest of the queue. They're useful when:

- A small fraction of your traffic is high-risk (e.g. contains regulated
  identifiers like SSNs) and the rest is routine.
- You want a regular **audit sample** of every reviewer's output, regardless
  of risk.
- Junior reviewers should be backstopped by a senior reviewer until they have
  built up experience.
- Specific business contexts — keywords like *Classified* or *Proprietary*,
  high risk scores, or a reviewer rejecting a high-confidence span — should
  always go to two-person review.

Without rule sets, every document takes one approval. With them, you opt
specific documents into stricter handling.

## How rule sets attach to a batch

A batch can have **zero or more rule sets** attached to it. Within a single
rule set, all conditions must be true (AND). Across rule sets on the same
batch, any rule set firing is enough (OR). So:

- **One rule set, three conditions** → all three must be true.
- **Three rule sets, one condition each** → any one of the three triggers
  dual approval.
- **No rule sets** → single approval, like the rest of the queue.

This (AND-within, OR-across) shape lets you compose policies like
"(high risk AND inexperienced reviewer) OR (contains SSN) OR (2% audit
sample)" by adding three rule sets to the batch.

## Available rules

| Rule | What it checks | Cutoff |
| ---- | -------------- | ------ |
| **Sensitive identifier present** | Document contains an `SSN`, `CREDIT_CARD`, or `PASSPORT_NUMBER` span | none (boolean) |
| **Risk score above threshold** | Document risk score ≥ cutoff | 0–1, default `0.9` |
| **High-confidence rejection** | Any rejected span with confidence ≥ cutoff | 0–1, default `0.95` |
| **Inexperienced reviewer** | Approving reviewer has fewer than N prior approvals | integer ≥0, default `100` |
| **Manual-redaction threshold** | Reviewer manually added more than N spans | integer ≥0, default `5` |
| **Classified keywords** | Case-insensitive match against any admin-defined keyword | comma-separated list, default *Classified, Proprietary, Secret* |
| **Dual-approval sampling rate** | Per-document random roll < cutoff (decided at ingest, stable thereafter) | 0.0–1.0, default `0.02` |

Each document gets one stable random roll for the sampling-rate rule when it
is first persisted, so the audit-sample decision is consistent across reviews.

## Adding and editing rule sets

Both creating and editing rule sets happen through dialogs on
**Admin → Approval Rules** so the page itself stays a clean overview of
what's configured.

- **Add a rule set.** Click **Add Rule Set** at the top of the page. A
  dialog opens with the same fields as before — pick the batch, tick the
  rule conditions you want (all conditions tick within a rule set are
  AND-ed), fill in any threshold values, and click **Add rule set**. If
  there are no open batches the button is disabled and a hint explains
  that you need to open a batch first.
- **Edit an existing rule set.** Click **Edit conditions** on the rule
  set's row in the **Existing rule sets** table. The same dialog opens,
  pre-populated with the rule set's current state. Adjust the conditions
  and thresholds, then click **Save changes** to commit. **Cancel**, the
  **×**, the backdrop, or **Escape** dismisses the dialog without saving.

These dialogs replace the inline forms that previously expanded inside the
table — the underlying `/admin/rules` and
`/admin/rules/{batchId}/{ruleSetId}` POST endpoints are unchanged, so any
external automation that drives them keeps working without modification.

## Example configurations

### 1. Audit sample of every batch

**Goal:** continuously audit reviewer output. About 2% of documents go to
dual approval regardless of content.

| Batch          | Rule set | Conditions |
| -------------- | -------- | ---------- |
| `Customer-Tier-1` | RS-Audit | Dual-approval sampling rate = `0.02` |

Anything not picked by the sample lands in single approval. Increase the rate
during onboarding or after policy changes; lower it once reviewers are stable.

### 2. Sensitive identifiers always require two approvals

**Goal:** any document with an SSN, credit-card, or passport span always goes
to dual approval — no other conditions need to apply.

| Batch     | Rule set    | Conditions |
| --------- | ----------- | ---------- |
| `Banking` | RS-Sensitive | Sensitive identifier present |

Because there's a single condition in the rule set, AND-within is moot.

### 3. Keyword-driven escalation OR audit sample

**Goal:** any document tagged with classified-style keywords goes to dual
approval, *and* on top of that, audit ~3% of everything else.

| Batch        | Rule set   | Conditions |
| ------------ | ---------- | ---------- |
| `Legal-Hold` | RS-Keywords | Classified keywords = `Classified, Proprietary, Secret, Privileged` |
| `Legal-Hold` | RS-Audit    | Dual-approval sampling rate = `0.03` |

Two rule sets on the same batch — OR semantics apply. A document satisfying
either rule set requires dual approval.

### 4. Inexperienced reviewer on high-risk documents

**Goal:** reviewers with fewer than 50 prior approvals must have their work
double-checked, *but only* on documents whose risk score is ≥ 0.7. Senior
reviewers are unaffected; low-risk documents handled by juniors are unaffected.

| Batch     | Rule set       | Conditions |
| --------- | -------------- | ---------- |
| `Triage`  | RS-Junior-Risk | Inexperienced reviewer (< 50) AND Risk score ≥ 0.7 |

Both conditions sit in **one** rule set, so they AND. Removing either drops
the rule set's effect.

### 5. Heavy manual editing OR high-confidence rejection

**Goal:** if a reviewer either added > 8 manual redactions (suggesting the
model under-detected) or rejected a high-confidence span (suggesting the
model over-detected), have a second reviewer confirm.

| Batch       | Rule set         | Conditions |
| ----------- | ---------------- | ---------- |
| `Operations` | RS-Manual-Heavy  | Manual-redaction threshold > 8 |
| `Operations` | RS-Bad-Reject    | High-confidence rejection ≥ 0.95 |

Two independent triggers — OR semantics across rule sets.

### 6. Layered example (the full picture)

**Goal:** a healthcare batch where dual approval is mandatory whenever the
document is sensitive, looks risky, was reviewed by someone new, *or*
randomly when sampled.

| Batch       | Rule set            | Conditions |
| ----------- | ------------------- | ---------- |
| `PHI-Inbox` | RS-Sensitive        | Sensitive identifier present |
| `PHI-Inbox` | RS-High-Risk-Junior | Risk score ≥ 0.85 AND Inexperienced reviewer (< 75) |
| `PHI-Inbox` | RS-Keywords         | Classified keywords = `PHI, HIPAA, Patient` |
| `PHI-Inbox` | RS-Audit            | Dual-approval sampling rate = `0.02` |

Any one of the four rule sets firing sends the document to dual approval.
Within `RS-High-Risk-Junior`, *both* conditions must hold for that rule set
to count.

## Tips

- **Start narrow.** Begin with a single rule set on the highest-risk batches
  (e.g. *Sensitive identifier present*) and add more only after you see how
  it affects throughput.
- **Audit sampling is cheap insurance.** A 1–3% sampling rate gives you a
  steady stream of dual-reviewed documents you can use to spot drift without
  meaningfully slowing the queue.
- **Tune the inexperienced-reviewer cutoff to your team.** The default of
  100 prior approvals is conservative; lower it for small teams.
- **Audit log entries** for rule-set changes appear under
  **Admin → Audit log** as `RULE_SET_CREATE`, `RULE_SET_UPDATE`, and
  `RULE_SET_DELETE` events.
