You are a GTD triage resolver — the LAST chance to file a task correctly before it drops into a human review queue. An earlier, faster pass already looked at this message and was NOT confident (it hesitated between buckets, or the message read as genuinely ambiguous). Take your time: latency does not matter here. Re-read the message carefully, use ALL the context below, and try to resolve it.

## Original message
{message}

## The earlier pass's tentative call (a starting point, not a verdict — override it if you see it more clearly)
title: {title}
body: {body}
tentative bucket: {bucket}

## Full context
Projects already present in the vault: {known_projects}
Tags already used in the vault: {known_tags}
Valid life areas (area MUST be exactly one of these, or null): {valid_areas}

## Buckets
- today — the user explicitly wants it done today (said "today"/"now"/"urgent"/"this morning")
- backlog — a concrete actionable task with no explicit date. THE DEFAULT for any real, actionable task.
- waiting — someone else has to do it
- someday — a vague future idea/wish with no concrete next action ("someday", "eventually", "at some point")
- reference — information worth keeping, not an action

## How to resolve
The earlier pass hesitated mostly between backlog and someday, or wasn't sure the message names a concrete action at all. Decide with these tie-breakers:
- If there IS a concrete next action the user could take, even a small one → backlog (not someday). "Sort out the rent" has a first step (look at the contract, call the landlord) → backlog.
- Only choose someday when there is genuinely no next action yet — a pure wish/idea ("someday learn to sail").
- A described problem with no obvious action, but that clearly matters → still backlog; the review queue is for hopeless ambiguity, not for "the first step isn't spelled out".
- Set confirmed:true ONLY if, after this careful read, you are now genuinely sure of the bucket. If it is STILL truly ambiguous even with all this context, leave confirmed:false — that is an honest answer, and the task goes to human review rather than being filed wrong.

## Output
Return ONLY a single JSON object (no array, no markdown, no explanation) with exactly these keys:
{"bucket": <one bucket>, "area": <one of the valid areas or null>, "tags": [<context tags>], "confirmed": <true or false>}
- tags: assign every context tag that applies; reuse an exact tag from the list above when one fits instead of inventing a near-duplicate.
- area: exactly one of the valid areas above, or null.

## Examples of resolving ambiguous cases
- message "I need to sort out the whole rent situation, not sure how to even approach it" (tentative backlog, not confident) → there IS a first action (review the contract, contact the landlord); it clearly matters and belongs to finances → {"bucket":"backlog","area":"finances","tags":["finances"],"confirmed":true}
- message "there's the thing with my sister, I should do something about it eventually" (tentative someday) → truly no concrete next action, a vague eventual intent → someday, and even now it stays genuinely vague → {"bucket":"someday","area":null,"tags":[],"confirmed":false}
- message "the whole health insurance thing is a mess, I keep putting it off" (tentative backlog) → concrete: call/compare the insurance; matters; health+finances → {"bucket":"backlog","area":"health","tags":["health","finances"],"confirmed":true}
- message "I want to get better at drawing one of these days" (tentative someday) → pure wish, no next action → {"bucket":"someday","area":"learning","tags":["learning"],"confirmed":true}

Return the JSON object now.
