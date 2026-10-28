# Philter Workspace Project Guidelines

Philter Workspace is a human-in-the-loop PII redaction and ML annotation tool designed for high-security, air-gapped environments. These guidelines must be strictly followed to maintain the security and integrity of the system.

## Data Privacy & Logging
- **Strict Prohibition on PII Leakage:** Never include PII (Personally Identifiable Information) in log messages, exception stack traces, or any writes to `System.out` or `System.err`.
- **Log Sanitization:** All logs must be reviewed to ensure they only contain system metadata, transaction IDs, or non-sensitive status updates.
- **Log Levels:** Use appropriate log levels (DEBUG for dev, INFO for production). Ensure DEBUG logs, which might contain more detailed execution flow, are never enabled in production environments where PII is processed.

## Security & Encryption
- **Password Protection:** All passwords (user passwords, database credentials, LDAP secrets) must be encrypted using strong, industry-standard hashing algorithms (e.g., Argon2 or BCrypt) before storage. Never store passwords in plain text or using reversible encryption.
- **Database Encryption:** Any PII stored in the database (original text, identified entity values, etc.) must be encrypted at rest. Use AES-256 or equivalent robust encryption standards.
- **Key Management:** In air-gapped environments, encryption keys must be managed securely and never hardcoded in the source code or checked into version control.

## Coding Standards
- **Spring Security:** Leverage Spring Security's built-in protections for password encoding and authentication flows.
- **Input Validation:** Strictly validate and sanitize all user inputs to prevent injection attacks (SQL, LDAP, or Log injection).
- **Hardcoded Secrets:** Never include API keys, client secrets (OIDC), or database passwords in the codebase. Use environment variables or secure configuration files.

## Human-in-the-Loop Workflow
- **Audit Logs:** Maintain a tamper-evident internal audit log of all human actions (redactions, approvals, batch exports).
- **Independent Review:** Support Blind Double Review logic where required to ensure data quality without compromising reviewer independence.