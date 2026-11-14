import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "repository_docs", Path(__file__).resolve().parents[1] / "verify_repository_docs.py")
docs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(docs)


class RepositoryDocsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()

    def write(self, name, text=""):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def check(self, name, text):
        return docs.check_document(self.root, self.write(name, text))

    def test_encoded_and_reference_links_resolve_from_document_directory(self):
        self.write("docs/My guide.md")
        self.assertEqual([], self.check("README.md",
            "[Guide](docs/My%20guide.md#build)\n[guide]: <docs/My guide.md>\n"))

    def test_missing_link_reports_actual_source_line(self):
        self.assertEqual(["docs/guide.md:3: missing target: absent.md"],
                         self.check("docs/guide.md", "# Guide\n\n[Missing](absent.md)\n"))

    def test_examples_and_remote_links_are_not_treated_as_local_paths(self):
        self.assertEqual([], self.check("README.md", "```md\n[x](not-real.md)\n```\n"
            "`[x](also-not-real.md)`\n[x](https://example.test/a)\n[x](#anchor)\n"
            "~~~\n[example]: missing.md\n~~~\n"))

    def test_template_links_use_export_destination(self):
        self.write("docs/distribution/fdroid.md")
        self.assertEqual([], self.check("tools/fdroid/README.md",
                                       "[Build](docs/distribution/fdroid.md)\n"))

    def test_link_cannot_escape_source_root(self):
        self.assertEqual(["README.md:1: link escapes repository: ../outside.md"],
                         self.check("README.md", "[Outside](../outside.md)"))


if __name__ == "__main__":
    unittest.main()
