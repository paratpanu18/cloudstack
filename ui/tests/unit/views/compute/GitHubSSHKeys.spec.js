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

import { webcrypto, createHash } from 'crypto'
import { fetchGitHubSSHKeys, validGitHubUsername } from '@/utils/githubSSHKeys'
import Importer from '@/components/widgets/GitHubSSHKeyImport.vue'
import Dialog from '@/views/compute/CreateSSHKeyPair.vue'
import { postAPI } from '@/api'

jest.mock('@/api', () => ({ postAPI: jest.fn(), getAPI: jest.fn() }))

beforeEach(() => {
  jest.clearAllMocks()
  global.fetch = jest.fn()
  Object.defineProperty(window, 'crypto', { configurable: true, value: webcrypto })
})

const key = { id: 1, key: 'ssh-ed25519 YWJj' }
const response = data => ({ ok: true, json: async () => data })

test('validates usernames before lookup', () => {
  for (const username of ['octocat', 'a', 'user-1']) expect(validGitHubUsername(username)).toBe(true)
  for (const username of ['', '-abc', 'abc-', 'a--b', 'a/b', 'a'.repeat(40)]) expect(validGitHubUsername(username)).toBe(false)
})

test('fetches keys without credentials and computes SHA256 fingerprints', async () => {
  fetch.mockResolvedValue(response([key]))
  const keys = await fetchGitHubSSHKeys('octocat')
  expect(fetch.mock.calls[0][0]).toBe('https://api.github.com/users/octocat/keys?per_page=100&page=1')
  expect(fetch.mock.calls[0][1].credentials).toBe('omit')
  expect(keys[0]).toMatchObject({ selected: false, name: 'github-octocat-1', type: 'ssh-ed25519', fingerprint: 'SHA256:' + createHash('sha256').update('abc').digest('base64').replace(/=+$/, '') })
})

test('retrieves every page and deduplicates IDs', async () => {
  fetch.mockResolvedValueOnce(response(Array.from({ length: 100 }, (_, i) => ({ ...key, id: i + 1 })))).mockResolvedValueOnce(response([key, { ...key, id: 101 }]))
  expect(await fetchGitHubSSHKeys('user')).toHaveLength(101)
  expect(fetch.mock.calls[1][0]).toContain('page=2')
})

test.each([[404, 'notfound'], [403, 'ratelimit'], [429, 'ratelimit'], [500, 'fetch']])('reports HTTP %s', async (status, code) => {
  fetch.mockResolvedValue({ ok: false, status })
  await expect(fetchGitHubSSHKeys('user')).rejects.toMatchObject({ code })
})

test('handles empty results and malformed responses', async () => {
  fetch.mockResolvedValueOnce(response([])).mockResolvedValueOnce(response({})).mockResolvedValueOnce(response([{ id: 1 }]))
  expect(await fetchGitHubSSHKeys('user')).toEqual([])
  await expect(fetchGitHubSSHKeys('user')).rejects.toThrow()
  await expect(fetchGitHubSSHKeys('user')).rejects.toThrow()
})

function context () {
  return {
    canImport: true,
    githubLoading: false,
    loading: false,
    githubKeys: [1, 2].map(id => ({ id, name: ` key-${id} `, publickey: key.key, selected: true })),
    scope: { domainid: 'domain', account: 'owner' },
    domains: [{ id: 'domain' }],
    form: { domainid: 0, account: 'owner' },
    $t: key => key,
    $emit: jest.fn()
  }
}

test('imports with owner scope and retries only failed keys', async () => {
  const vm = context()
  postAPI.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('Duplicate name'))
  await Importer.methods.importGitHubKeys.call(vm)
  expect(postAPI).toHaveBeenCalledWith('registerSSHKeyPair', { domainid: 'domain', account: 'owner', name: 'key-1', publickey: key.key })
  expect(vm.githubKeys[0].imported).toBe(true)
  expect(vm.githubKeys[1].error).toBe('Duplicate name')
  expect(vm.$emit).toHaveBeenCalledWith('imported', ['key-1'])
  postAPI.mockResolvedValue({})
  await Importer.methods.importGitHubKeys.call(vm)
  expect(postAPI).toHaveBeenCalledTimes(3)
  expect(vm.githubKeys.every(key => key.imported)).toBe(true)
  expect(vm.loading).toBe(false)
})

