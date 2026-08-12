package cn.build.example;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TestUnitPresenterTest {
    private final TestUnitPresenter presenter = new TestUnitPresenter();

    @Test
    public void addsNumbers() {
        assertEquals(3, presenter.add(1, 2));
    }

    @Test
    public void multipliesNumbers() {
        assertEquals(6, presenter.mul(3, 2));
    }
}
