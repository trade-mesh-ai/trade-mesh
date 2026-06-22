## Project 
Use Spring WebFlux reactive style.
Use R2DBC for PostgreSQL.
Use existing Redis connection.

## Java Docs
For Javadoc comments, use concise single or two-line summaries only. 
No <p>, <ul>, <li> HTML tags. No "Responsibilities:" sections.


## Database

- PostgreSQL is the source of truth.
- All schema changes must go through Liquibase.
- Never modify database tables manually.
- Create a new Liquibase changeset for every schema change.

## Logging

- Use structured logging.
- Avoid System.out.println.
- Log business events at INFO.
- Log exceptions at ERROR.

## Testing

- Unit tests required for business logic.
- Integration tests for controllers and repositories.
- Maintain high coverage for trading logic.

## When Generating Code

- Generate production-ready code.
