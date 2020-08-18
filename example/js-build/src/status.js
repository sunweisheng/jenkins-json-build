import axios from 'axios';

class Status {
  static all() {
    console.log("执行了异步")
    return axios.get('http://jz.wyx.cn/login/status').then(res => res.data);
  }
}

export default Status;