# Destinations

Destinations are the places Arbiter writes finalized redacted documents to.
They are the counterpart of [data sources](data-sources.md): a data source
brings documents *in* for redaction; a destination sends them *out* once the
review process is complete. Multiple destinations of multiple types can
coexist.

The page is at `/admin/destinations`, also reachable from the
**Destinations** link in the sidebar's Administration section. ROLE_ADMIN
only.

> **Status:** Destinations can be **created**, **edited**, **removed**, and
> **tested** from this page today, but Arbiter does not yet emit finalized
> documents to them. Batches do not currently expose a destination picker, and
> the finalize flow does not write to any destination. Configurations created
> here are stored and verified (the **Test** button works end-to-end) but
> remain inert until destination-write support is wired into finalize. Treat
> the page as configuration the system will start using once that support
> lands rather than a live output channel today.

## Destination types

The page is split into two tabs — **Local Directory** and **Amazon S3**
— one per type. Click a tab to switch between them. The selected tab
is reflected in the URL (`?tab=…`), so refreshing or sharing the URL
preserves the tab. Names must be unique *within* a tab (case-insensitive)
— so a Local Directory destination and an S3 destination can both be
named `archive` without conflict.

### Local Directory

Writes finalized documents to a directory on the **application server's**
filesystem.

| Field          | Required | Notes                                                                         |
| -------------- | -------- | ----------------------------------------------------------------------------- |
| Name           | yes      | Display name; unique among local destinations (case-insensitive)              |
| Directory path | yes      | Absolute path on the application server's filesystem                          |

No credentials — the directory is written with the application's process
identity, so make sure the path exists, is a directory, and is writable by
that user.

### Amazon S3

Writes finalized documents to an Amazon S3 bucket under the configured key
prefix.

| Field        | Required | Notes                                                                                                                |
| ------------ | -------- | -------------------------------------------------------------------------------------------------------------------- |
| Name         | yes      | Unique among S3 destinations (case-insensitive)                                                                       |
| Bucket name  | yes      | The bucket to write to                                                                                                |
| Bucket key   | yes      | Object-key prefix under which finalized documents are placed (e.g. `finalized/`)                                       |
| Access key   | no       | Encrypted at rest                                                                                                     |
| Secret key   | no       | Encrypted at rest                                                                                                     |

Access key and secret key are validated as a pair — provide both or neither.
Leaving them blank means Arbiter uses whatever ambient AWS credentials the
application process has (environment variables, instance profile, shared
credentials file, etc.). The listing table shows a **Credentials** badge
that reads **Configured** if explicit credentials were stored, **Ambient**
otherwise.

#### Recommended IAM policy

Grant the configured access key **write-only access scoped to the bucket
and key prefix** above — and nothing else. Delivering redacted output needs
`s3:PutObject` on the objects under the prefix. No read, delete, or
bucket-admin permissions are required.

