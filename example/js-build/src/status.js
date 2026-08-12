class Status {
  static all(client) {
    return client.get('/login/status').then(res => res.data);
  }
}

module.exports = Status;
