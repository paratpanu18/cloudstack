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

import Selection from '@/views/compute/wizard/SshKeyPairSelection.vue'
import { postAPI } from '@/api'

jest.mock('@/api', () => ({ postAPI: jest.fn() }))

function context (project = {}) {
  return {
    savingKey: false,
    keyName: ' laptop ',
    publicKey: ' ssh-ed25519 AAAA ',
    showAddKey: true,
    value: ['existing'],
    owner: { account: 'owner', domainid: 'domain-1' },
    $store: { getters: { project } },
    $t: key => key,
    $emit: jest.fn(),
    $message: { success: jest.fn(), error: jest.fn() },
    $notifyError: jest.fn(),
    handleSearch: jest.fn()
  }
}

beforeEach(() => {
  jest.clearAllMocks()
  postAPI.mockResolvedValue({})
})

test('registers for the selected owner and selects the new key alongside existing keys', async () => {
  const vm = context()
  await Selection.methods.addKey.call(vm)
  expect(postAPI).toHaveBeenCalledWith('registerSSHKeyPair', {
    name: 'laptop', publickey: 'ssh-ed25519 AAAA', account: 'owner', domainid: 'domain-1'
  })
  expect(vm.$emit).toHaveBeenCalledWith('select-ssh-key-pair-item', [{ name: 'existing' }, { name: 'laptop' }])
  expect(vm.handleSearch).toHaveBeenCalledWith('')
  expect(vm.showAddKey).toBe(false)
})

test('registers project keys without account ownership parameters', async () => {
  const vm = context({ id: 'project-1' })
  await Selection.methods.addKey.call(vm)
  expect(postAPI).toHaveBeenCalledWith('registerSSHKeyPair', {
    name: 'laptop', publickey: 'ssh-ed25519 AAAA', projectid: 'project-1'
  })
})

test('keeps the dialog and input on API failure without selecting a key', async () => {
  const vm = context()
  const error = new Error('Duplicate key name')
  postAPI.mockRejectedValue(error)
  await Selection.methods.addKey.call(vm)
  expect(vm.$notifyError).toHaveBeenCalledWith(error)
  expect(vm.$emit).not.toHaveBeenCalled()
  expect(vm.showAddKey).toBe(true)
  expect(vm.publicKey).toBe(' ssh-ed25519 AAAA ')
  expect(vm.savingKey).toBe(false)
})

test('rejects blank keys before calling the API', async () => {
  const vm = context()
  vm.publicKey = ' '
  await Selection.methods.addKey.call(vm)
  expect(postAPI).not.toHaveBeenCalled()
  expect(vm.$message.error).toHaveBeenCalled()
})

test('refreshing the key list preserves the deployment selection', () => {
  const vm = { loading: false, oldZoneId: 'zone-1', zoneId: 'zone-1', $emit: jest.fn() }
  Selection.watch.loading.call(vm)
  expect(vm.$emit).not.toHaveBeenCalled()
})

test('delegates GitHub imports to the shared importer', async () => {
  const vm = context()
  vm.keyMode = 'github'
  const importGitHubKeys = jest.fn()
  vm.$refs = { githubImporter: { importGitHubKeys } }
  await Selection.methods.addKey.call(vm)
  expect(importGitHubKeys).toHaveBeenCalled()
  expect(postAPI).not.toHaveBeenCalled()
})

test('selects imported GitHub keys alongside existing keys and refreshes results', () => {
  const vm = context()
  Selection.methods.handleGitHubImported.call(vm, ['github-user-1', 'github-user-2', 'existing'])
  expect(vm.$emit).toHaveBeenCalledWith('select-ssh-key-pair-item', [{ name: 'existing' }, { name: 'github-user-1' }, { name: 'github-user-2' }])
  expect(vm.handleSearch).toHaveBeenCalledWith('')
  expect(vm.showAddKey).toBe(true)
})

test('GitHub imports respect selected account and project ownership', () => {
  const vm = context()
  expect(Selection.computed.importScope.call(vm)).toEqual({ account: 'owner', domainid: 'domain-1' })
  vm.owner.projectid = 'owner-project'
  expect(Selection.computed.importScope.call(vm)).toEqual({ projectid: 'owner-project' })
  vm.$store.getters.project = { id: 'current-project' }
  expect(Selection.computed.importScope.call(vm)).toEqual({ projectid: 'current-project' })
})
