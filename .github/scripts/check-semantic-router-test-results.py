"""Require every semantic router scenario on full source integration builds."""

import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


EXPECTED = {
    "shouldRouteRequestsThroughWanaku": 3,
    "shouldRetainLinkedEvaluationAndDecisionWithoutSubmittedContent": 1,
    "shouldOverrideCatalogAuditWithRuntimeSetting": 2,
    "shouldKeepRoutingWhenAuditReceiverDisabled": 1,
    "shouldDeliverNewInvocationAfterAuditReceiverRecovers": 1,
    "shouldDeregisterForwardAndToolOnGracefulShutdown": 1,
}


def check_reports(reports):
    files = list(reports.glob("TEST-*.SemanticRouterITCase.xml"))
    if len(files) != 1:
        return [f"SemanticRouterITCase: expected one report, found {len(files)}"]
    try:
        cases = list(ET.parse(files[0]).getroot().iter("testcase"))
    except ET.ParseError as error:
        return [f"SemanticRouterITCase: invalid report: {error}"]
    errors = []
    counts = Counter()
    names = set()
    for case in cases:
        name = case.get("name", "")
        method = re.split(r"[\[(]", name, maxsplit=1)[0]
        counts[method] += 1
        if name in names:
            errors.append(f"SemanticRouterITCase: duplicate invocation {name}")
        names.add(name)
        if any(case.find(outcome) is not None for outcome in ("skipped", "failure", "error")):
            errors.append(f"SemanticRouterITCase: {name} did not pass")
    for method, count in EXPECTED.items():
        if counts[method] != count:
            errors.append(f"{method}: expected {count} invocations, found {counts[method]}")
    unexpected = set(counts) - set(EXPECTED)
    if unexpected:
        errors.append(f"Unexpected semantic router scenarios: {sorted(unexpected)}; update the CI contract")
    return errors


def main():
    errors = check_reports(Path(sys.argv[1]))
    if errors:
        for error in errors:
            print(f"::error::{error}")
        return 1
    print(f"All {sum(EXPECTED.values())} semantic router scenarios executed without skips or failures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
