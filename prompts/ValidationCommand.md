./gradlew test --tests "com.trademesh.trading.TradingControllerTest" --rerun-tasks --info
./gradlew test --rerun-tasks
grep -h "testsuite name" build/test-results/test/*.xml | grep -oP 'tests="\d+"'
git status

grep -c "<testcase" build/test-results/test/*.xml | awk -F: '{sum+=$2} END {print sum}'
cat build/reports/tests/test/index.html | grep -A3 "class=\"counter\""
cat build/test-results/test/TEST-com.trademesh.trading.DailyReportServiceTest.xml

OUTPUT for each:

1. ./gradlew test --tests "com.trademesh.trading.DailyReportServiceTest" --info
   All 4 tests individually, tie-break test must show PASSED.

2. ./gradlew test (full suite)
   Confirm 127 tests completed, 0 failed.

3. git diff -- src/main/java/com/trademesh/trading/DailyReportService.java
   Must show ONLY bestTrade's comparator changed.

4. grep -rn "reverseOrder\|getExitTime" src/main/java/com/trademesh/trading/
   Confirm no other caller depends on the prior incorrect state.

Paste all four as raw output — no summaries.