# KidWatch Implementation Playbook

## Phase 18 - AI Coding Workflow

1. Write a tight feature spec.
   - Example: "Capture YouTube channel metadata every monitoring cycle and classify content risk."
2. Prompt AI for implementation tasks.
   - Example: "Write Kotlin code to call OpenAI with channel list and store labels in Room."
3. Prompt AI for tests and edge cases.
   - Missing API key
   - offline sync
   - malformed model response
4. Prompt AI for refactor and cleanup.
   - Example: "Refactor sync pipeline into repository + service boundaries."

## Phase 19 - Family Pilot Testing

Start with 5 families and track:
- battery impact (daily drain and background worker cost)
- monitoring accuracy (usage minutes vs manual estimate)
- privacy concerns (data fields reviewed with parents)

Collect weekly feedback and prioritize:
- false positives in risky labels
- missing channels/videos
- dashboard readability

## Phase 20 - Production Rollout

1. Upload build to Google Play Internal Testing.
2. Test install and upgrade path.
3. Validate Firestore rules in production mode.
4. Run smoke checklist:
   - sign in
   - usage collection
   - content analysis
   - multi-device aggregation
   - role-based access
