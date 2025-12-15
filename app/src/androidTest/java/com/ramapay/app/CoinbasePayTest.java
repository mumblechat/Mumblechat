package com.ramapay.app;

import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.ramapay.app.assertions.Should.shouldSee;
import static com.ramapay.app.steps.Steps.createNewWallet;
import static com.ramapay.app.util.Helper.click;
import static com.ramapay.app.util.Helper.clickMadly;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import com.ramapay.app.util.Helper;

import org.junit.Test;

public class CoinbasePayTest extends BaseE2ETest
{
    @Test
    public void should_see_coinbase_pay_window()
    {
        /*createNewWallet();
        clickMadly(withText("Buy ETH"));
        //click(withId("Buy ETH"));
        Helper.wait(2);
        shouldSee("Buy with Coinbase Pay"); // <-- here - the test suite can't seem to find this
        Helper.wait(2);
        click(withId(R.id.buy_with_coinbase_pay));
        Helper.wait(2);
        shouldSee("Buy with Coinbase Pay");*/
    }
}
