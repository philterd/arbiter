# PII types

Arbiter knows about the following PII types, mirroring the upstream Phileas
filter set. Every type has a default sensitivity weight used by the
[risk score](risk-score.md); admins can override these per batch on the
batch's weights page.

| Value                   | Display label              | Default weight |
| ----------------------- | -------------------------- | -------------: |
| `age`                   | Age                        | 1              |
| `bank-routing-number`   | Bank Routing Number        | 1              |
| `bitcoin-address`       | Bitcoin Address            | 1              |
| `city`                  | City                       | 1              |
| `county`                | County                     | 1              |
| `credit-card`           | Credit Card                | **10**         |
| `currency`              | Currency                   | 1              |
| `custom-dictionary`     | Custom Dictionary          | 1              |
| `date`                  | Date                       | 1              |
| `drivers-license-number`| Drivers License Number     | 1              |
| `email-address`         | Email Address              | **5**          |
| `first-name`            | First Name                 | **3**          |
| `hospital`              | Hospital                   | 1              |
| `hospital-abbreviation` | Hospital Abbreviation      | 1              |
| `iban-code`             | IBAN Code                  | 1              |
| `id`                    | Identifier                 | 1              |
| `ip-address`            | IP Address                 | 1              |
| `mac-address`           | MAC Address                | 1              |
| `medical-condition`     | Medical Condition          | 1              |
| `other`                 | Other                      | 1              |
| `passport-number`       | Passport Number            | 1              |
| `person`                | Person                     | **3**          |
| `pheye`                 | PHEye                      | 1              |
| `phone-number`          | Phone Number               | **5**          |
| `phone-number-extension`| Phone Number Extension     | 1              |
| `physician-name`        | Physician Name             | **3**          |
| `section`               | Section                    | 1              |
| `ssn`                   | SSN                        | **10**         |
| `state`                 | State                      | 1              |
| `state-abbreviation`    | State Abbreviation         | 1              |
| `street-address`        | Street Address             | **3**          |
| `surname`               | Surname                    | **3**          |
| `tracking-number`       | Tracking Number            | 1              |
| `url`                   | URL                        | 1              |
| `vin`                   | VIN                        | 1              |
| `zip-code`              | Zip Code                   | **2**          |

The values in the left column are the canonical strings used everywhere PII
types appear: span records in MongoDB, the `type` field of `PATCH
/api/v1/spans/{id}`, and the per-batch weights document. The display labels
are what reviewers see in the **PII Navigator** and the **Weights** editor.

## Customizing weights

A weight of `0` is allowed and tells the [risk score](risk-score.md) to
ignore that type entirely (the term still drops out of the unresolved-span
penalty since that's based on status, not weight).

When you save weights for a batch, Arbiter only persists values that
**differ** from the default. So changing the global default for a type
later (in a future Arbiter release) automatically picks up for every batch
that hadn't explicitly overridden it.
