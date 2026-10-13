"""Regression checks for the required semantic router execution gate."""

import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "semantic_router_gate", Path(__file__).with_name("check-semantic-router-test-results.py")
)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class SemanticRouterExecutionGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.reports = Path(self.directory.name)
        self.report = self.reports / "TEST-ai.wanaku.test.semantic.SemanticRouterITCase.xml"
        self.suite = ET.Element("testsuite")
        for method, count in gate.EXPECTED.items():
            for invocation in range(count):
                name = method if count == 1 else f"{method}(String)[{invocation + 1}]"
                ET.SubElement(self.suite, "testcase", name=name)

    def write_report(self):
        ET.ElementTree(self.suite).write(self.report)

    def test_requires_every_parameterized_invocation(self):
        self.write_report()
        self.assertEqual([], gate.check_reports(self.reports))
        self.suite.remove(self.suite[0])
        self.write_report()
        self.assertTrue(gate.check_reports(self.reports))

    def test_rejects_skips_failures_and_errors(self):
        for outcome in ("skipped", "failure", "error"):
            with self.subTest(outcome=outcome):
                marker = ET.SubElement(self.suite[0], outcome)
                self.write_report()
                self.assertTrue(gate.check_reports(self.reports))
                self.suite[0].remove(marker)

    def test_rejects_duplicate_invocations(self):
        self.suite[1].set("name", self.suite[0].get("name"))
        self.write_report()
        self.assertTrue(gate.check_reports(self.reports))

    def test_rejects_missing_empty_or_malformed_reports(self):
        self.assertTrue(gate.check_reports(self.reports))
        ET.ElementTree(ET.Element("testsuite")).write(self.report)
        self.assertTrue(gate.check_reports(self.reports))
        self.report.write_text("<testsuite>")
        self.assertTrue(gate.check_reports(self.reports))

    def test_rejects_ambiguous_reports_and_unexpected_scenarios(self):
        self.write_report()
        second = self.reports / "TEST-other.SemanticRouterITCase.xml"
        second.write_bytes(self.report.read_bytes())
        self.assertTrue(gate.check_reports(self.reports))
        second.unlink()
        ET.SubElement(self.suite, "testcase", name="unregisteredScenario")
        self.write_report()
        self.assertTrue(gate.check_reports(self.reports))


if __name__ == "__main__":
    unittest.main()
