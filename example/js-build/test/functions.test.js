import functions from '../src/functions';
import axios from 'axios';
import Status from '../src/status';

test('1+2=3', () => {
  expect(functions.sum(1, 2)).toBe(3);
})

test('2+2≠5', () => {
  expect(functions.sum(2, 2)).not.toBe(5);
})

test('2*5=10', () => {
  expect(functions.mul(2, 5)).toBe(10);
})

test('2*5≠10', () => {
  expect(functions.mul(2, 2)).not.toBe(10);
})

test('obj()返回的对象深度相等', () => {
  expect(functions.obj()).toEqual(functions.obj());
})

test('obj()返回的对象内存地址不同', () => {
  expect(functions.obj()).not.toBe(functions.obj());
})

//测试匹配
test('null', () => {
  const n = null;
  expect(n).toBeNull();             //toBeNull 只匹配 null
  expect(n).toBeDefined();          //toBeUndefined 只匹配 undefined
  expect(n).not.toBeUndefined();    //toBeDefined 与 toBeUndefined 相反
  expect(n).not.toBeTruthy();       //toBeTruthy 匹配任何 if 语句为真
  expect(n).toBeFalsy();            //toBeFalsy 匹配任何 if 语句为假
});

test('if()返回有效值', () => {
  const n = functions.ifs(0);
  expect(n).toBeTruthy();
})
test('if()返回无效值', () => {
  const n = functions.ifs(-1);
  expect(n).toBeFalsy();
})
test('2+2等价匹配', () => {
  const value = functions.sum(2, 2);
  expect(value).toBeGreaterThan(3);           //超过
  expect(value).toBeGreaterThanOrEqual(3.5);  //大于或等于
  expect(value).toBeLessThan(5);              //比....小  
  expect(value).toBeLessThanOrEqual(4.5);     //不等于

  // toBe and toEqual 对数据同样有效
  expect(value).toBe(4);            //等于4
  expect(value).toEqual(4);         //等于4
});

test('两个浮点数字相加', () => {
  const value = functions.sum(0.1, 0.2);
  //expect(value).toBe(0.3);           这句会报错，因为浮点数有舍入误差
  expect(value).toBeCloseTo(0.3);       // 这句可以运行
});

test('判断字符串中不包含m', () => {
  expect(functions.stringFn()).not.toMatch(/m/);
});

test('判断字符串中包含Lig', () => {
  expect(functions.stringFn()).toMatch(/Lig/);
});

test('判断数组中包含4', () => {
  expect(functions.arrFn()).toContain(4);
  expect(new Set(functions.arrFn())).toContain(4);
});

// mock
test('测试jest.fn()返回固定值', () => {
  let mockFn = jest.fn();
  mockFn.mockReturnValue('default');// 断言mockFn执行后返回值为default
  expect(mockFn()).toBe('default');
})
test('测试jest.fn()内部实现', () => {
  let mockFn = jest.fn((num1, num2) => {
    return num1 * num2;// 断言mockFn执行后返回100
  })
  expect(mockFn(10, 10)).toBe(100);
})
test('测试jest.fn()返回Promise', async () => {
  let mockFn = jest.fn().mockResolvedValue('default');
  let result = await mockFn();
  // 断言mockFn通过await关键字执行后返回值为default
  expect(result).toBe('default');
  // 断言mockFn调用后返回的是Promise对象
  expect(Object.prototype.toString.call(mockFn())).toBe("[object Promise]");
})

const mockCallback = jest.fn(x => 1 + x);

functions.forEach([0, 1, 2], mockCallback);

test('mock方法被调用了两次', () => {
  expect(mockCallback.mock.calls.length).toBe(3);
})

test('对函数的第一个调用的第一个参数是0', () => {
  expect(mockCallback.mock.calls[0][0]).toBe(0);
})

test('对函数的第一个调用的返回值是1', () => {
  expect(mockCallback.mock.results[0].value).toBe(1);
})

test("测试mock模拟返回值", () => {
  let myMock = jest.fn();
  console.log(myMock());

  myMock.mockReturnValueOnce(10).mockReturnValueOnce("x").mockReturnValue(true);
  console.log(myMock(), myMock(), myMock(), myMock())
})

jest.mock('axios');
test('mock返回status', () => {
  const status = [{ status: 'incorrect-login' }];
  const res = { data: status };
  axios.get.mockResolvedValue(res);//返回我们希望测试断言的数据
  return Status.all().then(data => expect(data).toEqual(status));
});