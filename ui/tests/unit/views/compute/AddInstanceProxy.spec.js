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
let confirmations

beforeEach(() => {
  jest.clearAllMocks()
  customNames = true
  publicIps = [
    { id: 'ip-1', ipaddress: '203.0.113.10', state: 'Allocated', account: 'owner', domainid: 'domain-1' },
    { id: 'other-owner', ipaddress: '203.0.113.11', state: 'Allocated', account: 'another', domainid: 'domain-1' }
  ]
  confirmations = []
  jobs = []
  refresh = jest.fn()
  mocks = {
    $t: key => key,
    $config: { theme: { '@primary-color': '#000' } },
    $store: { getters: { apis: { listReverseProxyDomains: {}, listNics: {}, checkInstanceProxyName: {}, checkInstanceProxyBackend: {} } } },
    $notification: { success: jest.fn(), error: jest.fn() },
    $notifyError: jest.fn(),
    $confirm: jest.fn(options => {
      const modal = { ...options, destroy: jest.fn() }
      confirmations.push(modal)
      return modal
    }),
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
        'a-button': { props: ['disabled', 'loading'], template: '<button :disabled="disabled"><slot name="icon" /><slot /></button>' },
        'a-tooltip': { props: ['title'], template: '<span :data-tooltip="title"><slot /></span>' },
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
  wrapper.vm.submitData(true)
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

test('random-only policy disables the visible name field, explains it in a tooltip and omits a stale name', async () => {
  customNames = false
  await mountForm()
  const field = wrapper.find('[placeholder="label.proxy.name.placeholder"]')
  expect(field.exists()).toBe(true)
  expect(field.element.disabled).toBe(true)
  expect(wrapper.find('[data-tooltip="message.proxy.random.only"]').exists()).toBe(true)
  expect(wrapper.find('.proxy-name-input').attributes('tabindex')).toBe('0')
  await wrapper.setData({ name: 'stale-custom-name' })
  wrapper.vm.submitData(true)
  await flushPromises()
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('name')
})

test('empty custom name requests server generation', async () => {
  await mountForm()
  wrapper.vm.submitData(true)
  await flushPromises()
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('name')
})

test('shared exposure omits public IP and public port', async () => {
  await mountForm()
  await wrapper.setData({ nics: [{ ...nic, type: 'Shared' }] })
  wrapper.vm.submitData(true)
  await flushPromises()
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('ipaddressid')
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('publicport')
})

test('isolated exposure cannot submit without a compatible public IP', async () => {
  publicIps = []
  await mountForm()
  wrapper.vm.submitData(true)
  expect(postAPI).not.toHaveBeenCalled()
  expect(mocks.$notification.error).toHaveBeenCalled()
})

test('a failed job reports failure and keeps the form open', async () => {
  await mountForm()
  wrapper.vm.submitData(true)
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

async function startHealthCheck () {
  postAPI.mockResolvedValue({ checkinstanceproxybackendresponse: { jobid: 'health-job' } })
  wrapper.vm.checkBackend()
  await flushPromises()
}

async function finishHealthCheck (health = { healthy: true, httpstatus: 200, message: 'HTTP 200 OK', cleanupcomplete: true }) {
  jobs[0].successMethod({ jobresult: { instanceproxybackendhealth: health } })
  await flushPromises()
}

test('health check is manual, uses the selected connection and blocks duplicate checks and exposure until completion', async () => {
  await mountForm()
  expect(postAPI).not.toHaveBeenCalled()
  await wrapper.setData({ port: 8080, publicPort: 8443, protocol: 'https' })
  await startHealthCheck()
  expect(postAPI).toHaveBeenCalledWith('checkInstanceProxyBackend', {
    virtualmachineid: 'vm-1',
    networkid: 'network-1',
    protocol: 'https',
    port: 8080
  })
  expect(wrapper.vm.checkingBackend).toBe(true)
  expect(wrapper.vm.canCheckBackend).toBe(false)
  expect(jobs[0].action).toEqual({ isFetchData: false })
  wrapper.vm.checkBackend()
  wrapper.vm.submitData()
  expect(postAPI).toHaveBeenCalledTimes(1)
  await finishHealthCheck()
  expect(wrapper.vm.checkingBackend).toBe(false)
  expect(wrapper.find('.proxy-health__result').text()).toBe('200 OK')
  expect(refresh).not.toHaveBeenCalled()
  expect(wrapper.emitted('close-action')).toBeUndefined()
})

test('an unhealthy backend is advisory and still allows exposure', async () => {
  await mountForm()
  await startHealthCheck()
  await finishHealthCheck({ healthy: false, httpstatus: 503, message: 'Backend returned HTTP 503', cleanupcomplete: true })
  expect(wrapper.find('.proxy-health__error').text()).toContain('503')
  wrapper.vm.submitData()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(confirmations).toHaveLength(1)
  expect(confirmations[0].okText).toBe('label.proxy.expose.anyway')
  expect(confirmations[0].centered).toBe(true)
  confirmations[0].onOk()
  await flushPromises()
  expect(postAPI).toHaveBeenLastCalledWith('addInstanceProxy', expect.any(Object))
})

test('certificate and temporary cleanup warnings are shown separately from HTTP success', async () => {
  await mountForm()
  await startHealthCheck()
  await finishHealthCheck({
    healthy: true,
    httpstatus: 200,
    message: 'HTTP 200 OK; temporary forwarding cleanup failed',
    tlsverified: false,
    cleanupcomplete: false,
    temporaryruleid: 'temporary-rule'
  })
  expect(wrapper.vm.backendHealth.healthy).toBe(true)
  expect(wrapper.find('.proxy-health__result').text()).toBe('200 OK')
  expect(wrapper.find('[data-tooltip="message.proxy.backend.tls.warning"]').exists()).toBe(true)
  expect(wrapper.find('[data-tooltip="message.proxy.backend.cleanup.warning"]').exists()).toBe(true)
})

test.each(['port', 'publicPort', 'protocol', 'publicIpId', 'networkId'])('changing %s discards an in-flight health response', async field => {
  await mountForm()
  await startHealthCheck()
  const values = { port: 8080, publicPort: 8443, protocol: 'https', publicIpId: 'ip-2', networkId: 'network-2' }
  await wrapper.setData({ [field]: values[field] })
  await finishHealthCheck()
  expect(wrapper.vm.backendHealth).toBeNull()
  expect(wrapper.vm.checkingBackend).toBe(false)
})

test('changing connection settings clears a completed result', async () => {
  await mountForm()
  await startHealthCheck()
  await finishHealthCheck()
  await wrapper.setData({ port: 8080 })
  expect(wrapper.vm.backendHealth).toBeNull()
})

test('closing the form ignores a later health result without cancelling the server job', async () => {
  await mountForm()
  await startHealthCheck()
  wrapper.vm.closeAction()
  expect(wrapper.emitted('close-action')).toHaveLength(1)
  await finishHealthCheck()
  expect(wrapper.vm.backendHealth).toBeNull()
  expect(postAPI).toHaveBeenCalledTimes(1)
})

test('reset ignores the old job without clearing the busy state of a new check', async () => {
  await mountForm()
  await startHealthCheck()
  wrapper.vm.resetForm()
  await flushPromises()
  await wrapper.setData({ networkId: 'network-1', publicIpId: 'ip-1' })
  await startHealthCheck()
  await finishHealthCheck()
  expect(wrapper.vm.backendHealth).toBeNull()
  expect(wrapper.vm.checkingBackend).toBe(true)
  jobs[1].successMethod({ jobresult: { instanceproxybackendhealth: { healthy: true, message: 'New result' } } })
  await flushPromises()
  expect(wrapper.vm.backendHealth.message).toBe('New result')
  expect(wrapper.vm.checkingBackend).toBe(false)
})

test('shared health check omits public fields and does not require a domain or name', async () => {
  await mountForm()
  await wrapper.setData({ nics: [{ ...nic, type: 'Shared' }], proxyDomainId: undefined, name: '' })
  await startHealthCheck()
  const params = postAPI.mock.calls[0][1]
  expect(params).not.toHaveProperty('ipaddressid')
  expect(params).not.toHaveProperty('publicport')
  expect(params).not.toHaveProperty('domainid')
  expect(params).not.toHaveProperty('name')
  await finishHealthCheck()
})

test('invalid connection settings cannot start a health check', async () => {
  await mountForm()
  await wrapper.setData({ port: 65536 })
  await wrapper.vm.checkBackend()
  expect(postAPI).not.toHaveBeenCalled()
})

test('immediate health response is displayed without job polling', async () => {
  await mountForm()
  postAPI.mockResolvedValue({ checkinstanceproxybackendresponse: { instanceproxybackendhealth: { healthy: true, message: 'HTTP 200 OK' } } })
  await wrapper.vm.checkBackend()
  expect(wrapper.vm.backendHealth.message).toBe('HTTP 200 OK')
  expect(mocks.$pollJob).not.toHaveBeenCalled()
})

test('failed health job shows an advisory error and restores submission', async () => {
  await mountForm()
  await startHealthCheck()
  jobs[0].errorMethod({ jobresult: { errortext: 'Forwarding conflict' } })
  await flushPromises()
  expect(wrapper.vm.backendHealth).toEqual({ healthy: false, message: 'Forwarding conflict' })
  expect(wrapper.vm.checkingBackend).toBe(false)
  expect(wrapper.vm.canCheckBackend).toBe(true)
})

test('failed check confirmation can be cancelled without creating an exposure', async () => {
  await mountForm()
  await startHealthCheck()
  await finishHealthCheck({ healthy: false, message: 'Connection refused' })
  wrapper.vm.submitData()
  wrapper.vm.submitData()
  expect(confirmations).toHaveLength(1)
  confirmations[0].onCancel()
  expect(wrapper.vm.exposureConfirmation).toBeNull()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(wrapper.emitted('close-action')).toBeUndefined()
})

test.each(['change', 'close', 'reset'])('%s dismisses the failed-check confirmation and prevents a stale approval', async action => {
  await mountForm()
  await startHealthCheck()
  await finishHealthCheck({ healthy: false, message: 'Connection refused' })
  wrapper.vm.submitData()
  if (action === 'change') await wrapper.setData({ port: 8080 })
  if (action === 'close') wrapper.vm.closeAction()
  if (action === 'reset') wrapper.vm.resetForm()
  expect(confirmations[0].destroy).toHaveBeenCalled()
  confirmations[0].onOk()
  await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
})

test('a button event cannot bypass failed-check confirmation', async () => {
  await mountForm()
  await wrapper.setData({ backendHealth: { healthy: false, message: 'Failed' } })
  wrapper.vm.submitData(new MouseEvent('click'))
  expect(confirmations).toHaveLength(1)
  expect(postAPI).not.toHaveBeenCalled()
})

test('an unchecked backend requires confirmation and can be exposed anyway', async () => {
  await mountForm()
  wrapper.vm.submitData(new MouseEvent('click'))
  wrapper.vm.submitData()
  expect(confirmations).toHaveLength(1)
  expect(confirmations[0].content).toBe('message.proxy.backend.unchecked.description')
  expect(confirmations[0].centered).toBe(true)
  expect(postAPI).not.toHaveBeenCalled()
  confirmations[0].onOk()
  await flushPromises()
  expect(postAPI).toHaveBeenCalledWith('addInstanceProxy', expect.any(Object))
})

test('the unchecked warning offers a backend check without creating an exposure', async () => {
  await mountForm()
  postAPI.mockResolvedValue({ checkinstanceproxybackendresponse: { jobid: 'check-1' } })
  wrapper.vm.submitData()
  expect(confirmations[0].cancelText).toBe('label.proxy.backend.test')
  confirmations[0].onCancel()
  await flushPromises()
  expect(postAPI).toHaveBeenCalledWith('checkInstanceProxyBackend', expect.any(Object))
  expect(postAPI).not.toHaveBeenCalledWith('addInstanceProxy', expect.any(Object))
})

test.each(['change', 'close', 'reset'])('%s invalidates unchecked warning actions', async action => {
  await mountForm()
  wrapper.vm.submitData()
  if (action === 'change') await wrapper.setData({ port: 8080 })
  if (action === 'close') wrapper.vm.closeAction()
  if (action === 'reset') wrapper.vm.resetForm()
  confirmations[0].onOk()
  confirmations[0].onCancel()
  await flushPromises()
  expect(postAPI).not.toHaveBeenCalled()
})

test('a healthy backend can be exposed without a warning', async () => {
  await mountForm()
  await wrapper.setData({ backendHealth: { healthy: true } })
  wrapper.vm.submitData()
  await flushPromises()
  expect(confirmations).toHaveLength(0)
  expect(postAPI).toHaveBeenCalledWith('addInstanceProxy', expect.any(Object))
})

test('backend check is available before selecting a public IP', async () => {
  publicIps = []
  await mountForm()
  expect(wrapper.vm.canCheckBackend).toBe(true)
  await startHealthCheck()
  expect(postAPI).toHaveBeenCalledWith('checkInstanceProxyBackend', {
    virtualmachineid: 'vm-1', networkid: 'network-1', protocol: 'http', port: 80
  })
  await finishHealthCheck()
})

test('compact result shows status and keeps the full explanation in a tooltip', async () => {
  await mountForm()
  await startHealthCheck()
  await finishHealthCheck({ healthy: false, httpstatus: 503, message: 'Backend returned HTTP 503' })
  expect(wrapper.find('.proxy-health__result').text()).toBe('HTTP 503')
  expect(wrapper.find('[data-tooltip="Backend returned HTTP 503"]').exists()).toBe(true)
})

test('backend connection starts with HTTP and port 80 and offers a test action', async () => {
  await mountForm()
  expect(wrapper.vm.protocol).toBe('http')
  expect(wrapper.vm.port).toBe(80)
  expect(wrapper.find('.proxy-backend__protocol-field .form__label').text()).toContain('label.proxy.protocol')
  expect(wrapper.find('.proxy-backend__port-field .form__label').text()).toContain('label.proxy.port')
  expect(wrapper.text()).toContain('label.proxy.backend.test')
  expect(wrapper.find('.proxy-backend__encryption').exists()).toBe(false)
})

test('protocol selection switches default ports and preserves a custom application port', async () => {
  await mountForm()
  wrapper.vm.selectBackendProtocol('https')
  await flushPromises()
  expect(wrapper.vm.port).toBe(443)
  expect(wrapper.find('.proxy-backend__encryption').exists()).toBe(false)
  wrapper.vm.selectBackendProtocol('http')
  expect(wrapper.vm.port).toBe(80)
  await wrapper.setData({ port: 8080 })
  wrapper.vm.selectBackendProtocol('https')
  expect(wrapper.vm.port).toBe(8080)
})

test('connected result displays latency and a retest action', async () => {
  await mountForm()
  await startHealthCheck()
  await finishHealthCheck({ healthy: true, httpstatus: 200, elapsedms: 28, message: 'HTTP 200 OK' })
  expect(wrapper.find('.proxy-health__success').exists()).toBe(true)
  expect(wrapper.text()).toContain('label.proxy.backend.connected')
  expect(wrapper.find('.proxy-health__elapsed').text()).toContain('28ms')
  expect(wrapper.find('.proxy-health__result').text()).toBe('200 OK')
  expect(wrapper.text()).toContain('label.proxy.backend.retest')
})
