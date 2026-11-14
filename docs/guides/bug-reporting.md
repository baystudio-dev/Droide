# Bug report submission

Settings presents a native text form. `SettingsBugReportViewModel` and `BugReportCoordinator` retain the draft and own preparation, submission, cancellation and uncertain outcomes. Opening a form or storing a draft is not report submission.

`OfficialBugReportTransport` uses the official service's session/CSRF protocol. Submission sends the fixed official `Origin` and `Referer` from `BugReportPolicy`, uses a one-shot body, and does not follow redirects. These headers describe the configured service; they must not be copied from user input or an arbitrary redirect.

Reports contain the entered fields. Logs, project files and screenshots are not collected automatically. Keep credentials and private source out of report content. Do not expose transport implementation details as extra form copy.

A server rejection leaves the draft available with the actual error. An uncertain result requires explicit duplicate-aware retry. Service acceptance is not proof of delivery to the support inbox; avoid saying an email arrived solely from an HTTP response.

Use local HTTP interceptor fixtures for CSRF expiry, required headers, malformed responses, cancellation and duplicate prevention. Do not send synthetic reports to the live support service during automated tests. Device checks should cover form restoration, keyboard/layout, offline behavior and an explicitly authorized real submission.
