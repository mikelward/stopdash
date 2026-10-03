package app.stopdash.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/** The repo's own lint checks, loaded by :app's `lintChecks`. */
class StopdashIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(MainThreadWorkDetector.ISSUE)

    override val api: Int = CURRENT_API

    override val vendor: Vendor = Vendor(vendorName = "StopDash", feedbackUrl = "https://github.com/mikelward/stopdash/issues")
}
