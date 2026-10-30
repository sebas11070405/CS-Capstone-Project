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

## M6 – Release Candidate (Week 11, rc-1)

**Target:** Feature complete, all tests pass, no critical defects

### All of M4 checks, plus:

### Features Complete

- [ ] At least 2 generation algorithms implemented and tested
- [ ] Auto-complete module implemented
- [ ] Word browser / editor UI complete
- [ ] Reports screen complete (sorting, filtering, duplicate detection)
- [ ] Self-feeding loop (generate → import) works end-to-end
- [ ] Import progress indicator visible and functional

### Full System Test

- [ ] System test checklist (docs/test-plan.md §4) passes on large corpus (Gutenberg novel)
  - [ ] Two algorithms produce different, valid sentences
  - [ ] Low-probability words appear (rare pairs in 1000+ generated sentences)
  - [ ] Auto-complete offers suggestions after space/comma/period
  - [ ] Auto-complete adds unknown words and increments selection counts
  - [ ] Words can be viewed, edited, and changes persist
  - [ ] Reports sort/filter correctly and show duplicate sentences
  - [ ] Import shows smooth progress bar
- [ ] No console errors or warnings during full test run

### Regression Testing

- [ ] All 41 unit tests pass: `./gradlew test`
- [ ] Integration test passes (import → generate → UI workflow)
- [ ] Smoke test passes (fresh clone, build, import, generate, view report)

### Defect Status

- [ ] GitHub Issues: no open `critical` or `major` defects
  - [ ] All critical defects fixed and verified closed
  - [ ] All major defects documented or moved to future release
  - [ ] Minor defects may remain (documented as known limitations)
- [ ] Test report shows 100% pass rate

### Code & Documentation

- [ ] All public classes/methods have Javadoc comments
- [ ] Code follows style guide (docs/comment-style.md)
- [ ] User guide complete (docs/README.md includes usage for all features)
- [ ] CLAUDE.md updated with project progress and lessons learned (if using)

### Performance

- [ ] Import of 50K-word corpus completes in <30 seconds
- [ ] Generation of 10 sentences completes in <2 seconds
- [ ] UI remains responsive (no 5-second freezes)
- [ ] No memory leaks (app doesn't consume >500MB RAM at rest)

### Git & Release

- [ ] All work is committed and pushed to main: `git status` shows clean
- [ ] Tag `rc-1` created: `git tag rc-1 && git push origin rc-1`
- [ ] Release notes prepared (in `CHANGELOG.md` or PR description)

---

## v1.0 – Final Submission (Week 12)

**Target:** Ready for grading and presentation

### All of M6 checks, plus:

### Final Verification

- [ ] All defects from M6 rc-1 are resolved
- [ ] Final presentation script is prepared (5–10 min demo flow)
- [ ] Source code bundle is ready for submission (no .env, no build artifacts)

### Documentation Final

- [ ] README.md is complete and error-free
- [ ] Test plan is finalized (docs/test-plan.md)
- [ ] Code comments are complete and clear
- [ ] Database schema is documented with table/column purposes

### Submission

- [ ] Tag `v1.0` created and pushed: `git tag v1.0 && git push origin v1.0`
- [ ] Source code uploaded to eLearning (`.zip` or GitHub link)
- [ ] Presentation materials ready
- [ ] All team members have signed off on submission

---

## Running the Full Audit

**Command to run all checks at once:**

```bash
# Fresh clone test
rm -rf /tmp/audit-test
git clone https://github.com/sebas11070405/CS-Capstone-Project.git /tmp/audit-test
cd /tmp/audit-test

# Build
./gradlew clean build

# Tests
./gradlew test

# Check report
open build/reports/tests/test/index.html  # or: cat build/reports/tests/test/index.html | grep -o "success"
```

**Expected output:**
- `BUILD SUCCESSFUL` twice (clean build, then test)
- HTML report shows: `41 tests, 0 failures, 100% successful`

---

## Audit Owner

**Ujjwal Gupta** – QA Lead and Configuration Manager

All milestones must be audited before tags are pushed. Audit results are documented in the PR or commit message.
