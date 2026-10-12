"""Require all OPA suites to execute successfully on OPA-capable source builds."""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    reports = Path(sys.argv[1])
    suites = ("OpaPolicyITCase", "OpaContractITCase", "OpaGovernanceITCase")
    errors = []
    for suite in suites:
        files = list(reports.glob(f"TEST-*.{suite}.xml"))
        if len(files) != 1:
            errors.append(f"{suite}: expected one report, found {len(files)}")
            continue
        root = ET.parse(files[0]).getroot()
        cases = list(root.iter("testcase"))
        if not cases:
            errors.append(f"{suite}: no test cases")
        for case in cases:
            if any(case.find(outcome) is not None for outcome in ("skipped", "failure", "error")):
                errors.append(f"{suite}: {case.get('name')} did not pass")
    if errors:
        for error in errors:
            print(f"::error::{error}")
        return 1
    print("All expected OPA suites executed without skips or failures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
