package io.github.kauri_off.kiroku.design

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

// The compose rule's activity is not in the app's manifest, so Robolectric is told about it first.
fun composeRule() = createAndroidComposeRule<ComponentActivity>()

// [ended] runs once the activity is gone, so a scope it ends outlives every screen, as the app's own scope does.
fun registered(
    rule: TestRule,
    ended: () -> Unit = {},
): TestRule =
    RuleChain
        .outerRule(
            object : TestWatcher() {
                override fun starting(description: Description) {
                    val app = RuntimeEnvironment.getApplication()
                    Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                }

                override fun finished(description: Description) = ended()
            },
        ).around(rule)
