#!/usr/bin/env bash
# =============================================================================
# FantaFormula 1 - compatibility entry point
#
# The original psql-only lifecycle test could not observe Supabase migration
# history, `migration repair`, no-op `db push`, or real CLI transaction
# behavior. It is replaced by the real CLI lifecycle suite.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$SCRIPT_DIR/cli_lifecycle.sh" "$@"
