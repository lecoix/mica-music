// Node contract checks for the adapted preload only; NOT Android/QuickJS proof.
const { test } = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const preload = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/lx/user-api-preload.js'), 'utf8')

function environment() {
  const events = []
  const timers = new Map()
  const context = vm.createContext({
    console: { log() {} },
    __lx_native_call__(key, action, value) { assert.equal(key, 'test-key'); events.push({ action, data: JSON.parse(value) }) },
    __lx_native_call__set_timeout(id) { timers.set(id, true) },
    __lx_native_call__clear_timeout(id) { timers.delete(id) },
    __lx_native_call__utils_str2b64(value) { return Buffer.from(value).toString('base64') },
    __lx_native_call__utils_b642buf(value) { return JSON.stringify([...Buffer.from(value, 'base64')]) },
    __lx_native_call__utils_buf2b64(value) { return Buffer.from(JSON.parse(value)).toString('base64') },
    __lx_native_call__utils_buf2str(value) { return Buffer.from(JSON.parse(value)).toString('utf8') },
    __lx_native_call__utils_str2md5(value) { return crypto.createHash('md5').update(decodeURIComponent(value)).digest('hex') },
    __lx_native_call__utils_aes_encrypt() { throw Error('Native crypto requires Android test') },
    __lx_native_call__utils_rsa_encrypt() { throw Error('Native crypto requires Android test') },
  })
  vm.runInContext(preload, context)
  vm.runInContext("lx_setup('test-key', 'id', 'fixture', '', '1', '', '', '')", context)
  return {
    events, timers,
    run(script) { return vm.runInContext(script, context) },
    send(action, value) { context.__lx_native__('test-key', action, JSON.stringify(value)) },
  }
}

test('metadata, initialization and capability filtering match the mobile envelope', async () => {
  const env = environment()
  assert.equal(env.run('lx.env'), 'mobile')
  assert.equal(env.run('lx.version'), '2.0.0')
  await env.run("lx.send(lx.EVENT_NAMES.inited, {sources: {wy: {type: 'music', actions: ['musicUrl','search'], qualitys: ['flac','unknown']}}})")
  assert.deepEqual(env.events[0].data.info.sources.wy, { type: 'music', actions: ['musicUrl'], qualitys: ['flac'] })
})

test('binary base64 and non-BMP Unicode round trip', () => {
  const env = environment()
  assert.equal(env.run("lx.utils.buffer.bufToString(lx.utils.buffer.from('00ff80', 'hex'), 'base64')"), 'AP+A')
  assert.equal(env.run("lx.utils.buffer.bufToString(lx.utils.buffer.from('音乐🎵'))"), '音乐🎵')
  assert.equal(env.run("lx.utils.crypto.md5('abc')"), '900150983cd24fb0d6963f7d28e17f72')
})

test('asynchronous HTTP callback settles the original musicUrl request', async () => {
  const env = environment()
  await env.run("lx.on(lx.EVENT_NAMES.request, ({info}) => new Promise((resolve,reject) => lx.request(info.musicInfo.endpoint, {}, (err,resp,body) => err ? reject(err) : resolve(body.url))))")
  env.send('request', { requestKey: 'music', data: { source: 'wy', action: 'musicUrl', info: { type: 'flac', musicInfo: { endpoint: 'https://example.org/resolve' } } } })
  const request = env.events.find(e => e.action === 'request').data
  env.send('response', { requestKey: request.requestKey, error: null, response: { statusCode: 200, body: { url: 'https://example.org/audio.flac' } } })
  await new Promise(resolve => setImmediate(resolve))
  const response = env.events.find(e => e.action === 'response').data
  assert.equal(response.requestKey, 'music')
  assert.equal(response.status, true)
  assert.equal(response.result.data.type, 'flac')
  assert.equal(response.result.data.url, 'https://example.org/audio.flac')
})

test('timer cancellation reaches native host and request abort uses request identity', () => {
  const env = environment()
  env.run('clearTimeout(setTimeout(() => {}, 100))')
  assert.equal(env.timers.size, 0)
  env.run("lx.request('https://example.org', {}, () => {})()")
  assert.equal(env.events[0].data.requestKey, env.events[1].data)
  assert.equal(env.events[1].action, 'cancelRequest')
})

test('musicUrl rejects a non-HTTP response instead of returning fallback quality', async () => {
  const env = environment()
  await env.run("lx.on(lx.EVENT_NAMES.request, async () => 'file:///invalid')")
  env.send('request', { requestKey: 'bad', data: { source: 'wy', action: 'musicUrl', info: { type: 'flac24bit', musicInfo: {} } } })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(env.events.find(e => e.action === 'response').data.status, false)
})
