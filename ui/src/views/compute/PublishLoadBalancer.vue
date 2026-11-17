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

<template>
  <a-spin :spinning="loading">
    <a-alert v-if="error" type="error" show-icon :message="error" class="web-notice" />
    <a-alert type="info" show-icon :message="$t('message.exposure.lb.web')" class="web-notice" />
    <a-form layout="vertical">
      <a-alert v-if="!loading && !target" type="info" :message="$t('message.exposure.lb.none')" />
      <template v-if="target">
        <a-form-item :label="$t('label.proxy.domain')" required><a-select v-model:value="domainId" :disabled="busy" :options="domains.map(domain => ({ value: domain.id, label: domain.domain }))" @change="resetName" /></a-form-item>
        <a-form-item :label="$t('label.proxy.name')"><a-input v-model:value="name" :disabled="!customName || busy" :placeholder="$t('label.proxy.name.placeholder')"><template #addonAfter>.{{ domain?.domain }}</template></a-input><div v-if="!customName">{{ $t('message.proxy.random.only') }}</div></a-form-item>
        <a-form-item :label="$t('label.proxy.protocol')"><a-radio-group v-model:value="scheme" :disabled="busy"><a-radio-button value="http">HTTP</a-radio-button><a-radio-button value="https">HTTPS</a-radio-button></a-radio-group></a-form-item>
        <a-alert type="info" class="web-notice" :message="'HTTPS → ' + scheme.toUpperCase() + ' ' + target.publicip + ':' + target.publicport" />
        <a-button type="primary" :loading="busy" :disabled="busy || loading || !domainId" @click="publish">{{ $t('label.exposure.add.web') }}</a-button>
        <p v-if="!domains.length && !loading && !domainsFailed">{{ $t('message.exposure.lb.domain.none') }}</p>
        <a-divider />
        <a-table :columns="columns" :data-source="proxies" :pagination="false" row-key="id" :scroll="{ x: 400 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'url'"><a :href="record.url" target="_blank" rel="noopener noreferrer">{{ record.fqdn }}</a><a-typography-paragraph copyable>{{ record.url }}</a-typography-paragraph></template>
            <template v-if="column.key === 'actions'"><a-popconfirm :title="$t('message.exposure.remove.lb.web')" @confirm="remove(record)"><a-button v-if="'deleteInstanceProxy' in $store.getters.apis" danger :disabled="busy">{{ $t('label.delete') }}</a-button></a-popconfirm></template>
          </template>
        </a-table>
      </template>
    </a-form>
  </a-spin>
</template>
<script>
import { listExposureResources, exposureOperation } from '@/utils/internetExposure'
export default {
  props: { resource: { type: Object, required: true }, loadBalancer: { type: Object, required: true } },
  emits: ['close-action', 'busy'],
  data () { return { domains: [], domainId: undefined, name: '', scheme: 'http', proxies: [], loading: false, busy: false, request: 0, domainsFailed: false, error: '', columns: [{ key: 'url', title: this.$t('label.exposure.web') }, { dataIndex: 'state', title: this.$t('label.state') }, { key: 'actions', title: this.$t('label.actions') }] } },
  computed: {
    target () { return this.loadBalancer },
    domain () { return this.domains.find(domain => domain.id === this.domainId) },
    customName () { return this.domain?.allowcustomname === true }
  },
  watch: { busy (value) { this.$emit('busy', value) } },
  mounted () { this.load() },
  beforeUnmount () { this.request++ },
  methods: {
    resetName () { this.name = '' },
    showError (error) {
      const data = error?.response?.data
      const response = data && Object.values(data).find(value => value && typeof value === 'object' && value.errortext)
      this.error = error?.jobresult?.errortext || response?.errortext || error?.message || this.$t('label.error')
    },
    async load () {
      this.loading = true
      try {
        await this.loadDomains()
      } catch (error) { this.showError(error) } finally { this.loading = false }
    },
    async loadDomains () {
      const request = ++this.request
      this.error = ''
      this.domainsFailed = false
      this.domains = []
      this.domainId = undefined
      this.proxies = []
      this.name = ''
      if (!this.target) return
      this.loading = true
      try {
        const targetId = this.target.id
        const [domains, proxies] = await Promise.allSettled([
          listExposureResources('listReverseProxyDomains', 'reverseproxydomain', { loadbalancerruleid: targetId }),
          listExposureResources('listInstanceProxies', 'instanceproxy', { loadbalancerruleid: targetId, listall: true })
        ])
        if (request !== this.request) return
        this.domainsFailed = domains.status === 'rejected'
        if (domains.status === 'fulfilled') {
          this.domains = domains.value
          this.domainId = domains.value[0]?.id
        } else this.showError(domains.reason)
        if (proxies.status === 'fulfilled') this.proxies = proxies.value
        else if (!this.domainsFailed) this.showError(proxies.reason)
      } catch (error) { if (request === this.request) { this.domainsFailed = true; this.showError(error) } } finally { if (request === this.request) this.loading = false }
    },
    async publish () {
      if (this.busy || this.loading || !this.target || !this.domainId) return
      this.busy = true
      this.error = ''
      try {
        const params = { loadbalancerruleid: this.target.id, domainid: this.domainId, protocol: this.scheme, ...(this.customName && this.name.trim() ? { name: this.name.trim() } : {}) }
        if (params.name && 'checkInstanceProxyName' in this.$store.getters.apis) {
          const checked = await exposureOperation(this, 'checkInstanceProxyName', { name: params.name, loadbalancerruleid: params.loadbalancerruleid, domainid: params.domainid })
          if (!checked.instanceproxyname?.available) throw new Error(checked.instanceproxyname?.message || this.$t('message.exposure.name.unavailable'))
        }
        await exposureOperation(this, 'addInstanceProxy', params)
        await this.loadDomains()
      } catch (error) { this.showError(error) } finally { this.busy = false }
    },
    async remove (proxy) {
      if (this.busy) return
      this.busy = true
      try { await exposureOperation(this, 'deleteInstanceProxy', { id: proxy.id }); await this.loadDomains() } catch (error) { this.showError(error) } finally { this.busy = false }
    }
  }
}
</script>
<style scoped>
.web-notice { margin-bottom: 16px; }
</style>
