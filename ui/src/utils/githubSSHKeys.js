// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

// Use native fetch so CloudStack interceptors and credentials never reach GitHub.
export function validGitHubUsername (username) {
  return /^[a-z0-9](?:[a-z0-9-]{0,37}[a-z0-9])?$/i.test(username) && !username.includes('--')
}

export async function fetchGitHubSSHKeys (username, signal) {
  const keys = []
  for (let page = 1; ; page++) {
    const response = await fetch(`https://api.github.com/users/${encodeURIComponent(username)}/keys?per_page=100&page=${page}`, {
      credentials: 'omit',
      headers: { Accept: 'application/vnd.github+json' },
      signal
    })
    if (!response.ok) {
      const error = new Error('GitHub request failed')
      error.code = response.status === 404 ? 'notfound' : [403, 429].includes(response.status) ? 'ratelimit' : 'fetch'
      throw error
    }
    const data = await response.json()
    if (!Array.isArray(data) || data.some(item => !Number.isSafeInteger(item.id) || typeof item.key !== 'string')) {
      throw new Error('Invalid GitHub response')
    }
    for (const item of data) {
      if (keys.some(key => key.id === item.id)) continue
      const [type, encoded] = item.key.trim().split(/\s+/)
      if (!type || !encoded) throw new Error('Invalid SSH key')
      const bytes = Uint8Array.from(atob(encoded), char => char.charCodeAt(0))
      let hash
      if (window.crypto?.subtle) {
        const digest = await window.crypto.subtle.digest('SHA-256', bytes)
        hash = btoa(String.fromCharCode(...new Uint8Array(digest)))
      } else {
        // Webpack's Node crypto shim also supports installations served over HTTP.
        const { createHash } = await import('crypto')
        hash = createHash('sha256').update(bytes).digest('base64')
      }
      const fingerprint = 'SHA256:' + hash.replace(/=+$/, '')
      keys.push({ id: item.id, publickey: item.key, type, fingerprint, name: `github-${username}-${item.id}`, selected: false, imported: false, error: '' })
    }
    if (data.length < 100) return keys
  }
}
