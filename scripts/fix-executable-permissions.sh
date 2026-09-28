#!/usr/bin/env bash
# Script standard de correction des permissions d'exécution pour Android CI & local
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "==> Application des droits d'exécution sur gradlew..."

if [ -f "$ROOT_DIR/gradlew" ]; then
    chmod +x "$ROOT_DIR/gradlew"
    echo "gradlew: chmod +x appliqué"
    if git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        git -C "$ROOT_DIR" update-index --chmod=+x gradlew 2>/dev/null || true
        echo "gradlew: git update-index --chmod=+x appliqué"
    fi
fi

if [ -f "$ROOT_DIR/scripts/fix-executable-permissions.sh" ]; then
    chmod +x "$ROOT_DIR/scripts/fix-executable-permissions.sh"
fi

echo "==> Permissions vérifiées avec succès."
