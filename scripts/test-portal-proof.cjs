const assert = require('node:assert/strict');
const crypto = require('../datapath/portal_crypto.js');
const base = crypto.SHA256('cross-platform-salt:1234').toString();
const verifier = crypto.PBKDF2(base, crypto.enc.Utf8.parse('cross-platform-salt'), {
  keySize: 8, iterations: 120000, hasher: crypto.algo.SHA256,
}).toString();
assert.equal(verifier, '28c41880cf8f2f594a05750a287add9c78b6ed0606d62743a851f05dbeb30ee1');
assert.equal(crypto.HmacSHA256('shizzi-account-v1|test-nonce|1001', verifier).toString(),
  '44cf8d7c1d501a4ff8f892f0a63b9f42633152929e483b18e66d3c686ed516fb');
console.log('Portal JavaScript proof matches Go/Kotlin PBKDF2 and HMAC vectors.');
