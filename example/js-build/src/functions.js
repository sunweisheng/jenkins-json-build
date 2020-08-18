export default {
  sum(a, b) {
    return a + b;
  },
  mul(a, b) {
    return a * b;
  },
  obj() {
    return {
      name: 'ligen',
      age: 18
    }
  },
  ifs(a) {
    if (a >= 0) {
      return true;
    } else {
      return false;
    }
  },
  stringFn() {
    var name = 'Ligen';
    return name;
  },
  arrFn() {
    var arrlist = [0, 1, 2, 3, 4, 5]
    return arrlist;
  },
  forEach(items, callback) {
    for (let index = 0; index < items.length; index++) {
      callback(items[index]);
    }
  }
}