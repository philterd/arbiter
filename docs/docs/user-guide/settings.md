# Personal settings

The **Settings** page (`/settings`) is where you change your password and
configure how the Review page navigates. Its **Account** tab also shows your Philter account and
your multi-factor authentication (MFA), and its **API Keys** and **Webhook** tabs manage your
Philter API keys and webhook. Philter keeps all of these; Philter Workspace changes them through
Philter's API as you.

## Your Philter account

The **Account** tab shows your Philter username, email, and role. An administrator changes your
email and role under **Admin → Users**.

## Review page preferences

These options tune how Previous/Next behave on the Review page and what
happens when you approve or reject a document.

- **Skip accepted and rejected documents when navigating with Previous and
  Next** — when on, Prev/Next jump past documents that already have a
  decision (`APPROVED`, `AUTO_APPROVED`, and `REJECTED`) so you only stop
  on documents that still need attention.
- **Automatically go to the next document when a document is approved or
  rejected** — when on, hitting Approve *or* Reject takes you straight to
  the next document (respecting the skip-accepted-and-rejected setting).
  Falls back to the queue if there is no next document.
- **Sort documents when reviewing by** — controls the order Previous/Next walk through
  documents in the batch. All three options use a stable tie-break on
  document id so the order is the same every time you open the page.
    - *Document Risk Score* (default) — highest risk score first, so the
      most likely to need attention bubbles to the top.
    - *Document Priority* — highest priority first (High → Medium → Low),
      useful when uploads are tagged with a priority and you want to work
      that queue strictly by priority.
    - *Document Filename* — alphabetical by filename, for cases where you
      want to walk the batch in a predictable name order.

  This setting also controls the order used by the **Document X of Y**
  counter on the Review page so the counter stays consistent with what
  Previous/Next will do.

Settings are persisted per user in MongoDB (`user_settings` collection).

## Multi-factor authentication

The **Multi-factor authentication** section appears when Philter offers MFA (its `mfaAvailable`
setting) or you are enrolled. With MFA, you enter a code from an authenticator app after your
password at each sign-in.

- **Set up MFA** asks for your current password, then shows a QR code and a key to enter in your
  authenticator app. Enter the code the app shows. Philter then ends your sessions, so you sign in
  again with a code. A wrong password counts toward locking your sign-in.
- **Remove MFA** takes a current code from your app. A wrong code counts toward locking your MFA;
  when it is locked, or you have lost your device, ask an administrator to unlock or remove it under
  **Admin → Users**. When Philter requires MFA (`mfaRequired`), you set it up again at your next
  sign-in.

## Change password

Enter your current password, the new password, and a confirmation. The new password must be at
least 16 characters and differ from the current one. Philter then ends all of your sessions, so you
sign in again with the new password.

### Forgotten password

Philter Workspace has no self-service password reset. For a forgotten password or a locked sign-in, ask your
administrator, who resets or unlocks it under **Admin → Users**.

## API keys

The **API Keys** tab lists your long-lived Philter API keys, with each key's first characters, its
scopes, and when it was created and last used. Your sign-in sessions are not listed.

- **Scopes** clears scopes a key no longer needs. A key keeps at least one scope.
- **Revoke** stops a key working at once, after you confirm. It cannot be restored.
- The key Philter Workspace itself uses (`PHILTER_WORKSPACE_PHILTER_API_KEY`) is marked
  **Philter Workspace's key**. Revoking it, or clearing its `redact` scope, stops ingest and batch
  redaction; clearing `policies:read` stops the batch dialogs listing the policies batches use. Philter's bootstrap key is marked **Bootstrap key**; Philter
  does not recreate it once revoked.

Philter does not let a sign-in session create a key or add a scope to one, so do those with
Philter's API and a long-lived key (see Philter's
[API keys](https://philterd.github.io/philter/latest/account/api_keys.html)).

To call Philter Workspace's [REST API](../reference/api.md), use a Philter API key that holds the
`users:read` scope. Philter Workspace has no keys of its own. See
[API keys](../security.md#api-keys).

## Webhook

Philter calls your webhook when one of your asynchronous redactions completes or fails, signing
each call with a secret you share with the receiving service. Philter Workspace's batches do not
use it. The **Webhook** tab sets it:

- **Webhook URL** must be http or https, and its host must be allowed by Philter's webhook
  destination allowlist, which refuses private and loopback addresses when it is empty.
- **Secret** must be at least 16 characters. **Generate** makes one. Leave it blank to keep the
  current secret. Philter never shows a secret again, so copy a new one to the receiving service
  before you leave the page.
- **Send test event** sends Philter's test event now and says whether the receiver accepted it.
- **Remove webhook** asks you to confirm, then Philter stops calling it.

**Deliveries** lists Philter's calls, newest first, with each one's event, document, status
(`PENDING`, `PROCESSING`, `DELIVERED`, or `FAILED`), attempts, last error, and next attempt.
Philter keeps a delivery that ended for 30 days by default.

## Audit trail

Every `PASSWORD_CHANGE`, preferences change (`USER_SETTINGS_CHANGE`), and change to your MFA,
API keys, or webhook is recorded in the audit log with your username and a timestamp. Admins can
review these from
[Admin Settings → Audit log](../admin/audit-log.md).
