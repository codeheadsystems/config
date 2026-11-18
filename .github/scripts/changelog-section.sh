#!/usr/bin/env bash
# Prints CHANGELOG.md's section for a version: the lines after "## [VERSION]" up to the next
# "## [" heading or the link references at the end. A pre-release (1.2.0-rc.1) falls back to its
# release's section (1.2.0) when it has none of its own. Exits 1 when there is no section or it is
# empty, so a release without a changelog entry stops before anything is tagged or published.
#
# Usage: changelog-section.sh VERSION [CHANGELOG]
set -euo pipefail

version=${1:?usage: changelog-section.sh VERSION [CHANGELOG]}
changelog=${2:-CHANGELOG.md}

section() {
  awk -v heading="## [$1]" '
    index($0, heading) == 1 { found = 1; next }
    found && (/^## \[/ || /^\[[^]]+\]: /) { exit }
    found { print }
  ' "$changelog" | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}'
}

body=$(section "$version")
if [ -z "$body" ] && [[ $version == *-* ]]; then
  body=$(section "${version%%-*}")
fi

if [ -z "$body" ]; then
  echo "ERROR: $changelog has no entry for $version. Add a '## [$version] - YYYY-MM-DD' section." >&2
  exit 1
fi
printf '%s\n' "$body"
