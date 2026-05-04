#!/usr/bin/env bash
#
# Copyright 2026 Philterd
#
# Example curl commands for interacting with Arbiter's REST API.
#
# Usage:
#   ./api-examples.sh             # print every example
#   ./api-examples.sh ingest      # filter to a section (e.g. ingest, queue, spans, policies, llm)
#   ARBITER_URL=http://host:port ARBITER_API_KEY=... ./api-examples.sh
#
# To actually run an example, copy the printed curl command into your shell.
# Most endpoints require an API key issued from the Settings page (Generate API key).
# A user's session cookie also works for the API surface, but a Bearer token is
# the cleanest fit for scripting.

set -u

# ---------------------------------------------------------------------------
# Configuration - override via env vars or edit here.
# ---------------------------------------------------------------------------
ARBITER_URL="${ARBITER_URL:-http://localhost:8080}"
ARBITER_API_KEY="${ARBITER_API_KEY:-YOUR_API_KEY_HERE}"

# IDs used as placeholders in examples.
BATCH_ID="${BATCH_ID:-batch-uuid-here}"
DOCUMENT_ID="${DOCUMENT_ID:-document-uuid-here}"
SPAN_ID="${SPAN_ID:-span-uuid-here}"
PHILTER_INSTANCE_ID="${PHILTER_INSTANCE_ID:-embedded}"
POLICY_NAME="${POLICY_NAME:-Default}"
OLLAMA_INSTANCE_ID="${OLLAMA_INSTANCE_ID:-ollama-uuid-here}"
OLLAMA_MODEL="${OLLAMA_MODEL:-llama3}"

# ---------------------------------------------------------------------------
# Print helpers
# ---------------------------------------------------------------------------
SECTION_FILTER="${1:-}"

bold()    { printf '\033[1m%s\033[0m\n' "$*"; }
heading() { printf '\n\033[1;34m== %s ==\033[0m\n' "$*"; }
note()    { printf '  # %s\n' "$*"; }
cmd()     { printf '  %s\n\n' "$*"; }

section() {
    local key="$1"; shift
    local title="$1"; shift
    if [[ -n "$SECTION_FILTER" && "$SECTION_FILTER" != "$key" ]]; then
        return 1
    fi
    heading "$title"
    return 0
}

bold "Arbiter API examples"
echo "  base URL : $ARBITER_URL"
echo "  api key  : ${ARBITER_API_KEY:0:6}…  (export ARBITER_API_KEY to set)"
echo
echo "  Authentication header used everywhere below:"
echo "    Authorization: Bearer \$ARBITER_API_KEY"
echo
echo "  All API responses are JSON unless noted. Routes under /api/v1 are admin-or-group-member"
echo "  scoped; admin-only routes (e.g. /api/v1/policies) require the ADMIN role."
echo

# ---------------------------------------------------------------------------
# Ingest a document
# ---------------------------------------------------------------------------
if section ingest "Ingest"; then
    note "Submit a document to a batch for asynchronous redaction."
    note "POST /api/v1/ingest  (admin or group-member of the batch)"
    cmd "curl -X POST '$ARBITER_URL/api/v1/ingest' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY' \\
    -H 'Content-Type: application/json' \\
    -d '{
      \"batchId\": \"$BATCH_ID\",
      \"name\": \"contract-2026-04.txt\",
      \"text\": \"Patient John Doe (SSN 123-45-6789) was admitted on 2026-04-01.\"
    }'"
    note "Returns: {\"taskId\":\"<documentId>\"}. The document goes into PENDING then transitions"
    note "to REVIEW_REQUIRED / AUTO_APPROVED based on the batch's thresholds."
fi

# ---------------------------------------------------------------------------
# Queue & batches
# ---------------------------------------------------------------------------
if section queue "Queue & batches"; then
    note "List batches the caller can see (only ones in their groups by default)."
    note "GET /api/v1/batches"
    cmd "curl -G '$ARBITER_URL/api/v1/batches' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"

    note "Same, but for an admin to see every batch system-wide."
    cmd "curl -G '$ARBITER_URL/api/v1/batches' \\
    --data-urlencode 'myGroupsOnly=false' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"

    note "List the queue, paginated. Filterable by batch, status, and (case-insensitive) filename."
    note "GET /api/v1/queue?batchId=&status=&filename=&page=&size=&sort=&dir="
    cmd "curl -G '$ARBITER_URL/api/v1/queue' \\
    --data-urlencode 'page=0' \\
    --data-urlencode 'size=10' \\
    --data-urlencode 'status=REVIEW_REQUIRED' \\
    --data-urlencode 'filename=contract' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"
fi

# ---------------------------------------------------------------------------
# Spans (review)
# ---------------------------------------------------------------------------
if section spans "Spans (review actions)"; then
    note "Get every detected span for a document."
    note "GET /api/v1/documents/{id}/spans"
    cmd "curl '$ARBITER_URL/api/v1/documents/$DOCUMENT_ID/spans' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"

    note "Manually create a span (selection-based add). Reviewer marks a substring as PII."
    note "POST /api/v1/documents/{documentId}/spans"
    cmd "curl -X POST '$ARBITER_URL/api/v1/documents/$DOCUMENT_ID/spans' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY' \\
    -H 'Content-Type: application/json' \\
    -d '{\"start\": 12, \"end\": 20, \"type\": \"person\"}'"

    note "Update a span: change status (APPROVED/REJECTED/PENDING) and/or PII type."
    note "PATCH /api/v1/spans/{id}"
    cmd "curl -X PATCH '$ARBITER_URL/api/v1/spans/$SPAN_ID' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY' \\
    -H 'Content-Type: application/json' \\
    -d '{\"status\":\"APPROVED\",\"type\":\"phone-number\"}'"

    note "Delete a manually-created span (auto-detected spans cannot be deleted; use REJECTED)."
    note "DELETE /api/v1/spans/{id}"
    cmd "curl -X DELETE '$ARBITER_URL/api/v1/spans/$SPAN_ID' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"

    note "Redact every other occurrence of this span's text in the document."
    note "POST /api/v1/spans/{id}/redact-like"
    cmd "curl -X POST '$ARBITER_URL/api/v1/spans/$SPAN_ID/redact-like' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"
