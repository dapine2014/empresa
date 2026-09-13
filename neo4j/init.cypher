// Reference schema for manual inspection/bootstrap.
// The company-core service executes the same initialization idempotently at startup.
MERGE (c:Company {id: 'AI-COMPANY'})
SET c.name = 'AI Company', c.status = 'ACTIVE', c.seedCapitalUsd = 50.0, c.challengeDays = 60;
MATCH (c:Company {id: 'AI-COMPANY'})
UNWIND [
  ['ceo','CEO','Chief Executive Officer AI'],
  ['sales','Sales','Director of Sales AI'],
  ['product','Product','Chief Product AI'],
  ['finance','Finance','Chief Finance AI'],
  ['engineering','Engineering','Chief Engineering AI'],
  ['qa','QA & Operations','QA & Operations AI']
] AS item
MERGE (a:Agent {id:item[0]})
SET a.name=item[1], a.title=item[2], a.status='ACTIVE'
MERGE (a)-[:WORKS_FOR]->(c);
