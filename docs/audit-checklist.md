# Pre-Milestone Audit Checklist

Run this checklist before tagging each milestone. Covers build, tests, and repo consistency.

---

## M4 – Mid-Project Demo (Week 6, demo-v1)

**Target:** Feature complete for demo (import, basic generation, UI)

### Build & Compile

- [ ] Fresh clone from GitHub: `git clone https://github.com/sebas11070405/CS-Capstone-Project.git test-clone`
- [ ] Build succeeds: `./gradlew build` (all tasks pass, no errors)
- [ ] Java 21+: `java -version` shows 21.x or later
- [ ] Gradle wrapper cached: build finishes in <10s on second run

### Database

- [ ] `.env` exists with valid credentials (ask team lead)
- [ ] Schema script works: `psql -h <host> -U <user> < database/schema.sql` (or equivalent for MariaDB)
- [ ] Test database created and empty before test run

### Tests

- [ ] All unit tests pass: `./gradlew test` (41/41 pass, 0 failures)
  - [ ] DatabaseTest: 1 pass
  - [ ] CorpusStatsTest: 6 pass
  - [ ] ImporterTest: 2 pass
  - [ ] TokenizerTest: 32 pass
- [ ] No warnings in compilation output
- [ ] Test report generated: `build/reports/tests/test/index.html` (100% success rate)

### Application Launch

- [ ] App runs without errors: `./gradlew run` (or packaged JAR)
- [ ] Main window opens (JavaFX loads without crashes)
- [ ] DB connection succeeds (no "cannot connect to database" error)

### Code Quality

- [ ] All Java files compile with UTF-8 encoding
- [ ] No TODO or FIXME comments in committed code (except for future enhancements)
- [ ] Javadoc present on all public classes and methods (spot check 5+ classes)
- [ ] Code review completed on all PRs merged to main

### Git & Tagging

- [ ] All PRs merged: `git log origin/main..main` returns empty
- [ ] Branch `main` is up-to-date with `origin/main`
- [ ] Tag `demo-v1` created and pushed: `git tag demo-v1 && git push origin demo-v1`
- [ ] Tag verification: `git tag -v demo-v1` shows tag commit hash

### Documentation

- [ ] README.md exists and describes setup + usage
- [ ] Test plan exists (docs/test-plan.md)
- [ ] Database schema documented (database/schema.sql has comments)
- [ ] No broken links in docs

### Demo Verification

- [ ] Import a sample text file (Sherlock Holmes or equivalent)
- [ ] Word counts appear in the UI or logs
- [ ] At least one sentence generation algorithm runs without error
- [ ] No visible crashes or hangs during demo

---

