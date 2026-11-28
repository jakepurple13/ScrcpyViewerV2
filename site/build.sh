#!/usr/bin/env bash
# Builds the GitHub Pages site into _site/ (or the folder given as $1).
#
# site/index.html holds placeholders that this script fills from the latest GitHub release:
#   __VERSION__    release version without the "v" (1.28.0)
#   __MAC_URL__    direct link to the macOS .dmg
#   __WIN_URL__    direct link to the Windows NSIS .exe
#   __LINUX_URL__  direct link to the Linux .deb
#   __DEB_NAME__   file name of the .deb, for the apt install command
# A missing installer falls back to the releases/latest page, so the site never links to nothing.
#
# Needs gh (signed in, or GH_TOKEN set) and jq. Run from anywhere: site/build.sh [out-dir]
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="${1:-$root/_site}"
repo="${GH_REPO:-jakepurple13/ScrcpyViewerV2}"
latest="https://github.com/$repo/releases/latest"

release="$(gh api "repos/$repo/releases/latest")"
tag="$(jq -r '.tag_name' <<<"$release")"
version="${tag#v}"

# First asset whose name matches the regex, or empty.
asset() { jq -r --arg re "$1" '[.assets[] | select(.name | test($re))][0] // empty | .browser_download_url' <<<"$release"; }
asset_name() { jq -r --arg re "$1" '[.assets[] | select(.name | test($re))][0] // empty | .name' <<<"$release"; }

mac="$(asset '-mac-.*\.dmg$')"
win="$(asset '-nsis\.exe$')"
linux="$(asset '\.deb$')"
deb="$(asset_name '\.deb$')"

for name in mac win linux; do
  if [[ -z "${!name}" ]]; then
    echo "::warning::No $name installer in $tag; linking to the releases page instead."
    printf -v "$name" '%s' "$latest"
  fi
done
deb="${deb:-ScrcpyViewer-${version}-linux-amd64.deb}"

rm -rf "$out"
mkdir -p "$out"
cp "$root/composeApp/src/jvmMain/resources/icon.png" "$out/icon.png"

# '|' never appears in these values (versions, GitHub URLs, file names), so it is a safe sed delimiter.
sed -e "s|__VERSION__|$version|g" \
    -e "s|__MAC_URL__|$mac|g" \
    -e "s|__WIN_URL__|$win|g" \
    -e "s|__LINUX_URL__|$linux|g" \
    -e "s|__DEB_NAME__|$deb|g" \
    "$root/site/index.html" > "$out/index.html"

if grep -q '__[A-Z_]*__' "$out/index.html"; then
  echo "::error::Unfilled placeholders left in index.html:"
  grep -o '__[A-Z_]*__' "$out/index.html" | sort -u
  exit 1
fi

echo "Built $out for $tag"
