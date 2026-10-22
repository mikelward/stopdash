package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Marks a test that puts a real thread behind [LocalWorker], for [RealWorkerComposeRule]. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class RealWorker

/**
 * A compose rule chosen per test. One marked [RealWorker] queues its effects on the test's main thread, as the
 * app's main dispatcher does: the rule's default resumes an effect unconfined, on whichever thread hands it
 * back, so an effect back from a real worker carried on there and its screen threw
 * `CalledFromWrongThreadException` now and then ([RealWorkerComposeRuleTest]). Every other test keeps the default, which
 * the held-worker tests rely on to run an effect the moment it's ready (TODO.md: queuing every test's effects
 * failed them).
 */
class RealWorkerComposeRule : TestRule {
    lateinit var compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>
        private set

    override fun apply(base: Statement, description: Description): Statement {
        compose = if (description.getAnnotation(RealWorker::class.java) != null) {
            createAndroidComposeRule(ComponentActivity::class.java, effectContext = StandardTestDispatcher())
        } else {
            createAndroidComposeRule<ComponentActivity>()
        }
        return compose.apply(base, description)
    }
}
