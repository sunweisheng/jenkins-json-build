export default {
  /**
   * 
   * @param {*} a 
   * @param {*} b 
   */
  sum(a, b) {
    return a + b;
  },
  /**
   * 
   * @param {*} a 
   * @param {*} b 
   */
  mul(a, b) {
    return a * b;
  },
  obj() {
    return {
      name: 'ligen',
      age: 18
    }
  },
  /**
   * 
   * @param {*} a 
   */
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
  /**
   * 
   * @param {*} items 
   * @param {*} callback 
   */
  forEach(items, callback) {
    for (let index = 0; index < items.length; index++) {
      callback(items[index]);
    }
  }
}