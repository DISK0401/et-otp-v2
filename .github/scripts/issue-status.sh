#!/usr/bin/env bash
# Keeps issues open until the release that contains their fix is published.
#
# develop is the default branch, so "Fixes #1" would close an issue as soon as the fix reaches develop,
# before it is released. Instead:
#
#   issue-status.sh mark <sha> <by> < text
#       For each issue referenced in the text ("Refs #1", "Fixes #1", "Closes #1", "Resolves #1"):
#       reopen it if a closing keyword closed it, add the label "fixed in develop", and record <sha>
#       (the develop commit with the fix) in one comment of this workflow. <by> is shown in the comment,
#       for example "#31" or "commit 9af07ac". Lines of reverts ('Revert "..."') are left out. Then it waits up
#       to CLOSE_WAIT seconds (default 120) for GitHub to close the issues of closing keywords, and reopens them.
#
#   issue-status.sh release <tag> <release-url>
#       Closes every open issue with the label whose recorded commit is contained in <tag>, and removes the
#       label from the closed ones whose recorded commit is contained in <tag>.
#
# Environment: GITHUB_REPOSITORY (owner/name), GH_TOKEN; DRY_RUN=true only prints the changes.
set -euo pipefail

repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"
label="fixed in develop"
marker="fixed-in-develop"

run() {
  if [ "${DRY_RUN:-}" = true ]; then
    echo "DRY RUN: $*"
  else
    "$@"
  fi
}

# Prints "<number> <closing 0|1>" for each referenced issue, once, closing if any reference closes it.
references() {
  # not in HTML comments, such as the examples in the pull request template, and not in the lines of reverts,
  # which quote the reverted message: 'Revert "Fix the lock (Fixes #12)"' as a subject, the title of a pull
  # request or a line of a squashed commit
  perl -0pe 's/<!--.*?-->//gs' \
    | grep -viE '^[[:space:]]*([*-][[:space:]]+)?revert[[:space:]]+"' \
    | grep -oiE '(^|[^[:alnum:]_])(refs?|fix(es|ed)?|close[sd]?|resolve[sd]?)[[:space:]]*:?[[:space:]]+#[0-9]+' \
    | sed -E 's/^[^[:alpha:]]*//' \
    | awk '{
        kw = tolower($1); sub(/:$/, "", kw); n = $NF; sub(/^#/, "", n)
        closing = (kw ~ /^(fix|close|resolve)/) ? 1 : 0
        if (!(n in c) || closing) c[n] = closing
      }
      END { for (n in c) print n, c[n] }' \
    || true
}