test('requires selection and distinct nonempty names', async () => {
  for (const names of [[], ['', 'two'], ['same', ' same ']]) {
    const vm = context()
    vm.githubKeys = names.map(name => ({ name, selected: true }))
    await Importer.methods.importGitHubKeys.call(vm)
    expect(vm.githubError).toBe('message.ssh.github.selection')
  }
  expect(postAPI).not.toHaveBeenCalled()
})

test('username changes clear results and invalidate pending requests', () => {
  const vm = context()
  vm.githubRequest = 1
  vm.githubController = { abort: jest.fn() }
  vm.cancelGitHubFetch = () => Importer.methods.cancelGitHubFetch.call(vm)
  Importer.watch.githubUsername.call(vm)
  expect(vm.githubRequest).toBe(2)
  expect(vm.githubController.abort).toHaveBeenCalled()
  expect(vm.githubKeys).toEqual([])
})

test('ignores results from a fetch superseded by a username change', async () => {
  let resolveFetch
  fetch.mockReturnValue(new Promise(resolve => { resolveFetch = resolve }))
  const vm = context()
  Object.assign(vm, { githubUsername: 'octocat', githubRequest: 0 })
  vm.cancelGitHubFetch = () => Importer.methods.cancelGitHubFetch.call(vm)
  const pending = Importer.methods.fetchGitHubKeys.call(vm)
  Importer.watch.githubUsername.call(vm)
  resolveFetch(response([]))
  await pending
  expect(vm.githubFetched).toBe(false)
  expect(vm.githubKeys).toEqual([])
  expect(vm.githubLoading).toBe(false)
})

test('reports network errors and forwards cancellation', async () => {
  const controller = new AbortController()
  fetch.mockRejectedValue(new Error('Network error'))
  await expect(fetchGitHubSSHKeys('user', controller.signal)).rejects.toThrow('Network error')
  expect(fetch.mock.calls[0][1].signal).toBe(controller.signal)
})

test.each([false, true])('preserves manual creation/import flow (public key: %s)', async publickey => {
  const vm = context()
  vm.mode = 'manual'
  vm.form = { name: 'manual', ...(publickey ? { publickey: key.key } : {}) }
  vm.formRef = { value: { validate: jest.fn().mockResolvedValue(), scrollToField: jest.fn() } }
  vm.handleRemoveFields = form => form
  vm.isValidValueForKey = Dialog.methods.isValidValueForKey
  vm.arrayHasItems = Dialog.methods.arrayHasItems
  vm.$message = { success: jest.fn() }
  vm.$notifyError = jest.fn()
  vm.closeAction = jest.fn()
  postAPI.mockResolvedValue({ createsshkeypairresponse: { keypair: { privatekey: 'private' } } })
  Dialog.methods.handleSubmit.call(vm, { preventDefault: jest.fn() })
  await new Promise(resolve => setTimeout(resolve, 0))
  expect(postAPI).toHaveBeenCalledWith(publickey ? 'registerSSHKeyPair' : 'createSSHKeyPair', publickey ? { name: 'manual', publickey: key.key } : { name: 'manual' })
  if (publickey) expect(vm.closeAction).toHaveBeenCalled()
  else expect(vm.isSubmitted).toBe(true)
})

test('computes fingerprints when Web Crypto is unavailable on HTTP', async () => {
  Object.defineProperty(window, 'crypto', { configurable: true, value: {} })
  fetch.mockResolvedValue(response([key]))
  const keys = await fetchGitHubSSHKeys('user')
  expect(keys[0].fingerprint).toBe('SHA256:' + createHash('sha256').update('abc').digest('base64').replace(/=+$/, ''))
})

test('compact table selections exclude keys already imported', () => {
  const vm = context()
  vm.githubKeys[0].imported = true
  const selection = Importer.computed.rowSelection.call(vm)
  selection.onChange([1, 2])
  expect(vm.githubKeys.map(key => key.selected)).toEqual([false, true])
  expect(selection.getCheckboxProps(vm.githubKeys[0]).disabled).toBe(true)
})

test('forwards project scope when importing keys', async () => {
  const vm = context()
  vm.scope = { projectid: 'project-1' }
  postAPI.mockResolvedValue({})
  await Importer.methods.importGitHubKeys.call(vm)
  expect(postAPI).toHaveBeenCalledWith('registerSSHKeyPair', { projectid: 'project-1', name: 'key-1', publickey: key.key })
})

test('standalone dialog delegates GitHub imports to the shared importer', () => {
  const importGitHubKeys = jest.fn()
  Dialog.methods.handleSubmit.call({ mode: 'github', loading: false, $refs: { githubImporter: { importGitHubKeys } } })
  expect(importGitHubKeys).toHaveBeenCalled()
})
