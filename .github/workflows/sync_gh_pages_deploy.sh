#!/usr/bin/env bash
# Синхронизация deploy/ с актуальным origin/gh-pages для Publish docs.
# prepare — полная выгрузка gh-pages в deploy/ (сразу перед правкой help).
# ensure-not-stale — deploy не должен быть старее origin/gh-pages по папкам версий.
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-deploy}"
REMOTE="${GH_PAGES_REMOTE:-origin/gh-pages}"

version_dirs_from_tree() {
  git ls-tree --name-only "$1" 2>/dev/null \
    | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' \
    | sort -V \
    || true
}

fetch_gh_pages() {
  git fetch origin gh-pages
  if ! git rev-parse --verify "$REMOTE" >/dev/null 2>&1; then
    echo "ERROR: $REMOTE not found — run Publish p2 site first"
    exit 1
  fi
}

prepare_deploy() {
  fetch_gh_pages
  rm -rf "$DEPLOY_DIR"
  mkdir -p "$DEPLOY_DIR"
  git archive "$REMOTE" | tar -x -C "$DEPLOY_DIR"
  test -f "$DEPLOY_DIR/p2.index" || {
    echo "ERROR: $DEPLOY_DIR/p2.index missing after sync — run Publish p2 site first"
    exit 1
  }
  echo "Synced deploy from $REMOTE"
  echo "Version folders: $(version_dirs_from_tree "$REMOTE" | tr '\n' ' ')"
}

ensure_not_stale() {
  fetch_gh_pages
  mapfile -t remote_versions < <(version_dirs_from_tree "$REMOTE")
  mapfile -t deploy_versions < <(
    find "$DEPLOY_DIR" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null \
      | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' \
      | sort -V \
      || true
  )

  missing=()
  for version in "${remote_versions[@]}"; do
    [[ -z "$version" ]] && continue
    if [[ ! -d "$DEPLOY_DIR/$version" ]]; then
      missing+=("$version")
    fi
  done

  if ((${#missing[@]} > 0)); then
    echo "ERROR: deploy/ is stale — missing version folder(s) present on $REMOTE:"
    printf '  - %s\n' "${missing[@]}"
    echo "A Publish p2 site run finished while Publish docs was in progress."
    echo "Re-run Publish docs (Publish p2 site must finish first)."
    exit 1
  fi

  if ((${#remote_versions[@]} > 0)); then
    remote_latest="${remote_versions[-1]}"
    deploy_latest=""
    if ((${#deploy_versions[@]} > 0)); then
      deploy_latest="${deploy_versions[-1]}"
    fi
    if [[ "$remote_latest" != "$deploy_latest" ]]; then
      echo "ERROR: deploy latest version '$deploy_latest' != gh-pages '$remote_latest'"
      echo "Re-run Publish docs after Publish p2 site completes."
      exit 1
    fi
  fi

  echo "deploy/ matches gh-pages version folders (${#remote_versions[@]} total)"
}

case "${1:-}" in
  prepare) prepare_deploy ;;
  ensure-not-stale) ensure_not_stale ;;
  *)
    echo "Usage: $0 {prepare|ensure-not-stale}"
    exit 1
    ;;
esac
