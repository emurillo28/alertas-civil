# ADR-0001: Single-Table DynamoDB Design

## Status
Accepted

## Context
Need scalable, low-latency storage for LabResult entities with flexible query patterns:
- By result ID (point read)
- By patient (all results)
- By status (critical alerts)
- Historical versions (OCC)

## Decision
Use single-table design with overloaded GSI indexes.

## Consequences
**Positive:**
- Single round-trip for related entities
- Flexible query patterns via GSI
- Cost-effective (one table, pay-per-request)
- Scales to millions of items

**Negative:**
- Complex key design
- Requires careful access pattern planning
- Less intuitive than relational

## References
- `.hcg/schemas/database_schema.md`
- `.hcg/patterns/dynamodb_single_table.md`