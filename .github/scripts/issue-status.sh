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
#       for example "#31" or "commit 9af07ac". A closing keyword in a line of a revert (`Revert "..."`) takes
#       the fix back: the issue is reopened if the revert closed it, and loses the label. Then it waits up
#       to CLOSE_WAIT seconds (default 120) for GitHub to close the issues of closing keywords, and reopens them.
#
#   issue-status.sh release <tag> <release-url>
#       Closes every open issue with the label whose recorded commit is contained in <tag>, and removes the
#       label from the closed ones whose recorded commit is contained in <tag>.
#
# Environment: GITHUB_REPOSITORY (owner/name), GH_TOKEN; DRY_RUN=true only prints the changes.
set -euo pipefail
# a failed command in $(...) fails the assignment, not only the subshell: an API error must not look like
# "nothing recorded"
shopt -s inherit_errexit

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

# Prints "<number> <kind>" for each referenced issue, once: kind 1 if a reference closes it ("Fixes #1"),
# else 2 if a revert closes it, else 0 ("Refs #1").
#
# Not in HTML comments, such as the examples in the pull request template. A line of a revert quotes the
# reverted message: 'Revert "Fix the lock (Fixes #12)"' as a subject, the title of a pull request or a line of
# a squashed commit. GitHub closes #12 with it, but the fix is taken back (kind 2). 'Revert "Revert "..."'
# applies the fix again, so an even number of reverts counts as the message itself.
references() {
  perl -0pe 's/<!--.*?-->//gs' \
    | perl -ne '
        my $l = $_; my $reverts = 0;
        $l =~ s/^\s*(?:[*-]\s+)?//;
        $reverts++ while $l =~ s/^revert\s+"//i;
        while ($l =~ /(?:^|[^[:alnum:]_])(refs?|fix(?:es|ed)?|close[sd]?|resolve[sd]?)\s*:?\s+#([0-9]+)/gi) {
          my ($kw, $n) = (lc $1, $2);
          my $closing = $kw =~ /^(fix|close|resolve)/ ? 1 : 0;
          next if $reverts % 2 && !$closing;
          my $kind = $reverts % 2 ? 2 : $closing;
          $c{$n} = $kind if !exists $c{$n} || $kind == 1 || ($kind == 2 && $c{$n} == 0);
        }
        END { print "$_ $c{$_}\n" for sort { $a <=> $b } keys %c }' \
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
# Returns 2 if the issue was closed without a commit, 1 if by a commit that is not on develop.
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
    echo "#$number: closed without a commit (by hand or on a release, or the event is not listed yet)"
    return 2
  fi
  status="$(compare "$closed_by" "$sha")"
  if [ "$status" != ahead ] && [ "$status" != identical ]; then
    echo "#$number: closed by $closed_by, which is not on develop ($status), skipped"
    return 1
  fi
}

# GitHub may close the issues of "Fixes #1" only after mark has looked at them. Waits up to CLOSE_WAIT
# seconds for the open ones ("<number>:<kind>", kind as in references) and reopens them when a commit on
# develop closes them; with the label for a fix, without it for a revert.
await_closing() {
  local sha="$1" waiting="$2" deadline item number kind left state result
  deadline=$(( SECONDS + ${CLOSE_WAIT:-120} ))
  while [ -n "$waiting" ]; do
    left=""
    for item in $waiting; do
      number="${item%:*}" kind="${item#*:}"
      state="$(gh api "repos/$repo/issues/$number" --jq .state)"
      if [ "$state" != closed ]; then
        left="$left $item"
        continue
      fi
      if [ "$kind" = 1 ] && released "$sha"; then
        echo "#$number: closed, and $sha is released already"
        continue
      fi
      result=0
      closed_by_fix "$number" "$sha" || result=$?
      if [ "$result" = 2 ]; then
        left="$left $item" # the closed event may not be listed yet
      elif [ "$result" = 0 ]; then
        run gh issue reopen "$number" --repo "$repo"
        if [ "$kind" = 1 ]; then
          run gh issue edit "$number" --repo "$repo" --add-label "$label"
        fi
        echo "#$number: reopened, closed by GitHub after it was marked"
      fi
    done
    waiting="${left# }"
    if [ -z "$waiting" ] || [ "$SECONDS" -ge "$deadline" ]; then
      break
    fi
    sleep 10
  done
  for item in $waiting; do
    echo "#${item%:*}: not closed by a commit within ${CLOSE_WAIT:-120} seconds (still open, or closed by hand)"
  done
}

