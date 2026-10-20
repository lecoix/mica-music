/**
 * @name Mica HTTP contract demo
 * @description Self-authored test only
 * @author Mica contributors
 * @version 1.0.0
 */
// SPDX-License-Identifier: Apache-2.0
// This is a test fixture, not evidence of compatibility with third-party sources.
// MusicInfo: {"source":"wy","name":"My authorized audio","url":"https://your-host/audio.flac"}
// The wy label only exercises the protocol; this script never queries a music platform.
lx.on(lx.EVENT_NAMES.request, async ({ action, info }) => {
  if (action !== 'musicUrl') throw new Error('Unsupported action')
  await new Promise(resolve => setTimeout(resolve, 10))
  if (!info.musicInfo.url) throw new Error('Supply your own authorized audio URL')
  return info.musicInfo.url
})
lx.send(lx.EVENT_NAMES.inited, {
  sources: { wy: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k', 'flac', 'flac24bit'] } },
})
