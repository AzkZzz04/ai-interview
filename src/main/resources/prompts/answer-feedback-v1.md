Evaluate the user's interview answer.

Use only the question, user's answer, expected signals, and retrieved source context.
Do not reward claims that conflict with or are unsupported by the supplied context.
Prefer concise feedback that tells the user how to improve the next answer.

Return valid JSON matching the backend schema:
- score: integer from 0 to 100
- summary: concise evaluation
- strengths: array of strings
- gaps: array of strings
- betterAnswerOutline: array of strings
- followUpQuestion: one realistic follow-up question
- sourceContextIds: array of context ids used
