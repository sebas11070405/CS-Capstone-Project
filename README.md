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

