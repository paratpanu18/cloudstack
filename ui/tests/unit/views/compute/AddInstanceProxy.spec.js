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

import { shallowMount, flushPromises } from '@vue/test-utils'
import { getAPI, postAPI } from '@/api'
import AddInstanceProxy from '@/views/compute/AddInstanceProxy.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const resource = { id: 'vm-1', account: 'owner', domainid: 'domain-1' }
const nic = { networkid: 'network-1', networkname: 'Isolated', type: 'Isolated', ipaddress: '10.0.0.10' }
let wrapper
let customNames
let publicIps
let jobs
let refresh
let mocks

beforeEach(() => {
  jest.clearAllMocks()
  customNames = true
  publicIps = [
    { id: 'ip-1', ipaddress: '203.0.113.10', state: 'Allocated', account: 'owner', domainid: 'domain-1' },
    { id: 'other-owner', ipaddress: '203.0.113.11', state: 'Allocated', account: 'another', domainid: 'domain-1' }
  ]
  jobs = []
  refresh = jest.fn()
  mocks = {
    $t: key => key,
    $config: { theme: { '@primary-color': '#000' } },
    $store: { getters: { apis: { listReverseProxyDomains: {}, listNics: {}, checkInstanceProxyName: {} } } },
    $notification: { success: jest.fn(), error: jest.fn() },
    $notifyError: jest.fn(),
    $pollJob: jest.fn(options => jobs.push(options))
  }
  getAPI.mockImplementation((api) => {
    if (api === 'listNics') return Promise.resolve({ listnicsresponse: { nic: [nic] } })
    if (api === 'listReverseProxyDomains') {
      return Promise.resolve({
        listreverseproxydomainsresponse: {
          reverseproxydomain: [
            { id: 'suffix-1', domain: 'apps.example.com', allowcustomname: customNames, defaultnetworkid: 'network-1' }
          ]
        }
      })
    }
    if (api === 'listNetworks') return Promise.resolve({ listnetworksresponse: { network: [{ id: 'network-1' }] } })
    if (api === 'listPublicIpAddresses') return Promise.resolve({ listpublicipaddressesresponse: { publicipaddress: publicIps } })
    return Promise.reject(new Error('Unexpected API: ' + api))
  })
  postAPI.mockResolvedValue({ addinstanceproxyresponse: { jobid: 'job-1' } })
})

afterEach(() => { if (wrapper) wrapper.unmount() })

async function mountForm () {
  wrapper = shallowMount(AddInstanceProxy, {
    props: { resource },
    global: {
      mocks,
      provide: { parentFetchData: refresh },
      stubs: {
        'a-select': { template: '<div><slot /></div>' },
        'a-input-number': { template: '<input />' },
        'a-input': { props: ['placeholder'], template: '<input :placeholder="placeholder" />' },
        'a-alert': { props: ['message'], template: '<div>{{ message }}<slot name="message" /></div>' }
      }
    }
  })
  await flushPromises()
}

test('isolated exposure submits forwarding fields and waits for the job before success', async () => {
  await mountForm()
  expect(wrapper.vm.publicIpOptions).toEqual([{ label: '203.0.113.10', value: 'ip-1' }])
  await wrapper.setData({ name: ' myapp ', port: 8080, publicPort: 8443 })
  wrapper.vm.submitData()
  await flushPromises()
  expect(postAPI).toHaveBeenCalledWith('addInstanceProxy', {
    virtualmachineid: 'vm-1',
    domainid: 'suffix-1',
    name: 'myapp',
    networkid: 'network-1',
    ipaddressid: 'ip-1',
    publicport: 8443,
    protocol: 'http',
    port: 8080
  })
  expect(wrapper.vm.loading).toBe(true)
  expect(mocks.$notification.success).not.toHaveBeenCalled()
  expect(refresh).not.toHaveBeenCalled()
  jobs[0].successMethod()
  await flushPromises()
  expect(mocks.$notification.success).toHaveBeenCalledTimes(1)
  expect(refresh).toHaveBeenCalledTimes(1)
  expect(wrapper.emitted('close-action')).toHaveLength(1)
})

test('random-only policy hides the name field and omits even a stale name from the request', async () => {
  customNames = false
  await mountForm()
  expect(wrapper.html()).toContain('message.proxy.random.only')
  expect(wrapper.find('[placeholder="label.proxy.name.placeholder"]').exists()).toBe(false)
  await wrapper.setData({ name: 'stale-custom-name' })
  wrapper.vm.submitData()
  await flushPromises()
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('name')
})

test('empty custom name requests server generation', async () => {
  await mountForm()
  wrapper.vm.submitData()
  await flushPromises()
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('name')
})

test('shared exposure omits public IP and public port', async () => {
  await mountForm()
  await wrapper.setData({ nics: [{ ...nic, type: 'Shared' }] })
  wrapper.vm.submitData()
  await flushPromises()
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('ipaddressid')
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('publicport')
})

test('isolated exposure cannot submit without a compatible public IP', async () => {
  publicIps = []
  await mountForm()
  wrapper.vm.submitData()
  expect(postAPI).not.toHaveBeenCalled()
  expect(mocks.$notification.error).toHaveBeenCalled()
})

test('a failed job reports failure and keeps the form open', async () => {
  await mountForm()
  wrapper.vm.submitData()
  await flushPromises()
  jobs[0].errorMethod(new Error('port conflict'))
  await flushPromises()
  expect(mocks.$notification.success).not.toHaveBeenCalled()
  expect(mocks.$notifyError).toHaveBeenCalled()
  expect(wrapper.emitted('close-action')).toBeUndefined()
  expect(wrapper.vm.loading).toBe(false)
})

test('availability checks include the instance for network grants', async () => {
  await mountForm()
  postAPI.mockResolvedValue({ checkinstanceproxynameresponse: { instanceproxyname: { available: true } } })
  await wrapper.setData({ name: 'app' })
  wrapper.vm.checkName()
  await flushPromises()
  expect(postAPI).toHaveBeenCalledWith('checkInstanceProxyName', {
    name: 'app', virtualmachineid: 'vm-1', domainid: 'suffix-1'
  })
})

test('changing the name invalidates an older availability response', async () => {
  await mountForm()
  let resolveCheck
  postAPI.mockImplementation(() => new Promise(resolve => { resolveCheck = resolve }))
  await wrapper.setData({ name: 'old' })
  wrapper.vm.checkName()
  await wrapper.setData({ name: '' })
  wrapper.vm.handleNameInput()
  resolveCheck({ checkinstanceproxynameresponse: { instanceproxyname: { available: false, message: 'old name in use' } } })
  await flushPromises()
  expect(wrapper.vm.nameChecked).toBe(false)
  expect(wrapper.vm.nameCheckMessage).toBe('')
})
