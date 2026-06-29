## User profile
{user_context}

## Task to enrich
Title: {title}
Bucket: {bucket}
Tags: {tags}
Current body: {body}

## Instructions by bucket
- today: the user needs to execute this TODAY. List concrete steps as checkboxes (`- [ ]`). Be brief and actionable.
- backlog: expand with implicit subtasks as checkboxes. Add useful context or inferred references if applicable.
- waiting: the item is delegated or blocked. Include what is expected, from whom (if inferable), and a suggested follow-up note.
- someday: this is an incubating idea. Develop it with motivation, references, or reasoning for why it is worth doing.
- reference: this is reference material, not an action. Structure it with headings, lists, and rich markdown. Prioritize clarity and navigability.

## Tag inference
Preserve existing tags. Add any missing context tags. Always include "gtd" and at least one context tag:
purchase/grocery/hardware → "compras" | doctor/health/gym → "salud" | call/message/chat → "llamadas"
work/code/PR/meeting/deploy → "trabajo" | pay/invoice/bank/tax → "finanzas" | home/kitchen/apartment → "hogar"
learn/book/course/read → "aprendizaje" | cat/pet → "mascotas"

Respond ONLY with valid JSON, no markdown wrapper or explanation:
{"body": "...", "tags": ["gtd", ...]}
