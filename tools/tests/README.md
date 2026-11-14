# Tooling regressions

These tests exercise build/export selection, dependency trust, artifact verification, source admission and language/icon registries. They are source inputs, not generated test results.

Run an affected suite from the repository root with `python3 -B -m unittest discover -s tools/tests -p 'test_name.py' -v`. The [development guide](../../docs/DEVELOPMENT.md) lists fast repository checks. Native/APK tests declare additional inputs; missing artifact prerequisites must not be reported as successful runtime validation.

Versioned/private-lane tests and fixtures still protect consumers in Gradle and build tooling. Keep their frozen receipts intact. Retiring one requires checking all imports, task registrations and manifest references; a filename's age is insufficient evidence of obsolescence.

Do not store newly generated reports, compiled binaries or private signing material here. Intentional parser/error fixtures remain because their exact bytes are test input. See the [test strategy](../../docs/TESTING.md).
