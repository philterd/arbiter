# Try a policy

**Try a Policy** (`/try`, in the sidebar) redacts text or a PDF with one of your Philter policies,
outside any batch, so you can see what a policy finds before you use it. It acts through Philter's
API as you, so the policy is one of yours.

- **Text**: choose a policy, paste up to 2 MB of text, and select **Redact**. The redacted text
  appears beside it.
- **PDF**: choose a policy and a PDF, and select **Redact and download**. Philter returns the
  redacted PDF, which downloads. Philter refuses a PDF larger than its limit.

The policy field offers your policies by name, and any other of yours can be typed. If Philter has
no policy of yours by that name, or cannot apply it (for example, it refers to a custom list you do
not have), the page says so.

**Nothing you try is kept.** Philter Workspace stores neither what you send nor the result, and
adds nothing to a batch. Philter is sent no context, so it keeps no replacement for later requests
and writes no ledger entry, and it is not sent the PDF's filename. Philter may count the types of PII
it found, if its PII count settings are on (see [Philter settings](../admin/philter-settings.md)).

Each try is recorded in Philter Workspace's [audit log](../admin/audit-log.md) as `POLICY_TRY`, with
the policy, whether it was text or a PDF, and its size, never what was sent or returned. Philter
does not audit a redaction like this one.

Redaction is probabilistic: a policy that finds everything in a sample can still miss PII in other
documents, so check results on your own data.
