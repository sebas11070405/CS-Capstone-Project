# Test Plan – Sentence Builder

**Version:** 1.0  
**Date:** Week 6 (October 30, 2026) *(due Week 3; submitted late)*  
**Owner:** Ujjwal Gupta  
**Reviewed by:** Project Team

---

## 1. Overview

This test plan outlines the strategy, scope, and exit criteria for testing the Sentence Builder application. Testing occurs throughout development (unit testing), at integration points (mid-project demo, Week 6), and at full system level (Weeks 9–11).

### Test Phases

| Phase | Timing | Scope | Owner |
|-------|--------|-------|-------|
| **Unit Testing** | Weeks 3–9 | Each module in isolation | Module owner + QA |
| **Integration Testing** | Weeks 6, 9–11 | Full flow: import → DB → generation → UI | Ujjwal + all members |
| **System Testing** | Weeks 9–11 | Full checklist, Gutenberg novel | Ujjwal |
| **Regression Testing** | Before merge, before RC | Unit suite + prior regressions | All members, Ujjwal for RC |

---

## 2. Unit Testing

### 2.1 Tokenizer Module (`com.sentencebuilder.tokenizer`)

**Owner:** Jerry Chen  
**Test Framework:** JUnit 5

| Test Case | Input | Expected Output | Pass Criteria |
|-----------|-------|-----------------|---------------|
| TC-T1 | "Hello world" | ["Hello", "world"] | Splits on space |
| TC-T2 | "Hello, world." | ["Hello", "world"] | Removes punctuation, keeps words |
| TC-T3 | "don't" | ["don't"] | Contractions treated as one word |
| TC-T4 | "mother-in-law" | ["mother", "in", "law"] | Hyphenated words split |
| TC-T5 | "123abc" | ["123abc"] or skip | Numbers handled as specified |
| TC-T6 | "HELLO" | ["hello"] | Case normalized to lowercase |
| TC-T7 | "" (empty string) | [] | Empty input returns empty list |
| TC-T8 | Multiple spaces "a  b" | ["a", "b"] | Multiple spaces handled |

**Exit Criteria:** All tests pass; tokenization rules documented in code comments.

---

### 2.2 Database / DAO Module (`com.sentencebuilder.database`)

**Owner:** Sebastian Vinueza  
**Test Framework:** JUnit 5 + separate test database

| Test Case | Operation | Expected | Pass Criteria |
|-----------|-----------|----------|---------------|
| TC-D1 | Insert word "hello" | word_id assigned | Auto-increment ID |
| TC-D2 | Insert same word twice | duplicate rejected or count incremented | No duplicate rows |
| TC-D3 | Query all words | list returned sorted | Query works |
| TC-D4 | Insert word pair ("the", "quick") | pair_id assigned | Foreign keys valid |
| TC-D5 | Increment word-pair count | count goes from 1 → 2 | Count update works |
| TC-D6 | Query word pair by predecessor | list of successors returned | Join query works |
| TC-D7 | Record imported file metadata | file_id, import_date stored | Metadata saved |
| TC-D8 | Mark word as sentence start | start_count incremented | Sentence boundary tracked |
| TC-D9 | Mark word as sentence end | end_count incremented | Sentence boundary tracked |

**Exit Criteria:** All CRUD operations tested; test database resets before each run.

---

### 2.3 Importer Module (`com.sentencebuilder.importer`)

**Owner:** Jerry Chen  
**Test Framework:** JUnit 5

| Test Case | Input | Expected | Pass Criteria |
|-----------|-------|----------|---------------|
| TC-I1 | Small text file (10 words) | File imported, word counts in DB | Import succeeds |
| TC-I2 | Duplicate file (same content, different name) | Rejected with `DuplicateImportException` | Duplicate detection by hash |
| TC-I3 | File with 1000 words | All words in DB, counts accurate | Batching works |
| TC-I4 | Progress indicator during import | Callback fired N times | Progress reported |
| TC-I5 | File with 0 words | Handled gracefully (skip or error) | Edge case handled |

**Exit Criteria:** All import scenarios work; SHA-256 hash verified in test.

---

### 2.4 Generative Engine (`com.sentencebuilder.generator`)

**Owner:** Alicia Nea  
**Test Framework:** JUnit 5 + fixed random seed for reproducibility

#### TC-G1: Probability-Weighted (Markov) Generator

| Test Case | Setup | Expected | Pass Criteria |
|-----------|-------|----------|---------------|
| TC-G1a | Start word "the"; corpus has ["the cat", "the dog"] | Generated sentence contains "the" at start | Sentence starts with seed word |
| TC-G1b | Fixed seed; run twice | Same sentence generated both times | Reproducibility (deterministic) |
| TC-G1c | Word pair ("the", "cat"): count=90, ("the", "dog"): count=10 | "the cat" appears ~9x more often than "the dog" | Probability weighted |
| TC-G1d | Seed word not in corpus | Error or graceful fallback | Invalid seed handled |
| TC-G1e | Empty corpus | Error | No words to generate |

