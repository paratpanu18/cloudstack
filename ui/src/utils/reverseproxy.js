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

// Supports both immediate responses and asynchronous network operations.
export function waitForProxyJob (vm, response, responseKey) {
  const result = response[responseKey]
  if (!result?.jobid) return Promise.resolve(result)
  return new Promise((resolve, reject) => {
    vm.$pollJob({
      jobId: result.jobid,
      title: vm.$t('label.reverseproxy'),
      showSuccessMessage: false,
      successMethod: resolve,
      errorMethod: error => reject(error || new Error(vm.$t('label.error'))),
      catchMethod: error => reject(error || new Error(vm.$t('error.fetching.async.job.result')))
    })
  })
}
