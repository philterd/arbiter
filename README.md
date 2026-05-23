# Arbiter

Arbiter is a human-in-the-loop deidentification platform. It runs your
documents through the [Philter / Phileas](https://www.philterd.ai) PII
detector and gives reviewers a fast, focused workflow for confirming or
correcting every detection before the document leaves the redactor.

For full documentation — installation, configuration, user and admin
guides, REST API reference, and more — see
<https://philterd.github.io/arbiter>.

## Quick start

**1. Create your `.env` file with a secret key.**

Copy the example file and generate a cryptographically random 256-bit key:

```shell
cp .env.example .env
echo "ARBITER_CRYPTO_SECRET=$(openssl rand -base64 32)" > .env
```

`ARBITER_CRYPTO_SECRET` must be a base64-encoded 32-byte value. Arbiter
refuses to start if it is missing, not valid base64, or does not decode to
exactly 32 bytes.

**2. Build the Docker image.**

```shell
docker compose build
```

**3. Start Arbiter.**

```shell
docker compose up
```

Arbiter will be available at <http://localhost:8080>. The bootstrap admin
account is `admin@philterd.ai`; the initial password is printed to stdout
on first startup (or you can set `ARBITER_ADMIN_INITIAL_PASSWORD` in
`docker-compose.yaml` before starting).

## Related Philterd open source projects

Arbiter is part of a wider family of open source data-privacy tools from
Philterd. The full catalog lives at
<https://www.philterd.ai/open-source-software/>.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
