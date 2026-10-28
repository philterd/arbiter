# Personal settings

The **Settings** page (`/settings`) is where you change your password and
configure how the Review page navigates. Philter keeps your password,
two-factor authentication, and API keys.

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

## Two-factor authentication

Two-factor authentication is set up at sign-in, when your administrator requires it, rather than on
this page. You scan a QR code with an authenticator app, then enter a code from it after your
password at each sign-in.

## Change password

Enter your current password, the new password, and a confirmation. The new password must be at
least 16 characters and differ from the current one. Philter then ends all of your sessions, so you
sign in again with the new password.

### Forgotten password

Arbiter has no self-service password reset. For a forgotten password or a locked sign-in, ask your
administrator, who resets or unlocks it under **Admin → Users**.

## API keys

To call Arbiter's [REST API](../reference/api.md), use a Philter API key that holds the
`users:read` scope. Philter manages your keys; Arbiter has none of its own. See
[API keys](../security.md#api-keys).

## Audit trail

Every `PASSWORD_CHANGE` and preferences change (`USER_SETTINGS_CHANGE`) is
recorded in the audit log with your username and a timestamp. Admins can
review these from
[Admin Settings → Audit log](../admin/audit-log.md).
