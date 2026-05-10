Generate technical interview questions for the target role and seniority.

Use the retrieved resume, job description, assessment weakness, and prior-question context.
Questions must be specific enough to test the candidate's real experience, but must not assume facts that are not in the retrieved context.

Return valid JSON matching the backend schema:
- questions: array of objects
- each question: questionText, category, difficulty, expectedSignals, sourceContextIds