The following policy is a starting point — replace `my-redacted-bucket` and
`finalized/` with your actual bucket name and key prefix:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ArbiterWriteObjects",
      "Effect": "Allow",
      "Action": "s3:PutObject",
      "Resource": "arn:aws:s3:::my-redacted-bucket/finalized/*"
    }
  ]
}
```

This is a suggestion, not a substitute for AWS's own guidance. For
authoritative details on writing least-privilege S3 policies — including
prefix conditions, object-tag conditions, SSE-KMS key access, and the
permission semantics for non-AWS S3-compatible services (MinIO, Cloudflare
R2, Backblaze B2, etc.) — refer to the AWS documentation:

- [Identity and access management in Amazon S3](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-access-control.html)
- [Bucket policies and user policies](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-iam-policies.html)
- [Actions, resources, and condition keys for Amazon S3](https://docs.aws.amazon.com/service-authorization/latest/reference/list_amazons3.html)

For non-AWS S3-compatible services, consult that vendor's documentation —
the action names and ARN format are usually compatible, but the policy
attachment mechanics differ.

If you use the **Test** button (see below), the test code path also calls
`s3:PutObject` — so the same write-only policy is sufficient. The test
object is not deleted by Arbiter; if you want test artefacts cleaned up
automatically, grant `s3:DeleteObject` separately, or remove them by hand.

## Testing a destination

Beside each **Add** button there is a **Test** button that runs an immediate
end-to-end probe **using the values currently in the form**. The result is
shown in a popup with either a green *Success* bar (and a description of
what was written) or a red error bar (with the underlying message — for
example *AccessDenied*, *NoSuchBucket*, *Permission denied*).

What each Test does:

| Type            | What the Test button does                                                                       |
| --------------- | ----------------------------------------------------------------------------------------------- |
| Local Directory | Writes a small file `arbiter-test-<epoch>.txt` to the configured directory.                      |
| Amazon S3       | Puts a small text object at `<bucketKey>/arbiter-test-<epoch>.txt` in the configured bucket.     |

The test artifact is left behind so an operator can verify it landed where
expected; clean it up by hand if you don't want test files lingering. Test
does not save the destination, so it's safe to use repeatedly while you're
tuning credentials or paths. **Test does not produce an audit-log entry.**

For S3, Arbiter creates the SDK client with cross-region access enabled and
defaults the initial region to `us-east-1`; the SDK redirects to the
bucket's actual region as needed.

## Editing a destination

Each row has an **Edit** button that opens a popup pre-filled with the
saved values. **The destination name cannot be changed** — the modal
displays it read-only and explains that you must remove and re-create the
destination if a different name is needed.

For Local Directory, the only editable field is the directory path.

For S3, the credential fields behave the same as on the data sources
page:

- **Leave both blank** to keep the existing credentials untouched.
- **Fill both** to replace the stored credentials (encrypted before save).
- **Provide one without the other** is rejected up front.
- The **Clear stored credentials** checkbox wipes the stored access and
  secret keys, switching the destination back to ambient AWS credentials.
  When checked the credential inputs are disabled in the form. Clear wins
  over any values typed into the credential fields.

Saving fires a `*_DESTINATION_UPDATE` audit event (see the
[Audit trail](#audit-trail) below) with a `credentialsChanged` boolean and,
for S3, a `credentialsCleared` boolean.

## Credential encryption

Every credential typed into the Destinations page is encrypted with AES-GCM
before being written to MongoDB. The same scheme protects Philter API keys
and data-source passwords — see
[Security · Philter API keys](../security.md#philter-api-keys) for the full
description (key derivation, IV format, base64 layout, and the
`arbiter.crypto.secret` property). The plaintext is never displayed back;
the table only shows a **Configured** / **Ambient** badge.

## Removing a destination

Each row has a **Remove** button that deletes the destination after a
confirmation prompt. Removal is hard-delete; nothing is moved to a trash
collection.

## Audit trail

Every change is recorded in the [audit log](audit-log.md) with the actor's
email and the affected destination's id and name:

| Action                       | Resource                     | When fired                                                                                              |
| ---------------------------- | ---------------------------- | ------------------------------------------------------------------------------------------------------- |
| `LOCAL_DESTINATION_CREATE`   | `LocalDirectoryDestination`  | Local directory destination added                                                                        |
| `LOCAL_DESTINATION_UPDATE`   | `LocalDirectoryDestination`  | Local directory destination edited                                                                       |
| `LOCAL_DESTINATION_DELETE`   | `LocalDirectoryDestination`  | Local directory destination removed                                                                      |
| `S3_DESTINATION_CREATE`      | `S3Destination`              | S3 destination added; payload includes a `credentialsSet` boolean                                         |
| `S3_DESTINATION_UPDATE`      | `S3Destination`              | S3 destination edited; payload includes `credentialsChanged` and `credentialsCleared` booleans            |
| `S3_DESTINATION_DELETE`      | `S3Destination`              | S3 destination removed                                                                                   |

The `*_CREATE` and `*_UPDATE` entries record the connection details
(directory path / bucket + key / queue URL) along with the credential
booleans noted above. Encrypted credential values themselves are **never
logged**. The **Test** action does not produce its own audit event — it
doesn't save anything.
