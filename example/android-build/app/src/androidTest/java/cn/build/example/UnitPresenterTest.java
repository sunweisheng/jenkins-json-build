package cn.build.example;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class UnitPresenterTest {
    TestUnitPresenter presenter = new TestUnitPresenter();
    @Test
    public void useAppContext() {
        // Context of the app under test.
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("cn.build.example", appContext.getPackageName());
    }

    @Test
    public void testAdd(){
        Assert.assertEquals(3, presenter.add(1,2));
    }

    @Test
    public void testMul(){
        Assert.assertEquals(6, presenter.mul(3,2));
    }
}