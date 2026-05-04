# Arbiter

Arbiter is a web application that provides the ability to upload text and documents for redaction by [Philter](https://www.philterd.ai/philter) via Philter's API or [Phileas](https://github.com/philterd/phileas) directly.

## Features

- **Multi-format Support**: Redact plain text and searchable PDF documents.
- **Interactive Redaction**: Review and modify redactions in a modern web interface.
- **Manual Redaction**: Highlight text to add custom redactions with specific PII types.
- **Dual Redaction Engines**:
    - **Philter**: Uses a remote Philter instance via its API.
    - **Phileas**: Uses the Phileas library directly within the application.
- **PDF Preview**: View redacted PDF documents directly in the browser.
- **Download**: Export redacted documents in their original format.

## Project Structure

Arbiter is a multi-module Maven project:

- `arbiter-core`: Contains common data models for redactions and responses.
- `arbiter-philter-client`: Client implementation for interacting with the Philter API.
- `arbiter-service`: Core service logic and Phileas integration.
- `arbiter-webapp`: Spring Boot web application with Thymeleaf templates.
- `arbiter-api`: REST API for programmatic access to Arbiter's redaction services.

## Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.6 or higher

### Configuration

The application can be configured to use either a remote Philter instance or its bundled local Phileas library. Philter instances are managed at runtime by an administrator under **Admin → Philter** in the web UI:

- **To use Philter**: Sign in as an admin, register one or more Philter instances (name, endpoint, port), then choose one as the default. Documents are then redacted via that instance.
- **To use Phileas**: Leave the default Philter instance unset. Arbiter falls back to the bundled local Phileas library.

### Building the Project

From the root directory, run:

```bash
mvn clean install
```

### Running the Application

After building, you can run the web application:

```bash
mvn spring-boot:run -pl arbiter-webapp
```

The application will be available at `http://localhost:8081`.

## API documentation

Arbiter exposes its REST API as an OpenAPI 3 definition, served by Spring at runtime:

- **Swagger UI**: <http://localhost:8081/swagger-ui.html>
- **OpenAPI JSON**: <http://localhost:8081/v3/api-docs>
- **OpenAPI YAML**: <http://localhost:8081/v3/api-docs.yaml>

The API requires a Bearer API key. Generate one from the **Settings** page (`/settings`)
and click **Authorize** in the Swagger UI to make calls.

## Usage

1. **Upload**: Select a `.txt` or `.pdf` file to upload.
2. **Review**: The application will show the redacted text. Highlights indicate PII that has been found.
3. **Modify**:
    - Click a highlighted redaction to remove it and restore the original text.
    - Select any text to add a manual redaction. A floating menu will allow you to choose the PII type.
4. **Preview**: For PDF files, use the "View PDF" toggle to see how the redacted PDF looks.
5. **Download**: Click "Download Redacted Document" to get the final version.

## License

Apache License 2.0