# Prints the recorded commits of the issue, one per line. Each run that merges a fix adds a comment, never
# changes one, so runs at the same time cannot replace a newer record with an older one. Only comments of
# this workflow and of maintainers count: anyone else could write a marker with another commit.
recorded_shas() {
  gh api "repos/$repo/issues/$1/comments" --paginate \
    --jq ".[] | select(.user.login == \"github-actions[bot]\"
                       or (.author_association | IN(\"OWNER\", \"MEMBER\", \"COLLABORATOR\")))
               | select(.body | test(\"<!-- $marker: [0-9a-f]{40} -->\"))
               | .body | capture(\"<!-- $marker: (?<s>[0-9a-f]{40}) -->\").s"
}

# Prints the recorded commits of the issue that are not contained in the given commit or tag.
not_contained() {
  local shas sha status
  shas="$(recorded_shas "$1")"
  for sha in $shas; do
    status="$(compare "$sha" "$2")"
    if [ "$status" != ahead ] && [ "$status" != identical ]; then
      echo "$sha"
    fi
  done
}

# Prints the status of head compared with base: "ahead" or "identical" if base is contained in head,
# "unknown" if they cannot be compared (for example a commit that does not exist).
compare() {
  gh api "repos/$repo/compare/$1...$2" --jq .status 2>/dev/null || echo unknown
}

label_checked=""

# Creates the label on the first use (gh issue edit fails with a missing label).
ensure_label() {
  if [ -z "$label_checked" ]; then
    label_checked=yes
    if ! gh label list --repo "$repo" --search "$label" --json name --jq '.[].name' | grep -qxF "$label"; then
      run gh label create "$label" --repo "$repo" --color c2e0c6 \
        --description "Fixed in develop, closed when the release is published / develop で対応済み。リリースの公開時に閉じる"
    fi
  fi
}

# Succeeds if the latest release contains <sha>.
released() {
  local latest status
  latest="$(gh release view --repo "$repo" --json tagName --jq .tagName 2>/dev/null || true)"
  status="$( [ -n "$latest" ] && compare "$1" "$latest" || echo unknown)"
  [ "$status" = ahead ] || [ "$status" = identical ]
}

# Succeeds if the issue was closed last by a commit contained in <sha> ("Fixes #1" reaching develop); not if
# it was closed by hand or by "Close released issues" (for example when mark is run again after the release).
closed_by_fix() {
  local number="$1" sha="$2" closed_by status
  # called in conditions, where set -e does not apply: a failed lookup must fail the run, not look like
  # "closed by hand"
  if ! closed_by="$(gh api "repos/$repo/issues/$number/events" --paginate \
                      --jq '.[] | select(.event == "closed") | .commit_id // ""' | tail -n 1)"; then
    echo "#$number: the events cannot be read" >&2
    exit 1
  fi
  if [ -z "$closed_by" ]; then
    echo "#$number: closed without a commit (by hand or on a release), skipped"
    return 1
  fi
  status="$(compare "$closed_by" "$sha")"
  if [ "$status" != ahead ] && [ "$status" != identical ]; then
    echo "#$number: closed by $closed_by, which is not on develop ($status), skipped"
    return 1
  fi
}

# GitHub may close the issues of "Fixes #1" only after mark has looked at them. Waits up to CLOSE_WAIT
# seconds for the open ones and reopens them when a fix on develop closes them.
await_closing() {
  local sha="$1" waiting="$2" deadline number left state
  deadline=$(( SECONDS + ${CLOSE_WAIT:-120} ))
  while [ -n "$waiting" ]; do
    left=""
    for number in $waiting; do
      state="$(gh api "repos/$repo/issues/$number" --jq .state)"
      if [ "$state" != closed ]; then
        left="$left $number"
      elif released "$sha"; then
        echo "#$number: closed, and $sha is released already"
      elif closed_by_fix "$number" "$sha"; then
        run gh issue reopen "$number" --repo "$repo"
        run gh issue edit "$number" --repo "$repo" --add-label "$label"
        echo "#$number: reopened, closed by GitHub after it was marked"
      fi
    done
    waiting="${left# }"
    if [ -z "$waiting" ] || [ "$SECONDS" -ge "$deadline" ]; then
      break
    fi
    sleep 10
  done
  for number in $waiting; do
    echo "#$number: still open after ${CLOSE_WAIT:-120} seconds, not closed by GitHub"
  done
}

mark() {
  local sha="$1" by="$2" refs number closing info state is_pr recorded r status body covered latest waiting=""
  refs="$(references)"
  if [ -z "$refs" ]; then
    echo "No issue is referenced."
    return 0
  fi
  body="develop に取り込みました（$by）。この変更を含むリリースを公開したときに、この Issue を閉じます。
Merged into develop ($by). This issue is closed when a release that contains the change is published.

<!-- $marker: $sha -->"
  while read -r number closing; do
    if ! info="$(gh api "repos/$repo/issues/$number" --jq '"\(.state) \(.pull_request != null)"' 2>/dev/null)"; then
      echo "#$number: not found, skipped"
      continue
    fi
    read -r state is_pr <<< "$info"
    if [ "$is_pr" = true ]; then
      echo "#$number: a pull request, skipped"
      continue
    fi
    if [ "$state" = closed ]; then
      if [ "$closing" != 1 ]; then
        echo "#$number: closed and only referenced, skipped"
        continue
      fi
      if ! closed_by_fix "$number" "$sha"; then
        continue
      fi
      run gh issue reopen "$number" --repo "$repo"
    elif [ "$closing" = 1 ]; then
      waiting="$waiting $number"
    fi
    ensure_label
    run gh issue edit "$number" --repo "$repo" --add-label "$label"
    recorded="$(recorded_shas "$number")"
    covered=""
    for r in $recorded; do
      status="$(compare "$sha" "$r")"
      if [ "$status" = ahead ] || [ "$status" = identical ]; then
        covered="$r" # already recorded, or a newer record contains this commit (a run started again)
        break
      fi
    done
    if [ -n "$covered" ]; then
      echo "#$number: $sha is contained in the recorded $covered"
      continue
    fi
    run gh issue comment "$number" --repo "$repo" --body "$body"
    echo "#$number: recorded $sha"
    # a release published meanwhile may have closed the issue without this fix, or removed the label
    if [ "$(gh api "repos/$repo/issues/$number" --jq .state)" = closed ]; then
      if ! released "$sha"; then
        run gh issue reopen "$number" --repo "$repo"
        run gh issue edit "$number" --repo "$repo" --add-label "$label"
        echo "#$number: reopened, closed by a release without $sha"
      fi
    else
      run gh issue edit "$number" --repo "$repo" --add-label "$label"
    fi
  done <<< "$refs"
  await_closing "$sha" "${waiting# }"
}

release() {
  local tag="$1" url="$2" numbers closed number shas missing
  # assigned first: a failure must fail the workflow, not look like "no issues"
  numbers="$(gh issue list --repo "$repo" --label "$label" --state open --limit 500 --json number --jq '.[].number')"
  # listed before the open ones are closed below, which must not be counted twice
  closed="$(gh issue list --repo "$repo" --label "$label" --state closed --limit 500 --json number --jq '.[].number')"
  for number in $numbers; do
    shas="$(recorded_shas "$number")"
    if [ -z "$shas" ]; then
      echo "::warning::#$number has the label \"$label\" but no recorded commit; close it yourself when released."
      continue
    fi
    missing="$(not_contained "$number" "$tag")"
    if [ -n "$missing" ]; then
      echo "#$number: not all fixes are in $tag ($(echo $missing)), stays open"
      continue
    fi
    run gh issue comment "$number" --repo "$repo" --body "$tag で公開しました。
Released in $tag.

$url"
    run gh issue close "$number" --repo "$repo" --reason completed
    run gh issue edit "$number" --repo "$repo" --remove-label "$label"
    # a fix merged while this ran is recorded after the check above: then the issue is not released yet
    missing="$(not_contained "$number" "$tag")"
    if [ -n "$missing" ]; then
      run gh issue reopen "$number" --repo "$repo"
      run gh issue edit "$number" --repo "$repo" --add-label "$label"
      echo "#$number: reopened, a newer fix ($(echo $missing)) is not in $tag"
      continue
    fi
    echo "#$number: closed, released in $tag"
  done
  # closed by GitHub later than the wait of mark: only the label is left when the fix is released
  for number in $closed; do
    shas="$(recorded_shas "$number")"
    if [ -z "$shas" ] || [ -n "$(not_contained "$number" "$tag")" ]; then
      echo "::warning::#$number is closed with the label \"$label\" but not released in $tag; check it yourself."
      continue
    fi
    run gh issue comment "$number" --repo "$repo" --body "$tag で公開しました。
Released in $tag.

$url"
    run gh issue edit "$number" --repo "$repo" --remove-label "$label"
    # a fix merged while this ran may have reopened the issue and recorded a commit that is not in the tag
    missing="$(not_contained "$number" "$tag")"
    if [ -n "$missing" ]; then
      run gh issue edit "$number" --repo "$repo" --add-label "$label"
      echo "#$number: label kept, a newer fix ($(echo $missing)) is not in $tag"
      continue
    fi
    echo "#$number: closed before the release, label removed, released in $tag"
  done
}

case "${1:-}" in
  mark)
    mark "$2" "$3"
    ;;
  release)
    release "$2" "$3"
    ;;
  *)
    echo "usage: $0 mark <sha> <by> < text | $0 release <tag> <release-url>" >&2
    exit 2
    ;;
esac
