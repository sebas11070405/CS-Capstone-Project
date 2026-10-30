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

## Features

### 1. Text Import
- Import plain-text files (.txt)
- Tracks word frequencies, word pairs, and sentence boundaries
- Detects and prevents duplicate imports (SHA-256 hash)
- Progress indicator for large files

### 2. Sentence Generation
- **Markov (Probability-Weighted):** Generates sentences using word-pair probabilities. Low-frequency words occasionally appear.
- **Most-Frequent Next Word:** Always chooses the most likely successor.
- **Additional Algorithms:** Can be extended for custom generation strategies.

### 3. Auto-Complete
- Suggests next words after space, comma, or period
- Adds unknown words to the database
- Tracks selection counts (how often each word is chosen)

### 4. Word Browser
- View all words in the corpus
- Sort by frequency, alphabetic order, or other metrics
- Edit word counts and metadata
- View word-pair relationships

### 5. Reporting
- Display word lists with sorting and filtering
- Track generated-sentence history
- Flag duplicate generated sentences
- Filter words by frequency threshold

### 6. Self-Feeding Loop
- Generate N sentences and add them back to the corpus
- Sentences become part of the training data for future generation

---

## How to Use

### Importing Text

1. Click the **Import** tab or button.
2. Select a plain-text file (e.g., from [Project Gutenberg](https://www.gutenberg.org/)).
   - Download **text-only** versions (.txt), not PDF or EPUB.
   - Remove headers/footers if necessary (Gutenberg files often have legal notices).
3. Click **Import**.
4. Watch the progress bar; the database is updated with word counts and word pairs.

### Generating Sentences

1. Click the **Generate** tab.
2. Select a start word (or let the app choose a random one).
3. Choose a generation algorithm:
   - **Markov:** Probability-weighted, realistic variation
   - **Most-Frequent:** Deterministic, always the same output
4. Click **Generate Sentence**.
5. Review the output. Click **Add to Corpus** to import it back as training data.

### Auto-Complete

1. Click the **Auto-Complete** tab.
2. Start typing a sentence.
3. After a **space**, **comma**, or **period**, the app suggests the next words.
4. Click a suggestion to add it, or type a new word (if not in the database, it's added automatically).
5. Selection counts are tracked in the database.

### Browsing and Editing Words

1. Click the **Word Browser** tab.
2. View all words with their counts.
3. Click a word to view or edit its metadata.
4. Changes are saved immediately to the database.

### Reports

1. Click the **Reports** tab.
2. View word lists:
   - Sort by frequency (most/least common)
   - Sort alphabetically
   - Filter by minimum frequency
3. View generated-sentence history and flag duplicates.

---

## Project Structure

```
CS-Capstone-Project/
├── src/
│   ├── main/java/com/sentencebuilder/
│   │   ├── app/Main.java                 # Entry point
│   │   ├── ui/MainController.java        # JavaFX UI
│   │   ├── database/Database.java        # Database access (DAO)
│   │   ├── importer/
│   │   │   ├── Importer.java             # File import logic
│   │   │   ├── CorpusStats.java          # Word/pair counting
│   │   │   └── DuplicateImportException.java
│   │   ├── tokenizer/Tokenizer.java      # Word tokenization
│   │   └── generator/
│   │       ├── Generator.java            # Interface
│   │       ├── AbstractGenerator.java    # Base class
│   │       ├── MarkovGenerator.java      # Probability-weighted
│   │       └── MostFrequentGenerator.java # Greedy algorithm
│   ├── main/resources/
│   │   └── com/sentencebuilder/ui/main-view.fxml  # UI layout
│   └── test/java/com/sentencebuilder/   # Unit tests
├── database/
│   ├── schema.sql                        # Database schema
│   ├── reset.sql                         # Clear data (keep schema)
│   └── diagram.md                        # ER diagram
├── docs/
│   ├── project-description.md            # Assignment spec
│   ├── comment-style.md                  # Code documentation guide
│   └── test-plan.md                      # Test strategy and exit criteria
├── texts/
│   └── adventures-of-sherlock-holmes.txt # Sample text file
├── build.gradle.kts                      # Gradle build config
├── .env.example                          # Database credentials template
├── .gitignore
└── README.md                             # This file
```

---

## Database Schema

The application uses PostgreSQL (via Supabase) or compatible database. Key tables:

| Table | Purpose |
|-------|---------|
| `words` | Word text, frequency, sentence-start/end counts |
| `word_pairs` | (predecessor, successor) pairs with transition counts |
| `imported_files` | File import history, SHA-256 hash, word counts |
| `generated_sentences` | Sentences generated by algorithms, timestamps |
| `autocomplete_selections` | Track user choices in auto-complete |

See `database/schema.sql` for full schema.

---

## Build and Testing

### Build

```bash
./gradlew build
```

Compiles Java code, runs tests, and packages the application.

### Run Tests

```bash
./gradlew test
```

Runs all unit tests (tokenizer, database, importer, generators, auto-complete).

**Note:** Database tests require `.env` to be configured. Unit tests use a separate test database connection.

### Run the Application

```bash
./gradlew run
```

Launches the JavaFX GUI.

---

## Development

### Code Style

- Follow Java naming conventions (camelCase for variables, PascalCase for classes).
- Add Javadoc comments to public methods and classes.
- Keep comments concise and explain the "why," not the "what."
- See `docs/comment-style.md` for detailed guidelines.

### Branching Strategy

- `main` branch: Always buildable, ready to demo.
- Feature branches: `feature/<area>-<description>` (e.g., `feature/tokenizer-hyphen-handling`).
- Changes merge into `main` via pull request, reviewed by another team member.
- No direct pushes to `main`.

### Git Workflow

```bash
# Create a feature branch
git checkout -b feature/my-feature

# Make changes and commit
git add .
git commit -m "Descriptive message"

# Push and create a pull request
git push origin feature/my-feature
# Then open a PR on GitHub
```

### Milestones

| Milestone | Date | Status |
|-----------|------|--------|
| **M1** Project plan submitted | Week 1 | ✓ Done |
| **M2** Database schema baselined (`schema-v1`) | Week 2 | Pending |
| **M3** Text import working end-to-end | Week 4 | In progress |
| **M4** Mid-project demo (`demo-v1`) | Week 6 | In progress |
| **M5** Feature complete | Week 9 | Planned |
| **M6** Release candidate (`rc-1`) | Week 11 | Planned |
| **M7** Final submission (`v1.0`) | Week 12 | Planned |

---

## Troubleshooting

### "Cannot connect to database"
- Check `.env` is present and has correct credentials.
- Verify PostgreSQL/Supabase is running and reachable.
- Run `./gradlew test` to isolate whether it's a config or code issue.

### "Tests fail with IllegalStateException"
- Ensure `.env` is configured with valid database credentials.
- Database tests connect to a test database; ensure it exists.

### "Application won't build"
- Run `./gradlew clean` to clear old build artifacts.
- Ensure Java 21+ is installed: `java -version`.
- Check that JavaFX SDK is available (Gradle plugin should download it).

### "No suggestions in auto-complete"
- Import a large text file first (at least 1000 words).
- Auto-complete depends on word-pair frequency; sparse data = fewer suggestions.

---

## References

- **Project Spec:** `docs/project-description.md`
- **Test Plan:** `docs/test-plan.md`
- **Database Schema:** `database/schema.sql`
- **Text Sources:** [Project Gutenberg](https://www.gutenberg.org/) (text-only downloads)
- **Professor Cole's Guidance:** [How to Program](https://personal.utdallas.edu/~jxc064000/HowToProgram.html), [Documentation](https://personal.utdallas.edu/~jxc064000/Documentation.htm), [SE Basics](https://personal.utdallas.edu/~jxc064000/SE-Basics.html)

---

## License

Academic project for CS4485 Senior Design (Fall 2026), University of Texas at Dallas.
