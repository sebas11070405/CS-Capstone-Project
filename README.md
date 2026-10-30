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
     # Run the schema script against your database
     mysql -h <host> -u <user> -p < database/schema.sql
     ```
     (Substitute `psql` if using PostgreSQL directly)

3. **Build and run:**
   ```bash
   ./gradlew run
   ```
   The GUI window will open. If the database is not reachable, an error will appear.

---

