import UnitTestPresenter from '../source/UnitTestPresenter'
describe('登录页面Presenter单元测试', function () {
    var presenter = new UnitTestPresenter()
    it('测试add方法',function(){
        expect(presenter.add(1,2)).toEqual(3)
    })

    it('测试mul方法',function(){
        expect(presenter.mul(2,2)).toEqual(4)
    })
})