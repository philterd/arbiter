# Personal settings

The **Settings** page (`/settings`) is where you change your password,
configure how the Review page navigates, and manage your API key.

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

Arbiter supports TOTP-based two-factor authentication (2FA) using any standard authenticator
app — Google Authenticator, Authy, Microsoft Authenticator, 1Password, and similar apps all work.

### Enabling 2FA

1. Go to **Settings** and scroll to the **Two-factor authentication** section.
2. Click **Set up 2FA**. A QR code and a text secret are displayed.
3. Open your authenticator app, add a new account, and scan the QR code. If your app does not
   have a scanner, enter the text secret manually.
4. Type the 6-digit code shown in the app into the **Verification code** field and click
   **Enable 2FA**. The code is time-based and rotates every 30 seconds — use the current one.
5. On success you are redirected back to Settings, and the section will show a green **Enabled**
   badge.

From that point on, every login requires your password **and** a fresh code from your
authenticator app (see [Login flow with 2FA](#login-flow-with-2fa) below).

### Disabling 2FA

Expand the **Disable two-factor authentication** section at the bottom of the 2FA card, enter
a valid code from your authenticator app, and click **Disable 2FA**. You must supply a correct
code — this prevents an unattended session from being used to strip the protection. Once
disabled, subsequent logins no longer prompt for a code.

### Login flow with 2FA

When MFA is enabled on your account, the login sequence is:

1. Enter your email and password as usual.
2. Arbiter redirects you to the **Two-Factor Authentication** challenge page (`/mfa`).
3. Enter the 6-digit code from your authenticator app and click **Verify**.
4. On success you are signed in and redirected to the dashboard.

If the code is wrong you are returned to the challenge page with an error; the password step is
not repeated. If you cannot produce a valid code (e.g. lost device), contact your administrator
— they can disable MFA on your account from the Users page.

### If your administrator requires MFA

When an admin has turned on the **Require MFA for all users** policy, any user without 2FA
configured is redirected to the setup page immediately after entering their password. You cannot
navigate away until you complete enrollment. The setup flow is identical to the one above; a
yellow banner on the page explains why you have been sent there.

## Change password

Enter your current password, the new password, and a confirmation. The new
password must be at least 4 characters. On success, your stored password is
replaced with a freshly-salted SHA-512 hash — Arbiter never stores plaintext
passwords (see [Security](../security.md)).

If your current password is wrong, you'll get an error and nothing changes.

### Forgotten password

Arbiter has no automated self-service password-reset flow (no reset-by-email
link). If you cannot log in because you have forgotten your password, click
**Forgot password?** on the sign-in page. A message appears asking you to
contact your administrator. Your admin can set a new password for you from
**Admin → Users** without needing to know your old one — see
[Resetting a user's password](../admin/users-and-groups.md#reset-a-users-password).

## API key

Your account can have **one** API key at a time. The key authenticates
programmatic requests to `/api/v1/*` using a standard HTTP Authorization
header:

```
Authorization: Bearer <your-api-key>
```

API keys carry the same permissions as your user account — including group
membership and admin role.

### Generate a new key

Click **Generate API key**. The new key is displayed once on the resulting
page in a yellow callout. **Copy it immediately** — Arbiter only stores the
SHA-512 hash of the key, so it cannot be shown again.

If a key was already set, generating a new one replaces the old one.

### Revoke

**Revoke API key** clears the stored hash. Subsequent API requests using the
old key will be rejected.

## Audit trail

Both `API_KEY_GENERATE` and `API_KEY_REVOKE` (and every `PASSWORD_CHANGE`)
are recorded in the audit log with your email and a timestamp. Admins can
review these from
[Admin Settings → Audit log](../admin/audit-log.md).
