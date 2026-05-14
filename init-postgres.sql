-- Demo PostgreSQL fixture for the bundled docker-compose stack.
--
-- The postgres:16-alpine image automatically runs every .sql file under
-- /docker-entrypoint-initdb.d/ the first time the data directory is
-- initialised. This file is mounted there by docker-compose.yaml.
--
-- The DemoDataSourceLoader registers a "Demo PostgreSQL" relational-DB
-- data source whose SQL query reads from the documents table created
-- below. Rows are synthetic, PII-shaped strings so the redaction
-- pipeline has something realistic to chew on end-to-end.

CREATE TABLE IF NOT EXISTS documents (
    id       SERIAL PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    text     TEXT         NOT NULL
);

INSERT INTO documents (filename, text) VALUES
('contract-2026-001.txt',
 'This agreement is entered into between Alice Robertson, residing at 482 Maple Avenue, Atlanta, GA 30303 (SSN 123-45-6789), and Acme Corp. Contact: alice.robertson@example.com or (404) 555-0142.'),
('claim-2026-002.txt',
 'Insurance claim filed by Bob Patel (DOB 1978-03-14, SSN 234-56-7890) for incident on 2026-01-08. Policy 7745-A. Reach the claimant at bob.patel@example.com or 617-555-0177.'),
('hr-onboarding-003.txt',
 'New hire: Charlie Nguyen, Software Engineer, start date 2026-02-01. Bank routing 011000015, account 0123456789. Home address 91 Pine Street, Boston, MA 02108.'),
('support-ticket-004.txt',
 'Customer Dana Cohen called regarding order #44217. Phone (303) 555-0193, email dana.cohen@example.com. Credit card on file ending 4242 (Visa, exp 11/28).'),
('medical-record-005.txt',
 'Patient: Erik Müller. DOB 1965-09-21. MRN MR-558194. Diagnosed 2026-01-12. Emergency contact Greta Müller at +49 30 5551234.'),
('legal-discovery-006.txt',
 'Subpoena response for matter 2026-CV-0087. Custodian: Fatima al-Rashid (employee ID E-44217). Personal email fatima.alrashid@example.com, mobile (415) 555-0103.'),
('vendor-invoice-007.txt',
 'Invoice INV-2026-1187 from Grace O''Connor Consulting LLC. Remit to account 9988776655 at First National Bank, routing 021000021. Tax ID 12-3456789.'),
('expense-report-008.txt',
 'Expense report submitted by Hiroshi Tanaka. Card ending 1881, transactions on 2026-01-15 in Honolulu, HI. Reimburse to direct deposit, routing 122000247.'),
('lease-application-009.txt',
 'Applicant Ines García requests a 12-month lease at 17 Sycamore Lane, Denver, CO 80202. SSN 456-78-9012. Employer phone (720) 555-0166. Income $84,500.'),
('background-check-010.txt',
 'Background check for Jamal Carter (DOB 1990-07-04). Last known address 305 Birch Drive, El Paso, TX 79901. Drivers license TX D12345678. SSN 567-89-0123.'),
('email-thread-011.txt',
 'From: alice.robertson@example.com\nTo: bob.patel@example.com\nSubject: Q1 review\n\nBob - call me at (404) 555-0142 to discuss the Q1 numbers before the 2026-02-15 board meeting.'),
('shipping-manifest-012.txt',
 'Ship to: Charlie Nguyen, 91 Pine Street, Boston, MA 02108. Phone 617-555-0211. Tracking 1Z999AA10123456784. Order placed 2026-01-22.');