fi

# ---------------------------------------------------------------------------
# Document lifecycle
# ---------------------------------------------------------------------------
if section documents "Document lifecycle"; then
    note "Finalize a document: applies APPROVED spans and returns the redacted text."
    note "POST /api/v1/documents/{id}/finalize"
    cmd "curl -X POST '$ARBITER_URL/api/v1/documents/$DOCUMENT_ID/finalize' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"

    note "Per-document audit log (redaction + review history)."
    note "GET /api/v1/documents/{id}/audit"
    cmd "curl '$ARBITER_URL/api/v1/documents/$DOCUMENT_ID/audit' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"
fi

# ---------------------------------------------------------------------------
# Policies (admin-only)
# ---------------------------------------------------------------------------
if section policies "Policies (admin-only)"; then
    note "List policies for an instance. instanceId='embedded' (or empty) hits the local"
    note "MongoDB collection; an instance ID hits that Philter instance's /api/policies."
    note "GET /api/v1/policies?instanceId=embedded"
    cmd "curl -G '$ARBITER_URL/api/v1/policies' \\
    --data-urlencode 'instanceId=$PHILTER_INSTANCE_ID' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"

    note "Fetch the JSON content of a single policy."
    note "GET /api/v1/policies/content?instanceId=…&name=…"
    cmd "curl -G '$ARBITER_URL/api/v1/policies/content' \\
    --data-urlencode 'instanceId=$PHILTER_INSTANCE_ID' \\
    --data-urlencode 'name=$POLICY_NAME' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"
fi

# ---------------------------------------------------------------------------
# LLM-as-a-Judge (Ollama integration)
# ---------------------------------------------------------------------------
if section llm "LLM-as-a-Judge"; then
    note "List models available on a registered Ollama instance."
    note "GET /api/v1/ollama/{instanceId}/models"
    cmd "curl '$ARBITER_URL/api/v1/ollama/$OLLAMA_INSTANCE_ID/models' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"

    note "Ask an LLM to explain the PII findings on a document."
    note "POST /api/v1/documents/{documentId}/explain"
    cmd "curl -X POST '$ARBITER_URL/api/v1/documents/$DOCUMENT_ID/explain' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY' \\
    -H 'Content-Type: application/json' \\
    -d '{\"instanceId\":\"$OLLAMA_INSTANCE_ID\",\"model\":\"$OLLAMA_MODEL\"}'"

    note "Ask the configured Second-Opinion model to review one specific span."
    note "POST /api/v1/spans/{spanId}/second-opinion"
    cmd "curl -X POST '$ARBITER_URL/api/v1/spans/$SPAN_ID/second-opinion' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"
fi

# ---------------------------------------------------------------------------
# Comments
# ---------------------------------------------------------------------------
if section comments "Document comments"; then
    note "List comments on a document."
    note "GET /api/v1/documents/{id}/comments"
    cmd "curl '$ARBITER_URL/api/v1/documents/$DOCUMENT_ID/comments' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"

    note "Post a comment."
    note "POST /api/v1/documents/{id}/comments"
    cmd "curl -X POST '$ARBITER_URL/api/v1/documents/$DOCUMENT_ID/comments' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY' \\
    -H 'Content-Type: application/json' \\
    -d '{\"text\":\"Looks good — approved on second pass.\"}'"
fi

# ---------------------------------------------------------------------------
# End-to-end mini-walkthrough
# ---------------------------------------------------------------------------
if section walkthrough "End-to-end walkthrough"; then
    note "The shape of a typical scripted ingestion → review → finalize flow:"
    note ""
    note "1. Ingest a document (returns taskId == documentId)"
    cmd "DOC_ID=\$(curl -s -X POST '$ARBITER_URL/api/v1/ingest' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY' \\
    -H 'Content-Type: application/json' \\
    -d '{\"batchId\":\"'\"\$BATCH_ID\"'\",\"name\":\"sample.txt\",\"text\":\"Jane Doe email jane@example.com\"}' \\
    | jq -r .taskId)"

    note "2. Wait briefly (ingestion is async) then poll for spans"
    cmd "sleep 2; curl -s '$ARBITER_URL/api/v1/documents/'\"\$DOC_ID\"'/spans' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY' | jq ."

    note "3. Approve every PENDING span (one PATCH per span id)"
    cmd "curl -s -X PATCH '$ARBITER_URL/api/v1/spans/'\"\$SPAN_ID\" \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY' \\
    -H 'Content-Type: application/json' \\
    -d '{\"status\":\"APPROVED\"}'"

    note "4. Finalize and capture the redacted output"
    cmd "curl -s -X POST '$ARBITER_URL/api/v1/documents/'\"\$DOC_ID\"'/finalize' \\
    -H 'Authorization: Bearer \$ARBITER_API_KEY'"
fi

if [[ -n "$SECTION_FILTER" ]]; then
    echo
    bold "Filtered to section: $SECTION_FILTER"
fi
