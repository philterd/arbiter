# Security settings

The **Security** tab in Admin (`/admin/security`) contains policies that apply to all users
across the deployment.

## Require MFA for all users

When this checkbox is checked and saved, Arbiter enforces multi-factor authentication for every
account:

- Users who already have an authenticator app configured are unaffected — they continue through
  the normal [TOTP login flow](../user-guide/settings.md#login-flow-with-2fa).
- Users **without** MFA configured are redirected to the setup page (`/settings/mfa/setup`)
  immediately after entering their password. They cannot access any other page until they
  complete enrollment. A yellow banner on the setup page explains why they are there.
- The policy is enforced by `MfaEnrollmentInterceptor` on every request. It re-checks the
  database on each request, so disabling the policy takes effect for the next request without
  requiring a restart.

### Enabling the requirement

1. Navigate to **Admin → Security**.
2. Check **Require MFA for all users**.
3. Click **Save**. An amber warning banner confirms the policy is active.

!!! warning
    Before enabling this policy, communicate the change to your users and allow time for
    them to install an authenticator app (Google Authenticator, Authy, Microsoft Authenticator,
    etc.). Users who log in before setting up MFA will be sent to the setup wizard automatically,
    but they will need a phone or device with an authenticator app available at that moment.

### Disabling the requirement

Uncheck **Require MFA for all users** and click **Save**. Users without MFA configured can
then log in with password alone. Users who already enrolled are not affected — their MFA
remains active until they choose to disable it from their own [Settings page](../user-guide/settings.md#disabling-2fa).

### If a user is locked out

If a user loses their authenticator device and cannot produce a valid TOTP code, the admin
must clear the `mfaEnabled` flag and `totpSecret` field on the user's MongoDB document
directly — the Users page does not currently expose a "Clear MFA" action. Once cleared, the
user can log in with their password and re-enroll from their
[Settings page](../user-guide/settings.md#two-factor-authentication). If the user has also
forgotten their password, reset it first (see
[Resetting a user's password](users-and-groups.md#reset-a-users-password)) before clearing
MFA.

## Audit trail

All changes to security settings are recorded in the [audit log](audit-log.md):

| Event | Trigger |
|---|---|
| `SECURITY_SETTINGS_CHANGE` | Admin saves the Security settings form (logged whether or not the value changed) |
| `MFA_ENABLED` | A user successfully completes MFA setup |
| `MFA_DISABLED` | A user disables MFA from their Settings page |
