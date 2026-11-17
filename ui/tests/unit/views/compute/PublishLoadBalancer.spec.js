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
import PublishLoadBalancer from '@/views/compute/PublishLoadBalancer.vue'
import { listExposureResources } from '@/utils/internetExposure'

jest.mock('@/utils/internetExposure', () => ({ listExposureResources: jest.fn(), exposureOperation: jest.fn() }))
let wrapper
const domain = { id: 'suffix', domain: 'apps.example.com', allowcustomname: true }
beforeEach(() => {
  jest.clearAllMocks()
  listExposureResources.mockImplementation(async api => api === 'listReverseProxyDomains' ? [domain] : [])
})
afterEach(() => wrapper?.unmount())
async function mount () {
  wrapper = shallowMount(PublishLoadBalancer, {
    props: { resource: { id: 'public-ip' }, loadBalancer: { id: 'lb', publicip: '203.0.113.10', publicport: 80 } },
    global: { mocks: { $t: key => key, $store: { getters: { apis: {} } } } }
  })
  await flushPromises()
  return wrapper.vm
}
test('shows the API error text and distinguishes domain lookup failure from no available domains', async () => {
  listExposureResources.mockImplementation(async api => {
    if (api === 'listReverseProxyDomains') throw Object.assign(new Error('Request failed with status code 530'), { response: { data: { listreverseproxydomainsresponse: { errortext: 'Load balancer lookup failed' } } } })
    return []
  })
  const vm = await mount()
  expect(vm.error).toBe('Load balancer lookup failed')
  expect(vm.domainsFailed).toBe(true)
  expect(vm.domainId).toBeUndefined()
})
test('keeps successful domain choices when the independent proxy-list request fails', async () => {
  listExposureResources.mockImplementation(async api => {
    if (api === 'listReverseProxyDomains') return [domain]
    throw new Error('Could not load existing addresses')
  })
  const vm = await mount()
  expect(vm.domains).toEqual([domain])
  expect(vm.domainId).toBe('suffix')
  expect(vm.domainsFailed).toBe(false)
  expect(vm.error).toBe('Could not load existing addresses')
})
test('clears stale lookup errors when retry succeeds', async () => {
  const vm = await mount()
  vm.error = 'Old error'
  vm.domainsFailed = true
  await vm.loadDomains()
  expect(vm.error).toBe('')
  expect(vm.domainsFailed).toBe(false)
  expect(vm.domainId).toBe('suffix')
  expect(listExposureResources).toHaveBeenCalledWith('listReverseProxyDomains', 'reverseproxydomain', { loadbalancerruleid: 'lb' })
  expect(listExposureResources).toHaveBeenCalledWith('listInstanceProxies', 'instanceproxy', { loadbalancerruleid: 'lb', listall: true })
})
