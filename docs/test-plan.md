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

