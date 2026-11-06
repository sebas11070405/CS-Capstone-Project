# Sentence Builder

AI-powered sentence generation and auto-completion tool built in Java with JavaFX. Generates sentences using probabilistic word-chaining algorithms trained on imported text files.

## Quick Start

### Prerequisites

- **Java 21+** (download from [oracle.com](https://www.oracle.com/java/technologies/downloads/))
- **PostgreSQL or Supabase** (database)
- **Gradle** (included: `./gradlew`)

### Setup

1. **Clone the repository:**
   ```bash
   git clone https://github.com/sebas11070405/CS-Capstone-Project.git
   cd CS-Capstone-Project
   ```

2. **Configure the database:**
   - Copy `.env.example` to `.env`:
     ```bash
     cp .env.example .env
     ```
   - Edit `.env` and add your Supabase credentials:
     ```
     DB_URL=jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require
     DB_USER=postgres.<project-ref>
     DB_PASSWORD=<your-password>
     ```
   - Create the database schema:
     ```bash
     psql -h <host> -U <user> -d postgres < database/schema.sql
     ```

3. **Build and run:**
   ```bash
   ./gradlew run
   ```
   The GUI window will open. If the database is not reachable, an error will appear.

### Build & Test

```bash
# Build only
./gradlew build

# Run tests
./gradlew test

# Run the app
./gradlew run
```

### Troubleshooting

| Issue | Solution |
|-------|----------|
| "Cannot connect to database" | Check `.env` has valid DB credentials |
| Tests fail with IllegalStateException | Ensure `.env` is configured with Supabase credentials |
| Build fails | Run `./gradlew clean` and retry |

---

## References

- **Project Spec:** `docs/project-description.md`
- **Database Schema:** `database/schema.sql`
- **Text Sources:** [Project Gutenberg](https://www.gutenberg.org/) (text-only downloads)

Code style guide: `docs/comment-style.md`
