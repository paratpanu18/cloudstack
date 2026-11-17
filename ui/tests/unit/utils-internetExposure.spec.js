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

import { exposureOperation } from '@/utils/internetExposure'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const vm = { $t: key => key }
beforeEach(() => { jest.resetAllMocks(); postAPI.mockResolvedValue({ addinstanceproxyresponse: { jobid: 'job' } }) })
test('waits for a pending job, then resolves the created mapping without global poll handlers', async () => {
  getAPI.mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus: 0 } }).mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { instanceproxy: { id: 'proxy' } } } })
  const result = exposureOperation(vm, 'addInstanceProxy', {})
  expect(await result).toMatchObject({ instanceproxy: { id: 'proxy' } })
  expect(getAPI).toHaveBeenCalledWith('queryAsyncJobResult', { jobid: 'job' })
})
test('rejects a failed job with the server error', async () => {
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'NPM unavailable' } } })
  await expect(exposureOperation(vm, 'addInstanceProxy', {})).rejects.toThrow('NPM unavailable')
})
test('rejects malformed job results instead of leaving the operation pending', async () => {
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: {} })
  await expect(exposureOperation(vm, 'addInstanceProxy', {})).rejects.toThrow('Invalid job status')
})
