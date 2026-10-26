#!/bin/sh
# Publishes the debug APK for phones to download, and writes a QR code for its link.
#
# The APK goes to the `apk` branch as a single commit (force-pushed, so the branch never grows), named after the
# current commit so GitHub's raw-file cache can't serve an older build. The QR code encodes
#   https://raw.githubusercontent.com/<owner>/<repo>/apk/bag-it-<sha>.apk
# Scan it, download, open, Install.
#
# Usage (from the repo root, after ./gradlew assembleDebug):  sh android/tools/share-apk.sh
# Needs: git with push access to origin, python with segno (pip install segno).
set -e
cd "$(dirname "$0")/../.."
APK=android/app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || { echo "no APK at $APK: build it first (gradlew assembleDebug)"; exit 1; }

sha=$(git rev-parse --short HEAD)
dirty=$(git status --porcelain -- android/app/src | head -1)
name="bag-it-$sha${dirty:+-dirty}.apk"

# One-file tree and commit, built with git plumbing: the working tree and the current branch are untouched.
blob=$(git hash-object -w "$APK")
tree=$(printf '100644 blob %s\t%s\n' "$blob" "$name" | git mktree)
commit=$(git commit-tree "$tree" -m "Bag It debug build $name (from $(git rev-parse --abbrev-ref HEAD) $sha)")
git push -q -f origin "$commit:refs/heads/apk"

remote=$(git remote get-url origin)
repo=$(printf '%s' "$remote" | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')
url="https://raw.githubusercontent.com/$repo/apk/$name"

out=android/app/build/outputs/apk/debug/download-qr.png
python -c "import segno, sys; segno.make(sys.argv[1], error='m').save(sys.argv[2], scale=10, border=3)" "$url" "$out"
echo "$url"
echo "$out"
