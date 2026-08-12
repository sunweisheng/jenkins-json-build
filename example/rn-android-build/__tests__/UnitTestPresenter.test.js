import UnitTestPresenter from '../source/UnitTestPresenter';

describe('UnitTestPresenter', () => {
  const presenter = new UnitTestPresenter();

  test('adds numbers', () => {
    expect(presenter.add(1, 2)).toBe(3);
  });

  test('multiplies numbers', () => {
    expect(presenter.multiply(2, 3)).toBe(6);
  });
});
