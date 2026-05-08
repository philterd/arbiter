# Destinations

Destinations are the places Arbiter writes finalized redacted documents to.
They are the counterpart of [data sources](data-sources.md): a data source
brings documents *in* for redaction; a destination sends them *out* once the
review process is complete. Multiple destinations of multiple types can
coexist — each one is selectable on a batch when it's finalized.

The page is at `/admin/destinations`, also reachable from the
**Destinations** link in the sidebar's Administration section. ROLE_ADMIN
only.

## Destination types

The page is split into three sections, one per type. Names must be unique
*within* a section (case-insensitive) — so a Local Directory destination and
an S3 destination can both be named `archive` without conflict.

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

### Amazon SQS

Sends finalized documents to an Amazon SQS queue as message bodies.

| Field        | Required | Notes                                                                                                                              |
| ------------ | -------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| Name         | yes      | Unique among SQS destinations (case-insensitive)                                                                                    |
| Queue URL    | yes      | Full SQS queue URL, e.g. `https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue`. The AWS region is parsed from the host.        |
| Access key   | no       | Encrypted at rest                                                                                                                   |
| Secret key   | no       | Encrypted at rest                                                                                                                   |

Access key and secret key are validated as a pair — provide both or neither.
Same ambient-credentials fallback as S3. The same **Configured** /
**Ambient** badge applies in the listing table.

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
| Amazon SQS      | Sends a short text message to the configured queue.                                              |

The test artifact is left behind so an operator can verify it landed where
expected; clean it up by hand if you don't want test files lingering. Test
does not save the destination, so it's safe to use repeatedly while you're
tuning credentials or paths. **Test does not produce an audit-log entry.**

For S3, Arbiter creates the SDK client with cross-region access enabled and
defaults the initial region to `us-east-1`; the SDK redirects to the
bucket's actual region as needed. For SQS, the region is parsed from the
queue URL host (`sqs.<region>.amazonaws.com`); if the URL doesn't match that
shape the test fails up front with *"Could not parse AWS region from the
queue URL."*

## Editing a destination

Each row has an **Edit** button that opens a popup pre-filled with the
saved values. **The destination name cannot be changed** — the modal
displays it read-only and explains that you must remove and re-create the
destination if a different name is needed.

For Local Directory, the only editable field is the directory path.

For S3 and SQS, the credential fields behave the same as on the data
sources page:

- **Leave both blank** to keep the existing credentials untouched.
- **Fill both** to replace the stored credentials (encrypted before save).
- **Provide one without the other** is rejected up front.
- The **Clear stored credentials** checkbox wipes the stored access and
  secret keys, switching the destination back to ambient AWS credentials.
  When checked the credential inputs are disabled in the form. Clear wins
  over any values typed into the credential fields.

Saving fires a `*_DESTINATION_UPDATE` audit event (see the
[Audit trail](#audit-trail) below) with a `credentialsChanged` boolean and,
for S3/SQS, a `credentialsCleared` boolean.

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
| `SQS_DESTINATION_CREATE`     | `SqsDestination`             | SQS destination added; payload includes a `credentialsSet` boolean                                        |
| `SQS_DESTINATION_UPDATE`     | `SqsDestination`             | SQS destination edited; payload includes `credentialsChanged` and `credentialsCleared` booleans           |
| `SQS_DESTINATION_DELETE`     | `SqsDestination`             | SQS destination removed                                                                                  |

The `*_CREATE` and `*_UPDATE` entries record the connection details
(directory path / bucket + key / queue URL) along with the credential
booleans noted above. Encrypted credential values themselves are **never
logged**. The **Test** action does not produce its own audit event — it
doesn't save anything.
