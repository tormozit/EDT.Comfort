#!/usr/bin/env bash
# Проверка site/ перед публикацией на gh-pages.
set -euo pipefail

MODE="${1:-new}"

read_release() {
  grep 'comfort.release=' site/version.txt | head -1 | cut -d= -f2 | tr -d '[:space:]'
}

base_version() {
  echo "${1%%-*}"
}

qualifier() {
  local ver="$1"
  if [[ "$ver" == *-* ]]; then
    echo "${ver#*-}"
  else
    echo ""
  fi
}

RELEASE="$(read_release)"
if [[ -z "$RELEASE" ]]; then
  echo "ERROR: comfort.release not found in site/version.txt"
  exit 1
fi
echo "comfort.release=$RELEASE (mode=$MODE)"

PLUGIN_JAR="$(ls -v site/plugins/tormozit.comfort_*.jar 2>/dev/null | tail -1 || true)"
FEATURE_JAR="$(ls -v site/features/tormozit.comfort.feature_*.jar 2>/dev/null | tail -1 || true)"

if [[ -z "$PLUGIN_JAR" || -z "$FEATURE_JAR" ]]; then
  echo "ERROR: site/plugins or site/features jar missing. Build site/ in PDE (clean.bat + Build All) first."
  exit 1
fi

BUNDLE_FULL="$(unzip -p "$PLUGIN_JAR" META-INF/MANIFEST.MF | tr -d '\r' | grep '^Bundle-Version:' | head -1 | sed 's/^Bundle-Version: //')"
FEATURE_FULL="$(basename "$FEATURE_JAR" .jar | sed 's/tormozit.comfort.feature_//')"

BUNDLE_BASE="$(base_version "$BUNDLE_FULL")"
FEATURE_BASE="$(base_version "$FEATURE_FULL")"

echo "Bundle: $BUNDLE_FULL"
echo "Feature: $FEATURE_FULL"

if [[ "$BUNDLE_BASE" != "$RELEASE" ]]; then
  echo "ERROR: bundle base $BUNDLE_BASE != comfort.release $RELEASE"
  echo "Run site\\release.bat or fix version.txt"
  exit 1
fi

if [[ "$FEATURE_BASE" != "$RELEASE" ]]; then
  echo "ERROR: feature base $FEATURE_BASE != comfort.release $RELEASE"
  exit 1
fi

if [[ "$BUNDLE_BASE" != "$FEATURE_BASE" ]]; then
  echo "ERROR: bundle ($BUNDLE_BASE) and feature ($FEATURE_BASE) mismatch"
  exit 1
fi

CONTENT_XML=""
if [[ -f site/content.jar ]]; then
  CONTENT_XML="$(unzip -p site/content.jar content.xml 2>/dev/null || true)"
elif [[ -f site/content.xml ]]; then
  CONTENT_XML="$(cat site/content.xml)"
fi

if [[ -n "$CONTENT_XML" ]]; then
  CONTENT_BUNDLE="$(echo "$CONTENT_XML" | grep -o "id='tormozit.comfort' version='[^']*'" | head -1 | sed "s/.*version='//;s/'$//")"
  if [[ -n "$CONTENT_BUNDLE" && "$(base_version "$CONTENT_BUNDLE")" != "$RELEASE" ]]; then
    echo "ERROR: content.xml bundle $CONTENT_BUNDLE does not match release $RELEASE"
    exit 1
  fi
  LOCAL_QUAL="$(qualifier "${CONTENT_BUNDLE:-$BUNDLE_FULL}")"
else
  LOCAL_QUAL="$(qualifier "$BUNDLE_FULL")"
fi

if [[ -z "$LOCAL_QUAL" ]]; then
  echo "ERROR: cannot determine build qualifier (expected 1.0.0.N-yyyyMMddHHmm)"
  exit 1
fi

echo "Local qualifier: $LOCAL_QUAL"

if [[ "$MODE" == "resanitize" ]]; then
  echo "Resanitize: skip qualifier comparison — redeploy sanitized metadata for all gh-pages versions"
  echo "Verify OK: release $RELEASE, qualifier $LOCAL_QUAL"
  exit 0
fi

if git fetch origin gh-pages 2>/dev/null && git rev-parse --verify origin/gh-pages >/dev/null 2>&1; then
  LATEST_ON_SITE=""
  if git cat-file -e "origin/gh-pages:content.xml" 2>/dev/null; then
    LATEST_BUNDLE="$(git show origin/gh-pages:content.xml | grep -o "id='tormozit.comfort' version='[^']*'" | head -1 | sed "s/.*version='//;s/'$//")"
    LATEST_ON_SITE="$(base_version "${LATEST_BUNDLE:-}")"
  elif git cat-file -e "origin/gh-pages:compositeContent.xml" 2>/dev/null; then
    LATEST_ON_SITE="$(git show origin/gh-pages:compositeContent.xml | grep -o "location='[^']*'" | head -1 | sed "s/location='//;s/\/'//")"
  fi
  echo "Latest on gh-pages: ${LATEST_ON_SITE:-unknown}"

  if [[ -n "$LATEST_ON_SITE" ]]; then
    if [[ "$MODE" == "new" && "$RELEASE" == "$LATEST_ON_SITE" ]]; then
      echo "ERROR: comfort.release=$RELEASE equals latest on gh-pages."
      echo "Bump comfort.release in site/version.txt, or use workflow mode=resanitize / republish."
      exit 1
    fi

    if [[ "$RELEASE" == "$LATEST_ON_SITE" ]]; then
      REMOTE_XML="$(git show "origin/gh-pages:${RELEASE}/content.xml" 2>/dev/null || true)"
      if [[ -n "$REMOTE_XML" ]]; then
        REMOTE_BUNDLE="$(echo "$REMOTE_XML" | grep -o "id='tormozit.comfort' version='[^']*'" | head -1 | sed "s/.*version='//;s/'$//")"
        REMOTE_QUAL="$(qualifier "$REMOTE_BUNDLE")"
        echo "Remote qualifier on gh-pages: ${REMOTE_QUAL:-none}"
        if [[ -n "$REMOTE_QUAL" && "$LOCAL_QUAL" < "$REMOTE_QUAL" ]]; then
          echo "ERROR: local qualifier $LOCAL_QUAL is older than published $REMOTE_QUAL."
          echo "Rebuild site/ in PDE (clean.bat + Build All) or bump with site\\release.bat"
          exit 1
        fi
        if [[ "$MODE" == "new" && -n "$REMOTE_QUAL" && "$LOCAL_QUAL" == "$REMOTE_QUAL" ]]; then
          echo "ERROR: local qualifier $LOCAL_QUAL equals published $REMOTE_QUAL."
          echo "Use workflow mode=republish for the same build, or rebuild site/ for a new qualifier."
          exit 1
        fi
        if [[ "$MODE" == "republish" && -n "$REMOTE_QUAL" && "$LOCAL_QUAL" == "$REMOTE_QUAL" ]]; then
          echo "Republish: same qualifier as gh-pages — metadata redeploy OK"
        fi
      fi
    fi
  fi
fi

echo "Verify OK: release $RELEASE, qualifier $LOCAL_QUAL"
