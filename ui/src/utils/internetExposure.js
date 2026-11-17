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

import { getAPI, postAPI } from '@/api'

// Always fetch every page when making eligibility or conflict decisions.
export async function listExposureResources (api, key, params = {}) {
  const items = []
  for (let page = 1; ; page++) {
    const response = await getAPI(api, { ...params, page, pagesize: 500 })
    const result = response[api.toLowerCase() + 'response']
    if (!result) throw new Error('Invalid response from ' + api)
    const batch = result[key] || []
    items.push(...batch)
    if (!batch.length || (result.count != null ? items.length >= result.count : batch.length < 500)) return items
  }
}

export async function exposureOperation (vm, api, params) {
  const response = await postAPI(api, params)
  const result = response[api.toLowerCase() + 'response']
  if (!result) throw new Error('Invalid response from ' + api)
  if (!result.jobid) return result
  // Keep publishing independent of global page refresh and notice handlers.
  const deadline = Date.now() + 10 * 60 * 1000
  while (Date.now() < deadline) {
    const response = await getAPI('queryAsyncJobResult', { jobid: result.jobid })
    const job = response.queryasyncjobresultresponse
    if (!job) throw new Error('Invalid job response')
    const status = Number(job.jobstatus)
    if (status === 1) return { ...result, ...job.jobresult }
    if (status === 2) throw new Error(job.jobresult?.errortext || vm.$t('label.error'))
    if (status !== 0) throw new Error('Invalid job status')
    await new Promise(resolve => setTimeout(resolve, 2000))
  }
  throw new Error('Publishing is still running. Refresh the web address list before retrying to avoid creating a duplicate.')
}
