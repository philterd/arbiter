# Personal settings

The **Settings** page (`/settings`) is where you change your password and
manage your API key.

## Change password

Enter your current password, the new password, and a confirmation. The new
password must be at least 4 characters. On success, your stored password is
replaced with a freshly-salted SHA-512 hash — Arbiter never stores plaintext
passwords (see [Security](../security.md)).

If your current password is wrong, you'll get an error and nothing changes.

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