#### TC-G2: Second Generation Algorithm (e.g., most-frequent next word)

| Test Case | Setup | Expected | Pass Criteria |
|-----------|-------|----------|---------------|
| TC-G2a | Start "the"; most-frequent successor is "cat" | Generated sentence has "the cat" | Most-frequent strategy works |
| TC-G2b | Run multiple times | Same sentence every time (deterministic) | Reproducibility |

#### TC-G3: Additional Algorithm (e.g., top-k random)

| Test Case | Setup | Expected | Pass Criteria |
|-----------|-------|----------|---------------|
| TC-G3a | Start "the"; top-3 next words by frequency | Sentence picks from top 3 | Top-k selection works |
| TC-G3b | k=1 (same as most-frequent) | Same output as TC-G2a | Boundary case |

#### TC-G4: Low-Probability Words

| Test Case | Setup | Expected | Pass Criteria |
|-----------|-------|----------|---------------|
| TC-G4a | Word pair ("hello", "world"): count=1 out of 100 successors | Over 1000 sentences, "hello world" appears at least once | Low-probability words show up |

**Exit Criteria:** All generators tested; low-probability words verified over bulk run.

---

### 2.5 Auto-Complete Module

**Owner:** Alicia Nea  
**Test Framework:** JUnit 5

| Test Case | User Input | Expected | Pass Criteria |
|-----------|------------|----------|---------------|
| TC-A1 | "the " (space after "the") | List of words that follow "the" | Suggestions appear after space |
| TC-A2 | "the," (comma after "the") | Same as TC-A1 | Comma triggers suggestions |
| TC-A3 | "the." (period after "the") | Same as TC-A1 | Period triggers suggestions |
| TC-A4 | "the" (no space) | No suggestions | No suggestions mid-word |
| TC-A5 | User types "xyz" (not in DB) and presses space | "xyz" added to word list; count=0 for next word | Unknown word inserted |
| TC-A6 | User selects suggestion "cat" from "the \_" list | ("the", "cat") count incremented | Selection count updated |
| TC-A7 | User selects "cat" again | Count increments again (now 2) | Increments on repeat |

**Exit Criteria:** All auto-complete flows verified.

---

## 3. Integration Testing

### 3.1 Mid-Project Demo (Week 6)

**Scope:** Database schema, import module, Markov generation, basic UI  
**Data:** Small text file (100–500 words from Sherlock Holmes excerpt)

| Test Case | Steps | Expected | Pass Criteria |
|-----------|-------|----------|---------------|
| TC-Int1 | 1. Fresh DB. 2. Import text file. 3. Check word counts in DB. | Word counts match tokenizer output | Import end-to-end works |
| TC-Int2 | 1. Query DB for top 10 words. 2. Display on UI. | Top 10 words shown sorted by frequency | UI displays data |
| TC-Int3 | 1. Select start word "the". 2. Click "Generate". 3. Observe sentence. | Sentence generated, contains "the" at start | Generation UI works |
| TC-Int4 | 1. Generate 10 sentences. 2. Check that at least one is unique. | Sentences vary (not all identical) | Generation varies |

**Pass Criteria:** All four tests pass. Fresh clone builds and runs via README instructions. Tag: `demo-v1`.

---

### 3.2 Full System Integration (Weeks 9–11)

**Scope:** All features, full Gutenberg novel (50K–100K words)  
**Data:** Jane Austen novel or equivalent, cleaned

| Test Case | Steps | Expected | Pass Criteria |
|-----------|-------|----------|---------------|
| TC-Int5 | 1. Import novel. 2. Generate 100 sentences with Markov. 3. Generate 100 with Algorithm 2. | Both algorithms produce sentences | All generators work at scale |
| TC-Int6 | 1. Inspect word-pair counts in DB. 2. Generate 1000 sentences with Markov. 3. Check histogram of output. | Low-frequency pairs appear occasionally (e.g., 5–10 times in 1000) | Low-probability sampling works |
| TC-Int7 | 1. Generate 10 sentences. 2. Click "Add to Corpus". 3. Import them. 4. Query DB for sentence table. | Sentences visible in DB. Import progress shown. | Self-feeding loop works |
| TC-Int8 | 1. On auto-complete screen, type "the ". 2. Select "cat" from suggestions. 3. Generate. 4. Type "and " and select again. | Sentences use chosen words. Selection counts in DB increment. | Auto-complete + generation interaction works |
| TC-Int9 | 1. Word browser screen. 2. View all words, sorted by frequency. 3. Click a word, edit its count. 4. Save. 5. Re-query DB. | Edit persists in DB. | Word editing works |
| TC-Int10 | 1. Reports screen. 2. Sort words by frequency (desc). 3. Filter by min count (10+). | Report shows filtered/sorted list. | Report filtering + sorting works |
| TC-Int11 | 1. Reports screen. 2. View generated-sentence history. 3. Check for duplicates. | Duplicate sentences flagged or counted. | Duplicate detection works |

**Pass Criteria:** All tests pass. No open critical or major defects. Tag: `rc-1` (release candidate).

---