# The fix of the issue was reverted on develop: reopens it if the revert closed it, and removes the label.
reverted() {
  local sha="$1" by="$2" number="$3" state="$4" labeled="$5" result recorded r status
  if [ "$state" = closed ]; then
    result=0
    closed_by_fix "$number" "$sha" || result=$?
    if [ "$result" = 0 ]; then
      run gh issue reopen "$number" --repo "$repo"
      echo "#$number: reopened, closed by a revert"
    elif [ "$result" = 2 ]; then
      waiting="$waiting $number:2" # the closed event may not be listed yet
    fi
  else
    waiting="$waiting $number:2"
  fi
  # a run of a later fix may have been faster than this one: its record contains the revert
  recorded="$(recorded_shas "$number")"
  for r in $recorded; do
    status="$(compare "$sha" "$r")"
    if [ "$status" = ahead ] || [ "$status" = identical ]; then
      echo "#$number: the recorded $r contains the revert, label kept"
      return 0
    fi
  done
  if [ "$labeled" = true ]; then
    run gh issue edit "$number" --repo "$repo" --remove-label "$label"
    run gh issue comment "$number" --repo "$repo" --body "develop で修正を取り消しました（$by）。この Issue は対応していない状態に戻します。
The fix was reverted in develop ($by). This issue is not fixed now."
    echo "#$number: label removed, the fix was reverted"
  fi
}

mark() {
  local sha="$1" by="$2" refs number closing info state is_pr labeled recorded r status body covered now waiting
  waiting="" # also added to by reverted (dynamic scope)
  refs="$(references)"
  if [ -z "$refs" ]; then
    echo "No issue is referenced."
    return 0
  fi
  body="develop に取り込みました（$by）。この変更を含むリリースを公開したときに、この Issue を閉じます。
Merged into develop ($by). This issue is closed when a release that contains the change is published.

<!-- $marker: $sha -->"
  while read -r number closing; do
    if ! info="$(gh api "repos/$repo/issues/$number" \
                   --jq "\"\(.state) \(.pull_request != null) \(any(.labels[]; .name == \"$label\"))\"" 2>/dev/null)"
    then
      echo "#$number: not found, skipped"
      continue
    fi
    read -r state is_pr labeled <<< "$info"
    if [ "$is_pr" = true ]; then
      echo "#$number: a pull request, skipped"
      continue
    fi
    if [ "$closing" = 2 ]; then
      reverted "$sha" "$by" "$number" "$state" "$labeled"
      continue
    fi
    if [ "$state" = closed ]; then
      # a closed issue with the label was closed by GitHub after an earlier mark: a further fix with only
      # "Refs #1" is recorded as well
      if [ "$closing" != 1 ] && [ "$labeled" != true ]; then
        echo "#$number: closed and only referenced, skipped"
        continue
      fi
      if ! closed_by_fix "$number" "$sha"; then
        echo "#$number: skipped"
        continue
      fi
      run gh issue reopen "$number" --repo "$repo"
    elif [ "$closing" = 1 ]; then
      waiting="$waiting $number:1"
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
    now="$(gh api "repos/$repo/issues/$number" --jq .state)"
    if [ "$now" = closed ]; then
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
    missing="$(not_contained "$number" "$tag")"
    if [ -z "$shas" ] || [ -n "$missing" ]; then
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
