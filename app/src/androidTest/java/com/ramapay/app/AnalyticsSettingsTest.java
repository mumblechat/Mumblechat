package com.ramapay.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withSubstring;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.ramapay.app.assertions.Should.shouldSee;
import static com.ramapay.app.steps.Steps.closeSecurityWarning;
import static com.ramapay.app.steps.Steps.createNewWallet;
import static com.ramapay.app.steps.Steps.gotoSettingsPage;
import static com.ramapay.app.steps.Steps.scrollToImproved;
import static com.ramapay.app.steps.Steps.selectMenu;
import static com.ramapay.app.util.Helper.click;

import com.ramapay.app.util.Helper;

import org.junit.Before;
import org.junit.Test;

public class AnalyticsSettingsTest extends BaseE2ETest
{
    @Test
    public void title_should_see_analytics_settings_page()
    {
        createNewWallet();
        gotoSettingsPage();
        selectMenu("Advanced");
        Helper.wait(1);
        onView(withId(R.id.layout)).perform(swipeUp());
        click(withText("Analytics"));
        shouldSee("Share Anonymous Data");
    }

    @Test
    public void title_should_see_crash_report_settings_page()
    {
        createNewWallet();
        gotoSettingsPage();
        selectMenu("Advanced");
        Helper.wait(1);
        onView(withId(R.id.scroll_layer)).perform(swipeUp());
        onView(withId(R.id.scroll_layer)).perform(swipeUp());
        onView(withSubstring("Crash")).perform(scrollToImproved());

        click(withSubstring("Crash"));
        shouldSee("Share Anonymous Data");
    }
}
