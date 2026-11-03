# Test Plan – Sentence Builder

**Version:** 1.0  
**Date:** Week 6 (November 3, 2026)  
**Owner:** Ujjwal Gupta (QA Lead)

---

## Test Phases & Exit Criteria

| Phase | Timing | Scope | Exit Criteria |
|-------|--------|-------|---------------|
| **Unit Testing** | Weeks 3–9 | Each module (tokenizer, DB, importer, generators, auto-complete) | All unit tests pass; 43+ tests (Tokenizer: 32, Importer: 8, DB: 1, new: CorpusRepository: 2) |
| **Integration** | Weeks 6, 9–11 | Full flow: import → DB → generation → UI | Mid-demo (M4): weighted bigram works, UI launches. Full system (M6): all three algorithms + auto-complete + reports |
| **System Test** | Weeks 9–11 | Full checklist on Gutenberg corpus | Three algorithms (weighted, greedy, trigram-backoff), low-probability words appear, auto-complete functional, no auto-insert for unknown words, reports show duplicates |
| **Regression** | Before merge, before RC | Unit suite + prior regressions | 100% pass before merge to main; 100% pass before `rc-1` tag (M6) |

---

## Test Ownership

- **Tokenizer:** Jerry Chen (32 tests, all passing)
- **Importer & CorpusStats:** Jerry Chen (8 tests, all passing)
- **Database:** Sebastian Vinueza (1 connection test, passing)
- **Generators:** Alicia Nea (unit tests required)
- **Auto-Complete:** Alicia Nea (unit tests required)
- **UI Integration:** Amey Mahale (system test)
- **QA & Regression:** Ujjwal Gupta (all phases)

---

## Defect Severity & Tracking

- **Critical** – Blocks demo/import/generation. Fix before merge to main.
- **Major** – Feature broken but has workaround. Fix before rc-1 tag (M6).
- **Minor** – UI polish, edge case. Can defer to v1.0.

Defects tracked in GitHub Issues. No open critical/major defects allowed at rc-1.

---

## Milestones & Tags

| Milestone | Date | Tag | Criteria |
|-----------|------|-----|----------|
| **M4** – Mid-Demo | Nov 15 | `demo-v1` | Import works, weighted bigram generator runs, UI launches |
| **M6** – Release Candidate | Dec 10 | `rc-1` | All three generators work, auto-complete functional, 43+ tests pass, no critical defects |
| **v1.0** – Final | Week 12 | `v1.0` | All defects fixed, ready for submission |

---

## Current Status

- **43 unit tests passing** (100%) – includes new CorpusRepository tests
- **Fresh clone builds successfully**
- **Database connection verified**
- **Bigrams & trigrams populated in database** (PR #17 merged)
- **SQL refactored to shared module** (PR #21 in review)
- **Generators pending rebase to use real data** (PR #14 waiting)
- **Ready for integration phase (M4 demo)**
