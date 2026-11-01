#!/usr/bin/env bash
# Build StackCam and publish it as a GitHub release so the in-app updater can find it.
#
#   ./release.sh 2.2 "- 新增功能 A\n- 修正 B"        # bump (if needed), build, test, commit, publish
#   ./release.sh 2.2 --notes-file notes.md          # notes from a file
#   ./release.sh 2.1 "..." --dry-run                # build + test only, no commit / no publish
#
# If the version differs from app/build.gradle.kts, versionName is set to it and versionCode is
# incremented by 1 (the installer refuses an update whose versionCode is not higher).
set -euo pipefail

REPO="nkuo-git/stackcam"
cd "$(dirname "$0")"

VERSION="${1:-}"; shift || true
NOTES=""; NOTES_FILE=""; DRY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --notes-file) NOTES_FILE="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) NOTES="$1"; shift ;;
  esac
done
VERSION="${VERSION#v}"
if [[ ! "$VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
  echo "usage: $0 <version e.g. 2.2> \"<release notes>\" [--notes-file FILE] [--dry-run]" >&2; exit 1
fi
if [[ -z "$NOTES" && -z "$NOTES_FILE" ]]; then NOTES="StackCam $VERSION"; fi
TAG="v$VERSION"
GRADLE=app/build.gradle.kts

[[ -f ~/tools/env.sh ]] && source ~/tools/env.sh
KS="$HOME/.android/debug.keystore"
if [[ ! -f "$KS" ]]; then
  if [[ -f /workspace/keystore-backup/debug.keystore ]]; then
    mkdir -p "$HOME/.android"; cp /workspace/keystore-backup/debug.keystore "$KS"
    echo "Restored debug keystore from /workspace/keystore-backup"
  else
    echo "ERROR: $KS missing - a new key would break updates over the installed app." >&2; exit 1
  fi
fi

CUR_NAME=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' "$GRADLE")
CUR_CODE=$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' "$GRADLE")
if [[ "$CUR_NAME" != "$VERSION" ]]; then
  if [[ $DRY -eq 1 ]]; then echo "dry run: version in $GRADLE is $CUR_NAME, not $VERSION" >&2; exit 1; fi
  NEW_CODE=$((CUR_CODE + 1))
  sed -i -E "s/versionName = \"[^\"]+\"/versionName = \"$VERSION\"/; s/versionCode = [0-9]+/versionCode = $NEW_CODE/" "$GRADLE"
  echo "Bumped version: $CUR_NAME ($CUR_CODE) -> $VERSION ($NEW_CODE)"
fi

./gradlew --console=plain testDebugUnitTest assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk StackCam.apk
cp StackCam.apk "StackCam-$TAG.apk"
echo "Built StackCam.apk ($(du -h StackCam.apk | cut -f1))"

if [[ $DRY -eq 1 ]]; then echo "dry run: skipping commit and GitHub release"; exit 0; fi

if ! git diff --quiet -- "$GRADLE" || [[ -n "$(git status --porcelain)" ]]; then
  git add -A && git commit -m "Release $TAG" || true
fi
git tag -f "$TAG"
if git remote get-url origin >/dev/null 2>&1; then
  git push origin HEAD
  git push -f origin "$TAG"
fi

gh auth status >/dev/null 2>&1 || { echo "ERROR: gh is not logged in (run: gh auth login)" >&2; exit 1; }
if [[ -n "$NOTES_FILE" ]]; then
  gh release create "$TAG" StackCam.apk --repo "$REPO" --title "StackCam $VERSION" --notes-file "$NOTES_FILE"
else
  gh release create "$TAG" StackCam.apk --repo "$REPO" --title "StackCam $VERSION" --notes "$(printf '%b' "$NOTES")"
fi
echo "Published $TAG -> https://github.com/$REPO/releases/tag/$TAG"
